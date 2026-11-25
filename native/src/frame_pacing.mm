#import "metality/metal_context.hpp"
#include <cstdio>

extern "C" {

uint64_t metality_begin_frame(MetalityContext* ctx) {
    if (!ctx || !ctx->device) {
        return 0;
    }
    @autoreleasepool {
        ctx->render_thread_id = std::this_thread::get_id();
        ctx->current_frame_id += 1;
        const uint64_t frame_id = ctx->current_frame_id;

        // 1. Frames-in-Flight 節流（預設 2 幀在途）：當重載幀 GPU 耗時超過 14ms 時自動收斂至最多 2 幀，
        //    避免連續堆疊 3 份重載 CommandBuffer 餓死 macOS WindowServer 合成器
        uint32_t eff_max_fif = (ctx->max_frames_in_flight >= 1u && ctx->max_frames_in_flight <= 4u)
                                   ? ctx->max_frames_in_flight
                                   : 2u;
        if (eff_max_fif > 2u && ctx->last_gpu_time_ns &&
            ctx->last_gpu_time_ns->load(std::memory_order_relaxed) > 14'000'000ULL) {
            eff_max_fif = 2u;
        }
        if (frame_id > eff_max_fif) {
            const uint64_t wait_target = frame_id - eff_max_fif;
            [ctx->frame_event waitUntilSignaledValue:wait_target timeoutMS:2000];
        }

        // 2. 安全回收所有 GPU 已確認執行完畢的退役 UMA 區塊緩衝區與紋理（防止讀寫競爭並確保 Render Thread 無鎖查找生命週期安全）
        const uint64_t completed_frame = ctx->frame_event.signaledValue;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            while (!ctx->retired_buffers.empty() &&
                   ctx->retired_buffers.front().retire_frame_id <= completed_frame) {
                ctx->retired_buffers.pop_front();
            }
            while (!ctx->retired_textures.empty() &&
                   ctx->retired_textures.front().retire_frame_id <= completed_frame) {
                ctx->retired_textures.pop_front();
            }
        }

        // 3. 建立當前幀的主 Command Buffer（使用標準 retained commandBuffer，確保 @autoreleasepool 暫態資源安全存活至 GPU 完成）
        ctx->active_command_buffer = [ctx->command_queue commandBuffer];
        ctx->active_command_buffer.label =
            [NSString stringWithFormat:@"MetalityFrame_%llu", static_cast<unsigned long long>(frame_id)];
        return frame_id;
    }
}

void metality_end_frame(MetalityContext* ctx, bool present_drawable) {
    if (!ctx || !ctx->active_command_buffer) {
        return;
    }
    @autoreleasepool {
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
        }
        ctx->enc_state.reset();

        if (present_drawable) {
            id<CAMetalDrawable> drawable = ctx->active_drawable;
            if (!drawable && ctx->metal_layer) {
                drawable = [ctx->metal_layer nextDrawable];
            }
            if (drawable) {
                [ctx->active_command_buffer presentDrawable:drawable];
            }
            ctx->active_drawable = nil;
        }

        const uint64_t signaled_frame_id = ctx->current_frame_id;
        id<MTLSharedEvent> frame_event = ctx->frame_event;
        [ctx->active_command_buffer encodeSignalEvent:frame_event value:signaled_frame_id];
        std::shared_ptr<std::atomic<uint64_t>> gpu_ns_ptr = ctx->last_gpu_time_ns;
        [ctx->active_command_buffer addCompletedHandler:^(id<MTLCommandBuffer> cb) {
            if (cb.status == MTLCommandBufferStatusError) {
                const char* err_str = (cb.error && cb.error.localizedDescription)
                        ? cb.error.localizedDescription.UTF8String
                        : "unknown Metal error";
                std::fprintf(stderr, "[Metality Metal ERROR] CommandBuffer frame=%llu failed: %s\n",
                        static_cast<unsigned long long>(signaled_frame_id), err_str);
                std::fflush(stderr);
                if (frame_event.signaledValue < signaled_frame_id) {
                    frame_event.signaledValue = signaled_frame_id;
                }
            }
            if (cb.GPUEndTime > cb.GPUStartTime) {
                const double ns = (cb.GPUEndTime - cb.GPUStartTime) * 1e9;
                gpu_ns_ptr->store(static_cast<uint64_t>(ns), std::memory_order_relaxed);
            }
        }];
        [ctx->active_command_buffer commit];
        ctx->active_command_buffer = nil;
        ctx->active_pass_width = 0;
        ctx->active_pass_height = 0;
    }
}

double metality_last_gpu_frame_ms(const MetalityContext* ctx) {
    if (!ctx || !ctx->last_gpu_time_ns) {
        return 0.0;
    }
    const uint64_t ns = ctx->last_gpu_time_ns->load(std::memory_order_relaxed);
    return static_cast<double>(ns) / 1e6;
}

bool metality_wait_frame_complete(MetalityContext* ctx, uint64_t frame_id, uint64_t timeout_ms) {
    if (!ctx || !ctx->frame_event) {
        return false;
    }
    if (frame_id == 0 || ctx->frame_event.signaledValue >= frame_id) {
        return true;
    }
    return [ctx->frame_event waitUntilSignaledValue:frame_id timeoutMS:timeout_ms] == YES;
}

float metality_recommended_render_scale(const MetalityContext* ctx, bool thermal_adaptive_enabled) {
    if (!thermal_adaptive_enabled) {
        return 1.0f;
    }
    switch (metality_poll_thermal_state(ctx)) {
        case METALITY_THERMAL_NOMINAL:
            return 1.0f;
        case METALITY_THERMAL_FAIR:
            return 0.80f;
        case METALITY_THERMAL_SERIOUS:
            return 0.67f;
        case METALITY_THERMAL_CRITICAL:
            return 0.50f;
    }
    return 1.0f;
}

MetalityBufferHandle metality_buffer_alloc_shared(
    MetalityContext* ctx,
    uint64_t size_bytes,
    bool write_combined,
    void** out_cpu_ptr,
    uint64_t* out_gpu_address
) {
    if (!ctx || !ctx->device || size_bytes == 0) {
        return 0;
    }
    @autoreleasepool {
        // Apple Silicon 真零拷貝記憶體：CPU (Java FFM MemorySegment) 與 GPU 共用同一塊實體記憶體
        // 啟用 Tracked 讓 Metal 自動追蹤 Blit -> Render/Compute 跨 Encoder 讀寫相依屏障
        MTLResourceOptions opts = MTLResourceStorageModeShared | MTLResourceHazardTrackingModeTracked;
        if (write_combined) {
            opts |= MTLResourceCPUCacheModeWriteCombined;
        }

        // 對齊至 256 bytes 以利 Argument Buffer 與 SIMD 對齊存取
        const uint64_t aligned_size = (size_bytes + 255ULL) & ~255ULL;
        id<MTLBuffer> buf = [ctx->device newBufferWithLength:aligned_size options:opts];
        if (!buf) {
            return 0;
        }

        void* cpu_ptr = buf.contents;
        const uint64_t gpu_addr = buf.gpuAddress;

        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        const MetalityBufferHandle handle = ctx->next_resource_handle++;

        metality::SharedBufferEntry entry;
        entry.handle = handle;
        entry.size_bytes = aligned_size;
        entry.gpu_address = gpu_addr;
        entry.cpu_ptr = cpu_ptr;
        entry.mtl_buffer = buf;

        ctx->fast_buf_publish_locked(entry);
        ctx->live_buffers.emplace(handle, std::move(entry));

        if (out_cpu_ptr) {
            *out_cpu_ptr = cpu_ptr;
        }
        if (out_gpu_address) {
            *out_gpu_address = gpu_addr;
        }
        return handle;
    }
}

void metality_buffer_retire(MetalityContext* ctx, MetalityBufferHandle handle) {
    if (!ctx || handle == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    auto it = ctx->live_buffers.find(handle);
    if (it == ctx->live_buffers.end()) {
        return;
    }

    ctx->fast_buf_invalidate_locked(handle);
    // 推入延遲釋放佇列：直到當前在途的 Frame 完成後才真正釋放底層 MTLBuffer
    metality::RetiredBufferTicket ticket;
    ticket.retire_frame_id = ctx->current_frame_id > 0 ? ctx->current_frame_id : 1;
    ticket.entry = std::move(it->second);
    ctx->live_buffers.erase(it);
    ctx->retired_buffers.push_back(std::move(ticket));
}

uint32_t metality_active_buffer_count(const MetalityContext* ctx) {
    if (!ctx) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    return static_cast<uint32_t>(ctx->live_buffers.size() + ctx->retired_buffers.size());
}

void metality_query_uma_pressure(
    const MetalityContext* ctx,
    MetalityUmaPressureInfo* out_info
) {
    if (!out_info) {
        return;
    }
    std::memset(out_info, 0, sizeof(MetalityUmaPressureInfo));
    if (!ctx || !ctx->device) {
        return;
    }
    @autoreleasepool {
        const uint64_t allocated = static_cast<uint64_t>(ctx->device.currentAllocatedSize);
        const uint64_t recommended = static_cast<uint64_t>(ctx->device.recommendedMaxWorkingSetSize);
        out_info->current_allocated_bytes = allocated;
        out_info->recommended_working_set_bytes = recommended;
        out_info->utilization_ratio = recommended > 0
                ? static_cast<float>(static_cast<double>(allocated) / static_cast<double>(recommended))
                : 0.0f;
        out_info->thermal_state_level = static_cast<uint32_t>(metality_poll_thermal_state(ctx));
    }
}

} // extern "C"

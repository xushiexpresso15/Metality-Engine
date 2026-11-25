#import "metality/metal_context.hpp"
#import <CoreGraphics/CoreGraphics.h>

#include <algorithm>
#include <cstring>
#include <filesystem>

namespace {

uint32_t detect_apple_gpu_family(id<MTLDevice> device) {
    // 依照世代由高到低探測 Apple Silicon GPU Family
    // Apple9 = M3 / M4 世代；更高世代透過數值枚舉平滑向前探測
    const MTLGPUFamily apple10 = static_cast<MTLGPUFamily>(1010);
    if ([device supportsFamily:apple10]) {
        return 10;
    }
    if ([device supportsFamily:MTLGPUFamilyApple9]) {
        return 9;
    }
    if ([device supportsFamily:MTLGPUFamilyApple8]) {
        return 8;
    }
    if ([device supportsFamily:MTLGPUFamilyApple7]) {
        return 7;
    }
    return 0;
}

} // namespace

extern "C" {

MetalityContext* metality_context_create(const MetalityConfig* config) {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) {
            return nullptr;
        }

        // 強制確認為 Apple Silicon 統一記憶體架構
        if (!device.hasUnifiedMemory || ![device supportsFamily:MTLGPUFamilyApple7]) {
            return nullptr;
        }

        auto* ctx = new MetalityContextImpl();
        ctx->device = device;
        ctx->command_queue = [device newCommandQueueWithMaxCommandBufferCount:64];
        ctx->frame_event = [device newSharedEvent];
        ctx->frame_event.signaledValue = 0;

        if (config) {
            if (config->frames_in_flight >= 2 && config->frames_in_flight <= 4) {
                ctx->max_frames_in_flight = config->frames_in_flight;
            }
            ctx->enable_edr = (config->enable_edr_hdr != 0);
            if (config->cache_directory_utf8 && config->cache_directory_utf8[0] != '\0') {
                ctx->cache_dir = config->cache_directory_utf8;
            }
        }

        // 初始化 MTLBinaryArchive 持久化管線快取（避免遊戲執行中編譯 PSO 卡頓）
        if (!ctx->cache_dir.empty()) {
            std::error_code ec;
            std::filesystem::create_directories(ctx->cache_dir, ec);
            std::string archive_path = ctx->cache_dir + "/metality_pso_cache.metallib";
            ctx->archive_url = [NSURL fileURLWithPath:[NSString stringWithUTF8String:archive_path.c_str()]];

            MTLBinaryArchiveDescriptor* desc = [[MTLBinaryArchiveDescriptor alloc] init];
            if (std::filesystem::exists(archive_path, ec)) {
                desc.url = ctx->archive_url;
            }
            NSError* err = nil;
            ctx->binary_archive = [device newBinaryArchiveWithDescriptor:desc error:&err];
            if (!ctx->binary_archive) {
                // 若舊快取損毀或系統更新失效，建立全新空白 Binary Archive
                desc.url = nil;
                ctx->binary_archive = [device newBinaryArchiveWithDescriptor:desc error:&err];
            }
        }

        return ctx;
    }
}

void metality_context_destroy(MetalityContext* ctx) {
    if (!ctx) {
        return;
    }
    @autoreleasepool {
        metality_flush_pipeline_cache(ctx);
        // 等待所有在途 GPU Frame 完成後再安全釋放資源
        if (ctx->frame_event && ctx->current_frame_id > 0) {
            [ctx->frame_event waitUntilSignaledValue:ctx->current_frame_id timeoutMS:2000];
        }
        ctx->retired_buffers.clear();
        ctx->retired_textures.clear();
        ctx->live_buffers.clear();
        ctx->textures.clear();
        ctx->shader_libraries.clear();
        delete ctx;
    }
}

void metality_query_capabilities(const MetalityContext* ctx, MetalityDeviceCaps* out_caps) {
    if (!ctx || !out_caps || !ctx->device) {
        return;
    }
    @autoreleasepool {
        std::memset(out_caps, 0, sizeof(MetalityDeviceCaps));
        id<MTLDevice> dev = ctx->device;

        const char* utf8_name = dev.name ? dev.name.UTF8String : "Unknown Apple GPU";
        std::strncpy(out_caps->device_name, utf8_name, sizeof(out_caps->device_name) - 1);

        out_caps->recommended_max_working_set_bytes = dev.recommendedMaxWorkingSetSize;
        out_caps->current_allocated_bytes = dev.currentAllocatedSize;
        out_caps->apple_gpu_family = detect_apple_gpu_family(dev);
        out_caps->max_threads_per_threadgroup =
            static_cast<uint32_t>(dev.maxThreadsPerThreadgroup.width);
        out_caps->max_tile_memory_bytes =
            static_cast<uint32_t>(dev.maxThreadgroupMemoryLength);

        out_caps->has_unified_memory = dev.hasUnifiedMemory ? 1 : 0;
        out_caps->supports_argument_buffers_tier2 =
            (dev.argumentBuffersSupport >= MTLArgumentBuffersTier2) ? 1 : 0;
        out_caps->supports_hardware_raytracing = dev.supportsRaytracing ? 1 : 0;
        out_caps->supports_mesh_shaders = [dev supportsFamily:MTLGPUFamilyMetal3] ? 1 : 0;
        out_caps->supports_metalfx_temporal =
            [MTLFXTemporalScalerDescriptor supportsDevice:dev] ? 1 : 0;
        out_caps->supports_metalfx_spatial =
            [MTLFXSpatialScalerDescriptor supportsDevice:dev] ? 1 : 0;
        out_caps->supports_barycentric_coords = dev.supportsShaderBarycentricCoordinates ? 1 : 0;
        out_caps->supports_metal4_command_queue =
            [dev respondsToSelector:NSSelectorFromString(@"newMTL4CommandQueue")] ? 1 : 0;
    }
}

MetalityThermalState metality_poll_thermal_state(const MetalityContext* ctx) {
    (void)ctx;
    @autoreleasepool {
        NSProcessInfoThermalState state = NSProcessInfo.processInfo.thermalState;
        switch (state) {
            case NSProcessInfoThermalStateNominal:
                return METALITY_THERMAL_NOMINAL;
            case NSProcessInfoThermalStateFair:
                return METALITY_THERMAL_FAIR;
            case NSProcessInfoThermalStateSerious:
                return METALITY_THERMAL_SERIOUS;
            case NSProcessInfoThermalStateCritical:
                return METALITY_THERMAL_CRITICAL;
        }
        return METALITY_THERMAL_NOMINAL;
    }
}

bool metality_attach_nswindow(
    MetalityContext* ctx,
    void* cocoa_nswindow,
    uint32_t drawable_width,
    uint32_t drawable_height,
    float scale_factor,
    bool display_sync
) {
    if (!ctx || !cocoa_nswindow) {
        return false;
    }
    @autoreleasepool {
        NSWindow* window = (__bridge NSWindow*)cocoa_nswindow;
        NSView* content_view = window.contentView;
        if (!content_view) {
            return false;
        }

        CAMetalLayer* layer = [CAMetalLayer layer];
        layer.device = ctx->device;
        layer.pixelFormat = ctx->enable_edr ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm_sRGB;
        layer.wantsExtendedDynamicRangeContent = ctx->enable_edr ? YES : NO;
        layer.opaque = YES;
        CGColorSpaceRef cs = CGColorSpaceCreateWithName(
            ctx->enable_edr ? kCGColorSpaceExtendedLinearSRGB : kCGColorSpaceSRGB
        );
        if (cs) {
            layer.colorspace = cs;
            CGColorSpaceRelease(cs);
        }
        layer.framebufferOnly = YES;
        layer.maximumDrawableCount = 3; // 三重緩衝避免主執行緒阻塞於 nextDrawable
        layer.displaySyncEnabled = display_sync ? YES : NO;
        layer.contentsScale = scale_factor > 0.0f ? scale_factor : 2.0f;
        layer.drawableSize = CGSizeMake(drawable_width, drawable_height);

        content_view.wantsLayer = YES;
        content_view.layer = layer;
        ctx->metal_layer = layer;
        return true;
    }
}

void metality_resize_drawable(
    MetalityContext* ctx,
    uint32_t drawable_width,
    uint32_t drawable_height,
    float scale_factor
) {
    if (!ctx || !ctx->metal_layer) {
        return;
    }
    @autoreleasepool {
        ctx->metal_layer.contentsScale = scale_factor > 0.0f ? scale_factor : 2.0f;
        ctx->metal_layer.drawableSize = CGSizeMake(drawable_width, drawable_height);
    }
}

void metality_set_edr_enabled(MetalityContext* ctx, bool enabled) {
    if (!ctx) {
        return;
    }
    @autoreleasepool {
        ctx->enable_edr = enabled;
        if (ctx->metal_layer && !ctx->active_drawable) {
            ctx->metal_layer.pixelFormat = enabled ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm_sRGB;
            ctx->metal_layer.wantsExtendedDynamicRangeContent = enabled ? YES : NO;
            CGColorSpaceRef cs = CGColorSpaceCreateWithName(
                enabled ? kCGColorSpaceExtendedLinearSRGB : kCGColorSpaceSRGB
            );
            if (cs) {
                ctx->metal_layer.colorspace = cs;
                CGColorSpaceRelease(cs);
            }
        }
    }
}

void metality_set_display_sync(MetalityContext* ctx, bool enabled) {
    if (!ctx || !ctx->metal_layer) {
        return;
    }
    @autoreleasepool {
        ctx->metal_layer.displaySyncEnabled = enabled ? YES : NO;
    }
}

void metality_set_max_frames_in_flight(MetalityContext* ctx, uint32_t frames) {
    if (!ctx) {
        return;
    }
    ctx->max_frames_in_flight = std::clamp(frames, 2u, 3u);
}

void metality_set_shader_render_scale(MetalityContext* ctx, float scale) {
    if (!ctx) {
        return;
    }
    ctx->shader_render_scale = std::clamp(scale, 0.5f, 1.0f);
}

} // extern "C"

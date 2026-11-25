#pragma once

#include "metality/metality_c_api.h"

#ifdef __OBJC__
#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#import <QuartzCore/CAMetalLayer.h>
#endif

#include <atomic>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace metality {

static_assert(sizeof(MetalityDeviceCaps) == 168, "MetalityDeviceCaps must stay 168 bytes for Java Panama FFM");
static_assert(sizeof(MetalityPackedQuad) == 16, "MetalityPackedQuad must be strictly 16 bytes");
static_assert(sizeof(MetalityRenderPassBeginInfo) == 48, "MetalityRenderPassBeginInfo must stay 48 bytes");
static_assert(sizeof(MetalityChunkSectionInput) == 40, "MetalityChunkSectionInput must stay 40 bytes");
static_assert(sizeof(MetalityMeshedSectionOutput) == 72, "MetalityMeshedSectionOutput must stay 72 bytes");
static_assert(sizeof(MetalitySectionGpuRecord) == 64, "MetalitySectionGpuRecord must match 64-byte cache line");
static_assert(sizeof(MetalityUpscalerDesc) == 40, "MetalityUpscalerDesc must stay 40 bytes for Java Panama FFM");
static_assert(sizeof(MetalityUpscaleDispatchInfo) == 64, "MetalityUpscaleDispatchInfo must stay 64 bytes for Java Panama FFM");
static_assert(sizeof(MetalityDenoisedUpscaleDispatchInfo) == 96, "MetalityDenoisedUpscaleDispatchInfo must stay 96 bytes for Java Panama FFM");
static_assert(sizeof(MetalityPackManifestSummary) == 48, "MetalityPackManifestSummary must stay 48 bytes for Java Panama FFM");
static_assert(sizeof(MetalityBlockMaterialEntry) == 18, "MetalityBlockMaterialEntry must stay 18 bytes");
static_assert(sizeof(MetalityPipelineDesc) == 48, "MetalityPipelineDesc must stay 48 bytes for Java Panama FFM");
static_assert(sizeof(MetalityVertexBufferLayout) == 12, "MetalityVertexBufferLayout must stay 12 bytes");
static_assert(sizeof(MetalityVertexAttribute) == 16, "MetalityVertexAttribute must stay 16 bytes");
static_assert(sizeof(MetalityPipelineDescEx) == 80, "MetalityPipelineDescEx must stay 80 bytes for Java Panama FFM");
static_assert(sizeof(MetalityPipelineMrtDesc) == 112, "MetalityPipelineMrtDesc must stay 112 bytes for Java Panama FFM");
static_assert(sizeof(MetalityMrtRenderPassBeginInfo) == 128, "MetalityMrtRenderPassBeginInfo must stay 128 bytes for Java Panama FFM");
static_assert(sizeof(MetalitySpirvOptStats) == 16, "MetalitySpirvOptStats must stay 16 bytes for Java Panama FFM");
static_assert(sizeof(MetalityUmaPressureInfo) == 24, "MetalityUmaPressureInfo must stay 24 bytes for Java Panama FFM");
static_assert(sizeof(MetalityComposerUniforms) == 272, "MetalityComposerUniforms must stay 272 bytes for Java Panama FFM");
static_assert(sizeof(MetalityWorldFrameUniforms) == 608, "MetalityWorldFrameUniforms must stay 608 bytes for Java Panama FFM");

#ifdef __OBJC__
struct RtSceneCustomBindResult {
    bool hardware_active = false;
    uint32_t instance_count = 0;
    int32_t voxel_origin[3] = {0, 0, 0};
    bool voxel_valid = false;
    uint32_t voxel_flags = 0; // bit0=valid, bit1=has_water, bit2=has_stained_glass, bit3=has_wide_water
    uint32_t emissive_light_count = 0;
};
RtSceneCustomBindResult query_rt_scene_state(MetalityRtSceneHandle scene);
id<MTLBuffer> get_rt_emissive_light_buffer(id<MTLDevice> dev, MetalityRtSceneHandle scene, uint32_t* out_light_count);
RtSceneCustomBindResult bind_rt_scene_for_custom_compute(
    id<MTLDevice> dev,
    MetalityRtSceneHandle scene,
    id<MTLComputeCommandEncoder> ce,
    uint32_t tlas_buf_slot,
    uint32_t meta_buf_slot,
    uint32_t offsets_buf_slot,
    uint32_t voxel_buf_slot,
    uint32_t emissive_buf_slot,
    uint32_t ripple_buf_slot,
    uint32_t entity_shadow_buf_slot
);
void bind_rt_scene_for_custom_fragment(
    id<MTLDevice> dev,
    MetalityRtSceneHandle scene,
    id<MTLRenderCommandEncoder> enc,
    uint32_t voxel_buf_slot,
    uint32_t emissive_buf_slot,
    uint32_t ripple_buf_slot,
    uint32_t shadow_buf_slot,
    uint32_t entity_shadow_buf_slot = 6u
);

struct RasterShadowRenderResult {
    id<MTLTexture> shadow_atlas_tex = nil;
    id<MTLBuffer> shadow_uniform_buf = nil;
    bool valid = false;
};
RasterShadowRenderResult render_raster_shadow_map(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    const MetalityWorldFrameUniforms* frame_uniforms,
    uint32_t cascade_resolution,
    uint32_t cascade_count = 3u
);
#endif

struct SharedBufferEntry {
    MetalityBufferHandle handle = 0;
    uint64_t size_bytes = 0;
    uint64_t gpu_address = 0;
    void* cpu_ptr = nullptr;
#ifdef __OBJC__
    id<MTLBuffer> mtl_buffer = nil;
    id<MTLTexture> cached_tex_view = nil;
    uint64_t cached_tex_offset = UINT64_MAX;
    uint64_t cached_tex_size = 0;
    uint32_t cached_tex_fmt = 0;
#endif
};

struct RetiredBufferTicket {
    uint64_t retire_frame_id = 0;
    SharedBufferEntry entry;
};

struct TextureEntry {
    MetalityTextureHandle handle = 0;
    MetalityTextureDesc desc{};
#ifdef __OBJC__
    id<MTLTexture> texture = nil;
#endif
};

struct RetiredTextureTicket {
    uint64_t retire_frame_id = 0;
    TextureEntry entry;
};

#ifdef __OBJC__
// Lock-free seqlock-protected direct-mapped handle caches (zero mutex / zero map lookup on render thread!)
constexpr size_t kFastBufSlots = 8192u;
constexpr size_t kFastTexSlots = 2048u;
constexpr size_t kFastPsoSlots = 512u;
constexpr size_t kFastSampSlots = 128u;

struct FastBufferSlot {
    std::atomic<uint64_t> seq{0};
    MetalityBufferHandle handle = 0;
    __unsafe_unretained id<MTLBuffer> mtl_buffer = nil;
    uint64_t size_bytes = 0;
    void* cpu_ptr = nullptr;
};

struct FastTextureSlot {
    std::atomic<uint64_t> seq{0};
    MetalityTextureHandle handle = 0;
    __unsafe_unretained id<MTLTexture> texture = nil;
};

struct FastSamplerSlot {
    std::atomic<uint64_t> seq{0};
    MetalitySamplerHandle handle = 0;
    __unsafe_unretained id<MTLSamplerState> sampler = nil;
};

struct FastPsoSlot {
    std::atomic<uint64_t> seq{0};
    MetalityPsoHandle handle = 0;
    __unsafe_unretained id<MTLRenderPipelineState> render_pso = nil;
    __unsafe_unretained id<MTLDepthStencilState> depth_stencil = nil;
    MTLPrimitiveType primitive_type = MTLPrimitiveTypeTriangle;
    MTLTriangleFillMode fill_mode = MTLTriangleFillModeFill;
    MetalityCullMode cull_mode = METALITY_CULL_NONE;
};
#endif

struct ActiveEncoderState {
    const void* bound_encoder_ptr = nullptr;
    MetalityPsoHandle bound_pso = 0;
    float bound_depth_bias = 0.0f;
    float bound_slope_scale = 0.0f;
    bool has_depth_bias = false;
    MetalityBufferHandle bound_vb[32] = {};
    uint64_t bound_vb_off[32] = {};
    MetalityBufferHandle bound_ub[16] = {};
    uint64_t bound_ub_off[16] = {};
    MetalityTextureHandle bound_tex[16] = {};
    MetalitySamplerHandle bound_samp[16] = {};

    void reset() noexcept {
        bound_encoder_ptr = nullptr;
        bound_pso = 0;
        has_depth_bias = false;
        for (int i = 0; i < 32; ++i) { bound_vb[i] = 0; bound_vb_off[i] = UINT64_MAX; }
        for (int i = 0; i < 16; ++i) {
            bound_ub[i] = 0; bound_ub_off[i] = UINT64_MAX;
            bound_tex[i] = 0; bound_samp[i] = 0;
        }
    }

    inline void ensure_encoder(const void* enc_ptr) noexcept {
        if (bound_encoder_ptr != enc_ptr) {
            reset();
            bound_encoder_ptr = enc_ptr;
        }
    }
};

struct SamplerEntry {
    MetalitySamplerHandle handle = 0;
    uint32_t packed_key = 0;
#ifdef __OBJC__
    id<MTLSamplerState> sampler = nil;
#endif
};

struct ShaderLibraryEntry {
    MetalityShaderHandle handle = 0;
    std::string label;
#ifdef __OBJC__
    id<MTLLibrary> library = nil;
#endif
};

struct PipelineStateEntry {
    MetalityPsoHandle handle = 0;
#ifdef __OBJC__
    id<MTLRenderPipelineState> render_pso = nil;
    id<MTLDepthStencilState> depth_stencil = nil;
    MTLPrimitiveType primitive_type = MTLPrimitiveTypeTriangle;
    MTLTriangleFillMode fill_mode = MTLTriangleFillModeFill;
#endif
    MetalityCullMode cull_mode = METALITY_CULL_NONE;
};

} // namespace metality

struct MetalityContextImpl {
    std::string cache_dir;
    uint32_t max_frames_in_flight = 3;
    bool enable_edr = true;
    float shader_render_scale = 1.0f;

    uint64_t current_frame_id = 0;
    std::thread::id render_thread_id{};
    uint32_t active_pass_width = 0;
    uint32_t active_pass_height = 0;
    std::shared_ptr<std::atomic<uint64_t>> last_gpu_time_ns = std::make_shared<std::atomic<uint64_t>>(0);

    mutable std::mutex resource_mutex;
    uint64_t next_resource_handle = 1;
    std::unordered_map<MetalityBufferHandle, metality::SharedBufferEntry> live_buffers;
    std::deque<metality::RetiredBufferTicket> retired_buffers;
    std::unordered_map<MetalityTextureHandle, metality::TextureEntry> textures;
    std::deque<metality::RetiredTextureTicket> retired_textures;
    std::unordered_map<uint32_t, metality::SamplerEntry> sampler_cache;
    std::unordered_map<MetalitySamplerHandle, uint32_t> sampler_handle_to_key;
    std::unordered_map<MetalityShaderHandle, metality::ShaderLibraryEntry> shader_libraries;
    std::unordered_map<MetalityPsoHandle, metality::PipelineStateEntry> render_psos;
    std::unordered_map<uint64_t, MetalityPsoHandle> builtin_pso_cache;
    MetalityShaderHandle builtin_blaze3d_lib = 0;
    metality::ActiveEncoderState enc_state{};

#ifdef __OBJC__
    std::unique_ptr<metality::FastBufferSlot[]> fast_buf_cache = std::make_unique<metality::FastBufferSlot[]>(metality::kFastBufSlots);
    std::unique_ptr<metality::FastTextureSlot[]> fast_tex_cache = std::make_unique<metality::FastTextureSlot[]>(metality::kFastTexSlots);
    std::unique_ptr<metality::FastSamplerSlot[]> fast_samp_cache = std::make_unique<metality::FastSamplerSlot[]>(metality::kFastSampSlots);
    std::unique_ptr<metality::FastPsoSlot[]> fast_pso_cache = std::make_unique<metality::FastPsoSlot[]>(metality::kFastPsoSlots);

    inline void fast_buf_publish_locked(const metality::SharedBufferEntry& e) noexcept {
        auto& s = fast_buf_cache[e.handle & (metality::kFastBufSlots - 1u)];
        const uint64_t v = s.seq.load(std::memory_order_relaxed);
        s.seq.store(v + 1u, std::memory_order_release);
        s.handle = e.handle;
        s.mtl_buffer = e.mtl_buffer;
        s.size_bytes = e.size_bytes;
        s.cpu_ptr = e.cpu_ptr;
        s.seq.store(v + 2u, std::memory_order_release);
    }
    inline void fast_buf_invalidate_locked(MetalityBufferHandle h) noexcept {
        auto& s = fast_buf_cache[h & (metality::kFastBufSlots - 1u)];
        if (s.handle == h) {
            const uint64_t v = s.seq.load(std::memory_order_relaxed);
            s.seq.store(v + 1u, std::memory_order_release);
            s.handle = 0;
            s.mtl_buffer = nil;
            s.size_bytes = 0;
            s.cpu_ptr = nullptr;
            s.seq.store(v + 2u, std::memory_order_release);
        }
    }
    inline void fast_tex_publish_locked(MetalityTextureHandle h, id<MTLTexture> tex) noexcept {
        auto& s = fast_tex_cache[h & (metality::kFastTexSlots - 1u)];
        const uint64_t v = s.seq.load(std::memory_order_relaxed);
        s.seq.store(v + 1u, std::memory_order_release);
        s.handle = h;
        s.texture = tex;
        s.seq.store(v + 2u, std::memory_order_release);
    }
    inline void fast_tex_invalidate_locked(MetalityTextureHandle h) noexcept {
        auto& s = fast_tex_cache[h & (metality::kFastTexSlots - 1u)];
        if (s.handle == h) {
            const uint64_t v = s.seq.load(std::memory_order_relaxed);
            s.seq.store(v + 1u, std::memory_order_release);
            s.handle = 0;
            s.texture = nil;
            s.seq.store(v + 2u, std::memory_order_release);
        }
    }
    inline void fast_samp_publish_locked(MetalitySamplerHandle h, id<MTLSamplerState> samp) noexcept {
        auto& s = fast_samp_cache[h & (metality::kFastSampSlots - 1u)];
        const uint64_t v = s.seq.load(std::memory_order_relaxed);
        s.seq.store(v + 1u, std::memory_order_release);
        s.handle = h;
        s.sampler = samp;
        s.seq.store(v + 2u, std::memory_order_release);
    }
    inline void fast_pso_publish_locked(const metality::PipelineStateEntry& e) noexcept {
        auto& s = fast_pso_cache[e.handle & (metality::kFastPsoSlots - 1u)];
        const uint64_t v = s.seq.load(std::memory_order_relaxed);
        s.seq.store(v + 1u, std::memory_order_release);
        s.handle = e.handle;
        s.render_pso = e.render_pso;
        s.depth_stencil = e.depth_stencil;
        s.primitive_type = e.primitive_type;
        s.fill_mode = e.fill_mode;
        s.cull_mode = e.cull_mode;
        s.seq.store(v + 2u, std::memory_order_release);
    }
#endif

#ifdef __OBJC__
    id<MTLDevice> device = nil;
    id<MTLCommandQueue> command_queue = nil;
    id<MTLSharedEvent> frame_event = nil;
    id<MTLBinaryArchive> binary_archive = nil;
    id<MTLCommandBuffer> active_command_buffer = nil;
    id<MTLRenderCommandEncoder> active_render_encoder = nil;
    MTLPrimitiveType active_primitive_type = MTLPrimitiveTypeTriangle;
    id<CAMetalDrawable> active_drawable = nil;
    CAMetalLayer* metal_layer = nil;
    NSURL* archive_url = nil;
    bool archive_dirty = false;
#endif
};

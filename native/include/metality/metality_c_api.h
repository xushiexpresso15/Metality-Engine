#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define METALITY_API __declspec(dllexport)
#else
#define METALITY_API __attribute__((visibility("default")))
#endif

#define METALITY_MAX_FramesInFlight 3

typedef struct MetalityContextImpl MetalityContext;
typedef struct MetalityRenderGraphImpl* MetalityGraphHandle;
typedef struct MetalityChunkArenaImpl* MetalityArenaHandle;
typedef struct MetalityTerrainPipelineImpl* MetalityTerrainHandle;
typedef struct MetalityUpscalerImpl* MetalityUpscalerHandle;
typedef struct MetalityShaderPackImpl* MetalityPackHandle;
typedef struct MetalityRayTracingSceneImpl* MetalityRtSceneHandle;
typedef struct MetalityComposerImpl* MetalityComposerHandle;

typedef uint64_t MetalityBufferHandle;
typedef uint64_t MetalityTextureHandle;
typedef uint64_t MetalitySamplerHandle;
typedef uint64_t MetalityShaderHandle;
typedef uint64_t MetalityPsoHandle;

#define METALITY_VERTEX_BUFFER_SLOT_BASE 20u
#define METALITY_PUSH_CONSTANT_SLOT 19u

typedef enum MetalityVertexFormat {
    METALITY_VTX_FLOAT = 1,
    METALITY_VTX_FLOAT2 = 2,
    METALITY_VTX_FLOAT3 = 3,
    METALITY_VTX_FLOAT4 = 4,
    METALITY_VTX_UBYTE4_NORM = 5,
    METALITY_VTX_BYTE4_NORM = 6,
    METALITY_VTX_UBYTE4 = 7,
    METALITY_VTX_BYTE4 = 8,
    METALITY_VTX_USHORT2 = 9,
    METALITY_VTX_SHORT2 = 10,
    METALITY_VTX_USHORT2_NORM = 11,
    METALITY_VTX_SHORT2_NORM = 12,
    METALITY_VTX_INT = 13,
    METALITY_VTX_INT2 = 14,
    METALITY_VTX_INT3 = 15,
    METALITY_VTX_INT4 = 16,
    METALITY_VTX_UINT = 17,
    METALITY_VTX_UINT2 = 18,
    METALITY_VTX_UINT3 = 19,
    METALITY_VTX_UINT4 = 20,
    METALITY_VTX_UBYTE3_NORM = 21,
    METALITY_VTX_BYTE3_NORM = 22,
    METALITY_VTX_UBYTE3 = 23,
    METALITY_VTX_BYTE3 = 24,
    METALITY_VTX_USHORT = 25,
    METALITY_VTX_SHORT = 26,
    METALITY_VTX_UBYTE = 27,
    METALITY_VTX_BYTE = 28
} MetalityVertexFormat;

typedef enum MetalityPrimitiveTopology {
    METALITY_TOPOLOGY_TRIANGLES = 0,
    METALITY_TOPOLOGY_LINES = 1,
    METALITY_TOPOLOGY_LINE_STRIP = 2,
    METALITY_TOPOLOGY_POINTS = 3,
    METALITY_TOPOLOGY_TRIANGLE_STRIP = 4
} MetalityPrimitiveTopology;

typedef enum MetalityThermalState {
    METALITY_THERMAL_NOMINAL = 0,
    METALITY_THERMAL_FAIR = 1,
    METALITY_THERMAL_SERIOUS = 2,
    METALITY_THERMAL_CRITICAL = 3
} MetalityThermalState;

typedef enum MetalityPixelFormat {
    METALITY_FORMAT_RGBA8_UNORM = 1,
    METALITY_FORMAT_RGBA8_SRGB = 2,
    METALITY_FORMAT_BGRA8_SRGB = 3,
    METALITY_FORMAT_RGB10A2_UNORM = 4,
    METALITY_FORMAT_RG11B10_FLOAT = 5,
    METALITY_FORMAT_RGBA16_FLOAT = 6,
    METALITY_FORMAT_R32_FLOAT = 7,
    METALITY_FORMAT_DEPTH32_FLOAT = 8,
    METALITY_FORMAT_RG16_FLOAT = 9,
    METALITY_FORMAT_R16_FLOAT = 10,
    METALITY_FORMAT_R8_UNORM = 11,
    METALITY_FORMAT_R8_SINT = 12,
    METALITY_FORMAT_R32_SINT = 13,
    METALITY_FORMAT_RGBA32_FLOAT = 14,
    METALITY_FORMAT_RGBA16_SINT = 15
} MetalityPixelFormat;

typedef enum MetalityUpscaleMode {
    METALITY_UPSCALE_OFF = 0,
    METALITY_UPSCALE_SPATIAL = 1,
    METALITY_UPSCALE_TEMPORAL = 2,
    METALITY_UPSCALE_TEMPORAL_DENOISED = 3
} MetalityUpscaleMode;

typedef enum MetalityTextureUsage {
    METALITY_TEX_USAGE_SAMPLED = 1 << 0,
    METALITY_TEX_USAGE_RENDER_TARGET = 1 << 1,
    METALITY_TEX_USAGE_STORAGE_WRITE = 1 << 2,
    METALITY_TEX_USAGE_MEMORYLESS = 1 << 3,
    METALITY_TEX_USAGE_CUBEMAP = 1 << 4
} MetalityTextureUsage;

typedef enum MetalityBlendMode {
    METALITY_BLEND_OPAQUE = 0,
    METALITY_BLEND_ALPHA = 1,
    METALITY_BLEND_ADDITIVE = 2,
    METALITY_BLEND_PREMULTIPLIED = 3
} MetalityBlendMode;

typedef enum MetalityDepthCompare {
    METALITY_DEPTH_ALWAYS = 0,
    METALITY_DEPTH_LESS = 1,
    METALITY_DEPTH_LEQUAL = 2,
    METALITY_DEPTH_EQUAL = 3,
    METALITY_DEPTH_GREATER = 4,
    METALITY_DEPTH_NEVER = 5,
    METALITY_DEPTH_GEQUAL = 6,
    METALITY_DEPTH_NOTEQUAL = 7
} MetalityDepthCompare;

typedef enum MetalityCullMode {
    METALITY_CULL_NONE = 0,
    METALITY_CULL_BACK = 1,
    METALITY_CULL_FRONT = 2
} MetalityCullMode;

typedef enum MetalityBuiltinPipeline {
    METALITY_BUILTIN_POSITION_COLOR = 0,
    METALITY_BUILTIN_POSITION_TEX = 1,
    METALITY_BUILTIN_POSITION_TEX_COLOR = 2,
    METALITY_BUILTIN_RENDERTYPE_TEXT = 3,
    METALITY_BUILTIN_BLIT_SCREEN = 4,
    METALITY_BUILTIN_BLIT_PRESENT_YFLIP = 5,
    METALITY_BUILTIN_CLEAR_RECT = 6,
    METALITY_BUILTIN_BLIT_PRESENT_YFLIP_SRGB_TO_LINEAR = 7
} MetalityBuiltinPipeline;

typedef enum MetalityBlockLayer {
    METALITY_LAYER_EMPTY = 0,
    METALITY_LAYER_SOLID = 1,       // 純不透明方塊（強制保護 Apple Silicon 硬體 HSR）
    METALITY_LAYER_CUTOUT = 2,      // 樹葉、草叢等鏤空方塊（獨立 PSO 執行 alpha test）
    METALITY_LAYER_TRANSLUCENT = 3  // 水體、染色玻璃（半透明排序 / OIT 層）
} MetalityBlockLayer;

typedef struct MetalityConfig {
    const char* cache_directory_utf8;
    uint32_t frames_in_flight;
    uint8_t enable_validation;
    uint8_t enable_edr_hdr;
    uint8_t reserved[2];
} MetalityConfig;

typedef struct MetalityDeviceCaps {
    char device_name[128];
    uint64_t recommended_max_working_set_bytes;
    uint64_t current_allocated_bytes;
    uint32_t apple_gpu_family;
    uint32_t max_threads_per_threadgroup;
    uint32_t max_tile_memory_bytes;
    uint8_t has_unified_memory;
    uint8_t supports_argument_buffers_tier2;
    uint8_t supports_hardware_raytracing;
    uint8_t supports_mesh_shaders;
    uint8_t supports_metalfx_temporal;
    uint8_t supports_metalfx_spatial;
    uint8_t supports_barycentric_coords;
    uint8_t supports_metal4_command_queue;
    uint32_t reserved_pad; // 明確對齊至 8-byte 結構體邊界 (168 bytes)
} MetalityDeviceCaps;

typedef struct MetalityGraphPlanStats {
    uint32_t total_passes;
    uint32_t fused_encoder_groups;
    uint32_t total_textures;
    uint32_t memoryless_textures;
    uint64_t dram_bytes_without_tbdr;
    uint64_t dram_bytes_with_tbdr;
    uint64_t saved_dram_bytes_per_frame;
} MetalityGraphPlanStats;

/* --- Phase 0: Blaze3D 紋理、取樣器與 RenderPass 管線描述子 --- */
typedef struct MetalityTextureDesc {
    uint32_t width;
    uint32_t height;
    uint32_t mip_levels;
    MetalityPixelFormat format;
    uint32_t usage_flags;
} MetalityTextureDesc;

typedef struct MetalitySamplerDesc {
    uint8_t linear_min_mag;
    uint8_t linear_mipmap;
    uint8_t repeat_wrap;
    uint8_t max_anisotropy;
} MetalitySamplerDesc;

typedef struct MetalityPipelineDesc {
    MetalityShaderHandle shader_library;
    const char* vertex_entry;
    const char* fragment_entry;
    MetalityPixelFormat color_format;
    MetalityPixelFormat depth_format; // 0 代表無 Depth Attachment
    MetalityBlendMode blend_mode;
    MetalityDepthCompare depth_compare;
    MetalityCullMode cull_mode;
    uint8_t depth_write_enabled;
    uint8_t color_write_mask;         // RGBA bitmask (0xF = 全寫)
    uint8_t reserved[2];
} MetalityPipelineDesc;

typedef struct MetalityVertexBufferLayout {
    uint32_t slot;
    uint32_t stride;
    uint32_t step_rate; // 0 = per-vertex, 1 = per-instance
} MetalityVertexBufferLayout;

typedef struct MetalityVertexAttribute {
    uint32_t location;
    uint32_t buffer_slot;
    uint32_t offset;
    uint32_t format; // MetalityVertexFormat
} MetalityVertexAttribute;

typedef struct MetalityPipelineDescEx {
    MetalityShaderHandle vertex_library;
    MetalityShaderHandle fragment_library; // 0 = reuse vertex_library
    const char* vertex_entry;
    const char* fragment_entry;
    MetalityPixelFormat color_format;
    MetalityPixelFormat depth_format;
    MetalityBlendMode blend_mode;
    MetalityDepthCompare depth_compare;
    MetalityCullMode cull_mode;
    uint8_t depth_write_enabled;
    uint8_t color_write_mask;
    uint8_t wireframe;
    uint8_t topology; // MetalityPrimitiveTopology
    uint32_t vb_count;
    uint32_t attr_count;
    const MetalityVertexBufferLayout* vb_layouts;
    const MetalityVertexAttribute* attributes;
} MetalityPipelineDescEx;

typedef struct MetalityPipelineMrtDesc {
    MetalityPipelineDescEx base;
    uint32_t color_attachment_count;
    int32_t extra_color_formats[3];
    int32_t extra_blend_modes[3];
    uint8_t extra_write_masks[3];
    uint8_t pad0;
} MetalityPipelineMrtDesc;

typedef struct MetalityRenderPassBeginInfo {
    MetalityTextureHandle color_target; // 0 代表直接渲染至視窗當前 CAMetalDrawable
    MetalityTextureHandle depth_target; // 0 代表不使用深度緩衝
    float clear_color[4];
    double clear_depth;
    uint8_t should_clear_color;
    uint8_t should_clear_depth;
    uint8_t store_color;
    uint8_t store_depth;
    uint32_t reserved_pad;
} MetalityRenderPassBeginInfo;

typedef struct MetalityMrtRenderPassBeginInfo {
    MetalityTextureHandle color_targets[4];
    MetalityTextureHandle depth_target;
    float clear_colors[16];
    double clear_depth;
    uint8_t should_clear_color[4];
    uint8_t store_color[4];
    uint8_t color_count;
    uint8_t should_clear_depth;
    uint8_t store_depth;
    uint8_t reserved[5];
} MetalityMrtRenderPassBeginInfo;

/* --- Phase 1: 16-Byte 極致壓縮 Quad-Pulling 頂點格式與區塊網格建構結構 --- */

/**
 * 16 Bytes 代表整個面片 (Quad = 4 個頂點，等效每頂點僅 4 Bytes！比原版小 9 倍，比標準 Sodium 小 5 倍)
 * word0:
 *   [0..4]   x (0..16)
 *   [5..9]   y (0..16)
 *   [10..14] z (0..16)
 *   [15..18] w - 1 (Greedy 寬度 1..16)
 *   [19..22] h - 1 (Greedy 高度 1..16)
 *   [23..25] face_dir (0=-X, 1=+X, 2=-Y, 3=+Y, 4=-Z, 5=+Z)
 *   [26..33] ao4 (4 頂點各 2 bits 環境光遮蔽)
 *   [34]     flip_diag (修正 AO 對角線各向異性接縫)
 *   [35..38] wave_tag (供光影使用的樹葉/草/水擺動標記)
 *   [39..47] material_flags (自發光/LabPBR 標記)
 *   [48..63] sprite_id (紋理圖集索引 0..65535)
 * word1:
 *   [0..31]  lightmap4 (4 頂點各 8 bits: sky<<4 | block)
 *   [32..55] color_tint_rgb (生物群系草/葉/水染色 RGB8)
 *   [56..63] alpha (半透明層不透明度 0..255)
 */
typedef struct MetalityPackedQuad {
    uint64_t word0;
    uint64_t word1;
} MetalityPackedQuad;

/**
 * 每個方塊座標於 18x18x18 鄰域切片中的輸入資料 (8 Bytes)
 */
typedef struct MetalityVoxelInput {
    uint16_t sprite_faces[6]; // 若單一材質可全填相同 sprite_id；此處為輕量化採用獨立材質表查表
} MetalityVoxelInput;

typedef struct MetalityBlockMaterialEntry {
    uint16_t face_sprites[6]; // -X, +X, -Y, +Y, -Z, +Z 六面各自對應的圖集 Sprite ID
    uint8_t layer;            // MetalityBlockLayer (EMPTY / SOLID / CUTOUT / TRANSLUCENT)
    uint8_t is_opaque_full_cube; // 1 = 完整遮擋視線的實心方塊（參與 SIMD 位元遮罩剔除與 AO 遮蔽）
    uint8_t wave_tag;         // 0=靜態, 1=草叢, 2=樹葉, 3=水波
    uint8_t emissive_level;   // 0..15 自發光強度
    uint8_t alpha;            // 0..255
    uint8_t reserved;
} MetalityBlockMaterialEntry;

/**
 * 18x18x18 含邊界鄰域區塊切片輸入（中心 16x16x16 為本區段，外圍 1 格供跨區塊面剔除與平滑 AO 讀取，無需跨執行緒鎖！）
 */
#define METALITY_PADDED_DIM 18
#define METALITY_PADDED_VOLUME (18 * 18 * 18)

typedef struct MetalityChunkSectionInput {
    const uint16_t* block_material_ids; // 長度 METALITY_PADDED_VOLUME (5832)
    const uint8_t* packed_light;        // 長度 METALITY_PADDED_VOLUME (sky<<4 | block)
    const uint32_t* biome_tint_rgb;     // 長度 16*16*16 (4096) 或 nullptr (預設 0xFFFFFF)
    const MetalityBlockMaterialEntry* palette_table;
    uint32_t palette_size;
    uint32_t enable_greedy_merge;
} MetalityChunkSectionInput;

typedef struct MetalityMeshedSectionOutput {
    MetalityPackedQuad* solid_quads;
    uint32_t solid_quad_capacity;
    uint32_t solid_quad_count;

    MetalityPackedQuad* cutout_quads;
    uint32_t cutout_quad_capacity;
    uint32_t cutout_quad_count;

    MetalityPackedQuad* translucent_quads;
    uint32_t translucent_quad_capacity;
    uint32_t translucent_quad_count;

    // 緊湊區段包圍盒 (0..16 浮點座標)，讓 GPU 視錐與 Hi-Z 剔除更精準
    float aabb_min[3];
    float aabb_max[3];
} MetalityMeshedSectionOutput;

typedef struct MetalitySpriteUvEntry {
    float u0;
    float v0;
    float u_size;
    float v_size;
} MetalitySpriteUvEntry;

typedef struct MetalitySectionGpuRecord {
    int32_t chunk_sec_x;
    int32_t chunk_sec_y;
    int32_t chunk_sec_z;
    uint32_t solid_quad_offset;
    uint32_t solid_quad_count;
    uint32_t cutout_quad_offset;
    uint32_t cutout_quad_count;
    uint32_t translucent_quad_offset;
    uint32_t translucent_quad_count;
    float aabb_min[3];
    float aabb_max[3];
    uint32_t pad;
} MetalitySectionGpuRecord;

/* --- 引擎生命週期與硬體偵測 --- */
METALITY_API const char* metality_native_version_string(void);
METALITY_API MetalityContext* metality_context_create(const MetalityConfig* config);
METALITY_API void metality_context_destroy(MetalityContext* ctx);
METALITY_API void metality_query_capabilities(const MetalityContext* ctx, MetalityDeviceCaps* out_caps);
METALITY_API MetalityThermalState metality_poll_thermal_state(const MetalityContext* ctx);

/* --- GLFW Cocoa NSWindow / CAMetalLayer 視窗接管 --- */
METALITY_API bool metality_attach_nswindow(
    MetalityContext* ctx,
    void* cocoa_nswindow,
    uint32_t drawable_width,
    uint32_t drawable_height,
    float scale_factor,
    bool display_sync
);

METALITY_API void metality_resize_drawable(
    MetalityContext* ctx,
    uint32_t drawable_width,
    uint32_t drawable_height,
    float scale_factor
);

/* --- 即時執行期顯示與效能控制（由設定面板直接驅動） --- */
METALITY_API void metality_set_edr_enabled(MetalityContext* ctx, bool enabled);
METALITY_API void metality_set_display_sync(MetalityContext* ctx, bool enabled);
METALITY_API void metality_set_max_frames_in_flight(MetalityContext* ctx, uint32_t frames);
METALITY_API void metality_set_shader_render_scale(MetalityContext* ctx, float scale);

/* --- 3-Frames-in-Flight 幀同步與 UMA 真零拷貝記憶體池 --- */
METALITY_API uint64_t metality_begin_frame(MetalityContext* ctx);
METALITY_API void metality_end_frame(MetalityContext* ctx, bool present_drawable);
METALITY_API bool metality_wait_frame_complete(MetalityContext* ctx, uint64_t frame_id, uint64_t timeout_ms);

METALITY_API MetalityBufferHandle metality_buffer_alloc_shared(
    MetalityContext* ctx,
    uint64_t size_bytes,
    bool write_combined,
    void** out_cpu_ptr,
    uint64_t* out_gpu_address
);

METALITY_API void metality_buffer_retire(
    MetalityContext* ctx,
    MetalityBufferHandle handle
);

METALITY_API uint32_t metality_active_buffer_count(const MetalityContext* ctx);

/* --- Phase 0: 紋理、取樣器、內建 Blaze3D PSO 與 RenderPass 指令編碼 --- */
METALITY_API MetalityTextureHandle metality_texture_create(
    MetalityContext* ctx,
    const MetalityTextureDesc* desc
);

METALITY_API void metality_texture_upload_region(
    MetalityContext* ctx,
    MetalityTextureHandle tex,
    uint32_t mip_level,
    uint32_t x,
    uint32_t y,
    uint32_t width,
    uint32_t height,
    const void* pixel_data,
    uint32_t bytes_per_row
);

METALITY_API void metality_texture_upload_slice_region(
    MetalityContext* ctx,
    MetalityTextureHandle tex,
    uint32_t mip_level,
    uint32_t array_slice,
    uint32_t x,
    uint32_t y,
    uint32_t width,
    uint32_t height,
    const void* pixel_data,
    uint32_t bytes_per_row
);

METALITY_API void metality_texture_destroy(MetalityContext* ctx, MetalityTextureHandle tex);

METALITY_API MetalityTextureHandle metality_texture_create_view(
    MetalityContext* ctx,
    MetalityTextureHandle parent_tex,
    uint32_t base_mip_level,
    uint32_t mip_level_count
);

METALITY_API MetalitySamplerHandle metality_sampler_get_or_create(
    MetalityContext* ctx,
    const MetalitySamplerDesc* desc
);

METALITY_API MetalitySamplerHandle metality_sampler_get_or_create_ex(
    MetalityContext* ctx,
    bool linear_min,
    bool linear_mag,
    bool repeat_u,
    bool repeat_v,
    uint32_t max_anisotropy,
    float max_lod
);

METALITY_API MetalityPsoHandle metality_pso_create(
    MetalityContext* ctx,
    const MetalityPipelineDesc* desc,
    char* err_buf,
    uint32_t err_buf_len
);

METALITY_API MetalityPsoHandle metality_pso_create_ex(
    MetalityContext* ctx,
    const MetalityPipelineDescEx* desc,
    char* err_buf,
    uint32_t err_buf_len
);

METALITY_API MetalityPsoHandle metality_pso_create_mrt(
    MetalityContext* ctx,
    const MetalityPipelineMrtDesc* desc,
    char* err_buf,
    uint32_t err_buf_len
);

METALITY_API MetalityPsoHandle metality_pso_get_builtin_blaze3d(
    MetalityContext* ctx,
    MetalityBuiltinPipeline builtin_kind,
    MetalityPixelFormat color_format,
    MetalityPixelFormat depth_format,
    MetalityBlendMode blend_mode
);

METALITY_API bool metality_cmd_begin_render_pass(
    MetalityContext* ctx,
    const MetalityRenderPassBeginInfo* begin_info
);

METALITY_API bool metality_cmd_begin_render_pass_mrt(
    MetalityContext* ctx,
    const MetalityMrtRenderPassBeginInfo* begin_info
);

METALITY_API void metality_cmd_set_pso(MetalityContext* ctx, MetalityPsoHandle pso);
METALITY_API void metality_cmd_set_depth_bias(MetalityContext* ctx, float depth_bias, float slope_scale);
METALITY_API void metality_cmd_set_viewport(MetalityContext* ctx, float x, float y, float w, float h, float znear, float zfar);
METALITY_API void metality_cmd_set_scissor(MetalityContext* ctx, uint32_t x, uint32_t y, uint32_t w, uint32_t h);
METALITY_API void metality_cmd_bind_vertex_buffer(MetalityContext* ctx, uint32_t slot, MetalityBufferHandle buf, uint64_t offset);
METALITY_API void metality_cmd_bind_uniform_buffer(MetalityContext* ctx, uint32_t slot, MetalityBufferHandle buf, uint64_t offset);
METALITY_API void metality_cmd_bind_uniform_bytes(MetalityContext* ctx, uint32_t slot, const void* data, uint32_t len);
METALITY_API void metality_cmd_bind_texture(MetalityContext* ctx, uint32_t slot, MetalityTextureHandle tex, MetalitySamplerHandle sampler);
METALITY_API void metality_cmd_bind_texel_buffer(
    MetalityContext* ctx,
    uint32_t slot,
    MetalityBufferHandle buf,
    uint64_t offset,
    uint64_t size_bytes,
    MetalityPixelFormat format
);
METALITY_API void metality_cmd_draw(MetalityContext* ctx, uint32_t vertex_start, uint32_t vertex_count);
METALITY_API void metality_cmd_draw_indexed_u32(
    MetalityContext* ctx,
    MetalityBufferHandle index_buf,
    uint64_t index_byte_offset,
    uint32_t index_count
);
METALITY_API void metality_cmd_draw_indexed_instanced(
    MetalityContext* ctx,
    MetalityBufferHandle index_buf,
    uint64_t index_byte_offset,
    uint32_t index_count,
    bool index_is_u16,
    uint32_t instance_count,
    int32_t base_vertex,
    uint32_t base_instance
);
METALITY_API void metality_cmd_draw_indexed_indirect(
    MetalityContext* ctx,
    MetalityBufferHandle index_buf,
    bool index_is_u16,
    MetalityBufferHandle indirect_buf,
    uint64_t indirect_byte_offset,
    uint32_t draw_count
);
METALITY_API void metality_cmd_end_render_pass(MetalityContext* ctx);
METALITY_API void metality_cmd_clear_color_depth(
    MetalityContext* ctx,
    MetalityTextureHandle color_tex,
    float r, float g, float b, float a,
    MetalityTextureHandle depth_tex,
    double clear_depth
);
METALITY_API void metality_cmd_clear_color_depth_rect(
    MetalityContext* ctx,
    MetalityTextureHandle color_tex,
    float r, float g, float b, float a,
    MetalityTextureHandle depth_tex,
    double clear_depth,
    uint32_t mip_level,
    uint32_t x,
    uint32_t y,
    uint32_t width,
    uint32_t height
);
METALITY_API void metality_cmd_present_blit_texture(
    MetalityContext* ctx,
    MetalityTextureHandle src_color_tex
);
METALITY_API void metality_cmd_copy_buffer(
    MetalityContext* ctx,
    MetalityBufferHandle src_buf,
    uint64_t src_off,
    MetalityBufferHandle dst_buf,
    uint64_t dst_off,
    uint64_t size
);
METALITY_API void metality_cmd_copy_texture_to_buffer(
    MetalityContext* ctx,
    MetalityTextureHandle src_tex,
    MetalityBufferHandle dst_buf,
    uint64_t dst_offset,
    uint32_t bytes_per_row,
    uint32_t width,
    uint32_t height
);
METALITY_API void metality_cmd_copy_texture_region(
    MetalityContext* ctx,
    MetalityTextureHandle src_tex,
    MetalityTextureHandle dst_tex,
    uint32_t mip_level,
    uint32_t dst_x,
    uint32_t dst_y,
    uint32_t src_x,
    uint32_t src_y,
    uint32_t width,
    uint32_t height
);

/* --- 免完整 Xcode 執行期 MSL 編譯與 MTLBinaryArchive 快取 --- */
METALITY_API MetalityShaderHandle metality_compile_msl_library(
    MetalityContext* ctx,
    const char* label,
    const char* msl_source,
    bool fast_math,
    char* err_buf,
    uint32_t err_buf_len
);

METALITY_API bool metality_cache_render_pso(
    MetalityContext* ctx,
    MetalityShaderHandle shader_lib,
    const char* vertex_fn,
    const char* fragment_fn,
    MetalityPixelFormat color_format,
    char* err_buf,
    uint32_t err_buf_len
);

METALITY_API bool metality_flush_pipeline_cache(MetalityContext* ctx);

/* --- Phase 1: UMA 區塊記憶體池 (Chunk Arena)、NEON 網格建構器與 GPU ICB 地形剔除管線 --- */
METALITY_API MetalityArenaHandle metality_arena_create(uint64_t total_capacity_bytes, uint32_t alignment_bytes);
METALITY_API void metality_arena_destroy(MetalityArenaHandle arena);
METALITY_API bool metality_arena_alloc(MetalityArenaHandle arena, uint64_t size_bytes, uint64_t* out_offset, uint64_t* out_aligned_size);
METALITY_API void metality_arena_free(MetalityArenaHandle arena, uint64_t offset, uint64_t aligned_size);
METALITY_API uint64_t metality_arena_used_bytes(MetalityArenaHandle arena);
METALITY_API uint32_t metality_arena_free_span_count(MetalityArenaHandle arena);

/**
 * ARM NEON SIMD 加速之 16x16x16 區塊網格建構器（執行緒安全、無全域鎖，可由多個 Java Worker 並行呼叫！）
 */
METALITY_API bool metality_mesh_chunk_section_neon(
    const MetalityChunkSectionInput* input,
    MetalityMeshedSectionOutput* output
);

/**
 * GPU-Driven 地形繪製管線（整合 UMA Quad MegaBuffer + Metal Compute Frustum Culling + Indirect Command Buffer）
 */
METALITY_API MetalityTerrainHandle metality_terrain_pipeline_create(
    MetalityContext* ctx,
    uint32_t max_sections,
    uint64_t quad_pool_bytes,
    char* err_buf,
    uint32_t err_buf_len
);

METALITY_API void metality_terrain_pipeline_destroy(MetalityContext* ctx, MetalityTerrainHandle terrain);

METALITY_API void* metality_terrain_quad_pool_cpu_ptr(MetalityTerrainHandle terrain);

METALITY_API void metality_terrain_update_sprites(
    MetalityTerrainHandle terrain,
    const MetalitySpriteUvEntry* entries,
    uint32_t count
);

METALITY_API void metality_terrain_update_section(
    MetalityTerrainHandle terrain,
    uint32_t section_slot,
    const MetalitySectionGpuRecord* record
);

METALITY_API void metality_terrain_encode_gpu_cull(
    MetalityContext* ctx,
    MetalityTerrainHandle terrain,
    const float frustum_planes[24], // 6 個平面 (nx, ny, nz, d)
    const float camera_world_pos[3],
    uint32_t active_section_count
);

METALITY_API void metality_terrain_encode_icb_draw(
    MetalityContext* ctx,
    MetalityTerrainHandle terrain,
    const float view_proj_matrix[16],
    const float camera_world_pos[3],
    MetalityTextureHandle block_atlas_tex,
    MetalitySamplerHandle atlas_sampler,
    MetalityBlockLayer pass_layer, // METALITY_LAYER_SOLID 或 METALITY_LAYER_CUTOUT
    uint32_t active_section_count
);

/* --- TBDR Render Graph 編譯器（自動 Pass 融合與 Memoryless 提升） --- */
METALITY_API MetalityGraphHandle metality_graph_create(void);
METALITY_API void metality_graph_destroy(MetalityGraphHandle graph);

METALITY_API uint32_t metality_graph_declare_texture(
    MetalityGraphHandle graph,
    const char* name,
    MetalityPixelFormat format,
    uint32_t width,
    uint32_t height,
    bool force_persistent
);

METALITY_API uint32_t metality_graph_add_pass(
    MetalityGraphHandle graph,
    const char* name,
    const uint32_t* read_tex_ids,
    uint32_t read_count,
    bool samples_neighbors,
    const uint32_t* write_tex_ids,
    uint32_t write_count
);

METALITY_API void metality_graph_compile_tbdr(
    MetalityGraphHandle graph,
    MetalityGraphPlanStats* out_stats
);

/* --- Phase 2: GPU Profiler、MetalFX 超解析度、Slang-to-MSL 編譯器與 Aperture pack.ts 載入器 --- */
typedef struct MetalityUpscalerDesc {
    MetalityUpscaleMode mode;
    uint32_t input_width;
    uint32_t input_height;
    uint32_t output_width;
    uint32_t output_height;
    MetalityPixelFormat color_format;
    MetalityPixelFormat depth_format;
    MetalityPixelFormat motion_format;
    MetalityPixelFormat output_format;
    uint8_t auto_exposure;
    uint8_t depth_reversed;
    uint8_t reserved[2];
} MetalityUpscalerDesc;

typedef struct MetalityUpscaleDispatchInfo {
    MetalityTextureHandle color_tex;
    MetalityTextureHandle depth_tex;
    MetalityTextureHandle motion_tex;
    MetalityTextureHandle output_tex;
    uint32_t content_width;
    uint32_t content_height;
    float jitter_x;
    float jitter_y;
    float motion_scale_x;
    float motion_scale_y;
    uint8_t reset_history;
    uint8_t reserved[7];
} MetalityUpscaleDispatchInfo;

/* --- Phase 3: MetalFX 光追去噪分派與 M3/M4/M5 BLAS/TLAS 硬體光追 --- */
typedef struct MetalityDenoisedUpscaleDispatchInfo {
    MetalityTextureHandle color_tex;
    MetalityTextureHandle depth_tex;
    MetalityTextureHandle motion_tex;
    MetalityTextureHandle diffuse_albedo_tex;
    MetalityTextureHandle specular_albedo_tex;
    MetalityTextureHandle normal_tex;
    MetalityTextureHandle roughness_tex;
    MetalityTextureHandle output_tex;
    float jitter_x;
    float jitter_y;
    float motion_scale_x;
    float motion_scale_y;
    uint8_t reset_history;
    uint8_t reserved[15];
} MetalityDenoisedUpscaleDispatchInfo;

typedef struct MetalityPackManifestSummary {
    uint32_t shadow_resolution;
    uint32_t shadow_cascades;
    uint32_t declared_texture_count;
    uint32_t memoryless_texture_count;
    uint32_t total_pass_count;
    uint32_t fused_group_count;
    uint32_t compiled_pso_count;
    uint32_t upscaler_mode;
    uint64_t saved_dram_bytes_per_frame;
    uint8_t shadow_enabled;
    uint8_t gpu_driven_shadow_cull;
    uint8_t enable_edr;
    uint8_t enable_hw_raytracing;
    uint8_t thermal_adaptive;
    uint8_t reserved[3];
} MetalityPackManifestSummary;

METALITY_API double metality_last_gpu_frame_ms(const MetalityContext* ctx);
METALITY_API float metality_recommended_render_scale(const MetalityContext* ctx, bool thermal_adaptive_enabled);

METALITY_API MetalityUpscalerHandle metality_upscaler_create(
    MetalityContext* ctx,
    const MetalityUpscalerDesc* desc
);
METALITY_API bool metality_upscaler_encode(
    MetalityContext* ctx,
    MetalityUpscalerHandle upscaler,
    const MetalityUpscaleDispatchInfo* info
);
METALITY_API bool metality_upscaler_encode_denoised(
    MetalityContext* ctx,
    MetalityUpscalerHandle upscaler,
    const MetalityDenoisedUpscaleDispatchInfo* info
);
METALITY_API void metality_upscaler_halton_jitter(
    uint64_t frame_index,
    uint32_t input_width,
    uint32_t output_width,
    float* out_jx,
    float* out_jy
);
METALITY_API void metality_upscaler_destroy(MetalityUpscalerHandle upscaler);

/* --- Phase 3: M3/M4/M5 硬體光追 BLAS/TLAS 與 M1/M2 Voxel DDA 後備管線 --- */
METALITY_API MetalityRtSceneHandle metality_rt_scene_create(
    MetalityContext* ctx,
    uint32_t max_sections,
    bool force_voxel_fallback
);
METALITY_API void metality_rt_scene_destroy(MetalityRtSceneHandle scene);
METALITY_API bool metality_rt_scene_uses_hardware_bvh(const MetalityRtSceneHandle scene);
METALITY_API uint32_t metality_rt_scene_active_blas_count(const MetalityRtSceneHandle scene);

METALITY_API bool metality_rt_scene_update_section(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    int32_t sec_x,
    int32_t sec_y,
    int32_t sec_z,
    const MetalityPackedQuad* solid_quads,
    uint32_t solid_count,
    const MetalityPackedQuad* cutout_quads,
    uint32_t cutout_count
);
METALITY_API bool metality_rt_scene_update_section_v2(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    int32_t sec_x,
    int32_t sec_y,
    int32_t sec_z,
    const MetalityPackedQuad* solid_quads,
    uint32_t solid_count,
    const MetalityPackedQuad* cutout_quads,
    uint32_t cutout_count,
    const MetalityPackedQuad* translucent_quads,
    uint32_t translucent_count
);
METALITY_API void metality_rt_scene_remove_section(
    MetalityRtSceneHandle scene,
    int32_t sec_x,
    int32_t sec_y,
    int32_t sec_z
);
METALITY_API void metality_rt_scene_clear_all(MetalityRtSceneHandle scene);
METALITY_API void metality_rt_scene_set_dynamic_lights(
    MetalityRtSceneHandle scene,
    const float* lights_data_8f,
    uint32_t light_count
);
METALITY_API void metality_rt_scene_set_water_ripples(
    MetalityRtSceneHandle scene,
    const float* ripples_data_8f,
    uint32_t ripple_count
);
METALITY_API void metality_rt_scene_set_entity_shadows(
    MetalityRtSceneHandle scene,
    const float* casters_data_8f,
    uint32_t caster_count
);
METALITY_API bool metality_rt_scene_rebuild_tlas(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    float cam_x,
    float cam_y,
    float cam_z
);
METALITY_API bool metality_rt_scene_sync_world_data(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    float cam_x,
    float cam_y,
    float cam_z
);
METALITY_API bool metality_rt_dispatch_gi(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    const float inv_view_proj[16],
    const float cam_pos[3],
    const float sun_dir[3],
    uint32_t width,
    uint32_t height,
    MetalityTextureHandle out_noisy_radiance,
    MetalityTextureHandle out_depth,
    MetalityTextureHandle out_motion,
    MetalityTextureHandle out_diffuse_albedo,
    MetalityTextureHandle out_specular_albedo,
    MetalityTextureHandle out_normal,
    MetalityTextureHandle out_roughness
);

METALITY_API bool metality_transpile_slang_to_msl(
    const char* slang_source,
    const char* entry_name,
    char* out_msl_buf,
    uint32_t out_msl_cap,
    char* err_buf,
    uint32_t err_cap
);

METALITY_API MetalityPackHandle metality_pack_load_directory(
    MetalityContext* ctx,
    const char* pack_root_dir,
    uint32_t screen_width,
    uint32_t screen_height,
    float internal_scale,
    MetalityPackManifestSummary* out_summary,
    char* err_buf,
    uint32_t err_cap
);
METALITY_API void metality_pack_destroy(MetalityPackHandle pack);

/* --- Phase 4: 舊版 Iris/OptiFine GLSL 智慧最佳化橋接與即時多通道 HDR 合成器 --- */
typedef struct MetalitySpirvOptStats {
    uint32_t input_word_count;
    uint32_t output_word_count;
    uint32_t discard_op_count;
    uint32_t relaxed_fp16_decorations;
} MetalitySpirvOptStats;

typedef struct MetalityUmaPressureInfo {
    uint64_t current_allocated_bytes;
    uint64_t recommended_working_set_bytes;
    float utilization_ratio;
    uint32_t thermal_state_level; // 0=Nominal, 1=Fair, 2=Serious, 3=Critical
} MetalityUmaPressureInfo;

typedef struct MetalityComposerUniforms {
    float inv_proj[16];
    float view_rot[16];
    float proj[16];
    float sun_world_dir[4];
    float sky_color[4];
    uint32_t width;
    uint32_t height;
    float time_seconds;
    float rain_strength;
    float bloom_strength;
    float ssao_strength;
    float godray_strength;
    float exposure;
    float fog_density;
    uint32_t tonemap_mode;   // 0=ACES, 1=Reinhard, 2=Linear
    uint32_t depth_reversed; // 1=Reversed-Z [1..0], 0=Standard-Z [0..1]
    uint32_t reserved_pad;
} MetalityComposerUniforms;

METALITY_API void metality_query_uma_pressure(
    const MetalityContext* ctx,
    MetalityUmaPressureInfo* out_info
);

METALITY_API bool metality_spirv_optimize_for_apple_gpu(
    const uint32_t* in_words,
    uint32_t in_word_count,
    bool inject_relaxed_fp16,
    uint32_t* out_words,
    uint32_t out_word_cap,
    MetalitySpirvOptStats* out_stats
);

METALITY_API bool metality_rewrite_msl_samplers_and_fp16(
    const char* in_msl,
    bool strip_discard_safety,
    char* out_msl,
    uint32_t out_msl_cap,
    uint32_t* out_inlined_samplers
);

METALITY_API MetalityComposerHandle metality_composer_create(MetalityContext* ctx);
METALITY_API void metality_composer_destroy(MetalityComposerHandle composer);
METALITY_API bool metality_composer_execute(
    MetalityContext* ctx,
    MetalityComposerHandle composer,
    MetalityTextureHandle inout_color_tex,
    MetalityTextureHandle depth_tex,
    const MetalityComposerUniforms* uniforms
);

/* --- Phase 5: 可程式化 Aperture pack.ts + .slang 世界執行期與跨維度 Uniform 介面 --- */
typedef struct MetalityWorldFrameUniforms {
    float inv_proj[16];          /* 0..64 */
    float proj[16];              /* 64..128 */
    float view_rot[16];          /* 128..192 */
    float inv_view_rot[16];      /* 192..256 */
    float inv_view_proj[16];     /* 256..320 */
    float prev_view_proj[16];    /* 320..384 */
    float cam_world_pos[4];      /* 384..400: xyz + frame_index_mod */
    float sun_world_dir[4];      /* 400..416: xyz + sun_visibility */
    float moon_world_dir[4];     /* 416..432: xyz + moon_phase_factor */
    float sky_color[4];          /* 432..448: rgb + star_brightness */
    float fog_color[4];          /* 448..464: rgb + fog_density */
    float sunrise_color[4];      /* 464..480: rgba */
    int32_t voxel_origin[4];     /* 480..496: xyz + voxel_fallback_valid */
    uint32_t width;              /* 496 */
    uint32_t height;             /* 500 */
    float time_seconds;          /* 504 */
    float rain_strength;         /* 508 */
    float thunder_strength;      /* 512 */
    float sea_level;             /* 516 */
    float edr_headroom;          /* 520 */
    int32_t dimension_id;        /* 524: 0=Overworld, -1=Nether, 1=End */
    int32_t submerged_mode;      /* 528: 0=Air, 1=Water, 2=Lava */
    uint32_t rt_instance_count;  /* 532 */
    uint32_t rt_hardware_active; /* 536 */
    uint32_t tonemap_mode;       /* 540 */
    float user_params[4];        /* 544..560: [0]=bloom, [1]=godray, [2]=water_clarity, [3]=rt_quality */
    float tonemap_params[4];     /* 560..576: [0]=exposure, [1]=contrast, [2]=saturation, [3]=bloom_threshold */
    float lighting_params[4];    /* 576..592: [0]=highlight_boost, [1]=shadow_depth, [2]=emissive_intensity, [3]=atmos_haze */
    float surface_params[4];     /* 592..608: [0]=water_wave_height, [1]=water_ripple_strength, [2]=specular_strength, [3]=reflection_strength */
} MetalityWorldFrameUniforms;

METALITY_API bool metality_pack_execute(
    MetalityContext* ctx,
    MetalityPackHandle pack,
    MetalityRtSceneHandle opt_rt_scene,
    MetalityTextureHandle inout_scene_color,
    MetalityTextureHandle scene_depth,
    const MetalityWorldFrameUniforms* frame_uniforms
);

#ifdef __cplusplus
}
#endif

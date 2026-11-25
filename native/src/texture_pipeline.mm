#import "metality/metal_context.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace {

// Lock-free seqlock-protected fast lookups for Render Thread draw/bind hot paths (zero mutex, zero ARC retain/autorelease!)
inline bool fast_lookup_buffer(
    MetalityContext* ctx,
    MetalityBufferHandle h,
    __unsafe_unretained id<MTLBuffer>& out_buf,
    uint64_t* out_size = nullptr,
    void** out_cpu = nullptr
) noexcept {
    out_buf = nil;
    if (h == 0) return false;
    auto& s = ctx->fast_buf_cache[h & (metality::kFastBufSlots - 1u)];
    const uint64_t seq1 = s.seq.load(std::memory_order_acquire);
    if ((seq1 & 1u) == 0u && s.handle == h) {
        __unsafe_unretained id<MTLBuffer> b = s.mtl_buffer;
        const uint64_t sz = s.size_bytes;
        void* cp = s.cpu_ptr;
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s.seq.load(std::memory_order_relaxed) == seq1 && b != nil) {
            out_buf = b;
            if (out_size) *out_size = sz;
            if (out_cpu) *out_cpu = cp;
            return true;
        }
    }
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    auto it = ctx->live_buffers.find(h);
    if (it == ctx->live_buffers.end()) return false;
    ctx->fast_buf_publish_locked(it->second);
    out_buf = it->second.mtl_buffer;
    if (out_size) *out_size = it->second.size_bytes;
    if (out_cpu) *out_cpu = it->second.cpu_ptr;
    return out_buf != nil;
}

inline bool fast_lookup_texture(
    MetalityContext* ctx,
    MetalityTextureHandle h,
    __unsafe_unretained id<MTLTexture>& out_tex
) noexcept {
    out_tex = nil;
    if (h == 0) return false;
    auto& s = ctx->fast_tex_cache[h & (metality::kFastTexSlots - 1u)];
    const uint64_t seq1 = s.seq.load(std::memory_order_acquire);
    if ((seq1 & 1u) == 0u && s.handle == h) {
        __unsafe_unretained id<MTLTexture> t = s.texture;
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s.seq.load(std::memory_order_relaxed) == seq1 && t != nil) {
            out_tex = t;
            return true;
        }
    }
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    auto it = ctx->textures.find(h);
    if (it == ctx->textures.end()) return false;
    ctx->fast_tex_publish_locked(h, it->second.texture);
    out_tex = it->second.texture;
    return out_tex != nil;
}

inline bool fast_lookup_sampler(
    MetalityContext* ctx,
    MetalitySamplerHandle h,
    __unsafe_unretained id<MTLSamplerState>& out_samp
) noexcept {
    out_samp = nil;
    if (h == 0) return false;
    auto& s = ctx->fast_samp_cache[h & (metality::kFastSampSlots - 1u)];
    const uint64_t seq1 = s.seq.load(std::memory_order_acquire);
    if ((seq1 & 1u) == 0u && s.handle == h) {
        __unsafe_unretained id<MTLSamplerState> sp = s.sampler;
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s.seq.load(std::memory_order_relaxed) == seq1 && sp != nil) {
            out_samp = sp;
            return true;
        }
    }
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    auto kit = ctx->sampler_handle_to_key.find(h);
    if (kit == ctx->sampler_handle_to_key.end()) return false;
    auto sit = ctx->sampler_cache.find(kit->second);
    if (sit == ctx->sampler_cache.end()) return false;
    ctx->fast_samp_publish_locked(h, sit->second.sampler);
    out_samp = sit->second.sampler;
    return out_samp != nil;
}

struct FastPsoInfo {
    __unsafe_unretained id<MTLRenderPipelineState> pso = nil;
    __unsafe_unretained id<MTLDepthStencilState> dss = nil;
    MTLPrimitiveType prim = MTLPrimitiveTypeTriangle;
    MTLTriangleFillMode fill = MTLTriangleFillModeFill;
    MetalityCullMode cull = METALITY_CULL_NONE;
};

inline bool fast_lookup_pso(MetalityContext* ctx, MetalityPsoHandle h, FastPsoInfo& out) noexcept {
    if (h == 0) return false;
    auto& s = ctx->fast_pso_cache[h & (metality::kFastPsoSlots - 1u)];
    const uint64_t seq1 = s.seq.load(std::memory_order_acquire);
    if ((seq1 & 1u) == 0u && s.handle == h) {
        out.pso = s.render_pso;
        out.dss = s.depth_stencil;
        out.prim = s.primitive_type;
        out.fill = s.fill_mode;
        out.cull = s.cull_mode;
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s.seq.load(std::memory_order_relaxed) == seq1 && out.pso != nil) {
            return true;
        }
    }
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    auto it = ctx->render_psos.find(h);
    if (it == ctx->render_psos.end()) return false;
    ctx->fast_pso_publish_locked(it->second);
    out.pso = it->second.render_pso;
    out.dss = it->second.depth_stencil;
    out.prim = it->second.primitive_type;
    out.fill = it->second.fill_mode;
    out.cull = it->second.cull_mode;
    return out.pso != nil;
}

MTLPixelFormat map_pixel_format(MetalityPixelFormat fmt) {
    switch (fmt) {
        case METALITY_FORMAT_RGBA8_UNORM:
            return MTLPixelFormatRGBA8Unorm;
        case METALITY_FORMAT_RGBA8_SRGB:
            return MTLPixelFormatRGBA8Unorm_sRGB;
        case METALITY_FORMAT_BGRA8_SRGB:
            return MTLPixelFormatBGRA8Unorm_sRGB;
        case METALITY_FORMAT_RGB10A2_UNORM:
            return MTLPixelFormatRGB10A2Unorm;
        case METALITY_FORMAT_RG11B10_FLOAT:
            return MTLPixelFormatRG11B10Float;
        case METALITY_FORMAT_RGBA16_FLOAT:
            return MTLPixelFormatRGBA16Float;
        case METALITY_FORMAT_R32_FLOAT:
            return MTLPixelFormatR32Float;
        case METALITY_FORMAT_DEPTH32_FLOAT:
            return MTLPixelFormatDepth32Float;
        case METALITY_FORMAT_RG16_FLOAT:
            return MTLPixelFormatRG16Float;
        case METALITY_FORMAT_R16_FLOAT:
            return MTLPixelFormatR16Float;
        case METALITY_FORMAT_R8_UNORM:
            return MTLPixelFormatR8Unorm;
        case METALITY_FORMAT_R8_SINT:
            return MTLPixelFormatR8Sint;
        case METALITY_FORMAT_R32_SINT:
            return MTLPixelFormatR32Sint;
        case METALITY_FORMAT_RGBA32_FLOAT:
            return MTLPixelFormatRGBA32Float;
        case METALITY_FORMAT_RGBA16_SINT:
            return MTLPixelFormatRGBA16Sint;
    }
    return MTLPixelFormatRGBA8Unorm;
}

MTLCompareFunction map_depth_compare(MetalityDepthCompare cmp) {
    switch (cmp) {
        case METALITY_DEPTH_ALWAYS:
            return MTLCompareFunctionAlways;
        case METALITY_DEPTH_LESS:
            return MTLCompareFunctionLess;
        case METALITY_DEPTH_LEQUAL:
            return MTLCompareFunctionLessEqual;
        case METALITY_DEPTH_EQUAL:
            return MTLCompareFunctionEqual;
        case METALITY_DEPTH_GREATER:
            return MTLCompareFunctionGreater;
        case METALITY_DEPTH_NEVER:
            return MTLCompareFunctionNever;
        case METALITY_DEPTH_GEQUAL:
            return MTLCompareFunctionGreaterEqual;
        case METALITY_DEPTH_NOTEQUAL:
            return MTLCompareFunctionNotEqual;
    }
    return MTLCompareFunctionAlways;
}

MTLCullMode map_cull_mode(MetalityCullMode mode) {
    switch (mode) {
        case METALITY_CULL_NONE:
            return MTLCullModeNone;
        case METALITY_CULL_BACK:
            return MTLCullModeBack;
        case METALITY_CULL_FRONT:
            return MTLCullModeFront;
    }
    return MTLCullModeNone;
}

MTLPrimitiveType map_primitive_topology(uint8_t topo) {
    switch (topo) {
        case METALITY_TOPOLOGY_LINES:
            return MTLPrimitiveTypeLine;
        case METALITY_TOPOLOGY_LINE_STRIP:
            return MTLPrimitiveTypeLineStrip;
        case METALITY_TOPOLOGY_POINTS:
            return MTLPrimitiveTypePoint;
        case METALITY_TOPOLOGY_TRIANGLE_STRIP:
            return MTLPrimitiveTypeTriangleStrip;
        case METALITY_TOPOLOGY_TRIANGLES:
        default:
            return MTLPrimitiveTypeTriangle;
    }
}

MTLVertexFormat map_vertex_format(uint32_t fmt) {
    switch (fmt) {
        case METALITY_VTX_FLOAT:        return MTLVertexFormatFloat;
        case METALITY_VTX_FLOAT2:       return MTLVertexFormatFloat2;
        case METALITY_VTX_FLOAT3:       return MTLVertexFormatFloat3;
        case METALITY_VTX_FLOAT4:       return MTLVertexFormatFloat4;
        case METALITY_VTX_UBYTE4_NORM:  return MTLVertexFormatUChar4Normalized;
        case METALITY_VTX_BYTE4_NORM:   return MTLVertexFormatChar4Normalized;
        case METALITY_VTX_UBYTE4:       return MTLVertexFormatUChar4;
        case METALITY_VTX_BYTE4:        return MTLVertexFormatChar4;
        case METALITY_VTX_USHORT2:      return MTLVertexFormatUShort2;
        case METALITY_VTX_SHORT2:       return MTLVertexFormatShort2;
        case METALITY_VTX_USHORT2_NORM: return MTLVertexFormatUShort2Normalized;
        case METALITY_VTX_SHORT2_NORM:  return MTLVertexFormatShort2Normalized;
        case METALITY_VTX_INT:          return MTLVertexFormatInt;
        case METALITY_VTX_INT2:         return MTLVertexFormatInt2;
        case METALITY_VTX_INT3:         return MTLVertexFormatInt3;
        case METALITY_VTX_INT4:         return MTLVertexFormatInt4;
        case METALITY_VTX_UINT:         return MTLVertexFormatUInt;
        case METALITY_VTX_UINT2:        return MTLVertexFormatUInt2;
        case METALITY_VTX_UINT3:        return MTLVertexFormatUInt3;
        case METALITY_VTX_UINT4:        return MTLVertexFormatUInt4;
        case METALITY_VTX_UBYTE3_NORM:  return MTLVertexFormatUChar3Normalized;
        case METALITY_VTX_BYTE3_NORM:   return MTLVertexFormatChar3Normalized;
        case METALITY_VTX_UBYTE3:       return MTLVertexFormatUChar3;
        case METALITY_VTX_BYTE3:        return MTLVertexFormatChar3;
        case METALITY_VTX_USHORT:       return MTLVertexFormatUShort;
        case METALITY_VTX_SHORT:        return MTLVertexFormatShort;
        case METALITY_VTX_UBYTE:        return MTLVertexFormatUChar;
        case METALITY_VTX_BYTE:         return MTLVertexFormatChar;
        default:                        return MTLVertexFormatFloat4;
    }
}

uint32_t bytes_per_pixel(MTLPixelFormat fmt) {
    switch (fmt) {
        case MTLPixelFormatR8Unorm:
        case MTLPixelFormatR8Sint:
            return 1u;
        case MTLPixelFormatR16Float:
            return 2u;
        case MTLPixelFormatRGBA16Float:
        case MTLPixelFormatRGBA16Sint:
            return 8u;
        case MTLPixelFormatRGBA32Float:
            return 16u;
        default:
            return 4u;
    }
}

MetalityPixelFormat reverse_map_pixel_format(MTLPixelFormat fmt) {
    switch (fmt) {
        case MTLPixelFormatRGBA32Float:     return METALITY_FORMAT_RGBA32_FLOAT;
        case MTLPixelFormatRGBA16Float:     return METALITY_FORMAT_RGBA16_FLOAT;
        case MTLPixelFormatRGBA16Sint:      return METALITY_FORMAT_RGBA16_SINT;
        case MTLPixelFormatRG16Float:       return METALITY_FORMAT_RG16_FLOAT;
        case MTLPixelFormatR16Float:        return METALITY_FORMAT_R16_FLOAT;
        case MTLPixelFormatR32Float:        return METALITY_FORMAT_R32_FLOAT;
        case MTLPixelFormatR32Sint:         return METALITY_FORMAT_R32_SINT;
        case MTLPixelFormatR8Unorm:         return METALITY_FORMAT_R8_UNORM;
        case MTLPixelFormatR8Sint:          return METALITY_FORMAT_R8_SINT;
        case MTLPixelFormatRGB10A2Unorm:    return METALITY_FORMAT_RGB10A2_UNORM;
        case MTLPixelFormatRG11B10Float:    return METALITY_FORMAT_RG11B10_FLOAT;
        case MTLPixelFormatRGBA8Unorm_sRGB: return METALITY_FORMAT_RGBA8_SRGB;
        case MTLPixelFormatRGBA8Unorm:      return METALITY_FORMAT_RGBA8_UNORM;
        case MTLPixelFormatBGRA8Unorm_sRGB:
        default:                            return METALITY_FORMAT_BGRA8_SRGB;
    }
}

uint32_t msl_data_type_size_and_format(MTLDataType dt, MTLVertexFormat* out_fmt) {
    switch (dt) {
        case MTLDataTypeFloat:  *out_fmt = MTLVertexFormatFloat;  return 4;
        case MTLDataTypeFloat2: *out_fmt = MTLVertexFormatFloat2; return 8;
        case MTLDataTypeFloat3: *out_fmt = MTLVertexFormatFloat3; return 12;
        case MTLDataTypeFloat4: *out_fmt = MTLVertexFormatFloat4; return 16;
        case MTLDataTypeInt:    *out_fmt = MTLVertexFormatInt;    return 4;
        case MTLDataTypeInt2:   *out_fmt = MTLVertexFormatInt2;   return 8;
        case MTLDataTypeInt3:   *out_fmt = MTLVertexFormatInt3;   return 12;
        case MTLDataTypeInt4:   *out_fmt = MTLVertexFormatInt4;   return 16;
        case MTLDataTypeUInt:   *out_fmt = MTLVertexFormatUInt;   return 4;
        case MTLDataTypeUInt2:  *out_fmt = MTLVertexFormatUInt2;  return 8;
        case MTLDataTypeUInt3:  *out_fmt = MTLVertexFormatUInt3;  return 12;
        case MTLDataTypeUInt4:  *out_fmt = MTLVertexFormatUInt4;  return 16;
        default:                *out_fmt = MTLVertexFormatFloat4; return 16;
    }
}

// Phase 0 內建 Blaze3D 核心著色器庫（支援 Minecraft 26.3 主選單、GUI、字體、螢幕合成，零 OpenGL / MoltenVK 依賴）
constexpr const char* kBuiltinBlaze3dMsl = R"(
#include <metal_stdlib>
using namespace metal;

struct BlazeUniforms {
    float4x4 modelViewProj;
    float4 colorModulator;
};

struct PosColorVertex {
    packed_float3 pos;
    uchar4 color;
};

struct PosTexVertex {
    packed_float3 pos;
    packed_float2 uv;
};

struct PosTexColorVertex {
    packed_float3 pos;
    packed_float2 uv;
    uchar4 color;
};

struct GuiVaryings {
    float4 position [[position]];
    float2 uv;
    half4 color;
};

vertex GuiVaryings vs_pos_color(
    uint vid [[vertex_id]],
    device const PosColorVertex* vbuf [[buffer(0)]],
    constant BlazeUniforms& u [[buffer(1)]]
) {
    GuiVaryings out;
    out.position = u.modelViewProj * float4(float3(vbuf[vid].pos), 1.0);
    out.uv = float2(0.0);
    out.color = half4(float4(vbuf[vid].color) * (1.0 / 255.0) * u.colorModulator);
    return out;
}

fragment half4 fs_pos_color(GuiVaryings in [[stage_in]]) {
    if (in.color.a == 0.0h) {
        discard_fragment();
    }
    return in.color;
}

vertex GuiVaryings vs_pos_tex(
    uint vid [[vertex_id]],
    device const PosTexVertex* vbuf [[buffer(0)]],
    constant BlazeUniforms& u [[buffer(1)]]
) {
    GuiVaryings out;
    out.position = u.modelViewProj * float4(float3(vbuf[vid].pos), 1.0);
    out.uv = float2(vbuf[vid].uv);
    out.color = half4(u.colorModulator);
    return out;
}

vertex GuiVaryings vs_pos_tex_color(
    uint vid [[vertex_id]],
    device const PosTexColorVertex* vbuf [[buffer(0)]],
    constant BlazeUniforms& u [[buffer(1)]]
) {
    GuiVaryings out;
    out.position = u.modelViewProj * float4(float3(vbuf[vid].pos), 1.0);
    out.uv = float2(vbuf[vid].uv);
    out.color = half4(float4(vbuf[vid].color) * (1.0 / 255.0) * u.colorModulator);
    return out;
}

fragment half4 fs_pos_tex_color(
    GuiVaryings in [[stage_in]],
    texture2d<half> tex0 [[texture(0)]],
    sampler samp0 [[sampler(0)]]
) {
    half4 sampled = tex0.sample(samp0, in.uv) * in.color;
    if (sampled.a < 0.004h) {
        discard_fragment();
    }
    return sampled;
}

fragment half4 fs_rendertype_text(
    GuiVaryings in [[stage_in]],
    texture2d<half> glyphAtlas [[texture(0)]],
    sampler glyphSampler [[sampler(0)]]
) {
    half4 glyph = glyphAtlas.sample(glyphSampler, in.uv) * in.color;
    if (glyph.a < 0.1h) {
        discard_fragment();
    }
    return glyph;
}

vertex GuiVaryings vs_blit_fullscreen(uint vid [[vertex_id]]) {
    constexpr float2 pos[3] = { float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0) };
    constexpr float2 uvs[3] = { float2(0.0, 1.0),   float2(2.0, 1.0),  float2(0.0, -1.0) };
    GuiVaryings out;
    out.position = float4(pos[vid % 3], 0.0, 1.0);
    out.uv = uvs[vid % 3];
    out.color = half4(1.0h);
    return out;
}

vertex GuiVaryings vs_blit_present_yflip(uint vid [[vertex_id]]) {
    constexpr float2 pos[3] = { float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0) };
    constexpr float2 uvs[3] = { float2(0.0, 0.0),   float2(2.0, 0.0),  float2(0.0, 2.0) };
    GuiVaryings out;
    out.position = float4(pos[vid % 3], 0.0, 1.0);
    out.uv = uvs[vid % 3];
    out.color = half4(1.0h);
    return out;
}

fragment half4 fs_blit_screen(
    GuiVaryings in [[stage_in]],
    texture2d<half> srcTex [[texture(0)]],
    sampler srcSampler [[sampler(0)]]
) {
    return srcTex.sample(srcSampler, in.uv);
}

static inline float3 metality_srgb_to_linear_exact(float3 srgb) {
    float3 c = max(srgb, float3(0.0f));
    float3 low = c / 12.92f;
    float3 high = pow((c + 0.055f) / 1.055f, float3(2.4f));
    return select(high, low, c <= float3(0.04045f));
}

fragment float4 fs_blit_present_linear(
    GuiVaryings in [[stage_in]],
    texture2d<float> srcTex [[texture(0)]],
    sampler srcSampler [[sampler(0)]]
) {
    float4 sampled = srcTex.sample(srcSampler, in.uv);
    return float4(sampled.rgb, 1.0f);
}

fragment float4 fs_blit_present_srgb_to_linear(
    GuiVaryings in [[stage_in]],
    texture2d<float> srcTex [[texture(0)]],
    sampler srcSampler [[sampler(0)]]
) {
    float4 sampled = srcTex.sample(srcSampler, in.uv);
    return float4(metality_srgb_to_linear_exact(sampled.rgb), 1.0f);
}

vertex GuiVaryings vs_clear_rect(
    uint vid [[vertex_id]],
    constant BlazeUniforms& u [[buffer(0)]]
) {
    constexpr float2 pos[3] = { float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0) };
    GuiVaryings out;
    out.position = float4(pos[vid % 3], clamp(u.modelViewProj[0][0], 0.0f, 1.0f), 1.0);
    out.uv = float2(0.0);
    out.color = half4(u.colorModulator);
    return out;
}

fragment half4 fs_clear_rect(GuiVaryings in [[stage_in]]) {
    return in.color;
}
)";

MTLBlendFactor map_custom_blend_factor(uint32_t code) {
    switch (code) {
        case 0:  return MTLBlendFactorZero;
        case 1:  return MTLBlendFactorOne;
        case 2:  return MTLBlendFactorSourceColor;
        case 3:  return MTLBlendFactorOneMinusSourceColor;
        case 4:  return MTLBlendFactorDestinationColor;
        case 5:  return MTLBlendFactorOneMinusDestinationColor;
        case 6:  return MTLBlendFactorSourceAlpha;
        case 7:  return MTLBlendFactorOneMinusSourceAlpha;
        case 8:  return MTLBlendFactorDestinationAlpha;
        case 9:  return MTLBlendFactorOneMinusDestinationAlpha;
        case 10: return MTLBlendFactorBlendColor;
        case 11: return MTLBlendFactorOneMinusBlendColor;
        case 12: return MTLBlendFactorBlendAlpha;
        case 13: return MTLBlendFactorOneMinusBlendAlpha;
        case 14: return MTLBlendFactorSourceAlphaSaturated;
        default: return MTLBlendFactorOne;
    }
}

MTLBlendOperation map_custom_blend_op(uint32_t code) {
    switch (code) {
        case 0:  return MTLBlendOperationAdd;
        case 1:  return MTLBlendOperationSubtract;
        case 2:  return MTLBlendOperationReverseSubtract;
        case 3:  return MTLBlendOperationMin;
        case 4:  return MTLBlendOperationMax;
        default: return MTLBlendOperationAdd;
    }
}

void apply_blend_mode(MTLRenderPipelineColorAttachmentDescriptor* att, int32_t mode, uint8_t write_mask) {
    // Bit 4 (0x10) marks an explicit write mask so write_mask==0 (masked all off) is preserved.
    const uint8_t resolved_mask = (write_mask & 0x10u) ? (write_mask & 0x0Fu) : (write_mask ? (write_mask & 0x0Fu) : 0x0Fu);
    MTLColorWriteMask mask = MTLColorWriteMaskNone;
    if (resolved_mask & 0x1u) mask |= MTLColorWriteMaskRed;
    if (resolved_mask & 0x2u) mask |= MTLColorWriteMaskGreen;
    if (resolved_mask & 0x4u) mask |= MTLColorWriteMaskBlue;
    if (resolved_mask & 0x8u) mask |= MTLColorWriteMaskAlpha;
    att.writeMask = mask;

    if (mode == METALITY_BLEND_OPAQUE) {
        att.blendingEnabled = NO;
        return;
    }

    if ((static_cast<uint32_t>(mode) & 0x40000000u) != 0u) {
        const uint32_t packed = static_cast<uint32_t>(mode);
        att.blendingEnabled = YES;
        att.sourceRGBBlendFactor = map_custom_blend_factor(packed & 0xFu);
        att.destinationRGBBlendFactor = map_custom_blend_factor((packed >> 4) & 0xFu);
        att.sourceAlphaBlendFactor = map_custom_blend_factor((packed >> 8) & 0xFu);
        att.destinationAlphaBlendFactor = map_custom_blend_factor((packed >> 12) & 0xFu);
        att.rgbBlendOperation = map_custom_blend_op((packed >> 16) & 0x7u);
        att.alphaBlendOperation = map_custom_blend_op((packed >> 19) & 0x7u);
        return;
    }

    att.blendingEnabled = YES;
    att.rgbBlendOperation = MTLBlendOperationAdd;
    att.alphaBlendOperation = MTLBlendOperationAdd;

    switch (mode) {
        case METALITY_BLEND_ALPHA:
            att.sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
            att.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            att.sourceAlphaBlendFactor = MTLBlendFactorOne;
            att.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            break;
        case METALITY_BLEND_ADDITIVE:
            att.sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
            att.destinationRGBBlendFactor = MTLBlendFactorOne;
            att.sourceAlphaBlendFactor = MTLBlendFactorOne;
            att.destinationAlphaBlendFactor = MTLBlendFactorOne;
            break;
        case METALITY_BLEND_PREMULTIPLIED:
            att.sourceRGBBlendFactor = MTLBlendFactorOne;
            att.destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            att.sourceAlphaBlendFactor = MTLBlendFactorOne;
            att.destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
            break;
        default:
            att.blendingEnabled = NO;
            break;
    }
}

} // namespace

extern "C" {

MetalityTextureHandle metality_texture_create(
    MetalityContext* ctx,
    const MetalityTextureDesc* desc
) {
    if (!ctx || !ctx->device || !desc || desc->width == 0 || desc->height == 0) {
        return 0;
    }
    @autoreleasepool {
        MTLTextureDescriptor* td = [[MTLTextureDescriptor alloc] init];
        bool is_cubemap = (desc->usage_flags & METALITY_TEX_USAGE_CUBEMAP) != 0;
        td.textureType = is_cubemap ? MTLTextureTypeCube : MTLTextureType2D;
        td.pixelFormat = map_pixel_format(desc->format);
        td.width = desc->width;
        td.height = is_cubemap ? desc->width : desc->height;
        td.mipmapLevelCount = desc->mip_levels > 0 ? desc->mip_levels : 1;

        MTLTextureUsage usage = MTLTextureUsageUnknown;
        if (desc->usage_flags & METALITY_TEX_USAGE_SAMPLED) {
            usage |= MTLTextureUsageShaderRead;
        }
        if (desc->usage_flags & METALITY_TEX_USAGE_RENDER_TARGET) {
            usage |= MTLTextureUsageRenderTarget;
        }
        if (desc->usage_flags & METALITY_TEX_USAGE_STORAGE_WRITE) {
            usage |= MTLTextureUsageShaderWrite;
        }
        td.usage = usage != MTLTextureUsageUnknown ? usage : MTLTextureUsageShaderRead;

        if (desc->usage_flags & METALITY_TEX_USAGE_MEMORYLESS) {
            td.storageMode = MTLStorageModeMemoryless;
        } else if (desc->format == METALITY_FORMAT_DEPTH32_FLOAT ||
                   (desc->usage_flags & (METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_STORAGE_WRITE))) {
            // MetalFX outputTexture 及 Compute Storage 貼圖嚴格要求 MTLStorageModePrivate
            td.storageMode = MTLStorageModePrivate;
        } else {
            // 一般材質貼圖（字體圖集、方塊圖集、GUI 貼圖）在 Apple Silicon UMA 上使用 Shared 支援零開銷 replaceRegion 上傳
            td.storageMode = MTLStorageModeShared;
        }

        id<MTLTexture> tex = [ctx->device newTextureWithDescriptor:td];
        if (!tex) {
            return 0;
        }

        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        const MetalityTextureHandle handle = ctx->next_resource_handle++;
        metality::TextureEntry entry;
        entry.handle = handle;
        entry.desc = *desc;
        entry.texture = tex;
        ctx->fast_tex_publish_locked(handle, tex);
        ctx->textures.emplace(handle, std::move(entry));
        return handle;
    }
}

void metality_texture_upload_slice_region(
    MetalityContext* ctx,
    MetalityTextureHandle tex_handle,
    uint32_t mip_level,
    uint32_t array_slice,
    uint32_t x,
    uint32_t y,
    uint32_t width,
    uint32_t height,
    const void* pixel_data,
    uint32_t bytes_per_row
) {
    if (!ctx || tex_handle == 0 || !pixel_data || width == 0 || height == 0) {
        return;
    }
    @autoreleasepool {
        id<MTLTexture> tex = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto it = ctx->textures.find(tex_handle);
            if (it == ctx->textures.end()) {
                return;
            }
            tex = it->second.texture;
        }
        const uint32_t bpp = bytes_per_pixel(tex.pixelFormat);
        const uint32_t effective_bpr = bytes_per_row > 0 ? bytes_per_row : (width * bpp);
        const size_t total_bytes = static_cast<size_t>(effective_bpr) * height;
        const bool can_use_active = (ctx->active_command_buffer != nil)
                && (std::this_thread::get_id() == ctx->render_thread_id);
        if (tex.storageMode == MTLStorageModeShared && !can_use_active && ctx->current_frame_id == 0) {
            MTLRegion region = MTLRegionMake2D(x, y, width, height);
            [tex replaceRegion:region
                   mipmapLevel:mip_level
                         slice:array_slice
                     withBytes:pixel_data
                   bytesPerRow:effective_bpr
                 bytesPerImage:total_bytes];
            return;
        }

        // 已開始 GPU 影格或 Private 紋理：透過暫存 Shared MTLBuffer + Blit 寫入，避免與在途 GPU 取樣競爭
        id<MTLBuffer> staging = [ctx->device newBufferWithBytes:pixel_data
                                                        length:total_bytes
                                                       options:MTLResourceStorageModeShared];
        if (!staging) {
            return;
        }

        id<MTLCommandBuffer> cmd_buf = can_use_active ? ctx->active_command_buffer : nil;
        const bool transient_cb = (cmd_buf == nil);
        if (transient_cb) {
            cmd_buf = [ctx->command_queue commandBuffer];
        } else if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
            ctx->active_pass_width = 0;
            ctx->active_pass_height = 0;
        }

        id<MTLBlitCommandEncoder> blit = [cmd_buf blitCommandEncoder];
        [blit copyFromBuffer:staging
                sourceOffset:0
           sourceBytesPerRow:effective_bpr
         sourceBytesPerImage:total_bytes
                  sourceSize:MTLSizeMake(width, height, 1)
                   toTexture:tex
            destinationSlice:array_slice
            destinationLevel:mip_level
           destinationOrigin:MTLOriginMake(x, y, 0)];
        [blit endEncoding];

        if (transient_cb) {
            // Serial MTLCommandQueue guarantees FIFO ordering without stalling CPU on waitUntilCompleted!
            [cmd_buf commit];
        }
    }
}

void metality_texture_upload_region(
    MetalityContext* ctx,
    MetalityTextureHandle tex_handle,
    uint32_t mip_level,
    uint32_t x,
    uint32_t y,
    uint32_t width,
    uint32_t height,
    const void* pixel_data,
    uint32_t bytes_per_row
) {
    metality_texture_upload_slice_region(
        ctx, tex_handle, mip_level, 0, x, y, width, height, pixel_data, bytes_per_row
    );
}

void metality_texture_destroy(MetalityContext* ctx, MetalityTextureHandle tex_handle) {
    if (!ctx || tex_handle == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    auto it = ctx->textures.find(tex_handle);
    if (it == ctx->textures.end()) {
        return;
    }
    ctx->fast_tex_invalidate_locked(tex_handle);
    metality::RetiredTextureTicket ticket;
    ticket.retire_frame_id = ctx->current_frame_id > 0 ? ctx->current_frame_id : 1;
    ticket.entry = std::move(it->second);
    ctx->textures.erase(it);
    ctx->retired_textures.push_back(std::move(ticket));
}

MetalityTextureHandle metality_texture_create_view(
    MetalityContext* ctx,
    MetalityTextureHandle parent_tex,
    uint32_t base_mip_level,
    uint32_t mip_level_count
) {
    if (!ctx || !ctx->device || parent_tex == 0) {
        return 0;
    }
    @autoreleasepool {
        id<MTLTexture> parent = nil;
        MetalityTextureDesc parent_desc{};
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto it = ctx->textures.find(parent_tex);
            if (it == ctx->textures.end()) {
                return 0;
            }
            parent = it->second.texture;
            parent_desc = it->second.desc;
        }
        if (!parent) {
            return 0;
        }
        const uint32_t total_mips = std::max<uint32_t>(1u, static_cast<uint32_t>(parent.mipmapLevelCount));
        const uint32_t safe_base = std::min<uint32_t>(base_mip_level, total_mips - 1u);
        const uint32_t safe_count = std::clamp<uint32_t>(mip_level_count, 1u, total_mips - safe_base);
        const NSUInteger slices = (parent.textureType == MTLTextureTypeCube) ? 6u : 1u;

        id<MTLTexture> view_tex = [parent newTextureViewWithPixelFormat:parent.pixelFormat
                                                            textureType:parent.textureType
                                                                 levels:NSMakeRange(safe_base, safe_count)
                                                                 slices:NSMakeRange(0, slices)];
        if (!view_tex) {
            return 0;
        }

        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        const MetalityTextureHandle handle = ctx->next_resource_handle++;
        metality::TextureEntry entry;
        entry.handle = handle;
        entry.desc = parent_desc;
        entry.desc.width = std::max<uint32_t>(1u, parent_desc.width >> safe_base);
        entry.desc.height = std::max<uint32_t>(1u, parent_desc.height >> safe_base);
        entry.desc.mip_levels = safe_count;
        entry.texture = view_tex;
        ctx->fast_tex_publish_locked(handle, view_tex);
        ctx->textures.emplace(handle, std::move(entry));
        return handle;
    }
}

MetalitySamplerHandle metality_sampler_get_or_create(
    MetalityContext* ctx,
    const MetalitySamplerDesc* desc
) {
    if (!ctx || !ctx->device || !desc) {
        return 0;
    }
    const uint8_t aniso = desc->max_anisotropy > 0 ? std::min<uint8_t>(desc->max_anisotropy, 16) : 1;
    const uint32_t key = (static_cast<uint32_t>(desc->linear_min_mag != 0) << 0) |
                         (static_cast<uint32_t>(desc->linear_mipmap != 0) << 1) |
                         (static_cast<uint32_t>(desc->repeat_wrap != 0) << 2) |
                         (static_cast<uint32_t>(aniso) << 8);

    {
        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        auto it = ctx->sampler_cache.find(key);
        if (it != ctx->sampler_cache.end()) {
            return it->second.handle;
        }
    }

    @autoreleasepool {
        MTLSamplerDescriptor* sd = [[MTLSamplerDescriptor alloc] init];
        sd.minFilter = desc->linear_min_mag ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        sd.magFilter = desc->linear_min_mag ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        sd.mipFilter = desc->linear_mipmap ? MTLSamplerMipFilterLinear : MTLSamplerMipFilterNearest;
        MTLSamplerAddressMode wrap = desc->repeat_wrap
                                         ? MTLSamplerAddressModeRepeat
                                         : MTLSamplerAddressModeClampToEdge;
        sd.sAddressMode = wrap;
        sd.tAddressMode = wrap;
        sd.maxAnisotropy = aniso;

        id<MTLSamplerState> samp = [ctx->device newSamplerStateWithDescriptor:sd];
        if (!samp) {
            return 0;
        }

        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        const MetalitySamplerHandle handle = ctx->next_resource_handle++;
        metality::SamplerEntry entry;
        entry.handle = handle;
        entry.packed_key = key;
        entry.sampler = samp;
        ctx->fast_samp_publish_locked(handle, samp);
        ctx->sampler_cache.emplace(key, std::move(entry));
        ctx->sampler_handle_to_key.emplace(handle, key);
        return handle;
    }
}

MetalitySamplerHandle metality_sampler_get_or_create_ex(
    MetalityContext* ctx,
    bool linear_min,
    bool linear_mag,
    bool repeat_u,
    bool repeat_v,
    uint32_t max_anisotropy,
    float max_lod
) {
    if (!ctx || !ctx->device) {
        return 0;
    }
    const uint32_t aniso = std::clamp<uint32_t>(max_anisotropy, 1u, 16u);
    const float clamped_lod = std::max(0.25f, std::isfinite(max_lod) ? max_lod : 1000.0f);
    const uint32_t lod_q = static_cast<uint32_t>(std::min(clamped_lod, 1000.0f) * 16.0f + 0.5f) & 0x7FFFu;
    const uint32_t key = (1u << 31) |
                         (static_cast<uint32_t>(linear_min ? 1u : 0u) << 0) |
                         (static_cast<uint32_t>(linear_mag ? 1u : 0u) << 1) |
                         (static_cast<uint32_t>(repeat_u ? 1u : 0u) << 2) |
                         (static_cast<uint32_t>(repeat_v ? 1u : 0u) << 3) |
                         (aniso << 8) |
                         (lod_q << 16);

    {
        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        auto it = ctx->sampler_cache.find(key);
        if (it != ctx->sampler_cache.end()) {
            return it->second.handle;
        }
    }

    @autoreleasepool {
        MTLSamplerDescriptor* sd = [[MTLSamplerDescriptor alloc] init];
        sd.minFilter = linear_min ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        sd.magFilter = linear_mag ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
        sd.mipFilter = (clamped_lod > 0.25f) ? MTLSamplerMipFilterLinear : MTLSamplerMipFilterNearest;
        sd.sAddressMode = repeat_u ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
        sd.tAddressMode = repeat_v ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
        sd.maxAnisotropy = aniso;
        sd.lodMinClamp = 0.0f;
        sd.lodMaxClamp = clamped_lod;

        id<MTLSamplerState> samp = [ctx->device newSamplerStateWithDescriptor:sd];
        if (!samp) {
            return 0;
        }

        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        const MetalitySamplerHandle handle = ctx->next_resource_handle++;
        metality::SamplerEntry entry;
        entry.handle = handle;
        entry.packed_key = key;
        entry.sampler = samp;
        ctx->fast_samp_publish_locked(handle, samp);
        ctx->sampler_cache.emplace(key, std::move(entry));
        ctx->sampler_handle_to_key.emplace(handle, key);
        return handle;
    }
}

MetalityPsoHandle metality_pso_create(
    MetalityContext* ctx,
    const MetalityPipelineDesc* desc,
    char* err_buf,
    uint32_t err_buf_len
) {
    if (!ctx || !ctx->device || !desc || !desc->vertex_entry || !desc->fragment_entry) {
        return 0;
    }
    @autoreleasepool {
        id<MTLLibrary> lib = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto it = ctx->shader_libraries.find(desc->shader_library);
            if (it == ctx->shader_libraries.end()) {
                return 0;
            }
            lib = it->second.library;
        }

        id<MTLFunction> vfn = [lib newFunctionWithName:[NSString stringWithUTF8String:desc->vertex_entry]];
        id<MTLFunction> ffn = [lib newFunctionWithName:[NSString stringWithUTF8String:desc->fragment_entry]];
        if (!vfn || !ffn) {
            if (err_buf && err_buf_len > 0) {
                std::strncpy(err_buf, "PSO shader entry function not found", err_buf_len - 1);
                err_buf[err_buf_len - 1] = '\0';
            }
            return 0;
        }

        MTLRenderPipelineDescriptor* pd = [[MTLRenderPipelineDescriptor alloc] init];
        pd.vertexFunction = vfn;
        pd.fragmentFunction = ffn;
        if (desc->color_format != 0) {
            pd.colorAttachments[0].pixelFormat = map_pixel_format(desc->color_format);
            apply_blend_mode(pd.colorAttachments[0], desc->blend_mode, desc->color_write_mask ? desc->color_write_mask : 0xF);
        } else {
            pd.colorAttachments[0].pixelFormat = MTLPixelFormatInvalid;
        }

        if (vfn.vertexAttributes.count > 0) {
            MTLVertexDescriptor* auto_vd = [MTLVertexDescriptor vertexDescriptor];
            uint32_t offset_acc = 0;
            for (MTLVertexAttribute* attr in vfn.vertexAttributes) {
                if (!attr.active) continue;
                MTLVertexFormat vf = MTLVertexFormatFloat4;
                uint32_t sz = msl_data_type_size_and_format(attr.attributeType, &vf);
                auto_vd.attributes[attr.attributeIndex].format = vf;
                auto_vd.attributes[attr.attributeIndex].offset = offset_acc;
                auto_vd.attributes[attr.attributeIndex].bufferIndex = METALITY_VERTEX_BUFFER_SLOT_BASE;
                offset_acc += sz;
            }
            if (offset_acc > 0) {
                auto_vd.layouts[METALITY_VERTEX_BUFFER_SLOT_BASE].stride = offset_acc;
                auto_vd.layouts[METALITY_VERTEX_BUFFER_SLOT_BASE].stepFunction = MTLVertexStepFunctionPerVertex;
                auto_vd.layouts[METALITY_VERTEX_BUFFER_SLOT_BASE].stepRate = 1;
                pd.vertexDescriptor = auto_vd;
            }
        }

        if (desc->depth_format != 0) {
            pd.depthAttachmentPixelFormat = map_pixel_format(desc->depth_format);
        }
        if (ctx->binary_archive) {
            pd.binaryArchives = @[ ctx->binary_archive ];
        }

        NSError* err = nil;
        id<MTLRenderPipelineState> pso = [ctx->device newRenderPipelineStateWithDescriptor:pd error:&err];
        if (!pso) {
            if (err_buf && err_buf_len > 0) {
                const char* msg = (err && err.localizedDescription) ? err.localizedDescription.UTF8String : "PSO creation failed";
                std::strncpy(err_buf, msg, err_buf_len - 1);
                err_buf[err_buf_len - 1] = '\0';
            }
            return 0;
        }

        if (ctx->binary_archive) {
            NSError* arch_err = nil;
            if ([ctx->binary_archive addRenderPipelineFunctionsWithDescriptor:pd error:&arch_err]) {
                ctx->archive_dirty = true;
            }
        }

        id<MTLDepthStencilState> dss = nil;
        if (desc->depth_format != 0) {
            MTLDepthStencilDescriptor* dd = [[MTLDepthStencilDescriptor alloc] init];
            dd.depthCompareFunction = map_depth_compare(desc->depth_compare);
            dd.depthWriteEnabled = desc->depth_write_enabled ? YES : NO;
            dss = [ctx->device newDepthStencilStateWithDescriptor:dd];
        }

        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        const MetalityPsoHandle handle = ctx->next_resource_handle++;
        metality::PipelineStateEntry entry;
        entry.handle = handle;
        entry.render_pso = pso;
        entry.depth_stencil = dss;
        entry.primitive_type = MTLPrimitiveTypeTriangle;
        entry.fill_mode = MTLTriangleFillModeFill;
        entry.cull_mode = desc->cull_mode;
        ctx->fast_pso_publish_locked(entry);
        ctx->render_psos.emplace(handle, std::move(entry));
        return handle;
    }
}

MetalityPsoHandle create_pso_ex_internal(
    MetalityContext* ctx,
    const MetalityPipelineDescEx* desc,
    uint32_t color_count,
    const int32_t* extra_color_formats,
    const int32_t* extra_blend_modes,
    const uint8_t* extra_write_masks,
    char* err_buf,
    uint32_t err_buf_len
) {
    if (!ctx || !ctx->device || !desc || !desc->vertex_entry || !desc->fragment_entry) {
        return 0;
    }
    @autoreleasepool {
        id<MTLLibrary> vlib = nil;
        id<MTLLibrary> flib = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto vit = ctx->shader_libraries.find(desc->vertex_library);
            if (vit == ctx->shader_libraries.end()) {
                return 0;
            }
            vlib = vit->second.library;
            const MetalityShaderHandle frag_handle =
                desc->fragment_library != 0 ? desc->fragment_library : desc->vertex_library;
            auto fit = ctx->shader_libraries.find(frag_handle);
            if (fit == ctx->shader_libraries.end()) {
                return 0;
            }
            flib = fit->second.library;
        }

        id<MTLFunction> vfn = [vlib newFunctionWithName:[NSString stringWithUTF8String:desc->vertex_entry]];
        id<MTLFunction> ffn = [flib newFunctionWithName:[NSString stringWithUTF8String:desc->fragment_entry]];
        if (!vfn || !ffn) {
            if (err_buf && err_buf_len > 0) {
                std::strncpy(err_buf, "PSO EX shader entry function not found", err_buf_len - 1);
                err_buf[err_buf_len - 1] = '\0';
            }
            return 0;
        }

        MTLRenderPipelineDescriptor* pd = [[MTLRenderPipelineDescriptor alloc] init];
        pd.vertexFunction = vfn;
        pd.fragmentFunction = ffn;
        if (desc->color_format != 0 && color_count > 0) {
            pd.colorAttachments[0].pixelFormat = map_pixel_format(desc->color_format);
            apply_blend_mode(pd.colorAttachments[0], static_cast<int32_t>(desc->blend_mode), desc->color_write_mask);
        } else {
            pd.colorAttachments[0].pixelFormat = MTLPixelFormatInvalid;
        }

        const uint32_t clamped_colors = color_count > 4 ? 4u : color_count;
        for (uint32_t idx = 1; idx < clamped_colors; ++idx) {
            const int32_t fmt = extra_color_formats ? extra_color_formats[idx - 1] : 0;
            if (fmt != 0) {
                pd.colorAttachments[idx].pixelFormat = map_pixel_format(static_cast<MetalityPixelFormat>(fmt));
                const int32_t bm = extra_blend_modes ? extra_blend_modes[idx - 1] : METALITY_BLEND_OPAQUE;
                const uint8_t wm = extra_write_masks ? extra_write_masks[idx - 1] : 0x0Fu;
                apply_blend_mode(pd.colorAttachments[idx], bm, wm);
            } else {
                pd.colorAttachments[idx].pixelFormat = MTLPixelFormatInvalid;
            }
        }

        if (desc->vb_count > 0 && desc->vb_layouts && desc->attr_count > 0 && desc->attributes) {
            MTLVertexDescriptor* vd = [MTLVertexDescriptor vertexDescriptor];
            for (uint32_t i = 0; i < desc->vb_count; ++i) {
                const uint32_t buf_idx = METALITY_VERTEX_BUFFER_SLOT_BASE + desc->vb_layouts[i].slot;
                if (buf_idx >= 31) continue;
                const uint32_t safe_stride = desc->vb_layouts[i].stride > 0 ? desc->vb_layouts[i].stride : 16u;
                vd.layouts[buf_idx].stride = safe_stride;
                if (desc->vb_layouts[i].step_rate > 0) {
                    vd.layouts[buf_idx].stepFunction = MTLVertexStepFunctionPerInstance;
                    vd.layouts[buf_idx].stepRate = desc->vb_layouts[i].step_rate;
                } else {
                    vd.layouts[buf_idx].stepFunction = MTLVertexStepFunctionPerVertex;
                    vd.layouts[buf_idx].stepRate = 1;
                }
            }
            for (uint32_t i = 0; i < desc->attr_count; ++i) {
                const uint32_t loc = desc->attributes[i].location;
                const uint32_t buf_idx = METALITY_VERTEX_BUFFER_SLOT_BASE + desc->attributes[i].buffer_slot;
                if (loc >= 31 || buf_idx >= 31) continue;
                vd.attributes[loc].format = map_vertex_format(desc->attributes[i].format);
                vd.attributes[loc].offset = desc->attributes[i].offset;
                vd.attributes[loc].bufferIndex = buf_idx;
            }
            pd.vertexDescriptor = vd;
        }

        if (desc->depth_format != 0) {
            pd.depthAttachmentPixelFormat = map_pixel_format(desc->depth_format);
        }
        if (ctx->binary_archive) {
            pd.binaryArchives = @[ ctx->binary_archive ];
        }

        NSError* err = nil;
        id<MTLRenderPipelineState> pso = [ctx->device newRenderPipelineStateWithDescriptor:pd error:&err];
        if (!pso) {
            if (err_buf && err_buf_len > 0) {
                const char* msg = (err && err.localizedDescription) ? err.localizedDescription.UTF8String : "PSO EX creation failed";
                std::strncpy(err_buf, msg, err_buf_len - 1);
                err_buf[err_buf_len - 1] = '\0';
            }
            return 0;
        }

        if (ctx->binary_archive) {
            NSError* arch_err = nil;
            if ([ctx->binary_archive addRenderPipelineFunctionsWithDescriptor:pd error:&arch_err]) {
                ctx->archive_dirty = true;
            }
        }

        id<MTLDepthStencilState> dss = nil;
        if (desc->depth_format != 0) {
            MTLDepthStencilDescriptor* dd = [[MTLDepthStencilDescriptor alloc] init];
            dd.depthCompareFunction = map_depth_compare(desc->depth_compare);
            dd.depthWriteEnabled = desc->depth_write_enabled ? YES : NO;
            dss = [ctx->device newDepthStencilStateWithDescriptor:dd];
        }

        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        const MetalityPsoHandle handle = ctx->next_resource_handle++;
        metality::PipelineStateEntry entry;
        entry.handle = handle;
        entry.render_pso = pso;
        entry.depth_stencil = dss;
        entry.primitive_type = map_primitive_topology(desc->topology);
        entry.fill_mode = desc->wireframe ? MTLTriangleFillModeLines : MTLTriangleFillModeFill;
        entry.cull_mode = desc->cull_mode;
        ctx->fast_pso_publish_locked(entry);
        ctx->render_psos.emplace(handle, std::move(entry));
        return handle;
    }
}

MetalityPsoHandle metality_pso_create_ex(
    MetalityContext* ctx,
    const MetalityPipelineDescEx* desc,
    char* err_buf,
    uint32_t err_buf_len
) {
    return create_pso_ex_internal(ctx, desc, 1u, nullptr, nullptr, nullptr, err_buf, err_buf_len);
}

MetalityPsoHandle metality_pso_create_mrt(
    MetalityContext* ctx,
    const MetalityPipelineMrtDesc* desc,
    char* err_buf,
    uint32_t err_buf_len
) {
    if (!desc) {
        return 0;
    }
    return create_pso_ex_internal(
        ctx,
        &desc->base,
        desc->color_attachment_count,
        desc->extra_color_formats,
        desc->extra_blend_modes,
        desc->extra_write_masks,
        err_buf,
        err_buf_len
    );
}

MetalityPsoHandle metality_pso_get_builtin_blaze3d(
    MetalityContext* ctx,
    MetalityBuiltinPipeline builtin_kind,
    MetalityPixelFormat color_format,
    MetalityPixelFormat depth_format,
    MetalityBlendMode blend_mode
) {
    if (!ctx) {
        return 0;
    }
    const uint64_t cache_key = (static_cast<uint64_t>(builtin_kind) << 32) |
                               (static_cast<uint64_t>(color_format) << 16) |
                               (static_cast<uint64_t>(depth_format) << 8) |
                               static_cast<uint64_t>(blend_mode);
    {
        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        auto it = ctx->builtin_pso_cache.find(cache_key);
        if (it != ctx->builtin_pso_cache.end()) {
            return it->second;
        }
    }

    if (ctx->builtin_blaze3d_lib == 0) {
        char err[512] = {0};
        ctx->builtin_blaze3d_lib = metality_compile_msl_library(
            ctx, "MetalityBuiltinBlaze3D", kBuiltinBlaze3dMsl, true, err, sizeof(err));
        if (ctx->builtin_blaze3d_lib == 0) {
            return 0;
        }
    }

    const char* vs_name = "vs_pos_color";
    const char* fs_name = "fs_pos_color";
    switch (builtin_kind) {
        case METALITY_BUILTIN_POSITION_COLOR:
            vs_name = "vs_pos_color";
            fs_name = "fs_pos_color";
            break;
        case METALITY_BUILTIN_POSITION_TEX:
            vs_name = "vs_pos_tex";
            fs_name = "fs_pos_tex_color";
            break;
        case METALITY_BUILTIN_POSITION_TEX_COLOR:
            vs_name = "vs_pos_tex_color";
            fs_name = "fs_pos_tex_color";
            break;
        case METALITY_BUILTIN_RENDERTYPE_TEXT:
            vs_name = "vs_pos_tex_color";
            fs_name = "fs_rendertype_text";
            break;
        case METALITY_BUILTIN_BLIT_SCREEN:
            vs_name = "vs_blit_fullscreen";
            fs_name = "fs_blit_screen";
            break;
        case METALITY_BUILTIN_BLIT_PRESENT_YFLIP:
            vs_name = "vs_blit_present_yflip";
            fs_name = "fs_blit_present_linear";
            break;
        case METALITY_BUILTIN_BLIT_PRESENT_YFLIP_SRGB_TO_LINEAR:
            vs_name = "vs_blit_present_yflip";
            fs_name = "fs_blit_present_srgb_to_linear";
            break;
        case METALITY_BUILTIN_CLEAR_RECT:
            vs_name = "vs_clear_rect";
            fs_name = "fs_clear_rect";
            break;
    }

    MetalityPipelineDesc pd{};
    pd.shader_library = ctx->builtin_blaze3d_lib;
    pd.vertex_entry = vs_name;
    pd.fragment_entry = fs_name;
    pd.color_format = color_format;
    pd.depth_format = depth_format;
    pd.blend_mode = blend_mode;
    if (builtin_kind == METALITY_BUILTIN_CLEAR_RECT) {
        pd.depth_compare = METALITY_DEPTH_ALWAYS;
        pd.depth_write_enabled = (depth_format != 0) ? 1 : 0;
        pd.color_write_mask = (color_format != 0) ? 0x1F : 0x10;
    } else {
        pd.depth_compare = depth_format != 0 ? METALITY_DEPTH_LEQUAL : METALITY_DEPTH_ALWAYS;
        pd.depth_write_enabled = (depth_format != 0 && blend_mode == METALITY_BLEND_OPAQUE) ? 1 : 0;
        pd.color_write_mask = 0xF;
    }
    pd.cull_mode = METALITY_CULL_NONE;

    char err[256] = {0};
    MetalityPsoHandle pso = metality_pso_create(ctx, &pd, err, sizeof(err));
    if (pso != 0) {
        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        ctx->builtin_pso_cache.emplace(cache_key, pso);
    }
    return pso;
}

bool begin_render_pass_internal(
    MetalityContext* ctx,
    const MetalityTextureHandle* color_targets,
    uint8_t color_count,
    const float* clear_colors,
    const uint8_t* should_clear_color,
    const uint8_t* store_color,
    MetalityTextureHandle depth_target,
    double clear_depth,
    uint8_t should_clear_depth,
    uint8_t store_depth,
    bool allow_swapchain_fallback
) {
    if (!ctx || !ctx->active_command_buffer) {
        return false;
    }
    @autoreleasepool {
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
        }

        id<MTLTexture> resolved_colors[4] = {nil, nil, nil, nil};
        id<MTLTexture> depth_tex = nil;
        const uint8_t count = color_count > 4 ? 4u : color_count;
        bool has_any_color = false;
        uint32_t pass_w = 0;
        uint32_t pass_h = 0;

        for (uint8_t i = 0; i < count; ++i) {
            const MetalityTextureHandle h = color_targets[i];
            if (h == 0) {
                if (i == 0 && allow_swapchain_fallback) {
                    if (!ctx->active_drawable && ctx->metal_layer) {
                        ctx->active_drawable = [ctx->metal_layer nextDrawable];
                    }
                    if (ctx->active_drawable) {
                        resolved_colors[0] = ctx->active_drawable.texture;
                    }
                }
            } else {
                std::lock_guard<std::mutex> lock(ctx->resource_mutex);
                auto it = ctx->textures.find(h);
                if (it != ctx->textures.end()) {
                    resolved_colors[i] = it->second.texture;
                }
            }
            id<MTLTexture> ct = resolved_colors[i];
            if (ct) {
                if ((ct.usage & MTLTextureUsageRenderTarget) == 0 || ct.pixelFormat == MTLPixelFormatDepth32Float) {
                    resolved_colors[i] = nil;
                } else {
                    has_any_color = true;
                    if (pass_w == 0) {
                        pass_w = static_cast<uint32_t>(ct.width);
                        pass_h = static_cast<uint32_t>(ct.height);
                    }
                }
            }
        }

        if (depth_target != 0) {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto it = ctx->textures.find(depth_target);
            if (it != ctx->textures.end()) {
                depth_tex = it->second.texture;
            }
        }
        if (depth_tex) {
            if ((depth_tex.usage & MTLTextureUsageRenderTarget) == 0
                    || depth_tex.pixelFormat != MTLPixelFormatDepth32Float
                    || (pass_w > 0 && (depth_tex.width != pass_w || depth_tex.height != pass_h))) {
                depth_tex = nil;
            } else if (pass_w == 0) {
                pass_w = static_cast<uint32_t>(depth_tex.width);
                pass_h = static_cast<uint32_t>(depth_tex.height);
            }
        }

        if (!has_any_color && !depth_tex) {
            return false;
        }

        MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
        for (uint8_t i = 0; i < count; ++i) {
            if (!resolved_colors[i]) continue;
            rpd.colorAttachments[i].texture = resolved_colors[i];
            rpd.colorAttachments[i].loadAction = should_clear_color[i] ? MTLLoadActionClear : MTLLoadActionLoad;
            rpd.colorAttachments[i].storeAction = store_color[i] ? MTLStoreActionStore : MTLStoreActionDontCare;
            const size_t base = static_cast<size_t>(i) * 4u;
            const float cr = std::isfinite(clear_colors[base + 0]) ? clear_colors[base + 0] : 0.0f;
            const float cg = std::isfinite(clear_colors[base + 1]) ? clear_colors[base + 1] : 0.0f;
            const float cb = std::isfinite(clear_colors[base + 2]) ? clear_colors[base + 2] : 0.0f;
            const float ca = std::isfinite(clear_colors[base + 3]) ? clear_colors[base + 3] : 1.0f;
            rpd.colorAttachments[i].clearColor = MTLClearColorMake(cr, cg, cb, ca);
        }
        if (depth_tex) {
            const double cd = std::isfinite(clear_depth) ? std::clamp(clear_depth, 0.0, 1.0) : 1.0;
            rpd.depthAttachment.texture = depth_tex;
            rpd.depthAttachment.loadAction = should_clear_depth ? MTLLoadActionClear : MTLLoadActionLoad;
            rpd.depthAttachment.storeAction = store_depth ? MTLStoreActionStore : MTLStoreActionDontCare;
            rpd.depthAttachment.clearDepth = cd;
        }

        ctx->active_pass_width = pass_w;
        ctx->active_pass_height = pass_h;
        ctx->active_render_encoder = [ctx->active_command_buffer renderCommandEncoderWithDescriptor:rpd];
        ctx->enc_state.reset();
        if (!ctx->active_render_encoder) {
            ctx->active_pass_width = 0;
            ctx->active_pass_height = 0;
            return false;
        }
        return true;
    }
}

bool metality_cmd_begin_render_pass(
    MetalityContext* ctx,
    const MetalityRenderPassBeginInfo* begin_info
) {
    if (!begin_info) {
        return false;
    }
    const MetalityTextureHandle color_targets[4] = {begin_info->color_target, 0, 0, 0};
    float clear_colors[16] = {
        begin_info->clear_color[0],
        begin_info->clear_color[1],
        begin_info->clear_color[2],
        begin_info->clear_color[3]
    };
    const uint8_t should_clear[4] = {begin_info->should_clear_color, 0, 0, 0};
    const uint8_t store_color[4] = {begin_info->store_color, 0, 0, 0};
    return begin_render_pass_internal(
        ctx,
        color_targets,
        1u,
        clear_colors,
        should_clear,
        store_color,
        begin_info->depth_target,
        begin_info->clear_depth,
        begin_info->should_clear_depth,
        begin_info->store_depth,
        true
    );
}

bool metality_cmd_begin_render_pass_mrt(
    MetalityContext* ctx,
    const MetalityMrtRenderPassBeginInfo* begin_info
) {
    if (!begin_info) {
        return false;
    }
    const bool swapchain_fallback = (begin_info->color_count == 1 && begin_info->color_targets[0] == 0);
    return begin_render_pass_internal(
        ctx,
        begin_info->color_targets,
        begin_info->color_count,
        begin_info->clear_colors,
        begin_info->should_clear_color,
        begin_info->store_color,
        begin_info->depth_target,
        begin_info->clear_depth,
        begin_info->should_clear_depth,
        begin_info->store_depth,
        swapchain_fallback
    );
}

void metality_cmd_set_pso(MetalityContext* ctx, MetalityPsoHandle pso_handle) {
    if (!ctx || !ctx->active_render_encoder || pso_handle == 0) {
        return;
    }
    ctx->enc_state.ensure_encoder((__bridge const void*)ctx->active_render_encoder);
    if (ctx->enc_state.bound_pso == pso_handle) {
        return;
    }
    FastPsoInfo info{};
    if (!fast_lookup_pso(ctx, pso_handle, info)) {
        return;
    }
    ctx->enc_state.bound_pso = pso_handle;
    [ctx->active_render_encoder setRenderPipelineState:info.pso];
    if (info.dss) {
        [ctx->active_render_encoder setDepthStencilState:info.dss];
    }
    [ctx->active_render_encoder setCullMode:map_cull_mode(info.cull)];
    [ctx->active_render_encoder setTriangleFillMode:info.fill];
    ctx->active_primitive_type = info.prim;
}

void metality_cmd_set_depth_bias(MetalityContext* ctx, float depth_bias, float slope_scale) {
    if (!ctx || !ctx->active_render_encoder) {
        return;
    }
    ctx->enc_state.ensure_encoder((__bridge const void*)ctx->active_render_encoder);
    if (ctx->enc_state.has_depth_bias &&
        ctx->enc_state.bound_depth_bias == depth_bias &&
        ctx->enc_state.bound_slope_scale == slope_scale) {
        return;
    }
    ctx->enc_state.has_depth_bias = true;
    ctx->enc_state.bound_depth_bias = depth_bias;
    ctx->enc_state.bound_slope_scale = slope_scale;
    [ctx->active_render_encoder setDepthBias:depth_bias slopeScale:slope_scale clamp:0.0f];
}

void metality_cmd_set_viewport(MetalityContext* ctx, float x, float y, float w, float h, float znear, float zfar) {
    if (!ctx || !ctx->active_render_encoder) {
        return;
    }
    MTLViewport vp = {x, y, w, h, znear, zfar};
    [ctx->active_render_encoder setViewport:vp];
}

void metality_cmd_set_scissor(MetalityContext* ctx, uint32_t x, uint32_t y, uint32_t w, uint32_t h) {
    if (!ctx || !ctx->active_render_encoder || w == 0 || h == 0) {
        return;
    }
    uint32_t sx = x;
    uint32_t sy = y;
    uint32_t sw = w;
    uint32_t sh = h;
    if (ctx->active_pass_width > 0 && ctx->active_pass_height > 0) {
        sx = std::min(x, ctx->active_pass_width - 1u);
        sy = std::min(y, ctx->active_pass_height - 1u);
        sw = std::clamp(w, 1u, ctx->active_pass_width - sx);
        sh = std::clamp(h, 1u, ctx->active_pass_height - sy);
    }
    MTLScissorRect sc = {sx, sy, sw, sh};
    [ctx->active_render_encoder setScissorRect:sc];
}

void metality_cmd_bind_vertex_buffer(MetalityContext* ctx, uint32_t slot, MetalityBufferHandle buf_handle, uint64_t offset) {
    if (!ctx || !ctx->active_render_encoder || buf_handle == 0) {
        return;
    }
    ctx->enc_state.ensure_encoder((__bridge const void*)ctx->active_render_encoder);
    if (slot < 32u && ctx->enc_state.bound_vb[slot] == buf_handle && ctx->enc_state.bound_vb_off[slot] == offset) {
        return;
    }
    __unsafe_unretained id<MTLBuffer> buf = nil;
    if (!fast_lookup_buffer(ctx, buf_handle, buf)) {
        return;
    }
    if (slot < 32u) {
        ctx->enc_state.bound_vb[slot] = buf_handle;
        ctx->enc_state.bound_vb_off[slot] = offset;
    }
    if (slot < 16u) {
        // Vertex buffer table is shared with uniform buffers on Metal; invalidate UB cache for this slot.
        ctx->enc_state.bound_ub[slot] = 0;
        ctx->enc_state.bound_ub_off[slot] = UINT64_MAX;
    }
    [ctx->active_render_encoder setVertexBuffer:buf offset:offset atIndex:slot];
}

void metality_cmd_bind_uniform_buffer(MetalityContext* ctx, uint32_t slot, MetalityBufferHandle buf_handle, uint64_t offset) {
    if (!ctx || !ctx->active_render_encoder || buf_handle == 0) {
        return;
    }
    ctx->enc_state.ensure_encoder((__bridge const void*)ctx->active_render_encoder);
    if (slot < 16u && ctx->enc_state.bound_ub[slot] == buf_handle && ctx->enc_state.bound_ub_off[slot] == offset) {
        return;
    }
    __unsafe_unretained id<MTLBuffer> buf = nil;
    if (!fast_lookup_buffer(ctx, buf_handle, buf)) {
        return;
    }
    if (slot < 16u) {
        ctx->enc_state.bound_ub[slot] = buf_handle;
        ctx->enc_state.bound_ub_off[slot] = offset;
    }
    if (slot < 32u) {
        ctx->enc_state.bound_vb[slot] = 0;
        ctx->enc_state.bound_vb_off[slot] = UINT64_MAX;
    }
    [ctx->active_render_encoder setVertexBuffer:buf offset:offset atIndex:slot];
    [ctx->active_render_encoder setFragmentBuffer:buf offset:offset atIndex:slot];
}

void metality_cmd_bind_uniform_bytes(MetalityContext* ctx, uint32_t slot, const void* data, uint32_t len) {
    if (!ctx || !ctx->active_render_encoder || !data || len == 0 || len > 4096) {
        return;
    }
    ctx->enc_state.ensure_encoder((__bridge const void*)ctx->active_render_encoder);
    if (slot < 16u) {
        ctx->enc_state.bound_ub[slot] = 0;
        ctx->enc_state.bound_ub_off[slot] = UINT64_MAX;
    }
    if (slot < 32u) {
        ctx->enc_state.bound_vb[slot] = 0;
        ctx->enc_state.bound_vb_off[slot] = UINT64_MAX;
    }
    [ctx->active_render_encoder setVertexBytes:data length:len atIndex:slot];
    [ctx->active_render_encoder setFragmentBytes:data length:len atIndex:slot];
}

void metality_cmd_bind_texture(MetalityContext* ctx, uint32_t slot, MetalityTextureHandle tex_handle, MetalitySamplerHandle samp_handle) {
    if (!ctx || !ctx->active_render_encoder) {
        return;
    }
    ctx->enc_state.ensure_encoder((__bridge const void*)ctx->active_render_encoder);
    const bool need_tex = (tex_handle != 0) && !(slot < 16u && ctx->enc_state.bound_tex[slot] == tex_handle);
    const bool need_samp = (samp_handle != 0) && !(slot < 16u && ctx->enc_state.bound_samp[slot] == samp_handle);
    if (!need_tex && !need_samp) {
        return;
    }
    if (need_tex) {
        __unsafe_unretained id<MTLTexture> tex = nil;
        if (fast_lookup_texture(ctx, tex_handle, tex)) {
            if (slot < 16u) ctx->enc_state.bound_tex[slot] = tex_handle;
            [ctx->active_render_encoder setVertexTexture:tex atIndex:slot];
            [ctx->active_render_encoder setFragmentTexture:tex atIndex:slot];
        }
    }
    if (need_samp) {
        __unsafe_unretained id<MTLSamplerState> samp = nil;
        if (fast_lookup_sampler(ctx, samp_handle, samp)) {
            if (slot < 16u) {
                ctx->enc_state.bound_samp[slot] = samp_handle;
                [ctx->active_render_encoder setVertexSamplerState:samp atIndex:slot];
            }
            [ctx->active_render_encoder setFragmentSamplerState:samp atIndex:slot];
        }
    }
}

void metality_cmd_bind_texel_buffer(
    MetalityContext* ctx,
    uint32_t slot,
    MetalityBufferHandle buf_handle,
    uint64_t offset,
    uint64_t size_bytes,
    MetalityPixelFormat format
) {
    if (!ctx || !ctx->active_render_encoder || !ctx->device || buf_handle == 0 || size_bytes == 0) {
        return;
    }
    ctx->enc_state.ensure_encoder((__bridge const void*)ctx->active_render_encoder);
    if (slot < 16u) {
        ctx->enc_state.bound_tex[slot] = 0;
    }
    const MTLPixelFormat pf = map_pixel_format(format);
    const uint32_t pf_raw = static_cast<uint32_t>(pf);
    id<MTLTexture> cached_view = nil;
    id<MTLBuffer> buf = nil;
    uint8_t* cpu_ptr = nullptr;
    {
        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        auto it = ctx->live_buffers.find(buf_handle);
        if (it == ctx->live_buffers.end()) {
            return;
        }
        auto& entry = it->second;
        if (entry.cached_tex_view &&
            entry.cached_tex_offset == offset &&
            entry.cached_tex_size == size_bytes &&
            entry.cached_tex_fmt == pf_raw) {
            cached_view = entry.cached_tex_view;
        } else {
            buf = entry.mtl_buffer;
            cpu_ptr = static_cast<uint8_t*>(entry.cpu_ptr);
        }
    }
    if (cached_view) {
        [ctx->active_render_encoder setVertexTexture:cached_view atIndex:slot];
        [ctx->active_render_encoder setFragmentTexture:cached_view atIndex:slot];
        return;
    }
    @autoreleasepool {
        if (!buf) {
            return;
        }
        const uint32_t bpp = bytes_per_pixel(pf);
        const NSUInteger elem_count = static_cast<NSUInteger>(size_bytes / bpp);
        if (elem_count == 0) {
            return;
        }
        NSUInteger align = [ctx->device minimumTextureBufferAlignmentForPixelFormat:pf];
        if (align == 0) {
            align = 16;
        }
        const NSUInteger aligned_bpr = ((elem_count * bpp + align - 1) / align) * align;

        id<MTLTexture> tex_view = nil;
        if ((offset % align) == 0 && (offset + aligned_bpr) <= buf.length) {
            MTLTextureDescriptor* td = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:pf
                                                                                              width:elem_count
                                                                                    resourceOptions:buf.resourceOptions
                                                                                              usage:MTLTextureUsageShaderRead];
            tex_view = [buf newTextureWithDescriptor:td
                                              offset:static_cast<NSUInteger>(offset)
                                         bytesPerRow:aligned_bpr];
            if (tex_view) {
                std::lock_guard<std::mutex> lock(ctx->resource_mutex);
                auto it = ctx->live_buffers.find(buf_handle);
                if (it != ctx->live_buffers.end() && it->second.mtl_buffer == buf) {
                    it->second.cached_tex_view = tex_view;
                    it->second.cached_tex_offset = offset;
                    it->second.cached_tex_size = size_bytes;
                    it->second.cached_tex_fmt = pf_raw;
                }
            }
        } else if (cpu_ptr != nullptr) {
            const NSUInteger copy_bytes = static_cast<NSUInteger>(
                std::min<uint64_t>(size_bytes, offset < buf.length ? (buf.length - offset) : 0)
            );
            id<MTLBuffer> scratch = [ctx->device newBufferWithLength:aligned_bpr
                                                             options:MTLResourceStorageModeShared];
            if (scratch && copy_bytes > 0) {
                std::memcpy(scratch.contents, cpu_ptr + offset, copy_bytes);
                MTLTextureDescriptor* td = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:pf
                                                                                                  width:elem_count
                                                                                        resourceOptions:MTLResourceStorageModeShared
                                                                                                  usage:MTLTextureUsageShaderRead];
                tex_view = [scratch newTextureWithDescriptor:td offset:0 bytesPerRow:aligned_bpr];
            }
        }
        if (tex_view) {
            [ctx->active_render_encoder setVertexTexture:tex_view atIndex:slot];
            [ctx->active_render_encoder setFragmentTexture:tex_view atIndex:slot];
        }
    }
}

void metality_cmd_draw(MetalityContext* ctx, uint32_t vertex_start, uint32_t vertex_count) {
    if (!ctx || !ctx->active_render_encoder || vertex_count == 0) {
        return;
    }
    [ctx->active_render_encoder drawPrimitives:ctx->active_primitive_type
                                   vertexStart:vertex_start
                                   vertexCount:vertex_count];
}

void metality_cmd_draw_indexed_u32(
    MetalityContext* ctx,
    MetalityBufferHandle index_buf,
    uint64_t index_byte_offset,
    uint32_t index_count
) {
    if (!ctx || !ctx->active_render_encoder || index_buf == 0 || index_count == 0) {
        return;
    }
    __unsafe_unretained id<MTLBuffer> ib = nil;
    if (!fast_lookup_buffer(ctx, index_buf, ib)) {
        return;
    }
    [ctx->active_render_encoder drawIndexedPrimitives:ctx->active_primitive_type
                                           indexCount:index_count
                                            indexType:MTLIndexTypeUInt32
                                          indexBuffer:ib
                                    indexBufferOffset:index_byte_offset];
}

void metality_cmd_draw_indexed_instanced(
    MetalityContext* ctx,
    MetalityBufferHandle index_buf,
    uint64_t index_byte_offset,
    uint32_t index_count,
    bool index_is_u16,
    uint32_t instance_count,
    int32_t base_vertex,
    uint32_t base_instance
) {
    if (!ctx || !ctx->active_render_encoder || index_buf == 0 || index_count == 0 || instance_count == 0) {
        return;
    }
    __unsafe_unretained id<MTLBuffer> ib = nil;
    if (!fast_lookup_buffer(ctx, index_buf, ib)) {
        return;
    }
    [ctx->active_render_encoder drawIndexedPrimitives:ctx->active_primitive_type
                                           indexCount:index_count
                                            indexType:(index_is_u16 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32)
                                          indexBuffer:ib
                                    indexBufferOffset:index_byte_offset
                                        instanceCount:instance_count
                                           baseVertex:base_vertex
                                         baseInstance:base_instance];
}

void metality_cmd_draw_indexed_indirect(
    MetalityContext* ctx,
    MetalityBufferHandle index_buf,
    bool index_is_u16,
    MetalityBufferHandle indirect_buf,
    uint64_t indirect_byte_offset,
    uint32_t draw_count
) {
    if (!ctx || !ctx->active_render_encoder || index_buf == 0 || indirect_buf == 0 || draw_count == 0) {
        return;
    }
    if ((indirect_byte_offset % 4ull) != 0ull) {
        return;
    }
    uint64_t ind_size = 0;
    void* ind_cpu = nullptr;
    __unsafe_unretained id<MTLBuffer> ib = nil;
    __unsafe_unretained id<MTLBuffer> ind_buf = nil;
    if (!fast_lookup_buffer(ctx, index_buf, ib) ||
        !fast_lookup_buffer(ctx, indirect_buf, ind_buf, &ind_size, &ind_cpu) ||
        indirect_byte_offset > ind_size ||
        static_cast<uint64_t>(draw_count) * 20ull > (ind_size - indirect_byte_offset)) {
        return;
    }
    const MTLIndexType idx_type = index_is_u16 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32;
    const auto* raw_cmds = ind_cpu
        ? reinterpret_cast<const uint32_t*>(static_cast<const uint8_t*>(ind_cpu) + indirect_byte_offset)
        : nullptr;
    for (uint32_t i = 0; i < draw_count; ++i) {
        // UMA CPU-written indirect buffer: skip 0-index / 0-instance culled chunk section slots (-50% CPU encoding!)
        if (raw_cmds && (raw_cmds[i * 5u] == 0u || raw_cmds[i * 5u + 1u] == 0u)) {
            continue;
        }
        const NSUInteger arg_offset = static_cast<NSUInteger>(indirect_byte_offset + static_cast<uint64_t>(i) * 20ull);
        [ctx->active_render_encoder drawIndexedPrimitives:ctx->active_primitive_type
                                                indexType:idx_type
                                              indexBuffer:ib
                                        indexBufferOffset:0
                                           indirectBuffer:ind_buf
                                     indirectBufferOffset:arg_offset];
    }
}

void metality_cmd_end_render_pass(MetalityContext* ctx) {
    if (!ctx || !ctx->active_render_encoder) {
        return;
    }
    [ctx->active_render_encoder endEncoding];
    ctx->active_render_encoder = nil;
    ctx->enc_state.reset();
    ctx->active_pass_width = 0;
    ctx->active_pass_height = 0;
    ctx->active_primitive_type = MTLPrimitiveTypeTriangle;
}

void metality_cmd_clear_color_depth(
    MetalityContext* ctx,
    MetalityTextureHandle color_tex,
    float r, float g, float b, float a,
    MetalityTextureHandle depth_tex,
    double clear_depth
) {
    if (!ctx || (color_tex == 0 && depth_tex == 0)) {
        return;
    }
    @autoreleasepool {
        if (ctx->active_render_encoder && std::this_thread::get_id() == ctx->render_thread_id) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
            ctx->active_pass_width = 0;
            ctx->active_pass_height = 0;
        }
        id<MTLTexture> ctex = nil;
        id<MTLTexture> dtex = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            if (color_tex != 0) {
                auto it = ctx->textures.find(color_tex);
                if (it != ctx->textures.end()) ctex = it->second.texture;
            }
            if (depth_tex != 0) {
                auto it = ctx->textures.find(depth_tex);
                if (it != ctx->textures.end()) dtex = it->second.texture;
            }
        }
        if (ctex && ctex.pixelFormat == MTLPixelFormatDepth32Float) {
            if (!dtex) dtex = ctex;
            ctex = nil;
        }
        if (dtex && dtex.pixelFormat != MTLPixelFormatDepth32Float) {
            dtex = nil;
        }

        const float cr = std::isfinite(r) ? r : 0.0f;
        const float cg = std::isfinite(g) ? g : 0.0f;
        const float cb = std::isfinite(b) ? b : 0.0f;
        const float ca = std::isfinite(a) ? a : 1.0f;
        const double cd = std::isfinite(clear_depth) ? std::clamp(clear_depth, 0.0, 1.0) : 1.0;

        if (ctex && ((ctex.usage & MTLTextureUsageRenderTarget) == 0)) {
            if (ctex.storageMode != MTLStorageModeMemoryless && color_tex != 0) {
                const uint32_t w = static_cast<uint32_t>(ctex.width);
                const uint32_t h = static_cast<uint32_t>(ctex.height);
                const uint32_t bpp = bytes_per_pixel(ctex.pixelFormat);
                std::vector<uint8_t> fill(static_cast<size_t>(w) * h * bpp, 0u);
                if (bpp == 4u && (ctex.pixelFormat == MTLPixelFormatRGBA8Unorm || ctex.pixelFormat == MTLPixelFormatRGBA8Unorm_sRGB)) {
                    const uint8_t c0 = static_cast<uint8_t>(std::clamp(cr * 255.0f + 0.5f, 0.0f, 255.0f));
                    const uint8_t c1 = static_cast<uint8_t>(std::clamp(cg * 255.0f + 0.5f, 0.0f, 255.0f));
                    const uint8_t c2 = static_cast<uint8_t>(std::clamp(cb * 255.0f + 0.5f, 0.0f, 255.0f));
                    const uint8_t c3 = static_cast<uint8_t>(std::clamp(ca * 255.0f + 0.5f, 0.0f, 255.0f));
                    for (size_t px = 0; px < fill.size(); px += 4u) {
                        fill[px + 0] = c0;
                        fill[px + 1] = c1;
                        fill[px + 2] = c2;
                        fill[px + 3] = c3;
                    }
                }
                const uint32_t slice_cnt = (ctex.textureType == MTLTextureTypeCube) ? 6u : 1u;
                for (uint32_t s = 0; s < slice_cnt; ++s) {
                    metality_texture_upload_slice_region(ctx, color_tex, 0, s, 0, 0, w, h, fill.data(), w * bpp);
                }
            }
            ctex = nil;
        }
        if (dtex && ((dtex.usage & MTLTextureUsageRenderTarget) == 0)) {
            dtex = nil;
        }
        if (!ctex && !dtex) {
            return;
        }

        const bool use_active = (ctx->active_command_buffer != nil) && (std::this_thread::get_id() == ctx->render_thread_id);
        id<MTLCommandBuffer> cmd_buf = use_active ? ctx->active_command_buffer : [ctx->command_queue commandBuffer];
        if (!cmd_buf) {
            return;
        }

        if (ctex && ctex.textureType == MTLTextureTypeCube) {
            for (uint32_t s = 0; s < 6u; ++s) {
                MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
                rpd.colorAttachments[0].texture = ctex;
                rpd.colorAttachments[0].slice = s;
                rpd.colorAttachments[0].loadAction = MTLLoadActionClear;
                rpd.colorAttachments[0].storeAction = MTLStoreActionStore;
                rpd.colorAttachments[0].clearColor = MTLClearColorMake(cr, cg, cb, ca);
                id<MTLRenderCommandEncoder> enc = [cmd_buf renderCommandEncoderWithDescriptor:rpd];
                if (enc) {
                    [enc endEncoding];
                }
            }
            ctex = nil;
        }

        if (ctex && dtex && (ctex.width != dtex.width || ctex.height != dtex.height)) {
            MTLRenderPassDescriptor* crpd = [MTLRenderPassDescriptor renderPassDescriptor];
            crpd.colorAttachments[0].texture = ctex;
            crpd.colorAttachments[0].loadAction = MTLLoadActionClear;
            crpd.colorAttachments[0].storeAction = MTLStoreActionStore;
            crpd.colorAttachments[0].clearColor = MTLClearColorMake(cr, cg, cb, ca);
            id<MTLRenderCommandEncoder> cenc = [cmd_buf renderCommandEncoderWithDescriptor:crpd];
            if (cenc) {
                [cenc endEncoding];
            }

            MTLRenderPassDescriptor* drpd = [MTLRenderPassDescriptor renderPassDescriptor];
            drpd.depthAttachment.texture = dtex;
            drpd.depthAttachment.loadAction = MTLLoadActionClear;
            drpd.depthAttachment.storeAction = MTLStoreActionStore;
            drpd.depthAttachment.clearDepth = cd;
            id<MTLRenderCommandEncoder> denc = [cmd_buf renderCommandEncoderWithDescriptor:drpd];
            if (denc) {
                [denc endEncoding];
            }
        } else if (ctex || dtex) {
            MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
            if (ctex) {
                rpd.colorAttachments[0].texture = ctex;
                rpd.colorAttachments[0].loadAction = MTLLoadActionClear;
                rpd.colorAttachments[0].storeAction = MTLStoreActionStore;
                rpd.colorAttachments[0].clearColor = MTLClearColorMake(cr, cg, cb, ca);
            }
            if (dtex) {
                rpd.depthAttachment.texture = dtex;
                rpd.depthAttachment.loadAction = MTLLoadActionClear;
                rpd.depthAttachment.storeAction = MTLStoreActionStore;
                rpd.depthAttachment.clearDepth = cd;
            }
            id<MTLRenderCommandEncoder> enc = [cmd_buf renderCommandEncoderWithDescriptor:rpd];
            if (enc) {
                [enc endEncoding];
            }
            if (ctex && ctex.mipmapLevelCount > 1) {
                for (NSUInteger mip = 1; mip < ctex.mipmapLevelCount; ++mip) {
                    MTLRenderPassDescriptor* mip_rpd = [MTLRenderPassDescriptor renderPassDescriptor];
                    mip_rpd.colorAttachments[0].texture = ctex;
                    mip_rpd.colorAttachments[0].level = mip;
                    mip_rpd.colorAttachments[0].loadAction = MTLLoadActionClear;
                    mip_rpd.colorAttachments[0].storeAction = MTLStoreActionStore;
                    mip_rpd.colorAttachments[0].clearColor = MTLClearColorMake(cr, cg, cb, ca);
                    id<MTLRenderCommandEncoder> mip_enc = [cmd_buf renderCommandEncoderWithDescriptor:mip_rpd];
                    if (mip_enc) {
                        [mip_enc endEncoding];
                    }
                }
            }
        }

        if (!use_active) {
            [cmd_buf commit];
            [cmd_buf waitUntilCompleted];
        }
    }
}

void metality_cmd_clear_color_depth_rect(
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
) {
    if (!ctx || !ctx->active_command_buffer || (color_tex == 0 && depth_tex == 0) || width == 0 || height == 0) {
        return;
    }
    @autoreleasepool {
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
            ctx->active_pass_width = 0;
            ctx->active_pass_height = 0;
        }
        id<MTLTexture> ctex = nil;
        id<MTLTexture> dtex = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            if (color_tex != 0) {
                auto it = ctx->textures.find(color_tex);
                if (it != ctx->textures.end()) ctex = it->second.texture;
            }
            if (depth_tex != 0) {
                auto it = ctx->textures.find(depth_tex);
                if (it != ctx->textures.end()) dtex = it->second.texture;
            }
        }
        if (ctex && ((ctex.usage & MTLTextureUsageRenderTarget) == 0 || ctex.pixelFormat == MTLPixelFormatDepth32Float)) {
            ctex = nil;
        }
        if (dtex && ((dtex.usage & MTLTextureUsageRenderTarget) == 0 || dtex.pixelFormat != MTLPixelFormatDepth32Float)) {
            dtex = nil;
        }
        if (!ctex && !dtex) {
            return;
        }
        id<MTLTexture> ref_tex = ctex ? ctex : dtex;
        const uint32_t safe_mip = std::min(mip_level, static_cast<uint32_t>(std::max<NSUInteger>(1, ref_tex.mipmapLevelCount) - 1));
        const uint32_t mip_w = std::max(1u, static_cast<uint32_t>(ref_tex.width >> safe_mip));
        const uint32_t mip_h = std::max(1u, static_cast<uint32_t>(ref_tex.height >> safe_mip));
        if (x >= mip_w || y >= mip_h) {
            return;
        }
        const uint32_t cw = std::min(width, mip_w - x);
        const uint32_t ch = std::min(height, mip_h - y);
        if (cw == 0 || ch == 0) {
            return;
        }
        if (safe_mip == 0 && x == 0 && y == 0 && cw == mip_w && ch == mip_h) {
            metality_cmd_clear_color_depth(ctx, color_tex, r, g, b, a, depth_tex, clear_depth);
            return;
        }
        if (ctex && dtex && (ctex.width != dtex.width || ctex.height != dtex.height)) {
            dtex = nil;
        }

        const MetalityPixelFormat color_fmt = ctex ? reverse_map_pixel_format(ctex.pixelFormat) : static_cast<MetalityPixelFormat>(0);
        const MetalityPixelFormat depth_fmt = dtex ? METALITY_FORMAT_DEPTH32_FLOAT : static_cast<MetalityPixelFormat>(0);
        const MetalityPsoHandle pso = metality_pso_get_builtin_blaze3d(
            ctx, METALITY_BUILTIN_CLEAR_RECT, color_fmt, depth_fmt, METALITY_BLEND_OPAQUE
        );
        if (pso == 0) {
            return;
        }

        MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
        if (ctex) {
            rpd.colorAttachments[0].texture = ctex;
            rpd.colorAttachments[0].level = safe_mip;
            rpd.colorAttachments[0].loadAction = MTLLoadActionLoad;
            rpd.colorAttachments[0].storeAction = MTLStoreActionStore;
        }
        if (dtex) {
            rpd.depthAttachment.texture = dtex;
            rpd.depthAttachment.level = safe_mip;
            rpd.depthAttachment.loadAction = MTLLoadActionLoad;
            rpd.depthAttachment.storeAction = MTLStoreActionStore;
        }

        id<MTLRenderCommandEncoder> enc = [ctx->active_command_buffer renderCommandEncoderWithDescriptor:rpd];
        if (!enc) {
            return;
        }
        ctx->active_render_encoder = enc;
        ctx->enc_state.reset();
        ctx->active_pass_width = mip_w;
        ctx->active_pass_height = mip_h;

        MTLViewport vp = {
            static_cast<double>(x),
            static_cast<double>(y),
            static_cast<double>(cw),
            static_cast<double>(ch),
            0.0,
            1.0
        };
        [enc setViewport:vp];
        MTLScissorRect sc = {x, y, cw, ch};
        [enc setScissorRect:sc];
        metality_cmd_set_pso(ctx, pso);

        const float cd = static_cast<float>(std::isfinite(clear_depth) ? std::clamp(clear_depth, 0.0, 1.0) : 1.0);
        struct ClearUniforms {
            float mvp[16];
            float color[4];
        } cu{};
        cu.mvp[0] = cd;
        cu.color[0] = std::isfinite(r) ? r : 0.0f;
        cu.color[1] = std::isfinite(g) ? g : 0.0f;
        cu.color[2] = std::isfinite(b) ? b : 0.0f;
        cu.color[3] = std::isfinite(a) ? a : 1.0f;
        [enc setVertexBytes:&cu length:sizeof(cu) atIndex:0];
        [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [enc endEncoding];
        ctx->active_render_encoder = nil;
        ctx->enc_state.reset();
        ctx->active_pass_width = 0;
        ctx->active_pass_height = 0;
        ctx->active_primitive_type = MTLPrimitiveTypeTriangle;
    }
}

void metality_cmd_present_blit_texture(
    MetalityContext* ctx,
    MetalityTextureHandle src_color_tex
) {
    if (!ctx || !ctx->active_command_buffer || src_color_tex == 0) {
        return;
    }
    @autoreleasepool {
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
        }
        if (!ctx->active_drawable && ctx->metal_layer) {
            ctx->active_drawable = [ctx->metal_layer nextDrawable];
        }
        if (!ctx->active_drawable || !ctx->active_drawable.texture) {
            return;
        }
        id<MTLTexture> src_tex = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto it = ctx->textures.find(src_color_tex);
            if (it == ctx->textures.end()) {
                return;
            }
            src_tex = it->second.texture;
        }
        id<MTLTexture> dst_tex = ctx->active_drawable.texture;
        const MTLPixelFormat src_pf = src_tex ? src_tex.pixelFormat : MTLPixelFormatInvalid;
        const MTLPixelFormat dst_pf = dst_tex.pixelFormat;
        const bool src_is_encoded_srgb_unorm =
            (src_pf == MTLPixelFormatRGBA8Unorm ||
             src_pf == MTLPixelFormatBGRA8Unorm ||
             src_pf == MTLPixelFormatRGB10A2Unorm);
        const bool dst_expects_linear =
            (dst_pf == MTLPixelFormatRGBA16Float ||
             dst_pf == MTLPixelFormatBGRA8Unorm_sRGB ||
             dst_pf == MTLPixelFormatRGBA8Unorm_sRGB);
        const MetalityBuiltinPipeline present_kind =
            (src_is_encoded_srgb_unorm && dst_expects_linear)
                ? METALITY_BUILTIN_BLIT_PRESENT_YFLIP_SRGB_TO_LINEAR
                : METALITY_BUILTIN_BLIT_PRESENT_YFLIP;

        const MetalityPixelFormat dst_fmt = reverse_map_pixel_format(dst_pf);
        const MetalityPsoHandle blit_pso = metality_pso_get_builtin_blaze3d(
            ctx, present_kind, dst_fmt, static_cast<MetalityPixelFormat>(0), METALITY_BLEND_OPAQUE);
        if (blit_pso == 0) {
            return;
        }

        MetalitySamplerDesc sd{};
        sd.linear_min_mag = 1;
        sd.linear_mipmap = 0;
        sd.repeat_wrap = 0;
        sd.max_anisotropy = 1;
        const MetalitySamplerHandle samp = metality_sampler_get_or_create(ctx, &sd);

        MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
        rpd.colorAttachments[0].texture = dst_tex;
        rpd.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        rpd.colorAttachments[0].storeAction = MTLStoreActionStore;

        id<MTLRenderCommandEncoder> enc = [ctx->active_command_buffer renderCommandEncoderWithDescriptor:rpd];
        if (!enc) {
            return;
        }
        ctx->active_render_encoder = enc;
        ctx->enc_state.reset();
        MTLViewport vp = {0.0, 0.0, static_cast<double>(dst_tex.width), static_cast<double>(dst_tex.height), 0.0, 1.0};
        [enc setViewport:vp];
        metality_cmd_set_pso(ctx, blit_pso);
        metality_cmd_bind_texture(ctx, 0, src_color_tex, samp);
        [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [enc endEncoding];
        ctx->active_render_encoder = nil;
        ctx->enc_state.reset();
        ctx->active_primitive_type = MTLPrimitiveTypeTriangle;
    }
}

void metality_cmd_copy_buffer(
    MetalityContext* ctx,
    MetalityBufferHandle src_buf,
    uint64_t src_off,
    MetalityBufferHandle dst_buf,
    uint64_t dst_off,
    uint64_t size
) {
    if (!ctx || src_buf == 0 || dst_buf == 0 || size == 0) {
        return;
    }
    @autoreleasepool {
        id<MTLBuffer> src = nil;
        id<MTLBuffer> dst = nil;
        uint8_t* src_cpu = nullptr;
        uint8_t* dst_cpu = nullptr;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto sit = ctx->live_buffers.find(src_buf);
            auto dit = ctx->live_buffers.find(dst_buf);
            if (sit == ctx->live_buffers.end() || dit == ctx->live_buffers.end()) {
                return;
            }
            if (src_off + size > sit->second.size_bytes || dst_off + size > dit->second.size_bytes) {
                return;
            }
            src = sit->second.mtl_buffer;
            dst = dit->second.mtl_buffer;
            src_cpu = static_cast<uint8_t*>(sit->second.cpu_ptr);
            dst_cpu = static_cast<uint8_t*>(dit->second.cpu_ptr);
        }
        if (ctx->active_command_buffer && std::this_thread::get_id() == ctx->render_thread_id) {
            if (ctx->active_render_encoder) {
                [ctx->active_render_encoder endEncoding];
                ctx->active_render_encoder = nil;
                ctx->enc_state.reset();
                ctx->active_pass_width = 0;
                ctx->active_pass_height = 0;
            }
            id<MTLBlitCommandEncoder> blit = [ctx->active_command_buffer blitCommandEncoder];
            [blit copyFromBuffer:src sourceOffset:src_off toBuffer:dst destinationOffset:dst_off size:size];
            [blit endEncoding];
        } else if (src_cpu && dst_cpu && ctx->current_frame_id == 0) {
            std::memmove(dst_cpu + dst_off, src_cpu + src_off, static_cast<size_t>(size));
        } else {
            id<MTLCommandBuffer> transient = [ctx->command_queue commandBuffer];
            if (transient) {
                id<MTLBlitCommandEncoder> blit = [transient blitCommandEncoder];
                [blit copyFromBuffer:src sourceOffset:src_off toBuffer:dst destinationOffset:dst_off size:size];
                [blit endEncoding];
                [transient commit];
                [transient waitUntilCompleted];
            }
        }
    }
}

void metality_cmd_copy_texture_to_buffer(
    MetalityContext* ctx,
    MetalityTextureHandle src_tex,
    MetalityBufferHandle dst_buf,
    uint64_t dst_offset,
    uint32_t bytes_per_row,
    uint32_t width,
    uint32_t height
) {
    if (!ctx || !ctx->active_command_buffer || src_tex == 0 || dst_buf == 0 || width == 0 || height == 0) {
        return;
    }
    @autoreleasepool {
        id<MTLTexture> tex = nil;
        id<MTLBuffer> buf = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto tit = ctx->textures.find(src_tex);
            auto bit = ctx->live_buffers.find(dst_buf);
            if (tit == ctx->textures.end() || bit == ctx->live_buffers.end()) {
                return;
            }
            tex = tit->second.texture;
            buf = bit->second.mtl_buffer;
        }
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
        }
        const uint32_t bpr = bytes_per_row > 0 ? bytes_per_row : (width * bytes_per_pixel(tex.pixelFormat));
        id<MTLBlitCommandEncoder> blit = [ctx->active_command_buffer blitCommandEncoder];
        [blit copyFromTexture:tex
                  sourceSlice:0
                  sourceLevel:0
                 sourceOrigin:MTLOriginMake(0, 0, 0)
                   sourceSize:MTLSizeMake(width, height, 1)
                     toBuffer:buf
            destinationOffset:dst_offset
       destinationBytesPerRow:bpr
     destinationBytesPerImage:static_cast<NSUInteger>(bpr) * height];
        [blit endEncoding];
    }
}

void metality_cmd_copy_texture_region(
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
) {
    if (!ctx || !ctx->active_command_buffer || src_tex == 0 || dst_tex == 0 || width == 0 || height == 0) {
        return;
    }
    @autoreleasepool {
        id<MTLTexture> src = nil;
        id<MTLTexture> dst = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto sit = ctx->textures.find(src_tex);
            auto dit = ctx->textures.find(dst_tex);
            if (sit == ctx->textures.end() || dit == ctx->textures.end()) {
                return;
            }
            src = sit->second.texture;
            dst = dit->second.texture;
        }
        if (!src || !dst) {
            return;
        }
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
        }

        const uint32_t src_mip_w = std::max(1u, static_cast<uint32_t>(src.width >> mip_level));
        const uint32_t src_mip_h = std::max(1u, static_cast<uint32_t>(src.height >> mip_level));
        const uint32_t dst_mip_w = std::max(1u, static_cast<uint32_t>(dst.width >> mip_level));
        const uint32_t dst_mip_h = std::max(1u, static_cast<uint32_t>(dst.height >> mip_level));
        if (src_x >= src_mip_w || src_y >= src_mip_h || dst_x >= dst_mip_w || dst_y >= dst_mip_h) {
            return;
        }
        const uint32_t cw = std::min(width, std::min(src_mip_w - src_x, dst_mip_w - dst_x));
        const uint32_t ch = std::min(height, std::min(src_mip_h - src_y, dst_mip_h - dst_y));
        if (cw == 0 || ch == 0) {
            return;
        }

        if (src.pixelFormat == dst.pixelFormat) {
            id<MTLBlitCommandEncoder> blit = [ctx->active_command_buffer blitCommandEncoder];
            [blit copyFromTexture:src
                      sourceSlice:0
                      sourceLevel:mip_level
                     sourceOrigin:MTLOriginMake(src_x, src_y, 0)
                       sourceSize:MTLSizeMake(cw, ch, 1)
                        toTexture:dst
                 destinationSlice:0
                 destinationLevel:mip_level
                destinationOrigin:MTLOriginMake(dst_x, dst_y, 0)];
            [blit endEncoding];
            return;
        }

        const MetalityPixelFormat dst_fmt = reverse_map_pixel_format(dst.pixelFormat);
        const MetalityPsoHandle blit_pso = metality_pso_get_builtin_blaze3d(
            ctx, METALITY_BUILTIN_BLIT_SCREEN, dst_fmt, static_cast<MetalityPixelFormat>(0), METALITY_BLEND_OPAQUE);
        if (blit_pso == 0) {
            return;
        }
        MetalitySamplerDesc sd{};
        sd.linear_min_mag = 0;
        sd.linear_mipmap = 0;
        sd.repeat_wrap = 0;
        sd.max_anisotropy = 1;
        const MetalitySamplerHandle samp = metality_sampler_get_or_create(ctx, &sd);

        MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
        rpd.colorAttachments[0].texture = dst;
        rpd.colorAttachments[0].level = mip_level;
        rpd.colorAttachments[0].loadAction = MTLLoadActionLoad;
        rpd.colorAttachments[0].storeAction = MTLStoreActionStore;

        id<MTLRenderCommandEncoder> enc = [ctx->active_command_buffer renderCommandEncoderWithDescriptor:rpd];
        if (!enc) {
            return;
        }
        ctx->active_render_encoder = enc;
        ctx->enc_state.reset();
        MTLViewport vp = {
            static_cast<double>(dst_x),
            static_cast<double>(dst_y),
            static_cast<double>(cw),
            static_cast<double>(ch),
            0.0,
            1.0
        };
        [enc setViewport:vp];
        metality_cmd_set_pso(ctx, blit_pso);
        metality_cmd_bind_texture(ctx, 0, src_tex, samp);
        [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [enc endEncoding];
        ctx->active_render_encoder = nil;
        ctx->enc_state.reset();
        ctx->active_primitive_type = MTLPrimitiveTypeTriangle;
    }
}

} // extern "C"

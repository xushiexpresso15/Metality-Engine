#import "metality/metal_context.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

constexpr const char* kComposerMsl = R"(
#include <metal_stdlib>
using namespace metal;

struct ComposerUniforms {
    float4x4 invProj;
    float4x4 viewRot;
    float4x4 proj;
    float4 sunWorldDir;
    float4 skyColor;
    uint width;
    uint height;
    float timeSeconds;
    float rainStrength;
    float bloomStrength;
    float ssaoStrength;
    float godrayStrength;
    float exposure;
    float fogDensity;
    uint tonemapMode;
    uint depthReversed;
    uint reservedPad;
};

struct ComposerVarying {
    float4 position [[position]];
    float2 uv;
};

vertex ComposerVarying vs_metality_composer(uint vid [[vertex_id]]) {
    // Matches Metality vs_blit_fullscreen: uv = (0,0) maps to y_tex = 0 (GL bottom),
    // uv = (0,1) maps to y_tex = height (GL top/sky) when SPIRV-Cross FLIP_VERTEX_Y is active.
    constexpr float2 pos[3] = { float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0) };
    constexpr float2 uvs[3] = { float2(0.0, 1.0),   float2(2.0, 1.0),  float2(0.0, -1.0) };
    ComposerVarying out;
    out.position = float4(pos[vid % 3], 0.0, 1.0);
    out.uv = uvs[vid % 3];
    return out;
}

inline bool is_sky_depth(float d, uint depthReversed) {
    return depthReversed != 0u ? (d <= 1e-6f) : (d >= 0.99999f);
}

inline float3 reconstruct_view_pos(float2 uv, float d, constant ComposerUniforms& u) {
    float4 clip = float4(uv * 2.0f - 1.0f, d, 1.0f);
    float4 v = u.invProj * clip;
    return v.xyz / max(abs(v.w), 1e-5f) * (v.w < 0.0f ? -1.0f : 1.0f);
}

inline float hash12(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031f);
    p3 += dot(p3, p3.yzx + 33.33f);
    return fract((p3.x + p3.y) * p3.z);
}

fragment half4 fs_metality_world_lighting(
    ComposerVarying in [[stage_in]],
    texture2d<float> colorTex [[texture(0)]],
    texture2d<float> depthTex [[texture(1)]],
    constant ComposerUniforms& u [[buffer(0)]]
) {
    constexpr sampler sLinear(coord::normalized, address::clamp_to_edge, filter::linear);
    constexpr sampler sPoint(coord::normalized, address::clamp_to_edge, filter::nearest);

    float4 src = colorTex.sample(sLinear, in.uv);
    float d = depthTex.sample(sPoint, in.uv).r;
    float3 linearColor = pow(max(src.rgb, float3(0.0f)), float3(2.2f));
    bool centerSky = is_sky_depth(d, u.depthReversed);

    // 1. Horizon SSAO (non-sky geometry only, depth-discontinuity protected)
    if (!centerSky && u.ssaoStrength > 0.001f) {
        float3 vPos = reconstruct_view_pos(in.uv, d, u);
        float centerDist = max(0.5f, -vPos.z);
        float2 texelSize = float2(1.0f / max(1u, u.width), 1.0f / max(1u, u.height));
        float angle = hash12(in.uv * float2(u.width, u.height)) * 6.2831853f;
        float radiusPx = clamp(26.0f / (centerDist * 0.28f + 1.0f), 2.5f, 16.0f);

        float occ = 0.0f;
        float weightSum = 0.0f;
        constexpr float goldenAngle = 2.3999632f;
        for (int i = 0; i < 8; ++i) {
            float frac = (float(i) + 0.5f) / 8.0f;
            float theta = angle + float(i) * goldenAngle;
            float2 dir = float2(cos(theta), sin(theta));
            float2 suv = clamp(in.uv + dir * (frac * radiusPx) * texelSize, float2(0.001f), float2(0.999f));
            float sd = depthTex.sample(sPoint, suv).r;
            if (is_sky_depth(sd, u.depthReversed)) {
                continue;
            }
            float3 svPos = reconstruct_view_pos(suv, sd, u);
            float dz = svPos.z - vPos.z;
            if (abs(dz) > 1.35f) {
                continue;
            }
            float3 delta = svPos - vPos;
            float distSq = dot(delta, delta);
            if (distSq > 0.0005f && dz > 0.025f) {
                float atten = 1.0f / (1.0f + distSq * 2.4f);
                occ += clamp(dz * 1.45f, 0.0f, 1.0f) * atten;
            }
            weightSum += 1.0f;
        }
        float aoFactor = weightSum > 0.0f ? (occ / max(weightSum, 1.0f)) : 0.0f;
        float shade = 1.0f - clamp(aoFactor * u.ssaoStrength * 0.85f, 0.0f, 0.55f);
        linearColor *= shade;
    }

    // 2. Crisp surface relief + subtle rain sheen boost on non-sky surfaces
    if (!centerSky && u.rainStrength > 0.01f) {
        float lum = dot(linearColor, float3(0.2126f, 0.7152f, 0.0722f));
        float wetSpec = smoothstep(0.18f, 0.70f, lum) * 0.14f * u.rainStrength;
        linearColor = mix(linearColor * (1.0f - 0.10f * u.rainStrength), linearColor + float3(0.75f, 0.85f, 1.0f) * wetSpec, 0.6f);
    }

    // 3. Volumetric Sun Lightshafts (16-step screen-space radial raymarch toward projected sun)
    if (u.godrayStrength > 0.001f && u.rainStrength < 0.85f) {
        float3 sunDir = normalize(u.sunWorldDir.xyz);
        if (sunDir.y > -0.18f) {
            float4 sunView = u.viewRot * float4(sunDir, 0.0f);
            if (sunView.z < -0.02f) {
                float4 sunClip = u.proj * float4(sunView.xyz, 1.0f);
                if (sunClip.w > 1e-4f) {
                    float2 sunUv = (sunClip.xy / sunClip.w) * 0.5f + 0.5f;
                    float2 toSun = sunUv - in.uv;
                    float distToSun = length(toSun);
                    if (distToSun < 1.35f) {
                        float stepScale = min(distToSun, 0.75f) / 16.0f;
                        float2 stepDir = (distToSun > 1e-4f) ? (toSun / distToSun) * stepScale : float2(0.0f);
                        float jitter = hash12(in.uv * float2(u.width, u.height) + float2(u.timeSeconds * 13.0f, 0.0f));
                        float2 rayUv = in.uv + stepDir * jitter;
                        float skyAccum = 0.0f;
                        for (int s = 0; s < 16; ++s) {
                            if (rayUv.x >= 0.0f && rayUv.x <= 1.0f && rayUv.y >= 0.0f && rayUv.y <= 1.0f) {
                                float rd = depthTex.sample(sPoint, rayUv).r;
                                if (is_sky_depth(rd, u.depthReversed)) {
                                    skyAccum += 1.0f;
                                }
                            }
                            rayUv += stepDir;
                        }
                        float rayVis = skyAccum / 16.0f;
                        float radialMask = pow(clamp(1.0f - distToSun / 1.15f, 0.0f, 1.0f), 2.1f);
                        float elevationWarmth = clamp(1.0f - max(sunDir.y, 0.0f) * 1.4f, 0.0f, 1.0f);
                        float3 shaftColor = mix(float3(1.0f, 0.93f, 0.80f), float3(1.0f, 0.58f, 0.24f), elevationWarmth);
                        float dayWeight = smoothstep(-0.15f, 0.12f, sunDir.y) * (1.0f - u.rainStrength * 0.85f);
                        float occlusionScale = centerSky ? 0.35f : 0.24f;
                        linearColor += shaftColor * (rayVis * radialMask * u.godrayStrength * dayWeight * occlusionScale);
                    }
                }
            }
        }
    }

    // 4. Warm sunlit highlights + subtle atmospheric aerial perspective on terrain
    float daylight = clamp(u.sunWorldDir.y * 1.35f + 0.15f, 0.0f, 1.0f) * (1.0f - u.rainStrength * 0.7f);
    if (!centerSky) {
        linearColor *= mix(float3(0.95f, 0.97f, 1.04f), float3(1.06f, 1.02f, 0.94f), daylight);
    }

    return half4(half3(linearColor), half(src.a));
}

fragment half4 fs_metality_bloom_downsample(
    ComposerVarying in [[stage_in]],
    texture2d<half> hdrTex [[texture(0)]],
    constant ComposerUniforms& u [[buffer(0)]]
) {
    constexpr sampler sLinear(coord::normalized, address::clamp_to_edge, filter::linear);
    float2 texel = float2(1.0f / max(1u, u.width), 1.0f / max(1u, u.height));
    half3 c0 = hdrTex.sample(sLinear, in.uv + float2(-texel.x, -texel.y)).rgb;
    half3 c1 = hdrTex.sample(sLinear, in.uv + float2( texel.x, -texel.y)).rgb;
    half3 c2 = hdrTex.sample(sLinear, in.uv + float2(-texel.x,  texel.y)).rgb;
    half3 c3 = hdrTex.sample(sLinear, in.uv + float2( texel.x,  texel.y)).rgb;
    half3 avg = (c0 + c1 + c2 + c3) * 0.25h;
    half luma = dot(avg, half3(0.2126h, 0.7152h, 0.0722h));
    half knee = clamp((luma - 0.55h) / 0.65h, 0.0h, 1.0h);
    return half4(avg * knee * knee, 1.0h);
}

fragment half4 fs_metality_bloom_blur(
    ComposerVarying in [[stage_in]],
    texture2d<half> bloomTex [[texture(0)]],
    constant ComposerUniforms& u [[buffer(0)]]
) {
    constexpr sampler sLinear(coord::normalized, address::clamp_to_edge, filter::linear);
    float2 texel = float2(4.0f / max(1u, u.width), 4.0f / max(1u, u.height));
    half3 sum = bloomTex.sample(sLinear, in.uv).rgb * 0.36h;
    sum += bloomTex.sample(sLinear, in.uv + float2(-1.5f, -1.5f) * texel).rgb * 0.16h;
    sum += bloomTex.sample(sLinear, in.uv + float2( 1.5f, -1.5f) * texel).rgb * 0.16h;
    sum += bloomTex.sample(sLinear, in.uv + float2(-1.5f,  1.5f) * texel).rgb * 0.16h;
    sum += bloomTex.sample(sLinear, in.uv + float2( 1.5f,  1.5f) * texel).rgb * 0.16h;
    return half4(sum, 1.0h);
}

inline float3 aces_tonemap(float3 x) {
    float a = 2.51f;
    float b = 0.03f;
    float c = 2.43f;
    float d = 0.59f;
    float e = 0.14f;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0f, 1.0f);
}

fragment half4 fs_metality_final_composite(
    ComposerVarying in [[stage_in]],
    texture2d<float> hdrTex [[texture(0)]],
    texture2d<float> bloomTex [[texture(1)]],
    constant ComposerUniforms& u [[buffer(0)]]
) {
    constexpr sampler sLinear(coord::normalized, address::clamp_to_edge, filter::linear);
    float4 hdr = hdrTex.sample(sLinear, in.uv);
    float3 bloom = (u.bloomStrength > 0.001f) ? bloomTex.sample(sLinear, in.uv).rgb : float3(0.0f);

    float3 col = (hdr.rgb + bloom * (u.bloomStrength * 0.42f)) * max(u.exposure, 0.05f);
    if (u.tonemapMode == 0u) {
        // Gentle ACES filmic curve anchored so midtones match Minecraft daylight brightness
        col = aces_tonemap(col * 0.88f);
    } else if (u.tonemapMode == 1u) {
        col = col / (1.0f + col);
    } else {
        col = clamp(col, 0.0f, 1.0f);
    }

    float3 srgb = pow(clamp(col, 0.0f, 1.0f), float3(1.0f / 2.2f));
    float dither = (hash12(in.uv * float2(u.width, u.height)) - 0.5f) / 255.0f;
    return half4(half3(clamp(srgb + dither, 0.0f, 1.0f)), half(hdr.a));
}
)";

} // namespace

struct MetalityComposerImpl {
    id<MTLDevice> device = nil;
    id<MTLRenderPipelineState> lighting_pso = nil;
    id<MTLRenderPipelineState> bloom_down_pso = nil;
    id<MTLRenderPipelineState> bloom_blur_pso = nil;
    id<MTLRenderPipelineState> final_pso = nil;

    id<MTLTexture> hdr_scene_tex = nil;
    id<MTLTexture> bloom_a_tex = nil;
    id<MTLTexture> bloom_b_tex = nil;
    uint32_t alloc_w = 0;
    uint32_t alloc_h = 0;
    MTLPixelFormat alloc_final_fmt = MTLPixelFormatInvalid;
};

static id<MTLRenderPipelineState> build_quad_pso(
    id<MTLDevice> dev,
    id<MTLLibrary> lib,
    NSString* frag_name,
    MTLPixelFormat color_fmt
) {
    id<MTLFunction> vfn = [lib newFunctionWithName:@"vs_metality_composer"];
    id<MTLFunction> ffn = [lib newFunctionWithName:frag_name];
    if (!vfn || !ffn) {
        return nil;
    }
    MTLRenderPipelineDescriptor* rpd = [[MTLRenderPipelineDescriptor alloc] init];
    rpd.vertexFunction = vfn;
    rpd.fragmentFunction = ffn;
    rpd.colorAttachments[0].pixelFormat = color_fmt;
    NSError* err = nil;
    return [dev newRenderPipelineStateWithDescriptor:rpd error:&err];
}

extern "C" {

MetalityComposerHandle metality_composer_create(MetalityContext* ctx) {
    if (!ctx || !ctx->device) {
        return nullptr;
    }
    @autoreleasepool {
        MTLCompileOptions* opts = [[MTLCompileOptions alloc] init];
        opts.languageVersion = MTLLanguageVersion3_1;
        opts.mathMode = MTLMathModeFast;
        NSError* err = nil;
        id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:kComposerMsl]
                                                       options:opts
                                                         error:&err];
        if (!lib) {
            return nullptr;
        }
        auto* comp = new MetalityComposerImpl();
        comp->device = ctx->device;
        comp->lighting_pso = build_quad_pso(ctx->device, lib, @"fs_metality_world_lighting", MTLPixelFormatRGBA16Float);
        comp->bloom_down_pso = build_quad_pso(ctx->device, lib, @"fs_metality_bloom_downsample", MTLPixelFormatRGBA16Float);
        comp->bloom_blur_pso = build_quad_pso(ctx->device, lib, @"fs_metality_bloom_blur", MTLPixelFormatRGBA16Float);
        comp->final_pso = build_quad_pso(ctx->device, lib, @"fs_metality_final_composite", MTLPixelFormatRGBA8Unorm);
        comp->alloc_final_fmt = MTLPixelFormatRGBA8Unorm;
        if (!comp->lighting_pso || !comp->bloom_down_pso || !comp->bloom_blur_pso || !comp->final_pso) {
            delete comp;
            return nullptr;
        }
        return comp;
    }
}

void metality_composer_destroy(MetalityComposerHandle composer) {
    delete composer;
}

bool metality_composer_execute(
    MetalityContext* ctx,
    MetalityComposerHandle composer,
    MetalityTextureHandle inout_color_tex,
    MetalityTextureHandle depth_tex,
    const MetalityComposerUniforms* uniforms
) {
    if (!ctx || !ctx->device || !composer || !uniforms || inout_color_tex == 0 || depth_tex == 0) {
        return false;
    }
    @autoreleasepool {
        id<MTLTexture> ctex = nil;
        id<MTLTexture> dtex = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto cit = ctx->textures.find(inout_color_tex);
            auto dit = ctx->textures.find(depth_tex);
            if (cit == ctx->textures.end() || dit == ctx->textures.end()) {
                return false;
            }
            ctex = cit->second.texture;
            dtex = dit->second.texture;
        }
        if (!ctex || !dtex || ctex.width == 0 || ctex.height == 0) {
            return false;
        }

        const uint32_t w = static_cast<uint32_t>(ctex.width);
        const uint32_t h = static_cast<uint32_t>(ctex.height);
        if (composer->alloc_w != w || composer->alloc_h != h || !composer->hdr_scene_tex) {
            MTLTextureDescriptor* hd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float
                                                                                          width:w
                                                                                         height:h
                                                                                      mipmapped:NO];
            hd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
            hd.storageMode = MTLStorageModePrivate;
            composer->hdr_scene_tex = [ctx->device newTextureWithDescriptor:hd];

            const uint32_t bw = std::max(1u, w / 4u);
            const uint32_t bh = std::max(1u, h / 4u);
            MTLTextureDescriptor* bd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float
                                                                                          width:bw
                                                                                         height:bh
                                                                                      mipmapped:NO];
            bd.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
            bd.storageMode = MTLStorageModePrivate;
            composer->bloom_a_tex = [ctx->device newTextureWithDescriptor:bd];
            composer->bloom_b_tex = [ctx->device newTextureWithDescriptor:bd];
            composer->alloc_w = w;
            composer->alloc_h = h;
        }
        if (composer->alloc_final_fmt != ctex.pixelFormat) {
            NSError* err = nil;
            MTLCompileOptions* opts = [[MTLCompileOptions alloc] init];
            opts.languageVersion = MTLLanguageVersion3_1;
            opts.mathMode = MTLMathModeFast;
            id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:kComposerMsl]
                                                           options:opts
                                                             error:&err];
            if (lib) {
                id<MTLRenderPipelineState> rebuilt =
                        build_quad_pso(ctx->device, lib, @"fs_metality_final_composite", ctex.pixelFormat);
                if (rebuilt) {
                    composer->final_pso = rebuilt;
                    composer->alloc_final_fmt = ctex.pixelFormat;
                }
            }
        }
        if (!composer->hdr_scene_tex || !composer->bloom_a_tex || !composer->bloom_b_tex) {
            return false;
        }

        if (ctx->active_render_encoder && std::this_thread::get_id() == ctx->render_thread_id) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
            ctx->active_pass_width = 0;
            ctx->active_pass_height = 0;
        }
        const bool use_active_cb = (ctx->active_command_buffer != nil)
                && (std::this_thread::get_id() == ctx->render_thread_id);
        id<MTLCommandBuffer> cb = use_active_cb ? ctx->active_command_buffer : [ctx->command_queue commandBuffer];
        if (!cb) {
            return false;
        }

        MetalityComposerUniforms u_copy = *uniforms;
        u_copy.width = w;
        u_copy.height = h;

        auto run_pass = [&](id<MTLTexture> target, id<MTLRenderPipelineState> pso,
                            id<MTLTexture> t0, id<MTLTexture> t1, uint32_t pw, uint32_t ph) {
            MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
            rpd.colorAttachments[0].texture = target;
            rpd.colorAttachments[0].loadAction = MTLLoadActionDontCare;
            rpd.colorAttachments[0].storeAction = MTLStoreActionStore;
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rpd];
            if (!enc) return;
            MTLViewport vp = {0.0, 0.0, static_cast<double>(pw), static_cast<double>(ph), 0.0, 1.0};
            [enc setViewport:vp];
            [enc setRenderPipelineState:pso];
            if (t0) [enc setFragmentTexture:t0 atIndex:0];
            if (t1) [enc setFragmentTexture:t1 atIndex:1];
            [enc setFragmentBytes:&u_copy length:sizeof(u_copy) atIndex:0];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
        };

        // Pass 1: HDR lighting (Horizon SSAO + Volumetric Godrays + Wetness)
        run_pass(composer->hdr_scene_tex, composer->lighting_pso, ctex, dtex, w, h);

        // Pass 2: Quarter-res Bloom chain
        if (u_copy.bloom_strength > 0.001f) {
            const uint32_t bw = static_cast<uint32_t>(composer->bloom_a_tex.width);
            const uint32_t bh = static_cast<uint32_t>(composer->bloom_a_tex.height);
            run_pass(composer->bloom_a_tex, composer->bloom_down_pso, composer->hdr_scene_tex, nil, bw, bh);
            run_pass(composer->bloom_b_tex, composer->bloom_blur_pso, composer->bloom_a_tex, nil, bw, bh);
        }

        // Pass 3: ACES Filmic / Reinhard / Linear Tonemapping back to MainTarget
        run_pass(ctex, composer->final_pso, composer->hdr_scene_tex, composer->bloom_b_tex, w, h);

        if (!use_active_cb) {
            [cb commit];
            [cb waitUntilCompleted];
        }
        return true;
    }
}

} // extern "C"

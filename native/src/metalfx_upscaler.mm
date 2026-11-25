#import "metality/metal_context.hpp"
#import <simd/simd.h>

#include <algorithm>
#include <cmath>

namespace {

MTLPixelFormat upscaler_to_mtl_format(MetalityPixelFormat fmt) {
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
    return MTLPixelFormatRGBA16Float;
}

double halton_sequence(uint64_t index, uint32_t base) {
    double f = 1.0;
    double r = 0.0;
    uint64_t i = index;
    while (i > 0) {
        f /= static_cast<double>(base);
        r += f * static_cast<double>(i % base);
        i /= base;
    }
    return r;
}

id<MTLTexture> find_mtl_texture(MetalityContext* ctx, MetalityTextureHandle handle) {
    if (!ctx || handle == 0) {
        return nil;
    }
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    auto it = ctx->textures.find(handle);
    return (it != ctx->textures.end()) ? it->second.texture : nil;
}

} // namespace

struct MetalityUpscalerImpl {
    MetalityUpscaleMode mode = METALITY_UPSCALE_OFF;
    uint32_t input_width = 0;
    uint32_t input_height = 0;
    uint32_t output_width = 0;
    uint32_t output_height = 0;
    bool depth_reversed = false;
    id<MTLFXTemporalScaler> temporal = nil;
    id<MTLFXSpatialScaler> spatial = nil;
#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 260000
    id<MTLFXTemporalDenoisedScaler> denoised API_AVAILABLE(macos(26.0)) = nil;
#endif
};

extern "C" {

MetalityUpscalerHandle metality_upscaler_create(
    MetalityContext* ctx,
    const MetalityUpscalerDesc* desc
) {
    if (!ctx || !ctx->device || !desc || desc->mode == METALITY_UPSCALE_OFF) {
        return nullptr;
    }
    if (desc->input_width == 0 || desc->input_height == 0 ||
        desc->output_width == 0 || desc->output_height == 0) {
        return nullptr;
    }

    @autoreleasepool {
        auto* up = new MetalityUpscalerImpl();
        up->mode = desc->mode;
        up->input_width = desc->input_width;
        up->input_height = desc->input_height;
        up->output_width = desc->output_width;
        up->output_height = desc->output_height;
        up->depth_reversed = desc->depth_reversed != 0;

        if (desc->mode == METALITY_UPSCALE_TEMPORAL_DENOISED) {
#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 260000
            if (@available(macOS 26.0, *)) {
                if ([MTLFXTemporalDenoisedScalerDescriptor supportsDevice:ctx->device]) {
                    MTLFXTemporalDenoisedScalerDescriptor* dd = [[MTLFXTemporalDenoisedScalerDescriptor alloc] init];
                    dd.colorTextureFormat = upscaler_to_mtl_format(desc->color_format);
                    dd.depthTextureFormat = upscaler_to_mtl_format(desc->depth_format);
                    dd.motionTextureFormat = upscaler_to_mtl_format(desc->motion_format);
                    dd.diffuseAlbedoTextureFormat = MTLPixelFormatRGBA8Unorm;
                    dd.specularAlbedoTextureFormat = MTLPixelFormatRGBA8Unorm;
                    dd.normalTextureFormat = MTLPixelFormatRGBA16Float;
                    dd.roughnessTextureFormat = MTLPixelFormatR16Float;
                    dd.outputTextureFormat = upscaler_to_mtl_format(desc->output_format);
                    dd.inputWidth = desc->input_width;
                    dd.inputHeight = desc->input_height;
                    dd.outputWidth = desc->output_width;
                    dd.outputHeight = desc->output_height;
                    dd.autoExposureEnabled = desc->auto_exposure ? YES : NO;
                    dd.reactiveMaskTextureEnabled = NO;
                    dd.specularHitDistanceTextureEnabled = NO;
                    dd.denoiseStrengthMaskTextureEnabled = NO;
                    dd.transparencyOverlayTextureEnabled = NO;

                    up->denoised = [dd newTemporalDenoisedScalerWithDevice:ctx->device];
                    if (up->denoised) {
                        up->denoised.depthReversed = desc->depth_reversed ? YES : NO;
                        return up;
                    }
                }
            }
#endif
            // 若系統低於 macOS 26 或裝置不支援 denoised scaler，優雅降級至 TemporalScaler
            up->mode = METALITY_UPSCALE_TEMPORAL;
        }

        if (up->mode == METALITY_UPSCALE_TEMPORAL) {
            if (![MTLFXTemporalScalerDescriptor supportsDevice:ctx->device]) {
                delete up;
                return nullptr;
            }
            MTLFXTemporalScalerDescriptor* td = [[MTLFXTemporalScalerDescriptor alloc] init];
            td.colorTextureFormat = upscaler_to_mtl_format(desc->color_format);
            td.depthTextureFormat = upscaler_to_mtl_format(desc->depth_format);
            td.motionTextureFormat = upscaler_to_mtl_format(desc->motion_format);
            td.outputTextureFormat = upscaler_to_mtl_format(desc->output_format);
            td.inputWidth = desc->input_width;
            td.inputHeight = desc->input_height;
            td.outputWidth = desc->output_width;
            td.outputHeight = desc->output_height;
            td.autoExposureEnabled = desc->auto_exposure ? YES : NO;

            up->temporal = [td newTemporalScalerWithDevice:ctx->device];
            if (!up->temporal) {
                delete up;
                return nullptr;
            }
            up->temporal.depthReversed = desc->depth_reversed ? YES : NO;
        } else if (desc->mode == METALITY_UPSCALE_SPATIAL) {
            if (![MTLFXSpatialScalerDescriptor supportsDevice:ctx->device]) {
                delete up;
                return nullptr;
            }
            MTLFXSpatialScalerDescriptor* sd = [[MTLFXSpatialScalerDescriptor alloc] init];
            sd.colorTextureFormat = upscaler_to_mtl_format(desc->color_format);
            sd.outputTextureFormat = upscaler_to_mtl_format(desc->output_format);
            sd.inputWidth = desc->input_width;
            sd.inputHeight = desc->input_height;
            sd.outputWidth = desc->output_width;
            sd.outputHeight = desc->output_height;
            sd.colorProcessingMode =
                (desc->color_format == METALITY_FORMAT_RGBA16_FLOAT ||
                 desc->color_format == METALITY_FORMAT_RG11B10_FLOAT)
                    ? MTLFXSpatialScalerColorProcessingModeHDR
                    : MTLFXSpatialScalerColorProcessingModePerceptual;

            up->spatial = [sd newSpatialScalerWithDevice:ctx->device];
            if (!up->spatial) {
                delete up;
                return nullptr;
            }
        }

        return up;
    }
}

bool metality_upscaler_encode(
    MetalityContext* ctx,
    MetalityUpscalerHandle upscaler,
    const MetalityUpscaleDispatchInfo* info
) {
    if (!ctx || !ctx->active_command_buffer || !upscaler || !info) {
        return false;
    }
    @autoreleasepool {
        // MetalFX 需於獨立非 RenderEncoder Scope 執行內部 Compute/Neural 管線
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
        }

        id<MTLTexture> color_tex = find_mtl_texture(ctx, info->color_tex);
        id<MTLTexture> output_tex = find_mtl_texture(ctx, info->output_tex);
        if (!color_tex || !output_tex) {
            return false;
        }

        const NSUInteger content_w = info->content_width > 0 ? info->content_width : upscaler->input_width;
        const NSUInteger content_h = info->content_height > 0 ? info->content_height : upscaler->input_height;

        if (upscaler->mode == METALITY_UPSCALE_TEMPORAL && upscaler->temporal) {
            id<MTLTexture> depth_tex = find_mtl_texture(ctx, info->depth_tex);
            id<MTLTexture> motion_tex = find_mtl_texture(ctx, info->motion_tex);
            if (!depth_tex || !motion_tex) {
                return false;
            }

            id<MTLFXTemporalScaler> scaler = upscaler->temporal;
            scaler.colorTexture = color_tex;
            scaler.depthTexture = depth_tex;
            scaler.motionTexture = motion_tex;
            scaler.outputTexture = output_tex;
            scaler.inputContentWidth = content_w;
            scaler.inputContentHeight = content_h;
            scaler.jitterOffsetX = info->jitter_x;
            scaler.jitterOffsetY = info->jitter_y;
            scaler.motionVectorScaleX = info->motion_scale_x != 0.0f ? info->motion_scale_x : 1.0f;
            scaler.motionVectorScaleY = info->motion_scale_y != 0.0f ? info->motion_scale_y : 1.0f;
            scaler.reset = info->reset_history ? YES : NO;

            [scaler encodeToCommandBuffer:ctx->active_command_buffer];
            return true;
        }

        if (upscaler->mode == METALITY_UPSCALE_SPATIAL && upscaler->spatial) {
            id<MTLFXSpatialScaler> scaler = upscaler->spatial;
            scaler.colorTexture = color_tex;
            scaler.outputTexture = output_tex;
            scaler.inputContentWidth = content_w;
            scaler.inputContentHeight = content_h;

            [scaler encodeToCommandBuffer:ctx->active_command_buffer];
            return true;
        }

        return false;
    }
}

bool metality_upscaler_encode_denoised(
    MetalityContext* ctx,
    MetalityUpscalerHandle upscaler,
    const MetalityDenoisedUpscaleDispatchInfo* info
) {
    if (!ctx || !ctx->active_command_buffer || !upscaler || !info) {
        return false;
    }
    @autoreleasepool {
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
        }

#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 260000
        if (@available(macOS 26.0, *)) {
            if (upscaler->denoised) {
                id<MTLTexture> color_tex = find_mtl_texture(ctx, info->color_tex);
                id<MTLTexture> depth_tex = find_mtl_texture(ctx, info->depth_tex);
                id<MTLTexture> motion_tex = find_mtl_texture(ctx, info->motion_tex);
                id<MTLTexture> diff_tex = find_mtl_texture(ctx, info->diffuse_albedo_tex);
                id<MTLTexture> spec_tex = find_mtl_texture(ctx, info->specular_albedo_tex);
                id<MTLTexture> norm_tex = find_mtl_texture(ctx, info->normal_tex);
                id<MTLTexture> rough_tex = find_mtl_texture(ctx, info->roughness_tex);
                id<MTLTexture> output_tex = find_mtl_texture(ctx, info->output_tex);
                if (!color_tex || !depth_tex || !motion_tex || !diff_tex ||
                    !spec_tex || !norm_tex || !rough_tex || !output_tex) {
                    return false;
                }

                id<MTLFXTemporalDenoisedScaler> denoiser = upscaler->denoised;
                denoiser.colorTexture = color_tex;
                denoiser.depthTexture = depth_tex;
                denoiser.motionTexture = motion_tex;
                denoiser.diffuseAlbedoTexture = diff_tex;
                denoiser.specularAlbedoTexture = spec_tex;
                denoiser.normalTexture = norm_tex;
                denoiser.roughnessTexture = rough_tex;
                denoiser.outputTexture = output_tex;
                denoiser.jitterOffsetX = info->jitter_x;
                denoiser.jitterOffsetY = info->jitter_y;
                denoiser.motionVectorScaleX = info->motion_scale_x != 0.0f ? info->motion_scale_x : 1.0f;
                denoiser.motionVectorScaleY = info->motion_scale_y != 0.0f ? info->motion_scale_y : 1.0f;
                denoiser.shouldResetHistory = info->reset_history ? YES : NO;
                denoiser.depthReversed = upscaler->depth_reversed ? YES : NO;
                denoiser.worldToViewMatrix = matrix_identity_float4x4;
                denoiser.viewToClipMatrix = matrix_identity_float4x4;

                [denoiser encodeToCommandBuffer:ctx->active_command_buffer];
                return true;
            }
        }
#endif

        MetalityUpscaleDispatchInfo fallback{};
        fallback.color_tex = info->color_tex;
        fallback.depth_tex = info->depth_tex;
        fallback.motion_tex = info->motion_tex;
        fallback.output_tex = info->output_tex;
        fallback.content_width = upscaler->input_width;
        fallback.content_height = upscaler->input_height;
        fallback.jitter_x = info->jitter_x;
        fallback.jitter_y = info->jitter_y;
        fallback.motion_scale_x = info->motion_scale_x;
        fallback.motion_scale_y = info->motion_scale_y;
        fallback.reset_history = info->reset_history;
        return metality_upscaler_encode(ctx, upscaler, &fallback);
    }
}

void metality_upscaler_halton_jitter(
    uint64_t frame_index,
    uint32_t input_width,
    uint32_t output_width,
    float* out_jx,
    float* out_jy
) {
    const float scale = (input_width > 0 && output_width > 0)
                            ? static_cast<float>(output_width) / static_cast<float>(input_width)
                            : 1.0f;
    const uint32_t phase_count = std::max<uint32_t>(8u, static_cast<uint32_t>(std::ceil(8.0f * scale * scale)));
    const uint64_t idx = (frame_index % phase_count) + 1ULL;

    const float jx = static_cast<float>(halton_sequence(idx, 2) - 0.5);
    const float jy = static_cast<float>(halton_sequence(idx, 3) - 0.5);
    if (out_jx) {
        *out_jx = jx;
    }
    if (out_jy) {
        *out_jy = jy;
    }
}

void metality_upscaler_destroy(MetalityUpscalerHandle upscaler) {
    delete upscaler;
}

} // extern "C"

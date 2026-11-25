#import "metality/metal_context.hpp"

#include <cstring>

namespace {

MTLPixelFormat to_mtl_pixel_format(MetalityPixelFormat fmt) {
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

void write_error(NSError* err, char* err_buf, uint32_t err_buf_len) {
    if (!err_buf || err_buf_len == 0) {
        return;
    }
    const char* msg = (err && err.localizedDescription)
                          ? err.localizedDescription.UTF8String
                          : "Unknown Metal compilation error";
    std::strncpy(err_buf, msg, err_buf_len - 1);
    err_buf[err_buf_len - 1] = '\0';
}

} // namespace

extern "C" {

MetalityShaderHandle metality_compile_msl_library(
    MetalityContext* ctx,
    const char* label,
    const char* msl_source,
    bool fast_math,
    char* err_buf,
    uint32_t err_buf_len
) {
    if (!ctx || !ctx->device || !msl_source) {
        return 0;
    }
    @autoreleasepool {
        MTLCompileOptions* opts = [[MTLCompileOptions alloc] init];
        opts.languageVersion = MTLLanguageVersion3_1;
        opts.mathMode = fast_math ? MTLMathModeFast : MTLMathModeSafe;

        NSError* err = nil;
        NSString* src = [NSString stringWithUTF8String:msl_source];
        id<MTLLibrary> lib = [ctx->device newLibraryWithSource:src options:opts error:&err];
        if (!lib) {
            write_error(err, err_buf, err_buf_len);
            return 0;
        }
        if (label) {
            lib.label = [NSString stringWithUTF8String:label];
        }

        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        const MetalityShaderHandle handle = ctx->next_resource_handle++;
        metality::ShaderLibraryEntry entry;
        entry.handle = handle;
        entry.label = label ? label : "UnnamedLibrary";
        entry.library = lib;
        ctx->shader_libraries.emplace(handle, std::move(entry));
        return handle;
    }
}

bool metality_cache_render_pso(
    MetalityContext* ctx,
    MetalityShaderHandle shader_lib,
    const char* vertex_fn,
    const char* fragment_fn,
    MetalityPixelFormat color_format,
    char* err_buf,
    uint32_t err_buf_len
) {
    if (!ctx || !ctx->device || !vertex_fn || !fragment_fn) {
        return false;
    }
    @autoreleasepool {
        id<MTLLibrary> lib = nil;
        {
            std::lock_guard<std::mutex> lock(ctx->resource_mutex);
            auto it = ctx->shader_libraries.find(shader_lib);
            if (it == ctx->shader_libraries.end()) {
                return false;
            }
            lib = it->second.library;
        }

        id<MTLFunction> vfn = [lib newFunctionWithName:[NSString stringWithUTF8String:vertex_fn]];
        id<MTLFunction> ffn = [lib newFunctionWithName:[NSString stringWithUTF8String:fragment_fn]];
        if (!vfn || !ffn) {
            if (err_buf && err_buf_len > 0) {
                std::strncpy(err_buf, "Entry function not found in MTLLibrary", err_buf_len - 1);
                err_buf[err_buf_len - 1] = '\0';
            }
            return false;
        }

        MTLRenderPipelineDescriptor* desc = [[MTLRenderPipelineDescriptor alloc] init];
        desc.vertexFunction = vfn;
        desc.fragmentFunction = ffn;
        desc.colorAttachments[0].pixelFormat = to_mtl_pixel_format(color_format);

        if (ctx->binary_archive) {
            desc.binaryArchives = @[ ctx->binary_archive ];
        }

        NSError* err = nil;
        id<MTLRenderPipelineState> pso =
            [ctx->device newRenderPipelineStateWithDescriptor:desc error:&err];
        if (!pso) {
            write_error(err, err_buf, err_buf_len);
            return false;
        }

        if (ctx->binary_archive) {
            NSError* arch_err = nil;
            if ([ctx->binary_archive addRenderPipelineFunctionsWithDescriptor:desc error:&arch_err]) {
                ctx->archive_dirty = true;
            }
        }
        return true;
    }
}

bool metality_flush_pipeline_cache(MetalityContext* ctx) {
    if (!ctx || !ctx->binary_archive || !ctx->archive_url) {
        return false;
    }
    if (!ctx->archive_dirty) {
        return true;
    }
    @autoreleasepool {
        NSError* err = nil;
        BOOL ok = [ctx->binary_archive serializeToURL:ctx->archive_url error:&err];
        if (ok) {
            ctx->archive_dirty = false;
        }
        return ok == YES;
    }
}

} // extern "C"

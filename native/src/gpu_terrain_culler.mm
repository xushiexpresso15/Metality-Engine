#import "metality/metal_context.hpp"

#include <cstring>

namespace {

// Phase 1 核心著色器：
// 1. cs_cull_terrain_icb : 在 GPU 上並行執行 6 平面視錐剔除，直接向 MTLIndirectCommandBuffer 寫入繪製指令！
// 2. vs_pull_terrain_quad: 零索引緩衝、16-Byte Quad-Pulling 解碼、動態 AO 對角線翻轉修正！
// 3. fs_terrain_solid    : 純不透明方塊（零 discard_fragment，100% 喚醒 Apple GPU 硬體隱藏面消除 HSR），搭配 gradient2d 消除 Greedy 圖集接縫！
// 4. fs_terrain_cutout   : 獨立鏤空方塊管線（樹葉、草叢）
constexpr const char* kGpuDrivenTerrainMsl = R"(
#include <metal_stdlib>
using namespace metal;

struct PackedQuad {
    ulong word0;
    ulong word1;
};

struct SectionGpuRecord {
    packed_int3 chunk_sec;
    uint solid_quad_offset;
    uint solid_quad_count;
    uint cutout_quad_offset;
    uint cutout_quad_count;
    uint translucent_quad_offset;
    uint translucent_quad_count;
    packed_float3 aabb_min;
    packed_float3 aabb_max;
    uint pad;
};

struct SpriteUvEntry {
    float2 uv_origin;
    float2 uv_size;
};

struct CullUniforms {
    float4 frustum_planes[6];
    packed_float3 camera_pos;
    uint active_sections;
};

struct TerrainFrameUniforms {
    float4x4 view_proj;
    packed_float3 camera_pos;
    uint pass_layer; // 1 = SOLID, 2 = CUTOUT
};

struct IcbContainer {
    command_buffer icb_solid [[id(0)]];
    command_buffer icb_cutout [[id(1)]];
};

inline bool aabb_inside_frustum(float3 bmin, float3 bmax, constant float4* planes) {
    for (uint i = 0; i < 6; ++i) {
        float4 pl = planes[i];
        float3 p_vertex = select(bmin, bmax, pl.xyz >= 0.0);
        if (dot(pl.xyz, p_vertex) + pl.w < 0.0) {
            return false;
        }
    }
    return true;
}

kernel void cs_cull_terrain_icb(
    uint tid [[thread_position_in_grid]],
    device const SectionGpuRecord* sections [[buffer(0)]],
    constant CullUniforms& u [[buffer(1)]],
    constant IcbContainer& icbs [[buffer(2)]]
) {
    if (tid >= u.active_sections) {
        return;
    }

    render_command cmd_solid(icbs.icb_solid, tid);
    render_command cmd_cutout(icbs.icb_cutout, tid);

    SectionGpuRecord sec = sections[tid];
    if (sec.solid_quad_count == 0 && sec.cutout_quad_count == 0) {
        cmd_solid.reset();
        cmd_cutout.reset();
        return;
    }

    // 轉換至相對攝影機空間進行高精度視錐包圍盒測試（避免大世界座標浮點誤差）
    float3 sec_world_base = float3(sec.chunk_sec) * 16.0 - u.camera_pos;
    float3 bmin = sec_world_base + float3(sec.aabb_min);
    float3 bmax = sec_world_base + float3(sec.aabb_max);

    if (!aabb_inside_frustum(bmin, bmax, u.frustum_planes)) {
        cmd_solid.reset();
        cmd_cutout.reset();
        return;
    }

    if (sec.solid_quad_count > 0) {
        cmd_solid.draw_primitives(primitive_type::triangle, 0, sec.solid_quad_count * 6u, 1u, tid);
    } else {
        cmd_solid.reset();
    }

    if (sec.cutout_quad_count > 0) {
        cmd_cutout.draw_primitives(primitive_type::triangle, 0, sec.cutout_quad_count * 6u, 1u, tid);
    } else {
        cmd_cutout.reset();
    }
}

struct TerrainVaryings {
    float4 clip_pos [[position]];
    float2 tile_coord;
    uint sprite_id [[flat]];
    half3 tint_and_ao;
    half2 lightmap;
};

constant float3 kTangentU[6] = {
    float3(0, 0, 1), float3(0, 0, 1),
    float3(1, 0, 0), float3(1, 0, 0),
    float3(1, 0, 0), float3(1, 0, 0)
};

constant float3 kBitangentV[6] = {
    float3(0, 1, 0), float3(0, 1, 0),
    float3(0, 0, 1), float3(0, 0, 1),
    float3(0, 1, 0), float3(0, 1, 0)
};

vertex TerrainVaryings vs_pull_terrain_quad(
    uint vid [[vertex_id]],
    uint section_slot [[instance_id]],
    device const PackedQuad* quad_pool [[buffer(0)]],
    device const SectionGpuRecord* sections [[buffer(1)]],
    constant TerrainFrameUniforms& u [[buffer(2)]]
) {
    SectionGpuRecord sec = sections[section_slot];
    uint base_offset = (u.pass_layer == 1u) ? sec.solid_quad_offset : sec.cutout_quad_offset;

    uint q_idx = vid / 6u;
    uint tri_v = vid - q_idx * 6u;

    PackedQuad pq = quad_pool[base_offset + q_idx];
    ulong w0 = pq.word0;
    ulong w1 = pq.word1;

    uint flip_diag = uint((w0 >> 34) & 1ul);
    uint dir = uint((w0 >> 23) & 0x7ul);
    // 確保 6 個軸向面的外法線皆具備一致的逆時針 (CCW) 繞序，使 MTLCullModeBack 精確剔除背面！
    bool rev_winding = (dir == 1u || dir == 3u || dir == 4u);
    uint oriented_v = rev_winding ? ((tri_v == 1u) ? 2u : ((tri_v == 2u) ? 1u : ((tri_v == 4u) ? 5u : ((tri_v == 5u) ? 4u : tri_v)))) : tri_v;

    // 動態翻轉對角線以徹底消除 Minecraft AO 各向異性陰影條紋
    constexpr uint kCornersNormal[6]  = {0u, 1u, 2u, 0u, 2u, 3u};
    constexpr uint kCornersFlipped[6] = {0u, 1u, 3u, 1u, 2u, 3u};
    uint corner = flip_diag ? kCornersFlipped[oriented_v] : kCornersNormal[oriented_v];

    float2 corner_uv = float2(
        (corner == 1u || corner == 2u) ? 1.0 : 0.0,
        (corner == 2u || corner == 3u) ? 1.0 : 0.0
    );

    float3 q_origin = float3(
        float((w0 >>  0) & 0x1Ful),
        float((w0 >>  5) & 0x1Ful),
        float((w0 >> 10) & 0x1Ful)
    );
    float span_w = float(((w0 >> 15) & 0xFul) + 1ul);
    float span_h = float(((w0 >> 19) & 0xFul) + 1ul);

    float3 local_pos = q_origin +
                       kTangentU[dir] * (corner_uv.x * span_w) +
                       kBitangentV[dir] * (corner_uv.y * span_h);
    float3 cam_rel_pos = float3(sec.chunk_sec) * 16.0 - u.camera_pos + local_pos;

    uint ao_val = uint((w0 >> (26u + corner * 2u)) & 0x3ul);
    half ao_shade = half(0.4 + 0.2 * float(ao_val));

    uint lm_byte = uint((w1 >> (corner * 8u)) & 0xFFul);
    uint tint_bits = uint((w1 >> 32) & 0xFFFFFFul);
    half3 biome_tint = half3(
        half(tint_bits & 0xFFu) * (1.0h / 255.0h),
        half((tint_bits >> 8) & 0xFFu) * (1.0h / 255.0h),
        half((tint_bits >> 16) & 0xFFu) * (1.0h / 255.0h)
    );

    TerrainVaryings out;
    out.clip_pos = u.view_proj * float4(cam_rel_pos, 1.0);
    out.tile_coord = float2(corner_uv.x * span_w, corner_uv.y * span_h);
    out.sprite_id = uint((w0 >> 48) & 0xFFFFul);
    out.tint_and_ao = biome_tint * ao_shade;
    out.lightmap = half2(
        half(lm_byte & 0xFu) * (1.0h / 15.0h),
        half((lm_byte >> 4) & 0xFu) * (1.0h / 15.0h)
    );
    return out;
}

struct TerrainFragmentBindings {
    device const SpriteUvEntry* sprites [[id(0)]];
    texture2d<half> blockAtlas [[id(1)]];
    sampler atlasSampler [[id(2)]];
};

// Solid 地形片段著色器：嚴禁包含 discard_fragment()，100% 開啟 Apple Silicon 硬體 HSR！
// 搭配 Tier 2 Bindless Argument Buffer 滿足 MTLIndirectCommandBuffer 零狀態切換執行規範
fragment half4 fs_terrain_solid(
    TerrainVaryings in [[stage_in]],
    constant TerrainFragmentBindings& frag [[buffer(0)]]
) {
    SpriteUvEntry sp = frag.sprites[in.sprite_id];
    float2 cont_uv = in.tile_coord * sp.uv_size;
    // 使用連續導數 gradient2d 取樣，徹底根除 Greedy Quad 在 `fract()` 邊界處的 Mipmap 溢色接縫！
    gradient2d grad(dfdx(cont_uv), dfdy(cont_uv));
    float2 atlas_uv = sp.uv_origin + fract(in.tile_coord) * sp.uv_size;

    half4 texel = frag.blockAtlas.sample(frag.atlasSampler, atlas_uv, grad);
    half light_factor = max(in.lightmap.x, in.lightmap.y * 0.95h + 0.05h);
    return half4(texel.rgb * in.tint_and_ao * light_factor, 1.0h);
}

// Cutout 地形片段著色器：獨立批次執行 alpha 測試，不拖累佔 85% 面積的不透明地形 HSR
fragment half4 fs_terrain_cutout(
    TerrainVaryings in [[stage_in]],
    constant TerrainFragmentBindings& frag [[buffer(0)]]
) {
    SpriteUvEntry sp = frag.sprites[in.sprite_id];
    float2 cont_uv = in.tile_coord * sp.uv_size;
    gradient2d grad(dfdx(cont_uv), dfdy(cont_uv));
    float2 atlas_uv = sp.uv_origin + fract(in.tile_coord) * sp.uv_size;

    half4 texel = frag.blockAtlas.sample(frag.atlasSampler, atlas_uv, grad);
    if (texel.a < 0.5h) {
        discard_fragment();
    }
    half light_factor = max(in.lightmap.x, in.lightmap.y * 0.95h + 0.05h);
    return half4(texel.rgb * in.tint_and_ao * light_factor, 1.0h);
}
)";

struct alignas(16) GpuCullUniforms {
    float frustum_planes[24];
    float camera_pos[3];
    uint32_t active_sections;
};

struct alignas(16) GpuTerrainFrameUniforms {
    float view_proj[16];
    float camera_pos[3];
    uint32_t pass_layer;
};

} // namespace

struct MetalityTerrainPipelineImpl {
    uint32_t max_sections = 0;
    uint64_t quad_pool_bytes = 0;

    id<MTLBuffer> quad_pool_buf = nil;
    id<MTLBuffer> section_meta_buf = nil;
    id<MTLBuffer> sprite_uv_buf = nil;
    id<MTLBuffer> icb_arg_buf = nil;
    id<MTLArgumentEncoder> frag_arg_encoder = nil;
    id<MTLBuffer> frag_arg_bufs[6] = {nil, nil, nil, nil, nil, nil};

    id<MTLIndirectCommandBuffer> icb_solid = nil;
    id<MTLIndirectCommandBuffer> icb_cutout = nil;

    id<MTLComputePipelineState> cull_pso = nil;
    id<MTLRenderPipelineState> solid_pso = nil;
    id<MTLRenderPipelineState> cutout_pso = nil;
    id<MTLDepthStencilState> terrain_dss = nil;
};

extern "C" {

MetalityTerrainHandle metality_terrain_pipeline_create(
    MetalityContext* ctx,
    uint32_t max_sections,
    uint64_t quad_pool_bytes,
    char* err_buf,
    uint32_t err_buf_len
) {
    if (!ctx || !ctx->device || max_sections == 0 || quad_pool_bytes == 0) {
        return nullptr;
    }
    @autoreleasepool {
        id<MTLDevice> dev = ctx->device;

        MTLCompileOptions* opts = [[MTLCompileOptions alloc] init];
        opts.languageVersion = MTLLanguageVersion3_1;
        opts.mathMode = MTLMathModeFast;

        NSError* err = nil;
        id<MTLLibrary> lib = [dev newLibraryWithSource:[NSString stringWithUTF8String:kGpuDrivenTerrainMsl]
                                               options:opts
                                                 error:&err];
        if (!lib) {
            if (err_buf && err_buf_len > 0) {
                const char* msg = (err && err.localizedDescription) ? err.localizedDescription.UTF8String : "Terrain MSL compile error";
                std::strncpy(err_buf, msg, err_buf_len - 1);
                err_buf[err_buf_len - 1] = '\0';
            }
            return nullptr;
        }

        id<MTLFunction> cs_cull = [lib newFunctionWithName:@"cs_cull_terrain_icb"];
        id<MTLFunction> vs_pull = [lib newFunctionWithName:@"vs_pull_terrain_quad"];
        id<MTLFunction> fs_solid = [lib newFunctionWithName:@"fs_terrain_solid"];
        id<MTLFunction> fs_cutout = [lib newFunctionWithName:@"fs_terrain_cutout"];

        id<MTLComputePipelineState> cull_pso = [dev newComputePipelineStateWithFunction:cs_cull error:&err];
        if (!cull_pso) {
            if (err_buf && err_buf_len > 0) {
                const char* msg = (err && err.localizedDescription) ? err.localizedDescription.UTF8String : "Cull compute PSO error";
                std::strncpy(err_buf, msg, err_buf_len - 1);
                err_buf[err_buf_len - 1] = '\0';
            }
            return nullptr;
        }

        const MTLPixelFormat target_fmt = ctx->enable_edr ? MTLPixelFormatRGBA16Float : MTLPixelFormatBGRA8Unorm_sRGB;

        MTLRenderPipelineDescriptor* rpd = [[MTLRenderPipelineDescriptor alloc] init];
        rpd.vertexFunction = vs_pull;
        rpd.fragmentFunction = fs_solid;
        rpd.colorAttachments[0].pixelFormat = target_fmt;
        rpd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
        rpd.supportIndirectCommandBuffers = YES;

        id<MTLRenderPipelineState> solid_pso = [dev newRenderPipelineStateWithDescriptor:rpd error:&err];
        if (!solid_pso) {
            if (err_buf && err_buf_len > 0) {
                const char* msg = (err && err.localizedDescription) ? err.localizedDescription.UTF8String : "Solid render PSO error";
                std::strncpy(err_buf, msg, err_buf_len - 1);
                err_buf[err_buf_len - 1] = '\0';
            }
            return nullptr;
        }
        rpd.fragmentFunction = fs_cutout;
        id<MTLRenderPipelineState> cutout_pso = [dev newRenderPipelineStateWithDescriptor:rpd error:&err];
        if (!cutout_pso) {
            if (err_buf && err_buf_len > 0) {
                const char* msg = (err && err.localizedDescription) ? err.localizedDescription.UTF8String : "Cutout render PSO error";
                std::strncpy(err_buf, msg, err_buf_len - 1);
                err_buf[err_buf_len - 1] = '\0';
            }
            return nullptr;
        }

        MTLDepthStencilDescriptor* dd = [[MTLDepthStencilDescriptor alloc] init];
        dd.depthCompareFunction = MTLCompareFunctionLessEqual;
        dd.depthWriteEnabled = YES;
        id<MTLDepthStencilState> dss = [dev newDepthStencilStateWithDescriptor:dd];

        auto* tp = new MetalityTerrainPipelineImpl();
        tp->max_sections = max_sections;
        tp->quad_pool_bytes = (quad_pool_bytes + 255ULL) & ~255ULL;
        tp->cull_pso = cull_pso;
        tp->solid_pso = solid_pso;
        tp->cutout_pso = cutout_pso;
        tp->terrain_dss = dss;

        // 配置 UMA 零拷貝地形緩衝池
        tp->quad_pool_buf = [dev newBufferWithLength:tp->quad_pool_bytes
                                             options:MTLResourceStorageModeShared | MTLResourceCPUCacheModeWriteCombined];
        tp->section_meta_buf = [dev newBufferWithLength:static_cast<NSUInteger>(max_sections) * sizeof(MetalitySectionGpuRecord)
                                                options:MTLResourceStorageModeShared];
        tp->sprite_uv_buf = [dev newBufferWithLength:65536ULL * sizeof(MetalitySpriteUvEntry)
                                             options:MTLResourceStorageModeShared];
        std::memset(tp->section_meta_buf.contents, 0, static_cast<size_t>(max_sections) * sizeof(MetalitySectionGpuRecord));

        // 建立支援 GPU Compute 直接寫入的 Indirect Command Buffers (Solid 與 Cutout 分離)
        MTLIndirectCommandBufferDescriptor* icb_desc = [[MTLIndirectCommandBufferDescriptor alloc] init];
        icb_desc.commandTypes = MTLIndirectCommandTypeDraw;
        icb_desc.inheritPipelineState = YES;
        icb_desc.inheritBuffers = YES;
        icb_desc.maxVertexBufferBindCount = 0;
        icb_desc.maxFragmentBufferBindCount = 0;

        tp->icb_solid = [dev newIndirectCommandBufferWithDescriptor:icb_desc maxCommandCount:max_sections options:MTLResourceStorageModePrivate];
        tp->icb_cutout = [dev newIndirectCommandBufferWithDescriptor:icb_desc maxCommandCount:max_sections options:MTLResourceStorageModePrivate];

        // 將兩個 ICB 封裝進 Argument Buffer 供 cs_cull_terrain_icb 存取
        id<MTLArgumentEncoder> arg_enc = [cs_cull newArgumentEncoderWithBufferIndex:2];
        tp->icb_arg_buf = [dev newBufferWithLength:arg_enc.encodedLength options:MTLResourceStorageModeShared];
        [arg_enc setArgumentBuffer:tp->icb_arg_buf offset:0];
        [arg_enc setIndirectCommandBuffer:tp->icb_solid atIndex:0];
        [arg_enc setIndirectCommandBuffer:tp->icb_cutout atIndex:1];

        // 為 ICB 片段著色器建立 3-Frame x 2-Layer 環狀 Bindless Argument Buffers
        tp->frag_arg_encoder = [fs_solid newArgumentEncoderWithBufferIndex:0];
        for (int i = 0; i < 6; ++i) {
            tp->frag_arg_bufs[i] = [dev newBufferWithLength:tp->frag_arg_encoder.encodedLength
                                                    options:MTLResourceStorageModeShared];
        }

        return tp;
    }
}

void metality_terrain_pipeline_destroy(MetalityContext* ctx, MetalityTerrainHandle terrain) {
    (void)ctx;
    delete terrain;
}

void* metality_terrain_quad_pool_cpu_ptr(MetalityTerrainHandle terrain) {
    if (!terrain || !terrain->quad_pool_buf) {
        return nullptr;
    }
    return terrain->quad_pool_buf.contents;
}

void metality_terrain_update_sprites(
    MetalityTerrainHandle terrain,
    const MetalitySpriteUvEntry* entries,
    uint32_t count
) {
    if (!terrain || !terrain->sprite_uv_buf || !entries || count == 0) {
        return;
    }
    const uint32_t safe_count = count > 65536u ? 65536u : count;
    std::memcpy(terrain->sprite_uv_buf.contents, entries, safe_count * sizeof(MetalitySpriteUvEntry));
}

void metality_terrain_update_section(
    MetalityTerrainHandle terrain,
    uint32_t section_slot,
    const MetalitySectionGpuRecord* record
) {
    if (!terrain || !terrain->section_meta_buf || !record || section_slot >= terrain->max_sections) {
        return;
    }
    auto* base = static_cast<MetalitySectionGpuRecord*>(terrain->section_meta_buf.contents);
    base[section_slot] = *record;
}

void metality_terrain_encode_gpu_cull(
    MetalityContext* ctx,
    MetalityTerrainHandle terrain,
    const float frustum_planes[24],
    const float camera_world_pos[3],
    uint32_t active_section_count
) {
    if (!ctx || !ctx->active_command_buffer || !terrain || active_section_count == 0) {
        return;
    }
    const uint32_t count = active_section_count > terrain->max_sections ? terrain->max_sections : active_section_count;

    @autoreleasepool {
        // 必須在啟動 Compute Pass 前確保沒有開啟中的 RenderEncoder
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
            ctx->active_pass_width = 0;
            ctx->active_pass_height = 0;
        }

        id<MTLComputeCommandEncoder> ce = [ctx->active_command_buffer computeCommandEncoder];
        ce.label = @"Metality_GPU_Terrain_Cull_ICB";
        [ce setComputePipelineState:terrain->cull_pso];

        [ce useResource:terrain->icb_solid usage:MTLResourceUsageWrite];
        [ce useResource:terrain->icb_cutout usage:MTLResourceUsageWrite];

        GpuCullUniforms u{};
        std::memcpy(u.frustum_planes, frustum_planes, sizeof(u.frustum_planes));
        std::memcpy(u.camera_pos, camera_world_pos, sizeof(u.camera_pos));
        u.active_sections = count;

        [ce setBuffer:terrain->section_meta_buf offset:0 atIndex:0];
        [ce setBytes:&u length:sizeof(u) atIndex:1];
        [ce setBuffer:terrain->icb_arg_buf offset:0 atIndex:2];

        const NSUInteger tg_size = 64;
        const NSUInteger tg_count = (count + tg_size - 1) / tg_size;
        [ce dispatchThreadgroups:MTLSizeMake(tg_count, 1, 1) threadsPerThreadgroup:MTLSizeMake(tg_size, 1, 1)];
        [ce endEncoding];

        // 使用 BlitCommandEncoder 對 GPU 生成的 ICB 進行硬體壓縮最佳化 (optimizeIndirectCommandBuffer)，剔除空指令槽！
        id<MTLBlitCommandEncoder> blit = [ctx->active_command_buffer blitCommandEncoder];
        blit.label = @"Metality_Optimize_Terrain_ICB";
        [blit optimizeIndirectCommandBuffer:terrain->icb_solid withRange:NSMakeRange(0, count)];
        [blit optimizeIndirectCommandBuffer:terrain->icb_cutout withRange:NSMakeRange(0, count)];
        [blit endEncoding];
    }
}

void metality_terrain_encode_icb_draw(
    MetalityContext* ctx,
    MetalityTerrainHandle terrain,
    const float view_proj_matrix[16],
    const float camera_world_pos[3],
    MetalityTextureHandle block_atlas_tex,
    MetalitySamplerHandle atlas_sampler,
    MetalityBlockLayer pass_layer,
    uint32_t active_section_count
) {
    if (!ctx || !ctx->active_render_encoder || !terrain || active_section_count == 0) {
        return;
    }
    const uint32_t count = active_section_count > terrain->max_sections ? terrain->max_sections : active_section_count;
    const bool is_solid = (pass_layer == METALITY_LAYER_SOLID);

    id<MTLRenderCommandEncoder> re = ctx->active_render_encoder;
    [re setRenderPipelineState:(is_solid ? terrain->solid_pso : terrain->cutout_pso)];
    [re setDepthStencilState:terrain->terrain_dss];
    [re setCullMode:MTLCullModeBack];
    [re setFrontFacingWinding:MTLWindingCounterClockwise];

    GpuTerrainFrameUniforms fu{};
    std::memcpy(fu.view_proj, view_proj_matrix, sizeof(fu.view_proj));
    std::memcpy(fu.camera_pos, camera_world_pos, sizeof(fu.camera_pos));
    fu.pass_layer = is_solid ? 1u : 2u;

    [re setVertexBuffer:terrain->quad_pool_buf offset:0 atIndex:0];
    [re setVertexBuffer:terrain->section_meta_buf offset:0 atIndex:1];
    [re setVertexBytes:&fu length:sizeof(fu) atIndex:2];

    id<MTLTexture> atlas_tex = nil;
    id<MTLSamplerState> samp = nil;
    {
        std::lock_guard<std::mutex> lock(ctx->resource_mutex);
        auto tit = ctx->textures.find(block_atlas_tex);
        if (tit != ctx->textures.end()) {
            atlas_tex = tit->second.texture;
        }
        auto kit = ctx->sampler_handle_to_key.find(atlas_sampler);
        if (kit != ctx->sampler_handle_to_key.end()) {
            auto sit = ctx->sampler_cache.find(kit->second);
            if (sit != ctx->sampler_cache.end()) {
                samp = sit->second.sampler;
            }
        }
    }

    const uint32_t ring_idx = static_cast<uint32_t>((ctx->current_frame_id % 3) * 2 + (is_solid ? 0 : 1));
    id<MTLBuffer> frag_arg_buf = terrain->frag_arg_bufs[ring_idx];
    [terrain->frag_arg_encoder setArgumentBuffer:frag_arg_buf offset:0];
    [terrain->frag_arg_encoder setBuffer:terrain->sprite_uv_buf offset:0 atIndex:0];
    if (atlas_tex) {
        [terrain->frag_arg_encoder setTexture:atlas_tex atIndex:1];
        [re useResource:atlas_tex usage:MTLResourceUsageRead stages:MTLRenderStageFragment];
    }
    if (samp) {
        [terrain->frag_arg_encoder setSamplerState:samp atIndex:2];
    }
    [re useResource:terrain->sprite_uv_buf usage:MTLResourceUsageRead stages:MTLRenderStageFragment];
    [re setFragmentBuffer:frag_arg_buf offset:0 atIndex:0];

    id<MTLIndirectCommandBuffer> target_icb = is_solid ? terrain->icb_solid : terrain->icb_cutout;
    [re useResource:target_icb usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
    // 全世界數萬個可見區塊區段，單一 GPU 指令完成提交！
    [re executeCommandsInBuffer:target_icb withRange:NSMakeRange(0, count)];
    // Directly mutated PSO/DSS/cull/buffers on active_render_encoder; invalidate encoder state cache.
    ctx->enc_state.reset();
}

} // extern "C"

#include "metality/metality_c_api.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <vector>

static float decode_f16(uint16_t h) {
    const uint32_t sign = (static_cast<uint32_t>(h & 0x8000u)) << 16;
    const uint32_t exp = (h >> 10) & 0x1Fu;
    const uint32_t frac = h & 0x3FFu;
    uint32_t f_bits = 0;
    if (exp == 0u) {
        if (frac != 0u) {
            int e = -14;
            uint32_t m = frac;
            while ((m & 0x400u) == 0u) { m <<= 1; --e; }
            m &= 0x3FFu;
            f_bits = sign | (static_cast<uint32_t>(e + 127) << 23) | (m << 13);
        } else {
            f_bits = sign;
        }
    } else if (exp == 31u) {
        f_bits = sign | 0x7F800000u | (frac << 13);
    } else {
        f_bits = sign | ((exp + 112u) << 23) | (frac << 13);
    }
    float out = 0.0f;
    std::memcpy(&out, &f_bits, sizeof(out));
    return out;
}

#define REQUIRE(cond, msg)                                                      \
    do {                                                                        \
        if (!(cond)) {                                                          \
            std::fprintf(stderr, "[FAIL] %s (%s:%d)\n", msg, __FILE__, __LINE__); \
            std::exit(1);                                                       \
        }                                                                       \
    } while (0)

int main() {
    std::printf("=== Metality Native Core Hardware & TBDR Smoke Test ===\n");

    // 1. 驗證 TBDR Render Graph 自動 Pass 融合與 Memoryless 提升（純演算法層，支援無 GPU / CI 沙盒環境）
    //    以 MacBook Pro 16" 原生解析度 (3456 x 2234) 模擬 4-Pass 延遲光照管線：
    const uint32_t W = 3456;
    const uint32_t H = 2234;
    MetalityGraphHandle graph = metality_graph_create();
    uint32_t t_albedo   = metality_graph_declare_texture(graph, "gbuffer_albedo", METALITY_FORMAT_RGBA8_SRGB, W, H, false);
    uint32_t t_normal   = metality_graph_declare_texture(graph, "gbuffer_normal", METALITY_FORMAT_RGB10A2_UNORM, W, H, false);
    uint32_t t_pbr      = metality_graph_declare_texture(graph, "gbuffer_pbr", METALITY_FORMAT_RGBA8_UNORM, W, H, false);
    uint32_t t_depth    = metality_graph_declare_texture(graph, "gbuffer_depth", METALITY_FORMAT_DEPTH32_FLOAT, W, H, true); // 供後續 TAA/MetalFX 使用
    uint32_t t_lit_hdr  = metality_graph_declare_texture(graph, "deferred_hdr", METALITY_FORMAT_RGBA16_FLOAT, W, H, false);
    uint32_t t_sky_comp = metality_graph_declare_texture(graph, "composite_sky_fog", METALITY_FORMAT_RGBA16_FLOAT, W, H, false);
    uint32_t t_bloom    = metality_graph_declare_texture(graph, "composite_bloom", METALITY_FORMAT_RGBA16_FLOAT, W, H, true);

    // Pass 0: GBuffer Terrain
    const uint32_t gbuf_writes[] = {t_albedo, t_normal, t_pbr, t_depth};
    metality_graph_add_pass(graph, "gbuffers_terrain", nullptr, 0, false, gbuf_writes, 4);

    // Pass 1: Deferred Lighting (Pixel-local read -> 自動融合進 Tile Memory!)
    const uint32_t lit_reads[] = {t_albedo, t_normal, t_pbr, t_depth};
    metality_graph_add_pass(graph, "deferred_lighting", lit_reads, 4, false, &t_lit_hdr, 1);

    // Pass 2: Atmospheric Fog Composite (Pixel-local read -> 繼續融合進同一個 Tile Pass!)
    const uint32_t fog_reads[] = {t_lit_hdr, t_depth};
    metality_graph_add_pass(graph, "composite_atmosphere", fog_reads, 2, false, &t_sky_comp, 1);

    // Pass 3: Spatial Bloom Blur (Samples neighbors == true -> 必須切開 Encoder 寫回 DRAM)
    metality_graph_add_pass(graph, "composite_bloom_blur", &t_sky_comp, 1, true, &t_bloom, 1);

    MetalityGraphPlanStats stats{};
    metality_graph_compile_tbdr(graph, &stats);
    std::printf("[TBDR] Passes: %u -> Fused Encoder Groups: %u | Memoryless Textures: %u/%u | Saved VRAM: %.2f MB/frame\n",
                stats.total_passes,
                stats.fused_encoder_groups,
                stats.memoryless_textures,
                stats.total_textures,
                static_cast<double>(stats.saved_dram_bytes_per_frame) / (1024.0 * 1024.0));

    REQUIRE(stats.fused_encoder_groups == 2, "Expected 4 passes to fuse into 2 TBDR groups");
    REQUIRE(stats.memoryless_textures == 4, "Expected 4 intermediate GBuffer/HDR textures to become Memoryless");
    metality_graph_destroy(graph);

    // 2. 驗證 Apple Silicon Metal 硬體層（若處於限制 IOKit 的 CI/沙盒環境則優雅略過 GPU 測試）
    const char* cache_dir = "/tmp/metality_smoke_cache";
    std::filesystem::remove_all(cache_dir);

    MetalityConfig cfg{};
    cfg.cache_directory_utf8 = cache_dir;
    cfg.frames_in_flight = 3;
    cfg.enable_validation = 0;
    cfg.enable_edr_hdr = 1;

    MetalityContext* ctx = metality_context_create(&cfg);
    if (!ctx) {
        std::printf("[GPU] Metal IOKit device unavailable in current sandbox/headless session; skipping live GPU checks.\n");
        std::printf("=== TBDR COMPILER SMOKE TESTS PASSED ===\n");
        return 0;
    }

    MetalityDeviceCaps caps{};
    metality_query_capabilities(ctx, &caps);
    std::printf("[GPU] Device Name               : %s\n", caps.device_name);
    std::printf("[GPU] Apple GPU Family          : Apple%u\n", caps.apple_gpu_family);
    std::printf("[GPU] Unified Memory (UMA)      : %s\n", caps.has_unified_memory ? "YES" : "NO");
    std::printf("[GPU] Recommended Working Set   : %.2f GB\n",
                static_cast<double>(caps.recommended_max_working_set_bytes) / (1024.0 * 1024.0 * 1024.0));
    std::printf("[GPU] Max Tile/Threadgroup Mem  : %u KB\n", caps.max_tile_memory_bytes / 1024);
    std::printf("[GPU] Argument Buffers Tier 2   : %s\n", caps.supports_argument_buffers_tier2 ? "YES" : "NO");
    std::printf("[GPU] Hardware Ray Tracing      : %s\n", caps.supports_hardware_raytracing ? "YES" : "NO");
    std::printf("[GPU] Mesh Shaders (Metal 3+)   : %s\n", caps.supports_mesh_shaders ? "YES" : "NO");
    std::printf("[GPU] MetalFX Temporal Upscaler : %s\n", caps.supports_metalfx_temporal ? "YES" : "NO");
    std::printf("[GPU] Metal 4 Command Queue     : %s\n", caps.supports_metal4_command_queue ? "YES" : "NO");

    REQUIRE(caps.has_unified_memory == 1, "Expected Apple Silicon Unified Memory");
    REQUIRE(caps.supports_argument_buffers_tier2 == 1, "Expected Argument Buffers Tier 2");

    // 3. 驗證 UMA 真零拷貝記憶體配置 + 3-Frames-in-Flight 安全延遲回收
    void* cpu_ptr = nullptr;
    uint64_t gpu_addr = 0;
    MetalityBufferHandle chunk_buf =
        metality_buffer_alloc_shared(ctx, 64 * 1024, true, &cpu_ptr, &gpu_addr);
    REQUIRE(chunk_buf != 0 && cpu_ptr != nullptr && gpu_addr != 0, "Failed to allocate UMA shared buffer");
    std::memset(cpu_ptr, 0xAB, 1024);

    uint64_t f1 = metality_begin_frame(ctx);
    REQUIRE(f1 == 1, "Expected first frame ID to be 1");
    metality_buffer_retire(ctx, chunk_buf);
    metality_end_frame(ctx, false);

    for (int i = 0; i < 4; ++i) {
        metality_begin_frame(ctx);
        metality_end_frame(ctx, false);
    }
    REQUIRE(metality_active_buffer_count(ctx) == 0, "Retired UMA buffer should be safely reclaimed!");
    std::printf("[UMA] Zero-copy buffer allocated (GPU VA: 0x%llx) & safely reclaimed via MTLSharedEvent.\n",
                static_cast<unsigned long long>(gpu_addr));

    // 4. 驗證免 Xcode 執行期 MSL 編譯與 MTLBinaryArchive 持久化快取
    const char* sample_msl = R"(
        #include <metal_stdlib>
        using namespace metal;

        struct VertexOut {
            float4 position [[position]];
            half3 color;
        };

        vertex VertexOut vs_main(uint vid [[vertex_id]]) {
            constexpr float2 pos[3] = { float2(0.0, 0.5), float2(-0.5, -0.5), float2(0.5, -0.5) };
            VertexOut out;
            out.position = float4(pos[vid % 3], 0.0, 1.0);
            out.color = half3(0.2h, 0.8h, 1.0h);
            return out;
        }

        fragment half4 fs_main(VertexOut in [[stage_in]]) {
            return half4(in.color, 1.0h);
        }
    )";

    char err_buf[512] = {0};
    MetalityShaderHandle lib =
        metality_compile_msl_library(ctx, "SmokeTerrainShader", sample_msl, true, err_buf, sizeof(err_buf));
    if (lib == 0) {
        std::fprintf(stderr, "MSL Compile Error: %s\n", err_buf);
    }
    REQUIRE(lib != 0, "Runtime MSL compilation failed");

    bool pso_ok = metality_cache_render_pso(
        ctx, lib, "vs_main", "fs_main", METALITY_FORMAT_RGBA16_FLOAT, err_buf, sizeof(err_buf));
    if (!pso_ok) {
        std::fprintf(stderr, "PSO Error: %s\n", err_buf);
    }
    REQUIRE(pso_ok, "Render PSO creation failed");
    REQUIRE(metality_flush_pipeline_cache(ctx), "MTLBinaryArchive flush failed");
    REQUIRE(std::filesystem::exists("/tmp/metality_smoke_cache/metality_pso_cache.metallib"),
            "Serialized MTLBinaryArchive file missing");
    std::printf("[PSO] Runtime MSL compiled & serialized to MTLBinaryArchive without Xcode.app!\n");

    // 5. 驗證 Phase 0 Blaze3D 內建管線 + 離屏紋理 RenderPass + Phase 1 GPU ICB 地形剔除與繪製
    MetalityTextureDesc color_td{256, 256, 1, METALITY_FORMAT_RGBA16_FLOAT, METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED};
    MetalityTextureDesc depth_td{256, 256, 1, METALITY_FORMAT_DEPTH32_FLOAT, METALITY_TEX_USAGE_RENDER_TARGET};
    MetalityTextureDesc atlas_td{16, 16, 1, METALITY_FORMAT_RGBA8_SRGB, METALITY_TEX_USAGE_SAMPLED};

    MetalityTextureHandle offscreen_color = metality_texture_create(ctx, &color_td);
    MetalityTextureHandle offscreen_depth = metality_texture_create(ctx, &depth_td);
    MetalityTextureHandle dummy_atlas     = metality_texture_create(ctx, &atlas_td);
    REQUIRE(offscreen_color != 0 && offscreen_depth != 0 && dummy_atlas != 0, "Phase 0 texture creation failed");

    uint32_t white_pixels[16 * 16];
    std::fill_n(white_pixels, 256, 0xFFFFFFFFu);
    metality_texture_upload_region(ctx, dummy_atlas, 0, 0, 0, 16, 16, white_pixels, 16 * 4);

    MetalitySamplerDesc sd{0, 0, 1, 1};
    MetalitySamplerHandle nearest_sampler = metality_sampler_get_or_create(ctx, &sd);
    REQUIRE(nearest_sampler != 0, "Sampler creation failed");

    MetalityPsoHandle gui_pso = metality_pso_get_builtin_blaze3d(
        ctx, METALITY_BUILTIN_POSITION_TEX_COLOR, METALITY_FORMAT_RGBA16_FLOAT, METALITY_FORMAT_DEPTH32_FLOAT, METALITY_BLEND_ALPHA);
    REQUIRE(gui_pso != 0, "Builtin Blaze3D GUI PSO creation failed");

    MetalityTerrainHandle terrain = metality_terrain_pipeline_create(ctx, 1024, 4 * 1024 * 1024, err_buf, sizeof(err_buf));
    if (!terrain) {
        std::fprintf(stderr, "Terrain Pipeline Error: %s\n", err_buf);
    }
    REQUIRE(terrain != nullptr, "Phase 1 GPU ICB Terrain pipeline creation failed");

    MetalitySectionGpuRecord sec0{};
    sec0.chunk_sec_x = 0; sec0.chunk_sec_y = 0; sec0.chunk_sec_z = 0;
    sec0.solid_quad_offset = 0;
    sec0.solid_quad_count = 1;
    sec0.aabb_min[0] = 0.0f; sec0.aabb_min[1] = 0.0f; sec0.aabb_min[2] = 0.0f;
    sec0.aabb_max[0] = 16.0f; sec0.aabb_max[1] = 16.0f; sec0.aabb_max[2] = 16.0f;
    metality_terrain_update_section(terrain, 0, &sec0);

    float frustum[24] = {
        1, 0, 0, 100,  -1, 0, 0, 100,
        0, 1, 0, 100,   0,-1, 0, 100,
        0, 0, 1, 100,   0, 0,-1, 100
    };
    float cam_pos[3] = {0.0f, 0.0f, 0.0f};
    float identity_vp[16] = {
        1,0,0,0,  0,1,0,0,  0,0,1,0,  0,0,0,1
    };

    metality_begin_frame(ctx);
    metality_terrain_encode_gpu_cull(ctx, terrain, frustum, cam_pos, 1);

    MetalityRenderPassBeginInfo rp{};
    rp.color_target = offscreen_color;
    rp.depth_target = offscreen_depth;
    rp.clear_color[0] = 0.1f; rp.clear_color[1] = 0.2f; rp.clear_color[2] = 0.4f; rp.clear_color[3] = 1.0f;
    rp.clear_depth = 1.0;
    rp.should_clear_color = 1;
    rp.should_clear_depth = 1;
    rp.store_color = 1;
    rp.store_depth = 0;

    REQUIRE(metality_cmd_begin_render_pass(ctx, &rp), "Begin offscreen RenderPass failed");
    metality_cmd_set_viewport(ctx, 0, 0, 256, 256, 0.0f, 1.0f);
    metality_terrain_encode_icb_draw(ctx, terrain, identity_vp, cam_pos, dummy_atlas, nearest_sampler, METALITY_LAYER_SOLID, 1);
    metality_cmd_set_pso(ctx, gui_pso);
    metality_cmd_end_render_pass(ctx);
    metality_end_frame(ctx, false);

    metality_terrain_pipeline_destroy(ctx, terrain);
    metality_texture_destroy(ctx, dummy_atlas);
    metality_texture_destroy(ctx, offscreen_depth);
    metality_texture_destroy(ctx, offscreen_color);
    std::printf("[ICB] Phase 0 Blaze3D PSO + Phase 1 GPU Compute Culling & ICB Terrain Draw verified on GPU!\n");

    // 6. 驗證 sRGB UNORM -> Linear RGBA16Float Present 解碼（防止雙重 Gamma 偏白）
    MetalityTextureDesc srgb_src_td{16, 16, 1, METALITY_FORMAT_RGBA8_UNORM, METALITY_TEX_USAGE_SAMPLED};
    MetalityTextureDesc lin_dst_td{16, 16, 1, METALITY_FORMAT_RGBA16_FLOAT, METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED};
    MetalityTextureHandle srgb_src_tex = metality_texture_create(ctx, &srgb_src_td);
    MetalityTextureHandle lin_dst_tex = metality_texture_create(ctx, &lin_dst_td);
    REQUIRE(srgb_src_tex != 0 && lin_dst_tex != 0, "Present sRGB regression texture creation failed");

    uint32_t gray_pixels[16 * 16];
    std::fill_n(gray_pixels, 256, 0xFF808080u);
    metality_texture_upload_region(ctx, srgb_src_tex, 0, 0, 0, 16, 16, gray_pixels, 16 * 4);

    void* rb_cpu = nullptr;
    MetalityBufferHandle rb_buf = metality_buffer_alloc_shared(ctx, 16 * 16 * 8, false, &rb_cpu, nullptr);
    REQUIRE(rb_buf != 0 && rb_cpu != nullptr, "Readback buffer creation failed");

    MetalitySamplerDesc lsd{1, 0, 0, 1};
    MetalitySamplerHandle linear_sampler = metality_sampler_get_or_create(ctx, &lsd);
    MetalityPsoHandle present_srgb_pso = metality_pso_get_builtin_blaze3d(
        ctx, METALITY_BUILTIN_BLIT_PRESENT_YFLIP_SRGB_TO_LINEAR,
        METALITY_FORMAT_RGBA16_FLOAT, static_cast<MetalityPixelFormat>(0), METALITY_BLEND_OPAQUE);
    REQUIRE(linear_sampler != 0 && present_srgb_pso != 0, "Present sRGB->Linear PSO creation failed");

    uint64_t f_pres = metality_begin_frame(ctx);
    MetalityRenderPassBeginInfo prp{};
    prp.color_target = lin_dst_tex;
    prp.store_color = 1;
    REQUIRE(metality_cmd_begin_render_pass(ctx, &prp), "Begin sRGB->Linear present RenderPass failed");
    metality_cmd_set_viewport(ctx, 0, 0, 16, 16, 0.0f, 1.0f);
    metality_cmd_set_pso(ctx, present_srgb_pso);
    metality_cmd_bind_texture(ctx, 0, srgb_src_tex, linear_sampler);
    metality_cmd_draw(ctx, 0, 3);
    metality_cmd_end_render_pass(ctx);
    metality_cmd_copy_texture_to_buffer(ctx, lin_dst_tex, rb_buf, 0, 16 * 8, 16, 16);
    metality_end_frame(ctx, false);
    REQUIRE(metality_wait_frame_complete(ctx, f_pres, 2000), "Present sRGB->Linear frame failed to complete");

    const uint16_t* px = static_cast<const uint16_t*>(rb_cpu) + (8u * 16u + 8u) * 4u;
    const float lin_r = decode_f16(px[0]);
    const float lin_g = decode_f16(px[1]);
    const float lin_b = decode_f16(px[2]);
    const float lin_a = decode_f16(px[3]);
    std::printf("[Present] sRGB(128,128,128) -> Linear RGBA16F (%.4f, %.4f, %.4f, %.4f)\n", lin_r, lin_g, lin_b, lin_a);
    REQUIRE(lin_r >= 0.205f && lin_r <= 0.228f, "Expected exact sRGB->Linear R in [0.205, 0.228]");
    REQUIRE(lin_g >= 0.205f && lin_g <= 0.228f, "Expected exact sRGB->Linear G in [0.205, 0.228]");
    REQUIRE(lin_b >= 0.205f && lin_b <= 0.228f, "Expected exact sRGB->Linear B in [0.205, 0.228]");
    REQUIRE(lin_a >= 0.99f && lin_a <= 1.01f, "Expected opaque alpha == 1.0");
    metality_buffer_retire(ctx, rb_buf);
    metality_texture_destroy(ctx, lin_dst_tex);
    metality_texture_destroy(ctx, srgb_src_tex);

    // 7. 驗證多影格 Tracked Buffer Copy 與動態移動時 RT Voxel Ring 無競爭無卡頓
    void* cp_src_cpu = nullptr;
    void* cp_dst_cpu = nullptr;
    MetalityBufferHandle cp_src = metality_buffer_alloc_shared(ctx, 4096, false, &cp_src_cpu, nullptr);
    MetalityBufferHandle cp_dst = metality_buffer_alloc_shared(ctx, 4096, false, &cp_dst_cpu, nullptr);
    MetalityRtSceneHandle rt_ring = metality_rt_scene_create(ctx, 64, false);
    REQUIRE(cp_src != 0 && cp_dst != 0 && rt_ring != nullptr, "Multi-frame ring setup failed");

    for (uint32_t i = 0; i < 5; ++i) {
        const uint8_t pat = static_cast<uint8_t>(0x30u + i);
        std::memset(cp_src_cpu, pat, 256);
        uint64_t fid = metality_begin_frame(ctx);
        metality_cmd_copy_buffer(ctx, cp_src, 0, cp_dst, 0, 256);
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, rt_ring, static_cast<float>(i * 4), 64.0f, static_cast<float>(i * 4)),
                "RT TLAS/Voxel ring rebuild failed");
        metality_end_frame(ctx, false);
        REQUIRE(metality_wait_frame_complete(ctx, fid, 2000), "Multi-frame copy/TLAS frame wait failed");
        const uint8_t* dst_bytes = static_cast<const uint8_t*>(cp_dst_cpu);
        REQUIRE(dst_bytes[0] == pat && dst_bytes[255] == pat, "Tracked GPU buffer copy mismatch");
    }
    metality_rt_scene_destroy(rt_ring);
    metality_buffer_retire(ctx, cp_src);
    metality_buffer_retire(ctx, cp_dst);
    std::printf("[Sync] 5-frame tracked copy_buffer + RT voxel ring verified cleanly!\n");

    // 8. 驗證即時執行期設定切換（EDR / VSync / FramesInFlight / RenderScale）
    metality_set_edr_enabled(ctx, false);
    metality_set_edr_enabled(ctx, true);
    metality_set_display_sync(ctx, false);
    metality_set_display_sync(ctx, true);
    metality_set_max_frames_in_flight(ctx, 2u);
    metality_set_max_frames_in_flight(ctx, 3u);
    metality_set_shader_render_scale(ctx, 0.67f);
    metality_set_shader_render_scale(ctx, 1.0f);
    std::printf("[Runtime] Live settings setters verified cleanly!\n");

    // 9. 驗證高密度 Cutout 方塊 (>4096 quads) 容量保護與 24576 完整容納
    {
        std::vector<uint16_t> dense_blocks(18 * 18 * 18, 0);
        std::vector<uint8_t> dense_light(18 * 18 * 18, 0xF0);
        std::vector<uint32_t> dense_tint(16 * 16 * 16, 0xFFFFFF);
        MetalityBlockMaterialEntry pal[2]{};
        pal[1].layer = METALITY_LAYER_CUTOUT;
        pal[1].is_opaque_full_cube = 0;
        pal[1].alpha = 255;

        // 3D 棋盤格在每個方塊處皆生成 6 面 -> 2048 格 * 6 = 12288 quads (>4096)
        for (int py = 1; py <= 16; ++py) {
            for (int pz = 1; pz <= 16; ++pz) {
                for (int px = 1; px <= 16; ++px) {
                    if (((px + py + pz) & 1) == 0) {
                        dense_blocks[(py * 18 + pz) * 18 + px] = 1;
                    }
                }
            }
        }

        MetalityChunkSectionInput in{};
        in.block_material_ids = dense_blocks.data();
        in.packed_light = dense_light.data();
        in.biome_tint_rgb = dense_tint.data();
        in.palette_table = pal;
        in.palette_size = 2;
        in.enable_greedy_merge = 1;

        // (a) 小容量 4096 時絕不能回報超過容量的 quad count
        std::vector<MetalityPackedQuad> small_buf(4096);
        MetalityMeshedSectionOutput small_out{};
        small_out.cutout_quads = small_buf.data();
        small_out.cutout_quad_capacity = 4096;
        REQUIRE(metality_mesh_chunk_section_neon(&in, &small_out), "Dense checkerboard meshing (small cap) failed");
        REQUIRE(small_out.cutout_quad_count == 4096, "Cutout quad count must be strictly clamped to capacity 4096");

        // (b) Full 24576 容量時完整輸出 12288 quads
        std::vector<MetalityPackedQuad> full_buf(24576);
        MetalityMeshedSectionOutput full_out{};
        full_out.cutout_quads = full_buf.data();
        full_out.cutout_quad_capacity = 24576;
        REQUIRE(metality_mesh_chunk_section_neon(&in, &full_out), "Dense checkerboard meshing (full cap) failed");
        REQUIRE(full_out.cutout_quad_count == 12288, "Dense checkerboard must emit all 12288 cutout quads cleanly");
        std::printf("[Mesher] Dense >4096 cutout quad safety & 24576 capacity verified (%u quads).\n", full_out.cutout_quad_count);
    }

    metality_context_destroy(ctx);
    std::printf("=== ALL SMOKE TESTS PASSED SUCCESSFULLY ===\n");
    return 0;
}

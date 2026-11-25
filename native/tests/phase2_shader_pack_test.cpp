#include "metality/metality_c_api.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#define REQUIRE(cond, msg)                                                        \
    do {                                                                          \
        if (!(cond)) {                                                            \
            std::fprintf(stderr, "[FAIL] %s (%s:%d)\n", msg, __FILE__, __LINE__); \
            std::exit(1);                                                         \
        }                                                                         \
    } while (0)

namespace {

std::string read_file_str(const std::filesystem::path& p) {
    std::ifstream ifs(p, std::ios::binary);
    std::ostringstream oss;
    oss << ifs.rdbuf();
    return oss.str();
}

} // namespace

int main(int argc, char** argv) {
    std::printf("=== Metality Phase 2: Aperture pack.ts + Slang-to-MSL + MetalFX Test ===\n");

    const std::filesystem::path pack_dir =
        (argc > 1 && argv[1] && argv[1][0] != '\0')
            ? std::filesystem::path(argv[1])
            : std::filesystem::path(METALITY_EXAMPLE_PACK_DIR);

    REQUIRE(std::filesystem::exists(pack_dir / "pack.ts"), "Missing example pack.ts");

    // -------------------------------------------------------------------------
    // 1. 驗證 Slang -> MSL 轉譯器：保護 Solid 地形 HSR 並保留 FP16 half 向量型別
    // -------------------------------------------------------------------------
    const std::string terrain_slang = read_file_str(pack_dir / "shaders" / "terrain.slang");
    const std::string lighting_slang = read_file_str(pack_dir / "shaders" / "deferred_lighting.slang");
    REQUIRE(!terrain_slang.empty() && !lighting_slang.empty(), "Failed to read .slang shader sources");

    std::vector<char> msl_buf(128 * 1024);
    char err_buf[512] = {0};

    REQUIRE(metality_transpile_slang_to_msl(
                terrain_slang.c_str(), "fsTerrainSolid",
                msl_buf.data(), static_cast<uint32_t>(msl_buf.size()),
                err_buf, sizeof(err_buf)),
            "Failed to transpile fsTerrainSolid");
    const std::string solid_msl(msl_buf.data());
    REQUIRE(solid_msl.find("discard_fragment") == std::string::npos,
            "fsTerrainSolid must NEVER emit discard_fragment (HSR guarantee!)");
    REQUIRE(solid_msl.find("half4") != std::string::npos,
            "Expected FP16 half4 emission for Apple Silicon ALU optimization");

    REQUIRE(metality_transpile_slang_to_msl(
                terrain_slang.c_str(), "fsTerrainCutout",
                msl_buf.data(), static_cast<uint32_t>(msl_buf.size()),
                err_buf, sizeof(err_buf)),
            "Failed to transpile fsTerrainCutout");
    const std::string cutout_msl(msl_buf.data());
    REQUIRE(cutout_msl.find("discard_fragment()") != std::string::npos,
            "fsTerrainCutout must emit discard_fragment()");

    REQUIRE(metality_transpile_slang_to_msl(
                lighting_slang.c_str(), "fsDeferredMain",
                msl_buf.data(), static_cast<uint32_t>(msl_buf.size()),
                err_buf, sizeof(err_buf)),
            "Failed to transpile fsDeferredMain");
    const std::string deferred_msl(msl_buf.data());
    REQUIRE(deferred_msl.find("metality_tex_load(") != std::string::npos,
            "Expected deferred lighting .Load() rewrite");
    std::printf("[Slang] Transpiled terrain.slang (Solid HSR clean + Cutout alpha-test) & deferred_lighting.slang to FP16 MSL.\n");

    // -------------------------------------------------------------------------
    // 2. 驗證 JavaScriptCore 執行 pack.ts 與 TBDR Render Graph 自動融合分析
    // -------------------------------------------------------------------------
    MetalityConfig cfg{};
    cfg.cache_directory_utf8 = "/tmp/metality_phase2_cache";
    cfg.frames_in_flight = 3;
    cfg.enable_edr_hdr = 1;
    std::filesystem::remove_all(cfg.cache_directory_utf8);

    MetalityContext* ctx = metality_context_create(&cfg);

    MetalityPackManifestSummary summary{};
    MetalityPackHandle pack = metality_pack_load_directory(
        ctx, pack_dir.c_str(), 3456, 2234, 0.67f, &summary, err_buf, sizeof(err_buf));
    if (!pack) {
        std::fprintf(stderr, "Pack load error: %s\n", err_buf);
    }
    REQUIRE(pack != nullptr, "metality_pack_load_directory failed");

    std::printf("[Pack ] Shadow: %ux%u (enabled=%u, %u cascades, GPU-Cull=%u) | EDR=%u | HWRT=%u | Thermal=%u\n",
                summary.shadow_resolution, summary.shadow_resolution,
                summary.shadow_enabled, summary.shadow_cascades,
                summary.gpu_driven_shadow_cull,
                summary.enable_edr, summary.enable_hw_raytracing, summary.thermal_adaptive);
    std::printf("[TBDR ] Passes=%u -> FusedGroups=%u | Memoryless=%u/%u | Saved DRAM=%.2f MB/frame | PSOs=%u\n",
                summary.total_pass_count, summary.fused_group_count,
                summary.memoryless_texture_count, summary.declared_texture_count,
                static_cast<double>(summary.saved_dram_bytes_per_frame) / (1024.0 * 1024.0),
                summary.compiled_pso_count);

    REQUIRE(summary.shadow_resolution == 2048, "Expected 2048 shadow resolution from pack.ts");
    REQUIRE(summary.enable_edr == 1, "Expected EDR enabled from pack.ts");
    REQUIRE(summary.memoryless_texture_count == 3, "Expected 3 G-Buffer attachments promoted to 0MB Memoryless");
    REQUIRE(summary.upscaler_mode == METALITY_UPSCALE_TEMPORAL, "Expected MetalFX Temporal upscaler");
    REQUIRE(summary.saved_dram_bytes_per_frame > 30ULL * 1024ULL * 1024ULL, "Expected >30MB/frame DRAM savings");

    // -------------------------------------------------------------------------
    // 3. 若具 Apple Silicon GPU，驗證真實 Slang->PSO 編譯與 MetalFX Temporal 執行及 GPU 計時器
    // -------------------------------------------------------------------------
    if (ctx) {
        REQUIRE(summary.compiled_pso_count >= 3,
                "All 3 Slang shader pipelines (Solid, Cutout, Deferred) must compile to Apple GPU PSOs");

        const uint32_t in_w = 1280, in_h = 800;
        const uint32_t out_w = 1920, out_h = 1200;

        MetalityUpscalerDesc udesc{};
        udesc.mode = METALITY_UPSCALE_TEMPORAL;
        udesc.input_width = in_w;
        udesc.input_height = in_h;
        udesc.output_width = out_w;
        udesc.output_height = out_h;
        udesc.color_format = METALITY_FORMAT_RGBA16_FLOAT;
        udesc.depth_format = METALITY_FORMAT_DEPTH32_FLOAT;
        udesc.motion_format = METALITY_FORMAT_RG16_FLOAT;
        udesc.output_format = METALITY_FORMAT_RGBA16_FLOAT;
        udesc.auto_exposure = 1;

        MetalityUpscalerHandle upscaler = metality_upscaler_create(ctx, &udesc);
        REQUIRE(upscaler != nullptr, "Failed to create MTLFXTemporalScaler on Apple Silicon");

        MetalityTextureDesc color_td{in_w, in_h, 1, METALITY_FORMAT_RGBA16_FLOAT, METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED};
        MetalityTextureDesc depth_td{in_w, in_h, 1, METALITY_FORMAT_DEPTH32_FLOAT, METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED};
        MetalityTextureDesc motion_td{in_w, in_h, 1, METALITY_FORMAT_RG16_FLOAT, METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED};
        MetalityTextureDesc out_td{out_w, out_h, 1, METALITY_FORMAT_RGBA16_FLOAT, METALITY_TEX_USAGE_STORAGE_WRITE | METALITY_TEX_USAGE_SAMPLED};

        MetalityTextureHandle ctex = metality_texture_create(ctx, &color_td);
        MetalityTextureHandle dtex = metality_texture_create(ctx, &depth_td);
        MetalityTextureHandle mtex = metality_texture_create(ctx, &motion_td);
        MetalityTextureHandle otex = metality_texture_create(ctx, &out_td);
        REQUIRE(ctex && dtex && mtex && otex, "Failed to allocate MetalFX input/output textures");

        for (uint64_t f = 0; f < 3; ++f) {
            metality_begin_frame(ctx);
            float jx = 0.0f, jy = 0.0f;
            metality_upscaler_halton_jitter(f, in_w, out_w, &jx, &jy);

            MetalityUpscaleDispatchInfo dinfo{};
            dinfo.color_tex = ctex;
            dinfo.depth_tex = dtex;
            dinfo.motion_tex = mtex;
            dinfo.output_tex = otex;
            dinfo.content_width = in_w;
            dinfo.content_height = in_h;
            dinfo.jitter_x = jx;
            dinfo.jitter_y = jy;
            dinfo.motion_scale_x = 1.0f;
            dinfo.motion_scale_y = 1.0f;
            dinfo.reset_history = (f == 0) ? 1 : 0;

            REQUIRE(metality_upscaler_encode(ctx, upscaler, &dinfo), "MetalFX Temporal encode failed");
            metality_end_frame(ctx, false);
        }

        // 再開啟兩幀確保前三幀的 completedHandler 已回傳實際 GPU 執行時間
        for (int i = 0; i < 3; ++i) {
            metality_begin_frame(ctx);
            metality_end_frame(ctx, false);
        }

        const double gpu_ms = metality_last_gpu_frame_ms(ctx);
        const float rec_scale = metality_recommended_render_scale(ctx, true);
        std::printf("[MetalFX] Encoded 1280x800 -> 1920x1200 Temporal Upscale | Last GPU Time: %.3f ms | Thermal Scale: %.2fx\n",
                    gpu_ms, rec_scale);

        metality_texture_destroy(ctx, otex);
        metality_texture_destroy(ctx, mtex);
        metality_texture_destroy(ctx, dtex);
        metality_texture_destroy(ctx, ctex);
        metality_upscaler_destroy(upscaler);
    } else {
        std::printf("[MetalFX] Headless sandbox mode; skipped live GPU PSO/MetalFX checks.\n");
    }

    metality_pack_destroy(pack);
    if (ctx) {
        metality_context_destroy(ctx);
    }
    std::printf("=== PHASE 2 SHADER PACK & METALFX TESTS PASSED ===\n");
    return 0;
}

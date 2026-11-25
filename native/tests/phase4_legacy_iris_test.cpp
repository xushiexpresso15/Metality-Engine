#include "metality/metality_c_api.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
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

std::vector<uint32_t> build_synthetic_fragment_spirv(bool include_discard) {
    // Valid minimal SPIR-V 1.0 Fragment module:
    // %v4float OpFAdd in fragment function (plus optional OpKill), ensuring Auto-FP16 optimizer injects RelaxedPrecision.
    std::vector<uint32_t> w;
    w.push_back(0x07230203u); // magic
    w.push_back(0x00010000u); // version 1.0
    w.push_back(0u);          // generator
    w.push_back(20u);         // bound
    w.push_back(0u);          // schema

    auto push_inst = [&](uint16_t op, std::initializer_list<uint32_t> operands) {
        const uint16_t wc = static_cast<uint16_t>(1u + operands.size());
        w.push_back((static_cast<uint32_t>(wc) << 16) | op);
        for (uint32_t opnd : operands) {
            w.push_back(opnd);
        }
    };

    push_inst(17u, {1u});          // OpCapability Shader
    push_inst(14u, {0u, 1u});      // OpMemoryModel Logical GLSL450
    push_inst(15u, {4u, 4u, 0x6E69616Du, 0u}); // OpEntryPoint Fragment %4 "main"

    push_inst(19u, {1u});          // OpTypeVoid %1
    push_inst(33u, {2u, 1u});      // OpTypeFunction %2 %1
    push_inst(22u, {3u, 32u});     // OpTypeFloat %3 32
    push_inst(23u, {5u, 3u, 4u});  // OpTypeVector %5 %3 4
    push_inst(43u, {3u, 6u, 0x3F800000u}); // OpConstant %3 %6 1.0f
    push_inst(44u, {5u, 7u, 6u, 6u, 6u, 6u}); // OpConstantComposite %5 %7 ...

    push_inst(54u, {1u, 4u, 0u, 2u}); // OpFunction %1 %4 None %2
    push_inst(248u, {8u});         // OpLabel %8
    push_inst(129u, {5u, 9u, 7u, 7u}); // OpFAdd %5 %9 %7 %7 (candidate for RelaxedPrecision)
    push_inst(133u, {5u, 10u, 9u, 7u}); // OpFMul %5 %10 %9 %7 (candidate for RelaxedPrecision)
    if (include_discard) {
        push_inst(252u, {});       // OpKill (inside unreachable block test / discard counter)
    } else {
        push_inst(253u, {});       // OpReturn
    }
    push_inst(56u, {});            // OpFunctionEnd
    return w;
}

} // namespace

int main() {
    std::printf("=== Metality Phase 4: Legacy Iris Bridge, >16 Sampler MSL & Live HDR Composer Test ===\n");

    // 1. SPIR-V Apple GPU Optimizer（HSR OpKill 計數 + Fragment FP16 RelaxedPrecision 注入）
    std::vector<uint32_t> spv_cutout = build_synthetic_fragment_spirv(true);
    std::vector<uint32_t> out_words(spv_cutout.size() + 64u, 0u);
    MetalitySpirvOptStats stats{};
    REQUIRE(metality_spirv_optimize_for_apple_gpu(
                spv_cutout.data(), static_cast<uint32_t>(spv_cutout.size()),
                true, out_words.data(), static_cast<uint32_t>(out_words.size()), &stats),
            "metality_spirv_optimize_for_apple_gpu failed");
    REQUIRE(stats.discard_op_count == 1u, "Expected 1 OpKill detected in cutout SPIR-V");
    REQUIRE(stats.relaxed_fp16_decorations >= 2u, "Expected RelaxedPrecision decorations injected");

    std::vector<uint32_t> spv_solid = build_synthetic_fragment_spirv(false);
    MetalitySpirvOptStats solid_stats{};
    REQUIRE(metality_spirv_optimize_for_apple_gpu(
                spv_solid.data(), static_cast<uint32_t>(spv_solid.size()),
                true, out_words.data(), static_cast<uint32_t>(out_words.size()), &solid_stats),
            "Solid SPIR-V optimize failed");
    REQUIRE(solid_stats.discard_op_count == 0u, "HSR solid shader must report 0 OpKill");

    // 2. 初始化原生 Metal 裝置與檢查 UMA / Thermal API
    MetalityConfig cfg{};
    cfg.cache_directory_utf8 = "/tmp/metality_phase4_test_cache";
    cfg.frames_in_flight = 3;
    cfg.enable_validation = 1;
    cfg.enable_edr_hdr = 1;
    MetalityContext* ctx = metality_context_create(&cfg);
    REQUIRE(ctx != nullptr, "Failed to create Metal context");

    MetalityUmaPressureInfo uma{};
    metality_query_uma_pressure(ctx, &uma);
    REQUIRE(uma.recommended_working_set_bytes > 0, "Expected non-zero Apple Silicon UMA working set");
    REQUIRE(uma.thermal_state_level <= 3u, "Thermal state level out of range");

    // 3. >16 Sampler MSL 重寫並在真實 Apple M4 Max GPU 上編譯通過
    std::string heavy_msl = "#include <metal_stdlib>\nusing namespace metal;\n"
                            "fragment half4 main0(float4 pos [[position]],\n";
    for (int i = 0; i < 20; ++i) {
        heavy_msl += "    texture2d<float> t" + std::to_string(i) + " [[texture(" + std::to_string(i) + ")]],\n";
        heavy_msl += "    sampler s" + std::to_string(i) + " [[sampler(" + std::to_string(i) + ")]]";
        heavy_msl += (i == 19) ? "\n) {\n" : ",\n";
    }
    heavy_msl += "    float4 c = t0.sample(s0, float2(0.5)) + t15.sample(s15, float2(0.5)) + t19.sample(s19, float2(0.5));\n"
                 "    if (c.a < 0.0) { discard_fragment(); }\n"
                 "    return half4(c);\n}\n";

    std::vector<char> rewritten_msl(heavy_msl.size() + 4096u, '\0');
    uint32_t inlined_samplers = 0u;
    REQUIRE(metality_rewrite_msl_samplers_and_fp16(
                heavy_msl.c_str(), true, rewritten_msl.data(),
                static_cast<uint32_t>(rewritten_msl.size()), &inlined_samplers),
            "MSL >16 sampler rewrite failed");
    REQUIRE(inlined_samplers == 5u, "Expected 5 samplers (slots 15..19) folded to shared constexpr sampler (15 dynamic + 1 constexpr = 16 hardware slots)");
    REQUIRE(std::string(rewritten_msl.data()).find("discard_fragment()") == std::string::npos,
            "strip_discard_safety must eliminate discard_fragment()");

    char err_buf[4096] = {0};
    MetalityShaderHandle sh = metality_compile_msl_library(
            ctx, "Phase4HeavySamplerShader", rewritten_msl.data(), true, err_buf, sizeof(err_buf));
    REQUIRE(sh != 0, err_buf);

    // 4. Live Multi-Pass HDR Composer 真實 GPU 渲染 + Readback 驗證（陽光體積光 + SSAO + Bloom + ACES）
    const uint32_t w = 256u, h = 144u;
    MetalityTextureDesc cdesc{w, h, 1, METALITY_FORMAT_RGBA8_UNORM, METALITY_TEX_USAGE_SAMPLED | METALITY_TEX_USAGE_RENDER_TARGET};
    MetalityTextureDesc ddesc{w, h, 1, METALITY_FORMAT_DEPTH32_FLOAT, METALITY_TEX_USAGE_SAMPLED | METALITY_TEX_USAGE_RENDER_TARGET};
    MetalityTextureHandle ctex = metality_texture_create(ctx, &cdesc);
    MetalityTextureHandle dtex = metality_texture_create(ctx, &ddesc);
    REQUIRE(ctex != 0 && dtex != 0, "Failed to create composer color/depth textures");

    MetalityComposerHandle comp = metality_composer_create(ctx);
    REQUIRE(comp != nullptr, "metality_composer_create failed");

    void* rb_ptr = nullptr;
    MetalityBufferHandle rb_buf = metality_buffer_alloc_shared(ctx, w * h * 4u, false, &rb_ptr, nullptr);
    REQUIRE(rb_buf != 0 && rb_ptr != nullptr, "Failed to allocate readback buffer");

    const uint64_t fid = metality_begin_frame(ctx);
    // 左側有前景地形（reversed depth=0.5），右側與上方是天空（reversed depth=0.0），夕陽位於畫面正前方天際
    metality_cmd_clear_color_depth(ctx, ctex, 0.72f, 0.52f, 0.34f, 1.0f, dtex, 0.0);
    metality_cmd_clear_color_depth_rect(ctx, ctex, 0.25f, 0.45f, 0.22f, 1.0f, dtex, 0.6, 0, 0, 0, w / 2u, h / 2u);

    MetalityComposerUniforms u{};
    // Simple identity-ish projection & view matching camera looking straight at sun (0, 0.2, -1)
    u.inv_proj[0] = 1.0f; u.inv_proj[5] = 1.0f; u.inv_proj[10] = -1.0f; u.inv_proj[15] = 1.0f;
    u.view_rot[0] = 1.0f; u.view_rot[5] = 1.0f; u.view_rot[10] = 1.0f; u.view_rot[15] = 1.0f;
    u.proj[0] = 1.0f; u.proj[5] = 1.0f; u.proj[10] = -1.0f; u.proj[11] = -1.0f; u.proj[14] = -0.1f;
    u.sun_world_dir[0] = 0.0f; u.sun_world_dir[1] = 0.22f; u.sun_world_dir[2] = -0.975f; u.sun_world_dir[3] = 1.0f;
    u.sky_color[0] = 0.7f; u.sky_color[1] = 0.5f; u.sky_color[2] = 0.3f; u.sky_color[3] = 1.0f;
    u.width = w;
    u.height = h;
    u.time_seconds = 1.5f;
    u.rain_strength = 0.0f;
    u.bloom_strength = 0.45f;
    u.ssao_strength = 0.65f;
    u.godray_strength = 0.80f;
    u.exposure = 1.15f;
    u.tonemap_mode = 0u;
    u.depth_reversed = 1u;

    REQUIRE(metality_composer_execute(ctx, comp, ctex, dtex, &u), "metality_composer_execute failed");
    metality_cmd_copy_texture_to_buffer(ctx, ctex, rb_buf, 0, w * 4u, w, h);
    metality_end_frame(ctx, false);
    REQUIRE(metality_wait_frame_complete(ctx, fid, 3000), "GPU frame did not complete in time");

    const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
    const size_t terrain_idx = (static_cast<size_t>(h / 4u) * w + (w / 4u)) * 4u;
    const size_t sky_idx = (static_cast<size_t>(h * 3u / 4u) * w + (w / 2u)) * 4u;
    std::printf("[SPIR-V] OpKill(cutout)=%u | RelaxedPrecision=%u | InlinedSamplers(>16)=%u\n",
                stats.discard_op_count, stats.relaxed_fp16_decorations, inlined_samplers);
    std::printf("[UMA   ] Allocated=%.2f MB / WorkingSet=%.2f MB (%.1f%%) | Thermal=%u\n",
                static_cast<double>(uma.current_allocated_bytes) / (1024.0 * 1024.0),
                static_cast<double>(uma.recommended_working_set_bytes) / (1024.0 * 1024.0),
                uma.utilization_ratio * 100.0,
                uma.thermal_state_level);
    std::printf("[GPU   ] Composer Output TerrainRGB=(%u,%u,%u) SkyGodrayRGB=(%u,%u,%u)\n",
                px[terrain_idx], px[terrain_idx + 1], px[terrain_idx + 2],
                px[sky_idx], px[sky_idx + 1], px[sky_idx + 2]);

    REQUIRE(px[sky_idx] > px[terrain_idx] && px[sky_idx + 1] > 0u, "Expected sunlit godray sky brighter than shaded terrain");

    metality_composer_destroy(comp);
    metality_context_destroy(ctx);
    std::printf("=== PHASE 4 NATIVE IRIS BRIDGE & COMPOSER TESTS PASSED ===\n");
    return 0;
}

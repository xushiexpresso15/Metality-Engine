#include "metality/metality_c_api.h"

#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <vector>

#define REQUIRE(cond, msg)                                                        \
    do {                                                                          \
        if (!(cond)) {                                                            \
            std::fprintf(stderr, "[FAIL] %s (%s:%d)\n", msg, __FILE__, __LINE__); \
            std::exit(1);                                                         \
        }                                                                         \
    } while (0)

namespace {

inline int pad_idx(int x, int y, int z) {
    return ((y + 1) * METALITY_PADDED_DIM + (z + 1)) * METALITY_PADDED_DIM + (x + 1);
}

void build_inv_view_proj(
    const float eye[3],
    const float target[3],
    const float up_hint[3],
    float fov_y_rad,
    float aspect,
    float z_near,
    float z_far,
    float out_inv_vp[16]
) {
    auto norm = [](float x, float y, float z, float out[3]) {
        float l = std::sqrt(x * x + y * y + z * z);
        out[0] = x / l; out[1] = y / l; out[2] = z / l;
    };
    auto cross = [](const float a[3], const float b[3], float out[3]) {
        out[0] = a[1] * b[2] - a[2] * b[1];
        out[1] = a[2] * b[0] - a[0] * b[2];
        out[2] = a[0] * b[1] - a[1] * b[0];
    };

    float fwd[3], right[3], up[3];
    norm(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2], fwd);
    cross(fwd, up_hint, right);
    norm(right[0], right[1], right[2], right);
    cross(right, fwd, up);

    // 明確解析建構 Inv(Proj * View) = View^-1 * Proj^-1 (Column-major)
    float f = 1.0f / std::tan(fov_y_rad * 0.5f);
    float p22 = z_far / (z_near - z_far);
    float p32 = (z_far * z_near) / (z_near - z_far);

    // inv_proj * (ndc_x, ndc_y, z_ndc, 1) -> camera space ray far point
    float proj[16] = {};
    proj[0] = f / aspect;
    proj[5] = f;
    proj[10] = p22;
    proj[11] = -1.0f;
    proj[14] = p32;

    float view[16] = {};
    view[0] = right[0];  view[4] = right[1];  view[8]  = right[2];  view[12] = -(right[0] * eye[0] + right[1] * eye[1] + right[2] * eye[2]);
    view[1] = up[0];     view[5] = up[1];     view[9]  = up[2];     view[13] = -(up[0] * eye[0] + up[1] * eye[1] + up[2] * eye[2]);
    view[2] = -fwd[0];   view[6] = -fwd[1];   view[10] = -fwd[2];   view[14] = (fwd[0] * eye[0] + fwd[1] * eye[1] + fwd[2] * eye[2]);
    view[15] = 1.0f;

    float vp[16] = {};
    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float s = 0.0f;
            for (int k = 0; k < 4; ++k) {
                s += proj[k * 4 + row] * view[col * 4 + k];
            }
            vp[col * 4 + row] = s;
        }
    }

    // Standard 4x4 matrix inversion
    float m[16];
    std::memcpy(m, vp, sizeof(m));
    float inv[16];
    inv[0] = m[5]  * m[10] * m[15] - m[5]  * m[11] * m[14] - m[9]  * m[6]  * m[15] +
             m[9]  * m[7]  * m[14] + m[13] * m[6]  * m[11] - m[13] * m[7]  * m[10];
    inv[4] = -m[4]  * m[10] * m[15] + m[4]  * m[11] * m[14] + m[8]  * m[6]  * m[15] -
             m[8]  * m[7]  * m[14] - m[12] * m[6]  * m[11] + m[12] * m[7]  * m[10];
    inv[8] = m[4]  * m[9] * m[15] - m[4]  * m[11] * m[13] - m[8]  * m[5] * m[15] +
             m[8]  * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
    inv[12] = -m[4]  * m[9] * m[14] + m[4]  * m[10] * m[13] + m[8]  * m[5] * m[14] -
              m[8]  * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
    inv[1] = -m[1]  * m[10] * m[15] + m[1]  * m[11] * m[14] + m[9]  * m[2] * m[15] -
             m[9]  * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
    inv[5] = m[0]  * m[10] * m[15] - m[0]  * m[11] * m[14] - m[8]  * m[2] * m[15] +
             m[8]  * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
    inv[9] = -m[0]  * m[9] * m[15] + m[0]  * m[11] * m[13] + m[8]  * m[1] * m[15] -
             m[8]  * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
    inv[13] = m[0]  * m[9] * m[14] - m[0]  * m[10] * m[13] - m[8]  * m[1] * m[14] +
              m[8]  * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
    inv[2] = m[1]  * m[6] * m[15] - m[1]  * m[7] * m[14] - m[5]  * m[2] * m[15] +
             m[5]  * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
    inv[6] = -m[0]  * m[6] * m[15] + m[0]  * m[7] * m[14] + m[4]  * m[2] * m[15] -
             m[4]  * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
    inv[10] = m[0]  * m[5] * m[15] - m[0]  * m[7] * m[13] - m[4]  * m[1] * m[15] +
              m[4]  * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
    inv[14] = -m[0]  * m[5] * m[14] + m[0]  * m[6] * m[13] + m[4]  * m[1] * m[14] -
              m[4]  * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
    inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
             m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
    inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
             m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
    inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
              m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
    inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
              m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];

    float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
    det = 1.0f / det;
    for (int i = 0; i < 16; ++i) {
        out_inv_vp[i] = inv[i] * det;
    }
}

void build_test_scene_section(
    std::vector<MetalityPackedQuad>& out_solid_quads
) {
    std::vector<MetalityBlockMaterialEntry> palette(2);
    palette[0].layer = METALITY_LAYER_EMPTY;
    palette[0].is_opaque_full_cube = 0;

    for (int f = 0; f < 6; ++f) palette[1].face_sprites[f] = 1;
    palette[1].layer = METALITY_LAYER_SOLID;
    palette[1].is_opaque_full_cube = 1;
    palette[1].alpha = 255;

    std::vector<uint16_t> blocks(METALITY_PADDED_VOLUME, 0);
    std::vector<uint8_t> light(METALITY_PADDED_VOLUME, 0xF0);
    std::vector<uint32_t> tint(16 * 16 * 16, 0xD8D8D8u);

    // 地面平台 y = 2, x = 1..14, z = 1..14
    for (int z = 1; z <= 14; ++z) {
        for (int x = 1; x <= 14; ++x) {
            blocks[pad_idx(x, 2, z)] = 1;
        }
    }
    // 遮蔽日照的石柱 x = 10..11, y = 3..10, z = 6..9（當陽光從 (+X, +Y) 射來時會在左側 x=5..8 投下明確陰影）
    for (int y = 3; y <= 10; ++y) {
        for (int z = 6; z <= 9; ++z) {
            for (int x = 10; x <= 11; ++x) {
                blocks[pad_idx(x, y, z)] = 1;
            }
        }
    }

    out_solid_quads.resize(4096);
    std::vector<MetalityPackedQuad> cutout_tmp(1024);
    std::vector<MetalityPackedQuad> trans_tmp(1024);

    MetalityChunkSectionInput in{};
    in.block_material_ids = blocks.data();
    in.packed_light = light.data();
    in.biome_tint_rgb = tint.data();
    in.palette_table = palette.data();
    in.palette_size = static_cast<uint32_t>(palette.size());
    in.enable_greedy_merge = 1;

    MetalityMeshedSectionOutput out{};
    out.solid_quads = out_solid_quads.data();
    out.solid_quad_capacity = static_cast<uint32_t>(out_solid_quads.size());
    out.cutout_quads = cutout_tmp.data();
    out.cutout_quad_capacity = static_cast<uint32_t>(cutout_tmp.size());
    out.translucent_quads = trans_tmp.data();
    out.translucent_quad_capacity = static_cast<uint32_t>(trans_tmp.size());

    REQUIRE(metality_mesh_chunk_section_neon(&in, &out), "NEON mesher failed for RT scene");
    out_solid_quads.resize(out.solid_quad_count);
}

void verify_rt_pipeline_mode(
    MetalityContext* ctx,
    const std::vector<MetalityPackedQuad>& solid_quads,
    bool force_voxel_fallback,
    bool expect_hw_bvh
) {
    const uint32_t in_w = 640, in_h = 360;
    const uint32_t out_w = 1280, out_h = 720;

    MetalityRtSceneHandle rt = metality_rt_scene_create(ctx, 128, force_voxel_fallback);
    REQUIRE(rt != nullptr, "metality_rt_scene_create returned null");
    REQUIRE(metality_rt_scene_uses_hardware_bvh(rt) == expect_hw_bvh, "Unexpected HWRT vs Voxel DDA mode");

    REQUIRE(metality_rt_scene_update_section(ctx, rt, 0, 0, 0,
                solid_quads.data(), static_cast<uint32_t>(solid_quads.size()), nullptr, 0),
            "Failed to upload section quads to RT scene");
    REQUIRE(metality_rt_scene_active_blas_count(rt) == 1, "Expected 1 active section BLAS");

    const float cam_pos[3] = {8.0f, 18.0f, 8.0f};
    const float target[3] = {8.0f, 3.0f, 8.0f};
    const float up_hint[3] = {0.0f, 0.0f, -1.0f};
    float inv_vp[16];
    build_inv_view_proj(cam_pos, target, up_hint, 60.0f * 3.14159265f / 180.0f,
                        static_cast<float>(in_w) / static_cast<float>(in_h), 0.1f, 200.0f, inv_vp);

    const float inv_sqrt2 = 0.70710678f;
    const float sun_dir[3] = {inv_sqrt2, inv_sqrt2, 0.0f};

    const uint32_t io_usage = METALITY_TEX_USAGE_SAMPLED | METALITY_TEX_USAGE_STORAGE_WRITE;
    MetalityTextureDesc td_rad{in_w, in_h, 1, METALITY_FORMAT_RGBA16_FLOAT, io_usage | METALITY_TEX_USAGE_RENDER_TARGET};
    MetalityTextureDesc td_dep{in_w, in_h, 1, METALITY_FORMAT_R32_FLOAT, io_usage};
    MetalityTextureDesc td_mot{in_w, in_h, 1, METALITY_FORMAT_RG16_FLOAT, io_usage};
    MetalityTextureDesc td_alb{in_w, in_h, 1, METALITY_FORMAT_RGBA8_UNORM, io_usage};
    MetalityTextureDesc td_nrm{in_w, in_h, 1, METALITY_FORMAT_RGBA16_FLOAT, io_usage};
    MetalityTextureDesc td_rgh{in_w, in_h, 1, METALITY_FORMAT_R16_FLOAT, io_usage};
    MetalityTextureDesc td_out{out_w, out_h, 1, METALITY_FORMAT_RGBA16_FLOAT, io_usage | METALITY_TEX_USAGE_RENDER_TARGET};

    MetalityTextureHandle t_rad = metality_texture_create(ctx, &td_rad);
    MetalityTextureHandle t_dep = metality_texture_create(ctx, &td_dep);
    MetalityTextureHandle t_mot = metality_texture_create(ctx, &td_mot);
    MetalityTextureHandle t_dif = metality_texture_create(ctx, &td_alb);
    MetalityTextureHandle t_spc = metality_texture_create(ctx, &td_alb);
    MetalityTextureHandle t_nrm = metality_texture_create(ctx, &td_nrm);
    MetalityTextureHandle t_rgh = metality_texture_create(ctx, &td_rgh);
    MetalityTextureHandle t_out = metality_texture_create(ctx, &td_out);
    REQUIRE(t_rad && t_dep && t_mot && t_dif && t_spc && t_nrm && t_rgh && t_out, "Failed to allocate RT G-Buffer textures");

    MetalityUpscalerDesc udesc{};
    udesc.mode = METALITY_UPSCALE_TEMPORAL_DENOISED;
    udesc.input_width = in_w;
    udesc.input_height = in_h;
    udesc.output_width = out_w;
    udesc.output_height = out_h;
    udesc.color_format = METALITY_FORMAT_RGBA16_FLOAT;
    udesc.depth_format = METALITY_FORMAT_R32_FLOAT;
    udesc.motion_format = METALITY_FORMAT_RG16_FLOAT;
    udesc.output_format = METALITY_FORMAT_RGBA16_FLOAT;
    udesc.auto_exposure = 1;
    udesc.depth_reversed = 0;

    MetalityUpscalerHandle denoiser = metality_upscaler_create(ctx, &udesc);
    REQUIRE(denoiser != nullptr, "Failed to create MetalFX Temporal Denoised Scaler");

    void* rad_cpu = nullptr;
    void* out_cpu = nullptr;
    const uint32_t rad_bpr = in_w * 8u;
    const uint32_t out_bpr = out_w * 8u;
    MetalityBufferHandle rb_rad = metality_buffer_alloc_shared(ctx, static_cast<uint64_t>(rad_bpr) * in_h, false, &rad_cpu, nullptr);
    MetalityBufferHandle rb_out = metality_buffer_alloc_shared(ctx, static_cast<uint64_t>(out_bpr) * out_h, false, &out_cpu, nullptr);
    REQUIRE(rb_rad && rb_out && rad_cpu && out_cpu, "Failed to allocate readback buffers");

    uint64_t last_fid = 0;
    for (int f = 0; f < 2; ++f) {
        last_fid = metality_begin_frame(ctx);
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, rt, cam_pos[0], cam_pos[1], cam_pos[2]),
                "Failed to rebuild TLAS / voxel grid");
        REQUIRE(metality_rt_dispatch_gi(ctx, rt, inv_vp, cam_pos, sun_dir, in_w, in_h,
                                        t_rad, t_dep, t_mot, t_dif, t_spc, t_nrm, t_rgh),
                "metality_rt_dispatch_gi failed");

        float jx = 0.0f, jy = 0.0f;
        metality_upscaler_halton_jitter(static_cast<uint64_t>(f), in_w, out_w, &jx, &jy);

        MetalityDenoisedUpscaleDispatchInfo dinfo{};
        dinfo.color_tex = t_rad;
        dinfo.depth_tex = t_dep;
        dinfo.motion_tex = t_mot;
        dinfo.diffuse_albedo_tex = t_dif;
        dinfo.specular_albedo_tex = t_spc;
        dinfo.normal_tex = t_nrm;
        dinfo.roughness_tex = t_rgh;
        dinfo.output_tex = t_out;
        dinfo.jitter_x = jx;
        dinfo.jitter_y = jy;
        dinfo.motion_scale_x = 1.0f;
        dinfo.motion_scale_y = 1.0f;
        dinfo.reset_history = (f == 0) ? 1 : 0;
        REQUIRE(metality_upscaler_encode_denoised(ctx, denoiser, &dinfo),
                "metality_upscaler_encode_denoised failed");

        if (f == 1) {
            metality_cmd_copy_texture_to_buffer(ctx, t_rad, rb_rad, 0, rad_bpr, in_w, in_h);
            metality_cmd_copy_texture_to_buffer(ctx, t_out, rb_out, 0, out_bpr, out_w, out_h);
        }
        metality_end_frame(ctx, false);
    }
    REQUIRE(metality_wait_frame_complete(ctx, last_fid, 5000), "GPU RT + Denoiser frame timed out");

    // 分析中線 (z=8, py = in_h/2) 之右側直射日光區 (x~13 -> px~420..445) 與柱影區 (x~7.5 -> px~285..315)
    const __fp16* rad_fp16 = reinterpret_cast<const __fp16*>(rad_cpu);
    const uint32_t row = in_h / 2;
    auto sample_luma = [&](uint32_t px) -> float {
        const size_t idx = (static_cast<size_t>(row) * in_w + px) * 4u;
        float r = static_cast<float>(rad_fp16[idx + 0]);
        float g = static_cast<float>(rad_fp16[idx + 1]);
        float b = static_cast<float>(rad_fp16[idx + 2]);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    };

    float max_lit = 0.0f;
    for (uint32_t px = 415; px <= 445; ++px) {
        max_lit = std::max(max_lit, sample_luma(px));
    }
    float min_shadow = 1e9f;
    for (uint32_t px = 285; px <= 315; ++px) {
        min_shadow = std::min(min_shadow, sample_luma(px));
    }

    // 確認 MetalFX Denoised 輸出貼圖具真實有限且非零之 HDR 像素
    const __fp16* out_fp16 = reinterpret_cast<const __fp16*>(out_cpu);
    const size_t mid_out = (static_cast<size_t>(out_h / 2) * out_w + (out_w * 2 / 3)) * 4u;
    float denoised_r = static_cast<float>(out_fp16[mid_out + 0]);
    float denoised_g = static_cast<float>(out_fp16[mid_out + 1]);
    float denoised_b = static_cast<float>(out_fp16[mid_out + 2]);

    std::printf("[%s] Sunlit luma=%.3f, Shadow luma=%.3f (ratio=%.2fx) | Denoised=(%.3f, %.3f, %.3f)\n",
                expect_hw_bvh ? "HWRT BVH" : "VoxelDDA",
                max_lit, min_shadow, max_lit / std::max(min_shadow, 1e-4f),
                denoised_r, denoised_g, denoised_b);

    REQUIRE(max_lit > min_shadow * 2.0f, "Expected direct sunlit floor luma > 2x shadowed pillar luma");
    REQUIRE(std::isfinite(denoised_r) && denoised_r > 0.05f, "Denoised MetalFX HDR output must be finite & non-zero");

    metality_buffer_retire(ctx, rb_rad);
    metality_buffer_retire(ctx, rb_out);
    metality_upscaler_destroy(denoiser);
    metality_texture_destroy(ctx, t_rad);
    metality_texture_destroy(ctx, t_dep);
    metality_texture_destroy(ctx, t_mot);
    metality_texture_destroy(ctx, t_dif);
    metality_texture_destroy(ctx, t_spc);
    metality_texture_destroy(ctx, t_nrm);
    metality_texture_destroy(ctx, t_rgh);
    metality_texture_destroy(ctx, t_out);
    metality_rt_scene_destroy(rt);
}

} // namespace

int main() {
    std::printf("=== Metality Phase 3: HWRT BLAS/TLAS + Voxel DDA + MetalFX Denoiser Test ===\n");

    MetalityConfig cfg{};
    cfg.cache_directory_utf8 = "/tmp/metality_phase3_cache";
    cfg.frames_in_flight = 3;
    cfg.enable_edr_hdr = 1;
    std::filesystem::remove_all(cfg.cache_directory_utf8);

    MetalityContext* ctx = metality_context_create(&cfg);
    REQUIRE(ctx != nullptr, "Apple Silicon Metal context required");

    MetalityDeviceCaps caps{};
    metality_query_capabilities(ctx, &caps);
    std::printf("[Device] %s | Apple%u | HWRT=%u | MetalFX=%u\n",
                caps.device_name, caps.apple_gpu_family,
                caps.supports_hardware_raytracing, caps.supports_metalfx_temporal);

    // 1. 載入 aurora-pathtraced pack.ts 並驗證 Compute RT + MetalFX Temporal Denoised PSO 編譯
    char err_buf[512] = {0};
    MetalityPackManifestSummary sum{};
    MetalityPackHandle pack = metality_pack_load_directory(
        ctx, METALITY_PATHTRACED_PACK_DIR, 1280, 720, 0.5f, &sum, err_buf, sizeof(err_buf));
    if (!pack) {
        std::fprintf(stderr, "aurora-pathtraced load error: %s\n", err_buf);
    }
    REQUIRE(pack != nullptr, "Failed to load aurora-pathtraced example pack");
    REQUIRE(sum.enable_hw_raytracing == 1, "Expected enable_hw_raytracing=1");
    REQUIRE(sum.upscaler_mode == METALITY_UPSCALE_TEMPORAL_DENOISED, "Expected METALFX_TEMPORAL_DENOISED");
    REQUIRE(sum.compiled_pso_count >= 2, "Expected Terrain + Compute RayTracing PSOs compiled");
    metality_pack_destroy(pack);

    // 2. 建構測試區塊網格並實測 HWRT BVH 與 M1/M2 Voxel DDA 雙路徑
    std::vector<MetalityPackedQuad> solid_quads;
    build_test_scene_section(solid_quads);
    REQUIRE(!solid_quads.empty(), "Expected non-empty solid quads");

    if (caps.supports_hardware_raytracing) {
        verify_rt_pipeline_mode(ctx, solid_quads, false, true);
    }
    verify_rt_pipeline_mode(ctx, solid_quads, true, false);

    metality_context_destroy(ctx);
    std::printf("[PASS] Phase 3 Hardware Ray Tracing + Voxel DDA + MetalFX Denoised Scaler verified!\n");
    return 0;
}

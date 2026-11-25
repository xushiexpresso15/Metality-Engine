#include "metality/metality_c_api.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <vector>

#define REQUIRE(cond, msg)                                                        \
    do {                                                                          \
        if (!(cond)) {                                                            \
            std::fprintf(stderr, "[FAIL] %s (%s:%d)\n", msg, __FILE__, __LINE__); \
            std::exit(1);                                                         \
        }                                                                         \
    } while (0)

namespace {

float decode_fp16(uint16_t h) {
    const uint32_t sign = static_cast<uint32_t>(h >> 15u) & 1u;
    const uint32_t exp  = static_cast<uint32_t>(h >> 10u) & 0x1Fu;
    const uint32_t mant = static_cast<uint32_t>(h) & 0x3FFu;
    uint32_t fbits = 0u;
    if (exp == 0u) {
        if (mant == 0u) {
            fbits = sign << 31u;
        } else {
            uint32_t m = mant;
            int e = -14;
            while ((m & 0x400u) == 0u) {
                m <<= 1u;
                --e;
            }
            m &= 0x3FFu;
            fbits = (sign << 31u) | (static_cast<uint32_t>(e + 127) << 23u) | (m << 13u);
        }
    } else if (exp == 31u) {
        fbits = (sign << 31u) | 0x7F800000u | (mant << 13u);
    } else {
        fbits = (sign << 31u) | ((exp + 112u) << 23u) | (mant << 13u);
    }
    float out = 0.0f;
    std::memcpy(&out, &fbits, sizeof(float));
    return out;
}

uint16_t encode_fp16(float v) {
    uint32_t fbits = 0u;
    std::memcpy(&fbits, &v, sizeof(float));
    const uint32_t sign = (fbits >> 16u) & 0x8000u;
    const int exp = static_cast<int>((fbits >> 23u) & 0xFFu) - 127;
    const uint32_t mant = fbits & 0x7FFFFFu;
    if (exp > 15) {
        return static_cast<uint16_t>(sign | 0x7BFFu);
    }
    if (exp >= -14) {
        return static_cast<uint16_t>(sign | (static_cast<uint32_t>(exp + 15) << 10u) | (mant >> 13u));
    }
    if (exp >= -24) {
        const uint32_t m = (mant | 0x800000u) >> static_cast<uint32_t>(-14 - exp + 13);
        return static_cast<uint16_t>(sign | (m & 0x3FFu));
    }
    return static_cast<uint16_t>(sign);
}

struct TestAabbBox {
    float x0, y0, z0, x1, y1, z1;
    float r, g, b;
    int tag;
};

void rasterize_aabb_scene(
    uint32_t w,
    uint32_t h,
    float fov_rad,
    float z_near,
    float cam_x,
    float cam_y,
    float cam_z,
    const std::vector<TestAabbBox>& boxes,
    std::vector<uint16_t>& out_color_fp16,
    std::vector<float>& out_depth,
    std::vector<int>& out_tag,
    float sky_r = 0.25f,
    float sky_g = 0.45f,
    float sky_b = 0.90f
) {
    out_color_fp16.resize(static_cast<size_t>(w) * h * 4u);
    out_depth.resize(static_cast<size_t>(w) * h);
    out_tag.assign(static_cast<size_t>(w) * h, -1);

    const float f = 1.0f / std::tan(fov_rad * 0.5f);
    const float aspect = static_cast<float>(w) / static_cast<float>(h);
    const uint16_t sky_rh = encode_fp16(sky_r);
    const uint16_t sky_gh = encode_fp16(sky_g);
    const uint16_t sky_bh = encode_fp16(sky_b);
    const uint16_t one_h = encode_fp16(1.0f);

    for (uint32_t py = 0; py < h; ++py) {
        const float uv_y = (static_cast<float>(py) + 0.5f) / static_cast<float>(h);
        const float dy = (uv_y * 2.0f - 1.0f) / f;
        for (uint32_t px = 0; px < w; ++px) {
            const float uv_x = (static_cast<float>(px) + 0.5f) / static_cast<float>(w);
            const float dx = (uv_x * 2.0f - 1.0f) * (aspect / f);
            const float dz = -1.0f;

            float best_t = 1e9f;
            int best_idx = -1;
            for (size_t bi = 0; bi < boxes.size(); ++bi) {
                const TestAabbBox& b = boxes[bi];
                float tmin = z_near;
                float tmax = best_t;
                auto slab = [&](float orig, float dir, float bmin, float bmax) -> bool {
                    if (std::fabs(dir) < 1e-8f) {
                        return orig >= bmin && orig <= bmax;
                    }
                    const float inv_d = 1.0f / dir;
                    float t0 = (bmin - orig) * inv_d;
                    float t1 = (bmax - orig) * inv_d;
                    if (t0 > t1) std::swap(t0, t1);
                    tmin = std::max(tmin, t0);
                    tmax = std::min(tmax, t1);
                    return tmax >= tmin;
                };
                if (slab(cam_x, dx, b.x0, b.x1) &&
                    slab(cam_y, dy, b.y0, b.y1) &&
                    slab(cam_z, dz, b.z0, b.z1)) {
                    if (tmin < best_t) {
                        best_t = tmin;
                        best_idx = static_cast<int>(bi);
                    }
                }
            }

            const size_t pidx = static_cast<size_t>(py) * w + px;
            if (best_idx >= 0) {
                const TestAabbBox& b = boxes[static_cast<size_t>(best_idx)];
                out_depth[pidx] = z_near / best_t;
                out_tag[pidx] = b.tag;
                out_color_fp16[pidx * 4u + 0u] = encode_fp16(b.r);
                out_color_fp16[pidx * 4u + 1u] = encode_fp16(b.g);
                out_color_fp16[pidx * 4u + 2u] = encode_fp16(b.b);
                out_color_fp16[pidx * 4u + 3u] = one_h;
            } else {
                out_depth[pidx] = 0.0f;
                out_tag[pidx] = -1;
                out_color_fp16[pidx * 4u + 0u] = sky_rh;
                out_color_fp16[pidx * 4u + 1u] = sky_gh;
                out_color_fp16[pidx * 4u + 2u] = sky_bh;
                out_color_fp16[pidx * 4u + 3u] = one_h;
            }
        }
    }
}

struct FrameHealthStats {
    float roi_avg_luma = 0.0f;
    float roi_min_luma = 0.0f;
    float roi_max_luma = 0.0f;
    float roi_avg_r = 0.0f;
    float roi_avg_g = 0.0f;
    float roi_avg_b = 0.0f;
    float roi_black_ratio = 0.0f;
};

FrameHealthStats assert_frame_healthy(
    const void* fp16_rgba_ptr,
    uint32_t w,
    uint32_t h,
    const char* label,
    uint32_t rx,
    uint32_t ry,
    uint32_t rw,
    uint32_t rh,
    float min_expected_roi_luma,
    float max_allowed_black_ratio = 0.01f
) {
    const uint16_t* p16 = static_cast<const uint16_t*>(fp16_rgba_ptr);
    uint32_t nan_inf_count = 0u;
    uint32_t neg_count = 0u;
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            const size_t base = (static_cast<size_t>(y) * w + x) * 4u;
            const float r = decode_fp16(p16[base + 0]);
            const float g = decode_fp16(p16[base + 1]);
            const float b = decode_fp16(p16[base + 2]);
            const float a = decode_fp16(p16[base + 3]);
            if (!std::isfinite(r) || !std::isfinite(g) || !std::isfinite(b) || !std::isfinite(a)) {
                ++nan_inf_count;
            }
            if (r < -1e-4f || g < -1e-4f || b < -1e-4f) {
                ++neg_count;
            }
        }
    }
    if (nan_inf_count != 0u || neg_count != 0u) {
        std::fprintf(stderr, "[FAIL] %s produced NaN/Inf=%u or negative=%u pixels\n", label, nan_inf_count, neg_count);
        std::exit(1);
    }

    FrameHealthStats st{};
    st.roi_min_luma = 1e9f;
    st.roi_max_luma = 0.0f;
    double sum_l = 0.0, sum_r = 0.0, sum_g = 0.0, sum_b = 0.0;
    uint32_t roi_cnt = 0u, black_cnt = 0u;
    const uint32_t x_end = std::min(w, rx + rw);
    const uint32_t y_end = std::min(h, ry + rh);
    for (uint32_t y = ry; y < y_end; ++y) {
        for (uint32_t x = rx; x < x_end; ++x) {
            const size_t base = (static_cast<size_t>(y) * w + x) * 4u;
            const float r = decode_fp16(p16[base + 0]);
            const float g = decode_fp16(p16[base + 1]);
            const float b = decode_fp16(p16[base + 2]);
            const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            sum_l += l;
            sum_r += r;
            sum_g += g;
            sum_b += b;
            st.roi_min_luma = std::min(st.roi_min_luma, l);
            st.roi_max_luma = std::max(st.roi_max_luma, l);
            if (std::max({r, g, b}) < 1e-4f) {
                ++black_cnt;
            }
            ++roi_cnt;
        }
    }
    if (roi_cnt > 0u) {
        const float inv = 1.0f / static_cast<float>(roi_cnt);
        st.roi_avg_luma = static_cast<float>(sum_l) * inv;
        st.roi_avg_r = static_cast<float>(sum_r) * inv;
        st.roi_avg_g = static_cast<float>(sum_g) * inv;
        st.roi_avg_b = static_cast<float>(sum_b) * inv;
        st.roi_black_ratio = static_cast<float>(black_cnt) * inv;
    } else {
        st.roi_min_luma = 0.0f;
    }
    if (st.roi_avg_luma < min_expected_roi_luma) {
        std::fprintf(stderr, "[FAIL] %s ROI avg_luma=%.4f < min_expected=%.4f\n",
                     label, st.roi_avg_luma, min_expected_roi_luma);
        std::exit(1);
    }
    if (st.roi_black_ratio > max_allowed_black_ratio) {
        std::fprintf(stderr, "[FAIL] %s ROI black_ratio=%.4f > max_allowed=%.4f\n",
                     label, st.roi_black_ratio, max_allowed_black_ratio);
        std::exit(1);
    }
    return st;
}

void fill_perspective_reversed_z(float fov_rad, float aspect, float z_near, float proj[16], float inv_proj[16]) {
    const float f = 1.0f / std::tan(fov_rad * 0.5f);
    std::memset(proj, 0, sizeof(float) * 16);
    // Column-major infinite Reversed-Z projection: z_ndc = z_near / (-z_view)
    proj[0] = f / aspect;
    proj[5] = f;
    proj[10] = 0.0f;
    proj[11] = -1.0f;
    proj[14] = z_near;
    proj[15] = 0.0f;

    std::memset(inv_proj, 0, sizeof(float) * 16);
    inv_proj[0] = aspect / f;
    inv_proj[5] = 1.0f / f;
    inv_proj[11] = 1.0f / z_near;
    inv_proj[14] = -1.0f;
}

void fill_identity(float m[16]) {
    std::memset(m, 0, sizeof(float) * 16);
    m[0] = m[5] = m[10] = m[15] = 1.0f;
}

} // namespace

int main() {
    std::printf("=== Metality Phase 5: Meteor Zenith (RT) & Meteor Dusk (Non-RT) Verification ===\n");

    MetalityConfig cfg{};
    cfg.cache_directory_utf8 = "/tmp/metality_phase5_test_cache";
    cfg.frames_in_flight = 3;
    cfg.enable_validation = 1;
    cfg.enable_edr_hdr = 1;

    MetalityContext* ctx = metality_context_create(&cfg);
    REQUIRE(ctx != nullptr, "Failed to create Metal context");

    const uint32_t w = 256;
    const uint32_t h = 144;

    MetalityPackManifestSummary sum_zenith{};
    char err_buf[1024]{};
    MetalityPackHandle pack_zenith = metality_pack_load_directory(
        ctx, METALITY_METEOR_ZENITH_DIR, w, h, 1.0f, &sum_zenith, err_buf, sizeof(err_buf));
    if (!pack_zenith) {
        std::fprintf(stderr, "meteor-zenith load error: %s\n", err_buf);
    }
    REQUIRE(pack_zenith != nullptr, "Failed to load meteor-zenith pack");
    REQUIRE(sum_zenith.enable_hw_raytracing != 0u, "Expected HWRT enabled in meteor-zenith");
    REQUIRE(sum_zenith.compiled_pso_count >= 14u, "Expected >=14 compiled PSOs in meteor-zenith");

    MetalityPackManifestSummary sum_dusk{};
    std::memset(err_buf, 0, sizeof(err_buf));
    MetalityPackHandle pack_dusk = metality_pack_load_directory(
        ctx, METALITY_METEOR_DUSK_DIR, w, h, 1.0f, &sum_dusk, err_buf, sizeof(err_buf));
    if (!pack_dusk) {
        std::fprintf(stderr, "meteor-dusk load error: %s\n", err_buf);
    }
    REQUIRE(pack_dusk != nullptr, "Failed to load meteor-dusk pack");
    REQUIRE(sum_dusk.enable_hw_raytracing == 0u, "Expected HWRT disabled in meteor-dusk (Non-RT)");
    REQUIRE(sum_dusk.shadow_enabled != 0u && sum_dusk.shadow_cascades == 2u, "Expected 2-cascade shadow config in meteor-dusk");
    REQUIRE(sum_dusk.compiled_pso_count == 13u, "Expected 13 Non-RT compiled PSOs in meteor-dusk");

    std::printf("[Load] meteor-zenith PSOs=%u (HWRT=%u) | meteor-dusk PSOs=%u (HWRT=%u, Cascades=%u@%u)\n",
                sum_zenith.compiled_pso_count, sum_zenith.enable_hw_raytracing,
                sum_dusk.compiled_pso_count, sum_dusk.enable_hw_raytracing,
                sum_dusk.shadow_cascades, sum_dusk.shadow_resolution);

    MetalityTextureDesc cd{
        w, h, 1u, METALITY_FORMAT_RGBA8_UNORM,
        METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED | METALITY_TEX_USAGE_STORAGE_WRITE
    };
    MetalityTextureHandle ctex = metality_texture_create(ctx, &cd);
    REQUIRE(ctex != 0, "Failed to create color texture");

    MetalityTextureDesc dd{
        w, h, 1u, METALITY_FORMAT_DEPTH32_FLOAT,
        METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED
    };
    MetalityTextureHandle dtex = metality_texture_create(ctx, &dd);
    REQUIRE(dtex != 0, "Failed to create depth texture");

    void* rb_ptr = nullptr;
    MetalityBufferHandle rb_buf = metality_buffer_alloc_shared(ctx, w * h * 4u, false, &rb_ptr, nullptr);
    REQUIRE(rb_buf != 0 && rb_ptr != nullptr, "Failed to alloc readback buffer");

    // Populate RT & Non-RT World Scene in section (0, 3, -1) -> world Y=[48..63], Z=[-16..-1]
    // Stone floor at y=53 (py=6 in padded section), tall opaque wall/pillar on right half x=[11..15], y=[54..63], z=[-16..-1]
    // Casting an angled shadow leftward when sun shines from +X (+0.65, +0.72, -0.24)
    MetalityRtSceneHandle rt_scene = metality_rt_scene_create(ctx, 64, false);
    REQUIRE(rt_scene != nullptr, "Failed to create scene");

    std::vector<MetalityBlockMaterialEntry> palette(3);
    palette[0].layer = METALITY_LAYER_EMPTY;
    palette[0].is_opaque_full_cube = 0;

    for (int f = 0; f < 6; ++f) palette[1].face_sprites[f] = 1;
    palette[1].layer = METALITY_LAYER_SOLID;
    palette[1].is_opaque_full_cube = 1;
    palette[1].alpha = 255;

    for (int f = 0; f < 6; ++f) palette[2].face_sprites[f] = 2;
    palette[2].layer = METALITY_LAYER_SOLID;
    palette[2].is_opaque_full_cube = 1;
    palette[2].wave_tag = 5;
    palette[2].emissive_level = 15;
    palette[2].alpha = 255;

    std::vector<uint16_t> blocks(METALITY_PADDED_VOLUME, 0u);
    std::vector<uint8_t> light(METALITY_PADDED_VOLUME, 0xF0u);
    std::vector<uint32_t> tint(16u * 16u * 16u, 0x809080u);
    auto pad_idx = [](int px, int py, int pz) -> size_t {
        return static_cast<size_t>((py * 18 + pz) * 18 + px);
    };
    for (int pz = 1; pz <= 16; ++pz) {
        for (int px = 1; px <= 16; ++px) {
            blocks[pad_idx(px, 6, pz)] = 1u; // floor at world y=53..54
            if (px >= 10 && px <= 12) {
                for (int py = 7; py <= 16; ++py) {
                    blocks[pad_idx(px, py, pz)] = 1u; // tall shadow wall casting toward -X
                }
            }
        }
    }

    MetalityChunkSectionInput minput{};
    minput.block_material_ids = blocks.data();
    minput.packed_light = light.data();
    minput.biome_tint_rgb = tint.data();
    minput.palette_table = palette.data();
    minput.palette_size = static_cast<uint32_t>(palette.size());
    minput.enable_greedy_merge = 1;

    std::vector<MetalityPackedQuad> q_solid(4096), q_cutout(512), q_trans(512);
    MetalityMeshedSectionOutput mout{};
    mout.solid_quads = q_solid.data(); mout.solid_quad_capacity = 4096;
    mout.cutout_quads = q_cutout.data(); mout.cutout_quad_capacity = 512;
    mout.translucent_quads = q_trans.data(); mout.translucent_quad_capacity = 512;
    REQUIRE(metality_mesh_chunk_section_neon(&minput, &mout), "Chunk meshing failed");
    REQUIRE(metality_rt_scene_update_section_v2(
                ctx, rt_scene, 0, 3, -1,
                q_solid.data(), mout.solid_quad_count,
                q_cutout.data(), mout.cutout_quad_count,
                q_trans.data(), mout.translucent_quad_count),
            "Scene update v2 failed");

    // 1. Verify Non-RT world sync without BLAS/TLAS first
    REQUIRE(metality_rt_scene_sync_world_data(ctx, rt_scene, 8.0f, 58.0f, -8.0f),
            "Non-RT sync_world_data failed");

    // Verify Dusk real GPU rasterized 2-cascade shadow map produces sunlit vs shadowed floor contrast
    {
        // Camera at (8.0, 58.0, 10.0) looking toward -Z; world floor y=54 is ~4m below eye.
        // Projection z_near = 0.05 -> floor at view_z = -18m (world z = -8) has reversed-Z = 0.05 / 18 = 0.00278.
        // At pixel uv=(0.25, 0.25): clip=( -0.5, -0.5 ), at z_view=-18 => relPos ~ (-10, -5, -18) (outside wall shadow -> sunlit)
        // Wait: better to choose floor depth directly behind wall at relPos = (0, -4, -15) -> world (8, 54, -5), which is JUST left of wall x=[9..11]!
        // Let sun shine from (+0.7, +0.6, 0.0) so wall at x=[9..11], y=[54..63] casts shadow across x=[2..9], y=54!
        // Meanwhile sunlit floor on far right x=[14..15] has uv.x ~ 0.65 (at y=54, d = 0.05 / 15 = 0.00333).
        const float cam_y = 58.0f;
        const float cam_x = 8.0f;
        const float cam_z = 7.0f; // view_z = -15 => world z = -8 (center of wall!)
        const float depth_floor_z15 = 0.05f / 15.0f;
        metality_rt_scene_sync_world_data(ctx, rt_scene, cam_x, cam_y, cam_z);

        const uint64_t fid = metality_begin_frame(ctx);
        // Entire bottom band (y < h/2) sits at flat grey albedo (0.55, 0.55, 0.55) and floor height (view_z = -15 => d = 0.003333)
        // uv.x=0.35 (center-left) -> view_x = -0.3 * 15 * aspect -> world x = ~0 (left/shadowed!) vs
        // Wait: with f = 1/tan(FOV/2) where proj[0]=1, view_x = rel_x = ndc_x * 15.
        // So wall x in [9..11] (rel_x in [1..3]) casts shadow leftward when sun comes from +X onto rel_x in [-4..+1] (ndc_x in [-0.26 .. +0.06], uv.x in [0.37 .. 0.53]),
        // while far-right floor at rel_x = +5.5 (ndc_x = +0.37, uv.x = 0.68 -> world x = 13.5, RIGHT of wall x=[9..11]) is sunlit!
        metality_cmd_clear_color_depth(ctx, ctex, 0.25f, 0.45f, 0.90f, 1.0f, dtex, 0.0);
        // Set floor rectangle at y=[0..h/3], giving view_y around -4..-5 -> floor around world y=53..54!
        // With FOV proj[5] = 1/tan(fov/2) = 1.60 => at uv.y=0.32, ndc_y=-0.36 => view_y = -0.36 / 1.60 * 15 = -3.38m -> y = 54.6m (just hitting floor/shadow volume!)
        // Even cleaner: proj[5] = 1.60, at uv.y = 0.25 (ndc_y = -0.5), view_y = -0.5 / 1.60 * 15 = -4.69 => world y = 53.3m (right on floor top!).
        metality_cmd_clear_color_depth_rect(ctx, ctex, 0.55f, 0.55f, 0.55f, 1.0f, dtex, depth_floor_z15, 0, 0, 0, w, h / 2u);

        MetalityWorldFrameUniforms fu{};
        fill_perspective_reversed_z(1.12f, static_cast<float>(w) / static_cast<float>(h), 0.05f, fu.proj, fu.inv_proj);
        fill_identity(fu.view_rot);
        fill_identity(fu.inv_view_rot);
        std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
        std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
        fu.cam_world_pos[0] = cam_x; fu.cam_world_pos[1] = cam_y; fu.cam_world_pos[2] = cam_z; fu.cam_world_pos[3] = 1.0f;
        const float sdx = 0.65f, sdy = 0.58f, sdz = 0.49f;
        const float sl = std::sqrt(sdx*sdx + sdy*sdy + sdz*sdz);
        fu.sun_world_dir[0] = sdx / sl; fu.sun_world_dir[1] = sdy / sl; fu.sun_world_dir[2] = sdz / sl; fu.sun_world_dir[3] = 1.0f;
        fu.moon_world_dir[0] = -fu.sun_world_dir[0]; fu.moon_world_dir[1] = -fu.sun_world_dir[1]; fu.moon_world_dir[2] = -fu.sun_world_dir[2]; fu.moon_world_dir[3] = 0.0f;
        fu.sky_color[0] = 0.5f; fu.sky_color[1] = 0.7f; fu.sky_color[2] = 1.0f; fu.sky_color[3] = 0.1f;
        fu.fog_color[0] = 0.6f; fu.fog_color[1] = 0.75f; fu.fog_color[2] = 0.9f; fu.fog_color[3] = 0.3f;
        fu.width = w; fu.height = h; fu.time_seconds = 10.0f; fu.sea_level = 40.0f; fu.edr_headroom = 1.0f;
        fu.user_params[0] = 0.85f; fu.user_params[1] = 1.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;

        REQUIRE(metality_pack_execute(ctx, pack_dusk, rt_scene, ctex, dtex, &fu), "Dusk shadow contrast pass failed");
        metality_cmd_copy_texture_to_buffer(ctx, ctex, rb_buf, 0, w * 4u, w, h);
        metality_end_frame(ctx, false);
        REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Dusk GPU frame timeout");

        const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
        const uint32_t row = (h * 3u) / 8u; // uv.y = 0.375 -> world_y ~ 55.65 (squarely behind wall [54..63])
        const uint32_t x_shadow = static_cast<uint32_t>(w * 0.45f); // uv.x=0.45 -> world x ~ 6.30 (inside wall shadow!)
        const uint32_t x_sunlit = static_cast<uint32_t>(w * 0.68f); // uv.x=0.68 -> world x ~ 14.0 (right of wall -> sunlit!)
        auto luma_at = [&](uint32_t xx) {
            const size_t idx = (static_cast<size_t>(row) * w + xx) * 4u;
            return 0.2126f * px[idx] + 0.7152f * px[idx + 1] + 0.0722f * px[idx + 2];
        };
        const float lum_sh = luma_at(x_shadow);
        const float lum_lit = luma_at(x_sunlit);
        std::printf("[Dusk RasterShadow] ShadowFloorLuma=%.1f | SunlitFloorLuma=%.1f (ratio=%.2fx)\n",
                    lum_sh, lum_lit, lum_lit / std::max(lum_sh, 1.0f));
        REQUIRE(lum_lit > lum_sh * 1.35f, "Dusk GPU raster shadow map must darken shadowed floor relative to sunlit floor");
    }

    // Re-populate with warm emissive froglight for Zenith/Dusk scene runs
    blocks[pad_idx(3, 7, 10)] = 2u;
    tint[(6 * 16 + 9) * 16 + 2] = 0x3296FFu;
    REQUIRE(metality_mesh_chunk_section_neon(&minput, &mout), "Chunk meshing with emissive failed");
    REQUIRE(metality_rt_scene_update_section_v2(
                ctx, rt_scene, 0, 3, -1,
                q_solid.data(), mout.solid_quad_count,
                q_cutout.data(), mout.cutout_quad_count,
                q_trans.data(), mout.translucent_quad_count),
            "Scene update v2 with emissive failed");

    // 2. Build full HWRT TLAS for Zenith comparison
    REQUIRE(metality_rt_scene_rebuild_tlas(ctx, rt_scene, 8.0f, 58.0f, 10.0f), "RT rebuild TLAS failed");

    auto run_scenario = [&](MetalityPackHandle pack, bool use_rt, int dim_id,
                            float sun_y, float fog_r, float fog_g, float fog_b,
                            uint8_t out_sky[3], uint8_t out_water[3]) {
        const uint64_t fid = metality_begin_frame(ctx);
        metality_cmd_clear_color_depth(ctx, ctex, 0.25f, 0.45f, 0.90f, 1.0f, dtex, 0.0);
        metality_cmd_clear_color_depth_rect(ctx, ctex, 0.35f, 0.55f, 0.28f, 1.0f, dtex, 0.008, 0, 0, 0, w / 2u, h / 2u);
        metality_cmd_clear_color_depth_rect(ctx, ctex, 0.16f, 0.44f, 0.88f, 1.0f, dtex, 0.006, 0, w / 2u, 0, w / 2u, h / 2u);

        MetalityWorldFrameUniforms fu{};
        fill_perspective_reversed_z(1.22f, static_cast<float>(w) / static_cast<float>(h), 0.05f, fu.proj, fu.inv_proj);
        fill_identity(fu.view_rot);
        fill_identity(fu.inv_view_rot);
        std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
        std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));

        fu.cam_world_pos[0] = 8.0f; fu.cam_world_pos[1] = 65.0f; fu.cam_world_pos[2] = 10.0f; fu.cam_world_pos[3] = 1.0f;
        const float slen = std::sqrt(sun_y * sun_y + 0.92f * 0.92f);
        fu.sun_world_dir[0] = 0.28f / slen; fu.sun_world_dir[1] = sun_y / slen; fu.sun_world_dir[2] = -0.88f / slen; fu.sun_world_dir[3] = 1.0f;
        fu.moon_world_dir[0] = -fu.sun_world_dir[0]; fu.moon_world_dir[1] = -fu.sun_world_dir[1]; fu.moon_world_dir[2] = -fu.sun_world_dir[2]; fu.moon_world_dir[3] = 1.0f;
        fu.sky_color[0] = 0.5f; fu.sky_color[1] = 0.7f; fu.sky_color[2] = 1.0f; fu.sky_color[3] = 0.2f;
        fu.fog_color[0] = fog_r; fu.fog_color[1] = fog_g; fu.fog_color[2] = fog_b; fu.fog_color[3] = 1.0f;
        fu.sunrise_color[0] = 0.9f; fu.sunrise_color[1] = 0.45f; fu.sunrise_color[2] = 0.25f; fu.sunrise_color[3] = 0.8f;
        fu.width = w; fu.height = h;
        fu.time_seconds = 12.5f;
        fu.sea_level = 63.0f;
        fu.edr_headroom = 1.0f;
        fu.dimension_id = dim_id;
        fu.submerged_mode = 0;
        fu.user_params[0] = 1.0f; fu.user_params[1] = 1.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;

        REQUIRE(metality_pack_execute(ctx, pack, use_rt ? rt_scene : nullptr, ctex, dtex, &fu),
                "metality_pack_execute failed");
        metality_cmd_copy_texture_to_buffer(ctx, ctex, rb_buf, 0, w * 4u, w, h);
        metality_end_frame(ctx, false);
        REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "GPU frame timeout");

        const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
        const size_t sky_off = (static_cast<size_t>(h * 3u / 4u) * w + (w / 2u)) * 4u;
        const size_t wat_off = (static_cast<size_t>(h / 4u) * w + (w * 3u / 4u)) * 4u;
        out_sky[0] = px[sky_off]; out_sky[1] = px[sky_off + 1]; out_sky[2] = px[sky_off + 2];
        out_water[0] = px[wat_off]; out_water[1] = px[wat_off + 1]; out_water[2] = px[wat_off + 2];
    };

    uint8_t dsky_ow[3]{}, dwat_ow[3]{}, dsky_soul[3]{}, dwat_soul[3]{}, dsky_end[3]{}, dwat_end[3]{};
    uint8_t zsky_ow[3]{}, zwat_ow[3]{}, zsky_end[3]{}, zwat_end[3]{};

    run_scenario(pack_dusk, true, 0, 0.08f, 0.7f, 0.6f, 0.8f, dsky_ow, dwat_ow);
    run_scenario(pack_dusk, true, -1, 0.5f, 0.09f, 0.34f, 0.40f, dsky_soul, dwat_soul);
    run_scenario(pack_dusk, true, 1, 0.5f, 0.3f, 0.1f, 0.5f, dsky_end, dwat_end);

    run_scenario(pack_zenith, true, 0, 0.08f, 0.7f, 0.6f, 0.8f, zsky_ow, zwat_ow);
    run_scenario(pack_zenith, true, 1, 0.5f, 0.3f, 0.1f, 0.5f, zsky_end, zwat_end);

    std::printf("[Meteor Dusk  ] SunsetSky=(%u,%u,%u) Water=(%u,%u,%u) | SoulMist=(%u,%u,%u) | EndGargantua=(%u,%u,%u)\n",
                dsky_ow[0], dsky_ow[1], dsky_ow[2], dwat_ow[0], dwat_ow[1], dwat_ow[2],
                dsky_soul[0], dsky_soul[1], dsky_soul[2], dsky_end[0], dsky_end[1], dsky_end[2]);
    std::printf("[Meteor Zenith] SunsetSky=(%u,%u,%u) Water=(%u,%u,%u) | EndGargantua=(%u,%u,%u)\n",
                zsky_ow[0], zsky_ow[1], zsky_ow[2], zwat_ow[0], zwat_ow[1], zwat_ow[2],
                zsky_end[0], zsky_end[1], zsky_end[2]);

    REQUIRE(dsky_ow[0] > 40u || dsky_ow[1] > 40u, "Dusk sunset sky too dark");
    REQUIRE(dsky_soul[1] > dsky_soul[0] && dsky_soul[2] > dsky_soul[0], "Dusk Soul Sand Valley mist should be teal/cyan");
    REQUIRE(zsky_ow[0] > 40u || zsky_ow[1] > 40u, "Zenith sunset sky too dark");
    REQUIRE(zsky_end[2] >= zsky_end[1], "Zenith End sky should retain violet/blue spectrum");

    // -------------------------------------------------------------------------
    // Regression Test A: 16 Point Lights (Front + Behind Opaque Wall + Smooth Profile)
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_a = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_a != nullptr, "Failed to create Test A scene");

        std::vector<uint16_t> blocks_a(METALITY_PADDED_VOLUME, 0u);
        for (int pz = 14; pz <= 15; ++pz) {
            for (int py = 1; py <= 16; ++py) {
                for (int px = 1; px <= 16; ++px) {
                    blocks_a[pad_idx(px, py, pz)] = 1u; // full opaque dividing wall at world z=[-3..-1]
                }
            }
        }
        MetalityChunkSectionInput in_a{};
        in_a.block_material_ids = blocks_a.data();
        in_a.packed_light = light.data();
        in_a.biome_tint_rgb = tint.data();
        in_a.palette_table = palette.data();
        in_a.palette_size = static_cast<uint32_t>(palette.size());
        in_a.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_a, &mout), "Test A meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_a, 0, 3, -1,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test A scene update failed");

        // 1 front warm light on left (x=4, z=+1.5), 15 bright behind-wall cyan lights on right (z=-6.0)
        std::vector<float> lights_a(16u * 8u, 0.0f);
        lights_a[0] = 4.0f; lights_a[1] = 56.0f; lights_a[2] = 1.5f; lights_a[3] = 22.0f;
        lights_a[4] = 1.0f; lights_a[5] = 0.65f; lights_a[6] = 0.25f; lights_a[7] = 0.5f;
        for (uint32_t i = 1u; i < 16u; ++i) {
            lights_a[i * 8u + 0] = 10.5f + 0.15f * static_cast<float>(i % 5u);
            lights_a[i * 8u + 1] = 55.0f + 0.20f * static_cast<float>(i / 5u);
            lights_a[i * 8u + 2] = -6.0f;
            lights_a[i * 8u + 3] = 45.0f;
            lights_a[i * 8u + 4] = 0.10f;
            lights_a[i * 8u + 5] = 0.85f;
            lights_a[i * 8u + 6] = 1.00f;
            lights_a[i * 8u + 7] = 0.6f;
        }
        metality_rt_scene_set_dynamic_lights(scene_a, lights_a.data(), 16u);
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_a, 8.0f, 56.0f, 10.0f), "Test A TLAS failed");

        auto eval_test_a_occl = [&](MetalityPackHandle pack, const char* tag_name) {
            const uint64_t fid = metality_begin_frame(ctx);
            const float d_wall = 0.05f / 10.5f; // z = +10 - 10.5 = -0.5 (front face of stone barrier)
            metality_cmd_clear_color_depth(ctx, ctex, 0.45f, 0.45f, 0.45f, 1.0f, dtex, d_wall);
            MetalityWorldFrameUniforms fu{};
            fill_perspective_reversed_z(1.22f, static_cast<float>(w) / h, 0.05f, fu.proj, fu.inv_proj);
            fill_identity(fu.view_rot);
            fill_identity(fu.inv_view_rot);
            std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
            std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
            fu.cam_world_pos[0] = 8.0f; fu.cam_world_pos[1] = 56.0f; fu.cam_world_pos[2] = 10.0f; fu.cam_world_pos[3] = 1.0f;
            fu.sun_world_dir[0] = 0.0f; fu.sun_world_dir[1] = -0.8f; fu.sun_world_dir[2] = -0.6f; fu.sun_world_dir[3] = 0.0f;
            fu.moon_world_dir[0] = 0.0f; fu.moon_world_dir[1] = -0.5f; fu.moon_world_dir[2] = 0.86f; fu.moon_world_dir[3] = 0.0f;
            fu.width = w; fu.height = h; fu.sea_level = 20.0f; fu.edr_headroom = 1.0f;
            fu.user_params[0] = 0.0f; fu.user_params[1] = 0.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;
            REQUIRE(metality_pack_execute(ctx, pack, scene_a, ctex, dtex, &fu), "Test A execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex, rb_buf, 0, w * 4u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test A timeout");

            const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
            const uint32_t mid_y = h / 2u;
            const size_t left_off = (static_cast<size_t>(mid_y) * w + (w / 4u)) * 4u;
            const size_t right_off = (static_cast<size_t>(mid_y) * w + (w * 3u / 4u)) * 4u;
            const float lum_left = 0.2126f * px[left_off] + 0.7152f * px[left_off + 1] + 0.0722f * px[left_off + 2];
            const float lum_right = 0.2126f * px[right_off] + 0.7152f * px[right_off + 1] + 0.0722f * px[right_off + 2];
            std::printf("[Test A Occl %-6s] FrontLitLeft=%.1f | BehindWallRight=%.1f | R_blue=%u\n",
                        tag_name, lum_left, lum_right, px[right_off + 2]);
            REQUIRE(lum_left > lum_right * 1.45f, "Front visible light must clearly exceed behind-wall blocked lights");
            REQUIRE(px[right_off + 2] <= px[left_off + 0] + 15u, "Behind-wall cyan lights must not bleed through opaque wall");
        };
        eval_test_a_occl(pack_dusk, "Dusk");
        eval_test_a_occl(pack_zenith, "Zenith");

        // All 16 lights visible in front of wall: check smooth lateral profile without Top-K eviction cliff
        for (uint32_t i = 0u; i < 16u; ++i) {
            lights_a[i * 8u + 0] = 3.5f + 0.65f * static_cast<float>(i);
            lights_a[i * 8u + 1] = 56.0f;
            lights_a[i * 8u + 2] = 1.5f;
            lights_a[i * 8u + 3] = 14.0f;
            lights_a[i * 8u + 4] = 1.0f; lights_a[i * 8u + 5] = 0.80f; lights_a[i * 8u + 6] = 0.45f; lights_a[i * 8u + 7] = 0.5f;
        }
        metality_rt_scene_set_dynamic_lights(scene_a, lights_a.data(), 16u);
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_a, 8.0f, 56.0f, 10.0f), "Test A smooth TLAS failed");
        auto eval_test_a_smooth = [&](MetalityPackHandle pack, const char* tag_name) {
            const uint64_t fid = metality_begin_frame(ctx);
            const float d_wall = 0.05f / 10.5f;
            metality_cmd_clear_color_depth(ctx, ctex, 0.45f, 0.45f, 0.45f, 1.0f, dtex, d_wall);
            MetalityWorldFrameUniforms fu{};
            fill_perspective_reversed_z(1.22f, static_cast<float>(w) / h, 0.05f, fu.proj, fu.inv_proj);
            fill_identity(fu.view_rot);
            fill_identity(fu.inv_view_rot);
            std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
            std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
            fu.cam_world_pos[0] = 8.0f; fu.cam_world_pos[1] = 56.0f; fu.cam_world_pos[2] = 10.0f; fu.cam_world_pos[3] = 1.0f;
            fu.sun_world_dir[0] = 0.0f; fu.sun_world_dir[1] = -0.8f; fu.sun_world_dir[2] = -0.6f; fu.sun_world_dir[3] = 0.0f;
            fu.moon_world_dir[0] = 0.0f; fu.moon_world_dir[1] = -0.5f; fu.moon_world_dir[2] = 0.86f; fu.moon_world_dir[3] = 0.0f;
            fu.width = w; fu.height = h; fu.sea_level = 20.0f; fu.edr_headroom = 1.0f;
            fu.user_params[0] = 0.0f; fu.user_params[1] = 0.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;
            REQUIRE(metality_pack_execute(ctx, pack, scene_a, ctex, dtex, &fu), "Test A smooth failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex, rb_buf, 0, w * 4u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test A smooth timeout");

            const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
            const uint32_t mid_y = h / 2u;
            float max_step = 0.0f;
            float prev_l = -1.0f;
            for (uint32_t xx = w / 6u; xx <= (w * 5u) / 6u; ++xx) {
                const size_t off = (static_cast<size_t>(mid_y) * w + xx) * 4u;
                const float l = 0.2126f * px[off] + 0.7152f * px[off + 1] + 0.0722f * px[off + 2];
                if (prev_l >= 0.0f) {
                    max_step = std::max(max_step, std::abs(l - prev_l));
                }
                prev_l = l;
            }
            std::printf("[Test A Smooth %-5s] MaxAdjacentPixelDelta=%.2f luma (no cliff)\n", tag_name, max_step);
            REQUIRE(max_step < 18.0f, "16 visible lights must transition smoothly without Apollonius ring cliff");
        };
        eval_test_a_smooth(pack_dusk, "Dusk");
        eval_test_a_smooth(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_a);
    }

    // -------------------------------------------------------------------------
    // Regression Test B: Sunlit Roof Over Sealed Room Must Not Leak Outdoors in Dusk
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_b = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_b != nullptr, "Failed to create Test B scene");
        std::vector<uint16_t> blocks_b(METALITY_PADDED_VOLUME, 0u);
        for (int pz = 1; pz <= 16; ++pz) {
            for (int px = 1; px <= 16; ++px) {
                blocks_b[pad_idx(px, 2, pz)] = 1u;  // floor y=49
                blocks_b[pad_idx(px, 14, pz)] = 1u; // solid roof y=61
                blocks_b[pad_idx(px, 15, pz)] = 1u; // solid roof y=62
                if (px == 1 || px == 16 || pz == 1 || pz == 16) {
                    for (int py = 3; py <= 13; ++py) {
                        blocks_b[pad_idx(px, py, pz)] = 1u;
                    }
                }
            }
        }
        MetalityChunkSectionInput in_b{};
        in_b.block_material_ids = blocks_b.data();
        in_b.packed_light = light.data();
        in_b.biome_tint_rgb = tint.data();
        in_b.palette_table = palette.data();
        in_b.palette_size = static_cast<uint32_t>(palette.size());
        in_b.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_b, &mout), "Test B meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_b, 0, 3, -1,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test B update failed");
        REQUIRE(metality_rt_scene_sync_world_data(ctx, scene_b, 8.0f, 54.0f, -2.0f), "Test B sync failed");

        const uint64_t fid = metality_begin_frame(ctx);
        const float d_in = 0.05f / 6.0f; // indoor point (8, 54, -8) well beneath roof y=61..62
        metality_cmd_clear_color_depth(ctx, ctex, 0.55f, 0.55f, 0.55f, 1.0f, dtex, d_in);
        MetalityWorldFrameUniforms fu{};
        fill_perspective_reversed_z(1.22f, static_cast<float>(w) / h, 0.05f, fu.proj, fu.inv_proj);
        fill_identity(fu.view_rot);
        fill_identity(fu.inv_view_rot);
        std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
        std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
        fu.cam_world_pos[0] = 8.0f; fu.cam_world_pos[1] = 54.0f; fu.cam_world_pos[2] = -2.0f; fu.cam_world_pos[3] = 1.0f;
        fu.sun_world_dir[0] = 0.10f; fu.sun_world_dir[1] = 0.98f; fu.sun_world_dir[2] = -0.17f; fu.sun_world_dir[3] = 1.0f;
        fu.moon_world_dir[0] = -0.10f; fu.moon_world_dir[1] = -0.98f; fu.moon_world_dir[2] = 0.17f; fu.moon_world_dir[3] = 0.0f;
        fu.width = w; fu.height = h; fu.sea_level = 20.0f; fu.edr_headroom = 1.0f;
        fu.user_params[0] = 0.0f; fu.user_params[1] = 0.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;
        REQUIRE(metality_pack_execute(ctx, pack_dusk, scene_b, ctex, dtex, &fu), "Test B execute failed");
        metality_cmd_copy_texture_to_buffer(ctx, ctex, rb_buf, 0, w * 4u, w, h);
        metality_end_frame(ctx, false);
        REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test B timeout");

        const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
        const size_t c_off = (static_cast<size_t>(h / 2u) * w + (w / 2u)) * 4u;
        const float lum_sealed = 0.2126f * px[c_off] + 0.7152f * px[c_off + 1] + 0.0722f * px[c_off + 2];
        std::printf("[Test B SealedRoom] IndoorLuma=%.1f (RGB=%u,%u,%u)\n", lum_sealed, px[c_off], px[c_off + 1], px[c_off + 2]);
        REQUIRE(lum_sealed < 55.0f, "Sealed indoor room under sunlit roof must stay genuinely dark in Dusk");
        metality_rt_scene_destroy(scene_b);
    }

    // -------------------------------------------------------------------------
    // Regression Test C: Stained Glass Tints Point Light in Meteor Dusk
    // -------------------------------------------------------------------------
    {
        auto run_glass_test = [&](bool with_glass, uint8_t out_rgb[3]) {
            MetalityRtSceneHandle scene_c = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_c != nullptr, "Failed to create Test C scene");
            std::vector<MetalityBlockMaterialEntry> pal_c = palette;
            pal_c.resize(4);
            for (int f = 0; f < 6; ++f) pal_c[3].face_sprites[f] = 3;
            pal_c[3].layer = METALITY_LAYER_TRANSLUCENT;
            pal_c[3].is_opaque_full_cube = 0;
            pal_c[3].wave_tag = 8;      // glass tag
            pal_c[3].emissive_level = 2; // vivid red stained glass sub=2 (>= 2)
            pal_c[3].alpha = 160;

            std::vector<uint16_t> blocks_c(METALITY_PADDED_VOLUME, 0u);
            blocks_c[pad_idx(8, 1, 2)] = 1u; // floor anchor well below camera ray y=55.5 in section (0,3,0)
            if (with_glass) {
                for (int py = 5; py <= 11; ++py) {
                    for (int px = 5; px <= 11; ++px) {
                        blocks_c[pad_idx(px, py, 2)] = 3u; // stained glass pane at world z=+1
                    }
                }
            }
            MetalityChunkSectionInput in_c{};
            in_c.block_material_ids = blocks_c.data();
            in_c.packed_light = light.data();
            in_c.biome_tint_rgb = tint.data();
            in_c.palette_table = pal_c.data();
            in_c.palette_size = static_cast<uint32_t>(pal_c.size());
            in_c.enable_greedy_merge = 1;
            REQUIRE(metality_mesh_chunk_section_neon(&in_c, &mout), "Test C meshing failed");
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, scene_c, 0, 3, 0,
                        q_solid.data(), mout.solid_quad_count,
                        q_cutout.data(), mout.cutout_quad_count,
                        q_trans.data(), mout.translucent_quad_count),
                    "Test C update failed");

            // Pure white bright point light at z=+3.5 (in front of stained glass at z=+1.0, shining onto -Z wall at z=-1.0)
            const float white_light[8] = {7.5f, 55.5f, 3.5f, 36.0f, 1.0f, 1.0f, 1.0f, 0.5f};
            metality_rt_scene_set_dynamic_lights(scene_c, white_light, 1u);
            REQUIRE(metality_rt_scene_sync_world_data(ctx, scene_c, 7.5f, 55.5f, 9.0f), "Test C sync failed");

            const uint64_t fid = metality_begin_frame(ctx);
            const float d_surf = 0.05f / 10.0f; // cam_z=+9 -> world z=-1 (in front of stained glass at z=-4)
            metality_cmd_clear_color_depth(ctx, ctex, 0.55f, 0.55f, 0.55f, 1.0f, dtex, d_surf);
            MetalityWorldFrameUniforms fu{};
            fill_perspective_reversed_z(1.22f, static_cast<float>(w) / h, 0.05f, fu.proj, fu.inv_proj);
            fill_identity(fu.view_rot);
            fill_identity(fu.inv_view_rot);
            std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
            std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
            fu.cam_world_pos[0] = 7.5f; fu.cam_world_pos[1] = 55.5f; fu.cam_world_pos[2] = 9.0f; fu.cam_world_pos[3] = 1.0f;
            fu.sun_world_dir[0] = 0.0f; fu.sun_world_dir[1] = -0.8f; fu.sun_world_dir[2] = -0.6f; fu.sun_world_dir[3] = 0.0f;
            fu.moon_world_dir[0] = 0.0f; fu.moon_world_dir[1] = -0.5f; fu.moon_world_dir[2] = 0.86f; fu.moon_world_dir[3] = 0.0f;
            fu.width = w; fu.height = h; fu.sea_level = 20.0f; fu.edr_headroom = 1.0f;
            fu.user_params[0] = 0.0f; fu.user_params[1] = 0.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;
            REQUIRE(metality_pack_execute(ctx, pack_dusk, scene_c, ctex, dtex, &fu), "Test C execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex, rb_buf, 0, w * 4u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test C timeout");

            const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
            const size_t off = (static_cast<size_t>(h / 2u) * w + (w / 2u)) * 4u;
            out_rgb[0] = px[off]; out_rgb[1] = px[off + 1]; out_rgb[2] = px[off + 2];
            metality_rt_scene_destroy(scene_c);
        };
        uint8_t clear_rgb[3]{}, glass_rgb[3]{};
        run_glass_test(false, clear_rgb);
        run_glass_test(true, glass_rgb);
        const int shift_from_clear = std::abs(int(glass_rgb[0]) - int(clear_rgb[0])) +
                                     std::abs(int(glass_rgb[1]) - int(clear_rgb[1])) +
                                     std::abs(int(glass_rgb[2]) - int(clear_rgb[2]));
        std::printf("[Test C GlassTint] Clear=(%u,%u,%u) | StainedGlass=(%u,%u,%u) | ShiftFromClear=%d\n",
                    clear_rgb[0], clear_rgb[1], clear_rgb[2], glass_rgb[0], glass_rgb[1], glass_rgb[2], shift_from_clear);
        REQUIRE(shift_from_clear >= 15, "Stained glass must shift downstream wall lighting relative to clear air");
    }

    // -------------------------------------------------------------------------
    // Regression Test D: MetalFX Spatial Upscale at 0.75x (Both STORAGE_WRITE and RT-Only Target)
    // -------------------------------------------------------------------------
    {
        MetalityTextureDesc td_no_write{
            w, h, 1u, METALITY_FORMAT_RGBA8_UNORM,
            METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED
        };
        MetalityTextureHandle ctex_rt_only = metality_texture_create(ctx, &td_no_write);
        REQUIRE(ctex_rt_only != 0, "Failed to create RT-only color target for MetalFX test");

        metality_set_shader_render_scale(ctx, 0.75f);
        auto run_scale_check = [&](MetalityTextureHandle target_tex, const char* label) {
            const uint64_t fid = metality_begin_frame(ctx);
            metality_cmd_clear_color_depth(ctx, target_tex, 0.25f, 0.45f, 0.90f, 1.0f, dtex, 0.0);
            metality_cmd_clear_color_depth_rect(ctx, target_tex, 0.35f, 0.55f, 0.28f, 1.0f, dtex, 0.008, 0, 0, 0, w / 2u, h / 2u);
            MetalityWorldFrameUniforms fu{};
            fill_perspective_reversed_z(1.22f, static_cast<float>(w) / h, 0.05f, fu.proj, fu.inv_proj);
            fill_identity(fu.view_rot);
            fill_identity(fu.inv_view_rot);
            std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
            std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
            fu.cam_world_pos[0] = 8.0f; fu.cam_world_pos[1] = 65.0f; fu.cam_world_pos[2] = 10.0f; fu.cam_world_pos[3] = 1.0f;
            fu.sun_world_dir[0] = 0.28f; fu.sun_world_dir[1] = 0.45f; fu.sun_world_dir[2] = -0.85f; fu.sun_world_dir[3] = 1.0f;
            fu.width = w; fu.height = h; fu.sea_level = 63.0f; fu.edr_headroom = 1.0f;
            fu.user_params[0] = 1.0f; fu.user_params[1] = 1.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;
            REQUIRE(metality_pack_execute(ctx, pack_dusk, rt_scene, target_tex, dtex, &fu), "MetalFX scaled execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, target_tex, rb_buf, 0, w * 4u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "MetalFX scaled timeout");

            const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
            const size_t sky_off = (static_cast<size_t>(h * 3u / 4u) * w + (w / 2u)) * 4u;
            const uint32_t sum_rgb = uint32_t(px[sky_off]) + uint32_t(px[sky_off + 1]) + uint32_t(px[sky_off + 2]);
            std::printf("[Test D MetalFX 0.75x %-12s] SkyRGB=(%u,%u,%u)\n", label, px[sky_off], px[sky_off + 1], px[sky_off + 2]);
            REQUIRE(sum_rgb > 20u, "MetalFX 0.75x upscaled output must be non-black");
        };
        run_scale_check(ctex, "STORAGE_WRITE");
        run_scale_check(ctex_rt_only, "RT_ONLY");
        metality_set_shader_render_scale(ctx, 1.0f);
        metality_texture_destroy(ctx, ctex_rt_only);
    }

    // -------------------------------------------------------------------------
    // Regression Test E: Odd-Resolution Hairline Stripe Prevention (Dusk & Zenith)
    // -------------------------------------------------------------------------
    {
        const uint32_t ow = 513u;
        const uint32_t oh = 307u;
        MetalityTextureDesc ocd{ow, oh, 1u, METALITY_FORMAT_RGBA8_UNORM, METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED | METALITY_TEX_USAGE_STORAGE_WRITE};
        MetalityTextureDesc odd{ow, oh, 1u, METALITY_FORMAT_DEPTH32_FLOAT, METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED};
        MetalityTextureHandle octex = metality_texture_create(ctx, &ocd);
        MetalityTextureHandle odtex = metality_texture_create(ctx, &odd);
        void* orb_ptr = nullptr;
        MetalityBufferHandle orb = metality_buffer_alloc_shared(ctx, static_cast<size_t>(ow) * oh * 4u, false, &orb_ptr, nullptr);
        REQUIRE(octex != 0 && odtex != 0 && orb != 0 && orb_ptr != nullptr, "Failed odd-res buffers");

        auto check_no_hairlines = [&](MetalityPackHandle pk, const char* tag) {
            const uint64_t fid = metality_begin_frame(ctx);
            const float d_wall = 0.05f / 6.0f;
            metality_cmd_clear_color_depth(ctx, octex, 0.45f, 0.42f, 0.38f, 1.0f, odtex, d_wall);
            MetalityWorldFrameUniforms fu{};
            fill_perspective_reversed_z(1.22f, static_cast<float>(ow) / oh, 0.05f, fu.proj, fu.inv_proj);
            fill_identity(fu.view_rot);
            fill_identity(fu.inv_view_rot);
            std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
            std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
            fu.cam_world_pos[0] = 8.0f; fu.cam_world_pos[1] = 65.0f; fu.cam_world_pos[2] = 10.0f; fu.cam_world_pos[3] = 1.0f;
            fu.sun_world_dir[0] = 0.25f; fu.sun_world_dir[1] = 0.85f; fu.sun_world_dir[2] = 0.46f; fu.sun_world_dir[3] = 1.0f;
            fu.width = ow; fu.height = oh; fu.sea_level = 63.0f; fu.edr_headroom = 1.0f;
            fu.user_params[0] = 0.5f; fu.user_params[1] = 0.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;
            fu.tonemap_mode = 0x1FF00u | (2u << 17) | 1u;
            REQUIRE(metality_pack_execute(ctx, pk, rt_scene, octex, odtex, &fu), "Test E execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, octex, orb, 0, ow * 4u, ow, oh);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test E timeout");

            const uint8_t* px = static_cast<const uint8_t*>(orb_ptr);
            float max_dx = 0.0f, max_dy = 0.0f;
            const uint32_t mid_y = oh / 2u;
            const uint32_t mid_x = ow / 2u;
            for (uint32_t x = 16u; x < ow - 16u; ++x) {
                const size_t o0 = (static_cast<size_t>(mid_y) * ow + x - 1u) * 4u;
                const size_t o1 = (static_cast<size_t>(mid_y) * ow + x) * 4u;
                const float l0 = 0.2126f * px[o0] + 0.7152f * px[o0 + 1] + 0.0722f * px[o0 + 2];
                const float l1 = 0.2126f * px[o1] + 0.7152f * px[o1 + 1] + 0.0722f * px[o1 + 2];
                max_dx = std::max(max_dx, std::abs(l1 - l0));
            }
            for (uint32_t y = 16u; y < oh - 16u; ++y) {
                const size_t o0 = (static_cast<size_t>(y - 1u) * ow + mid_x) * 4u;
                const size_t o1 = (static_cast<size_t>(y) * ow + mid_x) * 4u;
                const float l0 = 0.2126f * px[o0] + 0.7152f * px[o0 + 1] + 0.0722f * px[o0 + 2];
                const float l1 = 0.2126f * px[o1] + 0.7152f * px[o1 + 1] + 0.0722f * px[o1 + 2];
                max_dy = std::max(max_dy, std::abs(l1 - l0));
            }
            std::printf("[Test E OddRes 513x307 %-6s] MaxDx=%.2f MaxDy=%.2f\n", tag, max_dx, max_dy);
            REQUIRE(max_dx < 14.0f && max_dy < 14.0f, "Odd resolution wall must have zero hairline normal stripes");
        };
        check_no_hairlines(pack_dusk, "Dusk");
        check_no_hairlines(pack_zenith, "Zenith");
        metality_buffer_retire(ctx, orb);
        metality_texture_destroy(ctx, octex);
        metality_texture_destroy(ctx, odtex);
    }

    // -------------------------------------------------------------------------
    // Regression Test F: Zenith 16m BVH/DDA Continuity With Occluded Lights Present
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_f = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_f != nullptr, "Failed to create Test F scene");
        std::vector<uint16_t> blocks_f(METALITY_PADDED_VOLUME, 0u);
        for (int py = 1; py <= 16; ++py) {
            for (int px = 1; px <= 16; ++px) {
                blocks_f[pad_idx(px, py, 2)] = 1u; // solid back wall at world z=1
            }
        }
        MetalityChunkSectionInput in_f{};
        in_f.block_material_ids = blocks_f.data();
        in_f.packed_light = light.data();
        in_f.biome_tint_rgb = tint.data();
        in_f.palette_table = palette.data();
        in_f.palette_size = static_cast<uint32_t>(palette.size());
        in_f.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_f, &mout), "Test F meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_f, 0, 4, 0,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test F update failed");

        std::vector<float> lights_f(12u * 8u, 0.0f);
        for (uint32_t i = 0u; i < 10u; ++i) { // 10 occluded lights behind wall at z=-2
            lights_f[i * 8u + 0] = -4.0f + float(i);
            lights_f[i * 8u + 1] = 0.0f;
            lights_f[i * 8u + 2] = -2.0f;
            lights_f[i * 8u + 3] = 28.0f;
            lights_f[i * 8u + 4] = 1.0f; lights_f[i * 8u + 5] = 0.2f; lights_f[i * 8u + 6] = 0.1f; lights_f[i * 8u + 7] = 0.5f;
        }
        // Visible light straddling 16m boundary relative to wall points (-8..+8 at z=-16)
        lights_f[10u * 8u + 0] = -7.5f; lights_f[10u * 8u + 1] = 0.0f; lights_f[10u * 8u + 2] = -14.0f;
        lights_f[10u * 8u + 3] = 24.0f;
        lights_f[10u * 8u + 4] = 1.0f; lights_f[10u * 8u + 5] = 0.72f; lights_f[10u * 8u + 6] = 0.35f; lights_f[10u * 8u + 7] = 0.5f;
        lights_f[11u * 8u + 0] = 7.5f; lights_f[11u * 8u + 1] = 0.0f; lights_f[11u * 8u + 2] = -14.0f;
        lights_f[11u * 8u + 3] = 24.0f;
        lights_f[11u * 8u + 4] = 0.45f; lights_f[11u * 8u + 5] = 0.85f; lights_f[11u * 8u + 6] = 1.0f; lights_f[11u * 8u + 7] = 0.5f;
        metality_rt_scene_set_dynamic_lights(scene_f, lights_f.data(), 12u);
        REQUIRE(metality_rt_scene_sync_world_data(ctx, scene_f, 8.0f, 65.0f, 16.0f), "Test F sync failed");

        const uint64_t fid = metality_begin_frame(ctx);
        const float d_wall = 0.05f / 16.0f;
        metality_cmd_clear_color_depth(ctx, ctex, 0.50f, 0.50f, 0.50f, 1.0f, dtex, d_wall);
        MetalityWorldFrameUniforms fu{};
        fill_perspective_reversed_z(1.22f, static_cast<float>(w) / h, 0.05f, fu.proj, fu.inv_proj);
        fill_identity(fu.view_rot);
        fill_identity(fu.inv_view_rot);
        std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
        std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
        fu.cam_world_pos[0] = 8.0f; fu.cam_world_pos[1] = 65.0f; fu.cam_world_pos[2] = 16.0f; fu.cam_world_pos[3] = 1.0f;
        fu.sun_world_dir[1] = -0.8f;
        fu.width = w; fu.height = h; fu.sea_level = 20.0f; fu.edr_headroom = 1.0f;
        fu.user_params[0] = 0.0f; fu.user_params[1] = 0.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;
        fu.tonemap_mode = 0x1FF00u | (2u << 17) | 1u;
        REQUIRE(metality_pack_execute(ctx, pack_zenith, scene_f, ctex, dtex, &fu), "Test F execute failed");
        metality_cmd_copy_texture_to_buffer(ctx, ctex, rb_buf, 0, w * 4u, w, h);
        metality_end_frame(ctx, false);
        REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test F timeout");

        const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
        const uint32_t mid_y = h / 2u;
        float max_step = 0.0f, prev_l = -1.0f;
        for (uint32_t xx = 24u; xx < w - 24u; ++xx) {
            const size_t off = (static_cast<size_t>(mid_y) * w + xx) * 4u;
            const float l = 0.2126f * px[off] + 0.7152f * px[off + 1] + 0.0722f * px[off + 2];
            if (prev_l >= 0.0f) max_step = std::max(max_step, std::abs(l - prev_l));
            prev_l = l;
        }
        std::printf("[Test F Zenith 16m Seam] MaxStep=%.2f (smooth across 16m BVH/DDA transition)\n", max_step);
        REQUIRE(max_step < 14.0f, "Zenith 16m BVH/DDA boundary must not produce circular shadow cliff");
        metality_rt_scene_destroy(scene_f);
    }

    // -------------------------------------------------------------------------
    // Regression Test G: Structural Feature Bitmask & Pass Skipping (LOW vs HIGH)
    // -------------------------------------------------------------------------
    {
        auto run_profile_frame = [&](uint32_t flags, uint32_t& out_sky_sum, uint32_t& out_terr_sum) {
            const uint64_t fid = metality_begin_frame(ctx);
            metality_cmd_clear_color_depth(ctx, ctex, 0.25f, 0.45f, 0.90f, 1.0f, dtex, 0.0);
            metality_cmd_clear_color_depth_rect(ctx, ctex, 0.35f, 0.55f, 0.28f, 1.0f, dtex, 0.008, 0, 0, 0, w, h / 2u);
            MetalityWorldFrameUniforms fu{};
            fill_perspective_reversed_z(1.22f, static_cast<float>(w) / h, 0.05f, fu.proj, fu.inv_proj);
            fill_identity(fu.view_rot);
            fill_identity(fu.inv_view_rot);
            std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
            std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
            fu.cam_world_pos[0] = 8.0f; fu.cam_world_pos[1] = 65.0f; fu.cam_world_pos[2] = 10.0f; fu.cam_world_pos[3] = 1.0f;
            fu.sun_world_dir[0] = 0.28f; fu.sun_world_dir[1] = 0.45f; fu.sun_world_dir[2] = -0.85f; fu.sun_world_dir[3] = 1.0f;
            fu.width = w; fu.height = h; fu.sea_level = 63.0f; fu.edr_headroom = 1.0f;
            fu.user_params[0] = 1.0f; fu.user_params[1] = 1.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;
            fu.tonemap_mode = flags;
            REQUIRE(metality_pack_execute(ctx, pack_dusk, rt_scene, ctex, dtex, &fu), "Test G execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex, rb_buf, 0, w * 4u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test G timeout");
            const uint8_t* px = static_cast<const uint8_t*>(rb_ptr);
            const size_t sOff = (static_cast<size_t>(h * 3u / 4u) * w + (w / 2u)) * 4u;
            const size_t tOff = (static_cast<size_t>(h / 4u) * w + (w / 2u)) * 4u;
            out_sky_sum = uint32_t(px[sOff]) + uint32_t(px[sOff + 1]) + uint32_t(px[sOff + 2]);
            out_terr_sum = uint32_t(px[tOff]) + uint32_t(px[tOff + 1]) + uint32_t(px[tOff + 2]);
        };
        uint32_t sky_low = 0u, terr_low = 0u, sky_hi = 0u, terr_hi = 0u;
        run_profile_frame(0x100u, sky_low, terr_low);
        run_profile_frame(0x1FF00u | (2u << 17), sky_hi, terr_hi);
        std::printf("[Test G FeatureMask] LOW=(sky=%u,terr=%u) | HIGH=(sky=%u,terr=%u)\n", sky_low, terr_low, sky_hi, terr_hi);
        REQUIRE(sky_low > 20u && terr_low > 10u, "Dusk LOW with skipped passes must remain visible and valid");
        REQUIRE(sky_hi > 20u && terr_hi > 10u, "Dusk HIGH must render cleanly");
    }

    // Shared FP16 HDR render target & readback buffer for full-frame NaN/Inf/Negative/Dropout audits (Tests H..P)
    MetalityTextureDesc cd_hdr{
        w, h, 1u, METALITY_FORMAT_RGBA16_FLOAT,
        METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED | METALITY_TEX_USAGE_STORAGE_WRITE
    };
    MetalityTextureHandle ctex_hdr = metality_texture_create(ctx, &cd_hdr);
    REQUIRE(ctex_hdr != 0, "Failed to create RGBA16_FLOAT test target");
    void* rb_hdr_ptr = nullptr;
    MetalityBufferHandle rb_hdr_buf = metality_buffer_alloc_shared(ctx, static_cast<size_t>(w) * h * 8u, false, &rb_hdr_ptr, nullptr);
    REQUIRE(rb_hdr_buf != 0 && rb_hdr_ptr != nullptr, "Failed to alloc RGBA16_FLOAT readback buffer");

    auto make_high_uniforms = [&](float cam_x, float cam_y, float cam_z, float sun_x, float sun_y, float sun_z) {
        MetalityWorldFrameUniforms fu{};
        fill_perspective_reversed_z(1.22f, static_cast<float>(w) / static_cast<float>(h), 0.05f, fu.proj, fu.inv_proj);
        fill_identity(fu.view_rot);
        fill_identity(fu.inv_view_rot);
        std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
        std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
        fu.cam_world_pos[0] = cam_x; fu.cam_world_pos[1] = cam_y; fu.cam_world_pos[2] = cam_z; fu.cam_world_pos[3] = 1.0f;
        const float sl = std::max(std::sqrt(sun_x * sun_x + sun_y * sun_y + sun_z * sun_z), 1e-5f);
        fu.sun_world_dir[0] = sun_x / sl; fu.sun_world_dir[1] = sun_y / sl; fu.sun_world_dir[2] = sun_z / sl;
        fu.sun_world_dir[3] = (fu.sun_world_dir[1] > 0.0f) ? 1.0f : 0.0f;
        fu.moon_world_dir[0] = -fu.sun_world_dir[0]; fu.moon_world_dir[1] = -fu.sun_world_dir[1]; fu.moon_world_dir[2] = -fu.sun_world_dir[2];
        fu.moon_world_dir[3] = (fu.moon_world_dir[1] > 0.0f) ? 1.0f : 0.0f;
        fu.sky_color[0] = 0.45f; fu.sky_color[1] = 0.68f; fu.sky_color[2] = 1.0f; fu.sky_color[3] = (fu.sun_world_dir[1] < 0.0f) ? 1.0f : 0.0f;
        fu.fog_color[0] = 0.62f; fu.fog_color[1] = 0.74f; fu.fog_color[2] = 0.88f; fu.fog_color[3] = 0.25f;
        fu.sunrise_color[0] = 0.90f; fu.sunrise_color[1] = 0.45f; fu.sunrise_color[2] = 0.22f; fu.sunrise_color[3] = 0.0f;
        fu.width = w; fu.height = h; fu.time_seconds = 14.0f; fu.sea_level = 63.0f; fu.edr_headroom = 1.0f;
        fu.dimension_id = 0; fu.submerged_mode = 0;
        fu.tonemap_mode = 0x1FF00u | (2u << 17) | 1u;
        fu.user_params[0] = 0.85f; fu.user_params[1] = 1.0f; fu.user_params[2] = 1.0f; fu.user_params[3] = 1.0f;
        fu.tonemap_params[0] = 0.95f; fu.tonemap_params[1] = 1.16f; fu.tonemap_params[2] = 1.16f; fu.tonemap_params[3] = 1.25f;
        fu.lighting_params[0] = 1.20f; fu.lighting_params[1] = 1.15f; fu.lighting_params[2] = 1.15f; fu.lighting_params[3] = 0.65f;
        fu.surface_params[0] = 1.15f; fu.surface_params[1] = 1.15f; fu.surface_params[2] = 1.15f; fu.surface_params[3] = 1.10f;
        return fu;
    };

    // -------------------------------------------------------------------------
    // Regression Test H: glow_lichen Flat Cutout Decal (6.25mm Offset) — Zero Black Pixels & No Soul-Fire Cyan Bleed
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_h = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_h != nullptr, "Failed to create Test H scene");
        std::vector<MetalityBlockMaterialEntry> pal_h = palette;
        pal_h.resize(4);
        for (int f = 0; f < 6; ++f) pal_h[3].face_sprites[f] = 3;
        pal_h[3].layer = METALITY_LAYER_CUTOUT;
        pal_h[3].is_opaque_full_cube = 0;
        pal_h[3].wave_tag = 10;     // TAG_EMISSIVE_AQUA_SCULK (shared with glow_lichen)
        pal_h[3].emissive_level = 7; // glow_lichen emission == 7
        pal_h[3].alpha = 255;

        std::vector<uint16_t> blocks_h(METALITY_PADDED_VOLUME, 0u);
        std::vector<uint32_t> tint_h(4096u, 0x808080u);
        for (int py = 1; py <= 16; ++py) {
            for (int px = 1; px <= 16; ++px) {
                blocks_h[pad_idx(px, py, 3)] = 1u; // solid stone wall at world z=[2..3], front face at z=3.0
                if (px >= 4 && px <= 13 && py >= 4 && py <= 13) {
                    blocks_h[pad_idx(px, py, 4)] = 3u; // glow_lichen in adjacent cell z=[3..4]
                    tint_h[((py - 1) * 16 + 3) * 16 + (px - 1)] = 0x6A8268u;
                }
            }
        }
        MetalityChunkSectionInput in_h{};
        in_h.block_material_ids = blocks_h.data();
        in_h.packed_light = light.data();
        in_h.biome_tint_rgb = tint_h.data();
        in_h.palette_table = pal_h.data();
        in_h.palette_size = static_cast<uint32_t>(pal_h.size());
        in_h.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_h, &mout), "Test H meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_h, 0, 3, 0,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test H scene update failed");

        const float torch_h[8] = {8.0f, 56.0f, 5.5f, 22.0f, 1.0f, 0.80f, 0.48f, 0.35f};
        metality_rt_scene_set_dynamic_lights(scene_h, torch_h, 1u);
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_h, 8.0f, 56.0f, 8.0f), "Test H TLAS failed");

        auto eval_lichen_wall = [&](MetalityPackHandle pk, const char* tag) {
            const uint64_t fid = metality_begin_frame(ctx);
            const float d_wall = 0.05f / 5.0f;         // stone wall face at z = 3.00000m (view_z = -5.00000m)
            const float d_decal = 0.05f / 4.99375f;    // glow_lichen decal at z = 3.00625m (6.25mm offset!)
            metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.42f, 0.43f, 0.42f, 1.0f, dtex, d_wall);
            // Interleave 2px-wide vertical & horizontal decal tendrils (+0.00625m step) across center ROI
            for (uint32_t strip = 0; strip < 8u; ++strip) {
                const uint32_t sx = (w / 4u) + strip * 14u;
                metality_cmd_clear_color_depth_rect(ctx, ctex_hdr, 0.50f, 0.58f, 0.48f, 1.0f, dtex, d_decal, 0, sx, h / 4u, 3u, h / 2u);
            }
            MetalityWorldFrameUniforms fu = make_high_uniforms(8.0f, 56.0f, 8.0f, 0.0f, -0.85f, -0.52f);
            fu.user_params[0] = 0.0f;
            REQUIRE(metality_pack_execute(ctx, pk, scene_h, ctex_hdr, dtex, &fu), "Test H execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test H timeout");

            const FrameHealthStats st = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, w / 4u, h / 4u, w / 2u, h / 2u, 0.12f, 0.0f);
            std::printf("[Test H GlowLichen %-6s] AvgLuma=%.3f MinLuma=%.3f RGB=(%.3f,%.3f,%.3f) BlackRatio=%.4f\n",
                        tag, st.roi_avg_luma, st.roi_min_luma, st.roi_avg_r, st.roi_avg_g, st.roi_avg_b, st.roi_black_ratio);
            REQUIRE(st.roi_min_luma >= 0.06f, "glow_lichen 6.25mm decal tendrils and cutout holes must never collapse to black pixels");
            REQUIRE(st.roi_avg_b <= st.roi_avg_r * 1.45f + 0.05f, "glow_lichen must emit warm sage, never Soul-Fire cyan");
        };
        eval_lichen_wall(pack_dusk, "Dusk");
        eval_lichen_wall(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_h);
    }

    // -------------------------------------------------------------------------
    // Regression Test I: Water Shoreline Overhang Bank Edge (Zero Black Slits, Zero Waterfall-Seam Grid, View-Bob Sync)
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_i = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_i != nullptr, "Failed to create Test I scene");
        std::vector<MetalityBlockMaterialEntry> pal_i = palette;
        pal_i.resize(5);
        for (int f = 0; f < 6; ++f) pal_i[4].face_sprites[f] = 4;
        pal_i[4].layer = METALITY_LAYER_TRANSLUCENT;
        pal_i[4].is_opaque_full_cube = 0;
        pal_i[4].wave_tag = 3;       // water
        pal_i[4].emissive_level = 8; // still source water height 8/9
        pal_i[4].alpha = 180;

        std::vector<uint16_t> blocks_i(METALITY_PADDED_VOLUME, 0u);
        for (int pz = 1; pz <= 16; ++pz) {
            for (int px = 1; px <= 16; ++px) {
                blocks_i[pad_idx(px, 13, pz)] = 1u; // riverbed stone y=60
                blocks_i[pad_idx(px, 14, pz)] = 1u; // riverbed stone y=61
                blocks_i[pad_idx(px, 15, pz)] = 4u; // water at y=62..62.889 across entire river AND under bank overhang
                if (pz <= 8) {
                    blocks_i[pad_idx(px, 16, pz)] = 1u; // overhanging dirt bank at y=63..64 directly above water y=62!
                }
            }
        }
        MetalityChunkSectionInput in_i{};
        in_i.block_material_ids = blocks_i.data();
        in_i.packed_light = light.data();
        in_i.biome_tint_rgb = tint.data();
        in_i.palette_table = pal_i.data();
        in_i.palette_size = static_cast<uint32_t>(pal_i.size());
        in_i.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_i, &mout), "Test I meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_i, 0, 3, 0,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test I scene update failed");

        const float ripple_i[8] = {8.0f, 63.0f, 10.5f, 0.35f, 0.8f, 0.6f, 1.0f, 0.0f};
        metality_rt_scene_set_water_ripples(scene_i, ripple_i, 1u);

        auto eval_shoreline = [&](MetalityPackHandle pk, const char* tag, float bob_dx, float bob_dy) {
            const float cx = 8.0f + bob_dx;
            const float cy = 64.5f + bob_dy;
            const float cz = 15.0f;
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_i, cx, cy, cz), "Test I TLAS rebuild failed");

            const uint64_t fid = metality_begin_frame(ctx);
            const float d_bed = 0.05f / 7.8f; // background bank/riverbed geometry behind overhang water surface
            metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.25f, 0.45f, 0.90f, 1.0f, dtex, 0.0);
            metality_cmd_clear_color_depth_rect(ctx, ctex_hdr, 0.46f, 0.42f, 0.34f, 1.0f, dtex, d_bed, 0, 0, 0, w, h / 2u);

            MetalityWorldFrameUniforms fu = make_high_uniforms(cx, cy, cz, 0.32f, 0.76f, -0.56f);
            REQUIRE(metality_pack_execute(ctx, pk, scene_i, ctex_hdr, dtex, &fu), "Test I execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test I timeout");

            const FrameHealthStats st = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, w / 4u, h / 8u, w / 2u, h / 4u, 0.10f, 0.0f);
            const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
            float max_step_x = 0.0f;
            const uint32_t water_row = h / 4u;
            float prev_l = -1.0f;
            for (uint32_t x = w / 4u; x < (w * 3u) / 4u; ++x) {
                const size_t base = (static_cast<size_t>(water_row) * w + x) * 4u;
                const float l = 0.2126f * decode_fp16(p16[base]) + 0.7152f * decode_fp16(p16[base + 1]) + 0.0722f * decode_fp16(p16[base + 2]);
                if (prev_l >= 0.0f) max_step_x = std::max(max_step_x, std::abs(l - prev_l));
                prev_l = l;
            }
            std::printf("[Test I Shoreline %-10s] MinLuma=%.3f AvgLuma=%.3f MaxStepX=%.3f RGB=(%.3f,%.3f,%.3f)\n",
                        tag, st.roi_min_luma, st.roi_avg_luma, max_step_x, st.roi_avg_r, st.roi_avg_g, st.roi_avg_b);
            REQUIRE(st.roi_min_luma >= 0.05f, "Overhanging river bank shoreline must have zero pitch-black slit pixels");
            REQUIRE(st.roi_avg_b > st.roi_avg_r, "River water surface must retain blue/turquoise chroma");
            REQUIRE(max_step_x < 0.22f, "Water surface near overhanging bank must not exhibit 1-block grid seam spikes");
        };
        eval_shoreline(pack_dusk, "Dusk", 0.0f, 0.0f);
        eval_shoreline(pack_dusk, "Dusk-Bob", 0.08f, -0.06f);
        eval_shoreline(pack_zenith, "Zenith", 0.0f, 0.0f);
        eval_shoreline(pack_zenith, "Zenith-Bob", 0.08f, -0.06f);
        metality_rt_scene_destroy(scene_i);
    }

    // -------------------------------------------------------------------------
    // Regression Test J: Thin 1-Block Wall & 90-Degree Inner-Corner Block-Edge Light-Leak Guard
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_j = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_j != nullptr, "Failed to create Test J scene");
        std::vector<uint16_t> blocks_j(METALITY_PADDED_VOLUME, 0u);
        // Sealed dark room in x=[7..15], y=[49..62], z=[4..15] (px=[8..16], py=[2..15], pz=[5..16])
        // Front wall pz=5 (world z=[4..5]) and left wall px=8 (world x=[7..8]) are strictly 1-block thick!
        for (int pz = 5; pz <= 16; ++pz) {
            for (int px = 8; px <= 16; ++px) {
                blocks_j[pad_idx(px, 2, pz)] = 1u;  // floor y=49
                blocks_j[pad_idx(px, 15, pz)] = 1u; // roof y=62
                for (int py = 3; py <= 14; ++py) {
                    if (px == 8 || px == 16 || pz == 5 || pz == 16) {
                        blocks_j[pad_idx(px, py, pz)] = 1u;
                    }
                }
            }
        }
        MetalityChunkSectionInput in_j{};
        in_j.block_material_ids = blocks_j.data();
        in_j.packed_light = light.data();
        in_j.biome_tint_rgb = tint.data();
        in_j.palette_table = palette.data();
        in_j.palette_size = static_cast<uint32_t>(palette.size());
        in_j.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_j, &mout), "Test J meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_j, 0, 3, 0,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test J scene update failed");

        // 3 intense warm point lights placed 0.45m outside the 1-block walls and right outside the (7,4) corner!
        const float lights_j[3 * 8] = {
            11.0f, 55.0f, 3.55f, 48.0f, 1.0f, 0.85f, 0.45f, 0.45f,
            6.55f, 55.0f, 8.00f, 48.0f, 1.0f, 0.85f, 0.45f, 0.45f,
            6.55f, 55.0f, 3.55f, 48.0f, 1.0f, 0.85f, 0.45f, 0.45f
        };
        metality_rt_scene_set_dynamic_lights(scene_j, lights_j, 3u);
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_j, 11.0f, 55.0f, 10.5f), "Test J TLAS failed");

        auto eval_corner_leak = [&](MetalityPackHandle pk, const char* tag) {
            const uint64_t fid = metality_begin_frame(ctx);
            const float d_back_wall = 0.05f / 5.5f; // cam_z=10.5 -> world z=5.0 (interior face of 1-block wall z=[4..5]!)
            metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.50f, 0.50f, 0.50f, 1.0f, dtex, d_back_wall);
            MetalityWorldFrameUniforms fu = make_high_uniforms(11.0f, 55.0f, 10.5f, -0.52f, 0.72f, -0.45f);
            fu.user_params[0] = 0.0f;
            REQUIRE(metality_pack_execute(ctx, pk, scene_j, ctex_hdr, dtex, &fu), "Test J execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test J timeout");

            const FrameHealthStats st = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, 74u, h / 4u, 96u, h / 2u, 0.002f, 0.0f);
            std::printf("[Test J 1BlockWall+Corner %-6s] AvgLuma=%.3f MaxPixelLuma=%.3f\n",
                        tag, st.roi_avg_luma, st.roi_max_luma);
            REQUIRE(st.roi_avg_luma < 0.22f && st.roi_max_luma < 0.32f,
                    "1-block wall and inner corner must block exterior sun and 0.45m-adjacent point lights without edge leaks");
        };
        eval_corner_leak(pack_dusk, "Dusk");
        eval_corner_leak(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_j);
    }

    // -------------------------------------------------------------------------
    // Regression Test K: Distant 124m & 170m Point-Light Visibility & Smooth 160m-192m Fade
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_k = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_k != nullptr, "Failed to create Test K scene");
        std::vector<uint16_t> blocks_k(METALITY_PADDED_VOLUME, 0u);
        for (int py = 1; py <= 16; ++py) {
            for (int px = 1; px <= 16; ++px) {
                blocks_k[pad_idx(px, py, 5)] = 1u; // solid back wall at local z=4
            }
        }
        MetalityChunkSectionInput in_k{};
        in_k.block_material_ids = blocks_k.data();
        in_k.packed_light = light.data();
        in_k.biome_tint_rgb = tint.data();
        in_k.palette_table = palette.data();
        in_k.palette_size = static_cast<uint32_t>(palette.size());
        in_k.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_k, &mout), "Test K meshing failed");
        // Section (0, 3, -8) -> wall at world z = -128 + 4..5 = -123m; Section (0, 3, -11) -> wall at world z = -176 + 4..5 = -171m
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_k, 0, 3, -8,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test K sec -8 failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_k, 0, 3, -11,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test K sec -11 failed");

        auto eval_distant_light = [&](MetalityPackHandle pk, const char* tag, float wall_dist, bool enable_light, float& out_r, float& out_b) {
            if (enable_light) {
                const float dl[8] = {8.0f, 56.0f, -(wall_dist - 2.0f), 95.0f, 1.0f, 0.78f, 0.35f, 0.6f};
                metality_rt_scene_set_dynamic_lights(scene_k, dl, 1u);
            } else {
                metality_rt_scene_set_dynamic_lights(scene_k, nullptr, 0u);
            }
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_k, 8.0f, 56.0f, 0.0f), "Test K TLAS failed");

            const uint64_t fid = metality_begin_frame(ctx);
            metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.50f, 0.50f, 0.50f, 1.0f, dtex, 0.05f / wall_dist);
            MetalityWorldFrameUniforms fu = make_high_uniforms(8.0f, 56.0f, 0.0f, 0.0f, -0.80f, -0.60f);
            fu.user_params[0] = 0.0f;
            fu.lighting_params[3] = 0.0f; // disable distance haze so pure point-light contribution is isolated
            REQUIRE(metality_pack_execute(ctx, pk, scene_k, ctex_hdr, dtex, &fu), "Test K execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test K timeout");

            const FrameHealthStats st = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, (w / 2u) - 8u, (h / 2u) - 8u, 16u, 16u, 0.002f, 0.0f);
            out_r = st.roi_avg_r;
            out_b = st.roi_avg_b;
            return st.roi_avg_luma;
        };

        auto check_pack_distant = [&](MetalityPackHandle pk, const char* tag) {
            float r0 = 0, b0 = 0, r123 = 0, b123 = 0, r171 = 0, b171 = 0;
            const float lum_dark = eval_distant_light(pk, tag, 123.0f, false, r0, b0);
            const float lum_123  = eval_distant_light(pk, tag, 123.0f, true, r123, b123);
            const float lum_171  = eval_distant_light(pk, tag, 171.0f, true, r171, b171);
            std::printf("[Test K DistantLight %-6s] Dark123m=%.4f | Lit123m=%.4f (R=%.3f,B=%.3f) | Lit171m=%.4f\n",
                        tag, lum_dark, lum_123, r123, b123, lum_171);
            REQUIRE(lum_123 > lum_dark + 0.03f && r123 > b123,
                    "Distant 123m point light must remain clearly visible and warm-tinted beyond 120m");
            REQUIRE(lum_171 > lum_dark + 0.005f && lum_171 < lum_123,
                    "Very distant 171m point light inside 160m-192m fade band must attenuate smoothly without hard cutoff");
        };
        check_pack_distant(pack_dusk, "Dusk");
        check_pack_distant(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_k);
    }

    // -------------------------------------------------------------------------
    // Regression Test L: Multi-Section Sealed Dark Cave Dusk vs Zenith Luma Parity
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_l = metality_rt_scene_create(ctx, 32u, false);
        REQUIRE(scene_l != nullptr, "Failed to create Test L scene");
        // Build 3x1x3 (9-section, 48x16x48m) thick-roofed sealed cavern around camera so all 20m sky-openness probes stay under roof
        for (int sz = -1; sz <= 1; ++sz) {
            for (int sx = -1; sx <= 1; ++sx) {
                std::vector<uint16_t> blocks_l(METALITY_PADDED_VOLUME, 0u);
                for (int pz = 1; pz <= 16; ++pz) {
                    for (int px = 1; px <= 16; ++px) {
                        blocks_l[pad_idx(px, 1, pz)] = 1u;
                        blocks_l[pad_idx(px, 2, pz)] = 1u;  // floor y=49
                        blocks_l[pad_idx(px, 13, pz)] = 1u; // 4-block thick roof y=60..63
                        blocks_l[pad_idx(px, 14, pz)] = 1u;
                        blocks_l[pad_idx(px, 15, pz)] = 1u;
                        blocks_l[pad_idx(px, 16, pz)] = 1u;
                        if ((sx == -1 && px <= 2) || (sx == 1 && px >= 15) ||
                            (sz == -1 && pz <= 2) || (sz == 1 && pz >= 15)) {
                            for (int py = 3; py <= 12; ++py) {
                                blocks_l[pad_idx(px, py, pz)] = 1u;
                            }
                        }
                    }
                }
                MetalityChunkSectionInput in_l{};
                in_l.block_material_ids = blocks_l.data();
                in_l.packed_light = light.data();
                in_l.biome_tint_rgb = tint.data();
                in_l.palette_table = palette.data();
                in_l.palette_size = static_cast<uint32_t>(palette.size());
                in_l.enable_greedy_merge = 1;
                REQUIRE(metality_mesh_chunk_section_neon(&in_l, &mout), "Test L meshing failed");
                REQUIRE(metality_rt_scene_update_section_v2(
                            ctx, scene_l, sx, 3, sz,
                            q_solid.data(), mout.solid_quad_count,
                            q_cutout.data(), mout.cutout_quad_count,
                            q_trans.data(), mout.translucent_quad_count),
                        "Test L update failed");
            }
        }
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_l, 8.0f, 54.0f, 4.0f), "Test L TLAS failed");

        auto eval_sealed_cavern = [&](MetalityPackHandle pk, const char* tag) {
            const uint64_t fid = metality_begin_frame(ctx);
            metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.50f, 0.50f, 0.50f, 1.0f, dtex, 0.05f / 18.0f);
            MetalityWorldFrameUniforms fu = make_high_uniforms(8.0f, 54.0f, 4.0f, 0.15f, 0.96f, -0.22f);
            fu.user_params[0] = 0.0f;
            REQUIRE(metality_pack_execute(ctx, pk, scene_l, ctex_hdr, dtex, &fu), "Test L execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test L timeout");

            const FrameHealthStats st = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, w / 4u, h / 4u, w / 2u, h / 2u, 0.002f, 0.0f);
            return st.roi_avg_luma;
        };
        const float cave_dusk = eval_sealed_cavern(pack_dusk, "Dusk-Cavern");
        const float cave_zenith = eval_sealed_cavern(pack_zenith, "Zenith-Cavern");
        std::printf("[Test L SealedCave Parity] DuskLuma=%.4f | ZenithLuma=%.4f | Delta=%.4f\n",
                    cave_dusk, cave_zenith, std::abs(cave_dusk - cave_zenith));
        REQUIRE(cave_dusk < 0.18f && cave_zenith < 0.18f, "Sealed underground cavern under midday sun must stay genuinely dark in both packs");
        REQUIRE(std::abs(cave_dusk - cave_zenith) < 0.08f, "Dusk and Zenith sealed dark cave ambient luma must match within tight parity");
        metality_rt_scene_destroy(scene_l);
    }

    // -------------------------------------------------------------------------
    // Regression Test M: Double-Height Stained-Glass Sunbeam Tint & Vertical Block-Seam Uniformity
    // -------------------------------------------------------------------------
    {
        auto eval_double_glass_beam = [&](MetalityPackHandle pk, const char* tag, bool with_glass, float& out_r, float& out_b, float& out_max_vstep) {
            MetalityRtSceneHandle scene_m = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_m != nullptr, "Failed to create Test M scene");
            std::vector<MetalityBlockMaterialEntry> pal_m = palette;
            pal_m.resize(4);
            for (int f = 0; f < 6; ++f) pal_m[3].face_sprites[f] = 3;
            pal_m[3].layer = METALITY_LAYER_TRANSLUCENT;
            pal_m[3].is_opaque_full_cube = 0;
            pal_m[3].wave_tag = 8;       // glass tag
            pal_m[3].emissive_level = 2; // red stained glass (sub=2)
            pal_m[3].alpha = 165;

            std::vector<uint16_t> blocks_m(METALITY_PADDED_VOLUME, 0u);
            for (int px = 1; px <= 16; ++px) {
                blocks_m[pad_idx(px, 2, 6)] = 1u; // floor anchor
            }
            if (with_glass) {
                // Double-height (py=7..10 -> world y=54..58) red stained-glass window at pz=10 (world z=9..10)
                for (int py = 7; py <= 10; ++py) {
                    for (int px = 4; px <= 13; ++px) {
                        blocks_m[pad_idx(px, py, 10)] = 3u;
                    }
                }
            }
            MetalityChunkSectionInput in_m{};
            in_m.block_material_ids = blocks_m.data();
            in_m.packed_light = light.data();
            in_m.biome_tint_rgb = tint.data();
            in_m.palette_table = pal_m.data();
            in_m.palette_size = static_cast<uint32_t>(pal_m.size());
            in_m.enable_greedy_merge = 1;
            REQUIRE(metality_mesh_chunk_section_neon(&in_m, &mout), "Test M meshing failed");
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, scene_m, 0, 3, 0,
                        q_solid.data(), mout.solid_quad_count,
                        q_cutout.data(), mout.cutout_quad_count,
                        q_trans.data(), mout.translucent_quad_count),
                    "Test M update failed");
            // Camera at (8.0, 56.0, 8.2) looking -Z at receiving wall z=5.5 (Z_dist=2.7m); sun shines from +Z (0.04, 0.18, 0.98) through glass at z=9.5!
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_m, 8.0f, 56.0f, 8.2f), "Test M TLAS failed");

            const uint64_t fid = metality_begin_frame(ctx);
            metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.58f, 0.58f, 0.58f, 1.0f, dtex, 0.05f / 2.7f);
            MetalityWorldFrameUniforms fu = make_high_uniforms(8.0f, 56.0f, 8.2f, 0.04f, 0.18f, 0.98f);
            fu.user_params[0] = 0.0f;
            REQUIRE(metality_pack_execute(ctx, pk, scene_m, ctex_hdr, dtex, &fu), "Test M execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test M timeout");

            const FrameHealthStats st = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, w * 3u / 8u, h * 3u / 8u, w / 4u, h / 4u, 0.08f, 0.0f);
            out_r = st.roi_avg_r;
            out_b = st.roi_avg_b;

            const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
            const uint32_t mid_x = w / 2u;
            float max_vy = 0.0f, prev_l = -1.0f;
            for (uint32_t y = (h * 3u) / 8u; y <= (h * 5u) / 8u; ++y) {
                const size_t base = (static_cast<size_t>(y) * w + mid_x) * 4u;
                const float l = 0.2126f * decode_fp16(p16[base]) + 0.7152f * decode_fp16(p16[base + 1]) + 0.0722f * decode_fp16(p16[base + 2]);
                if (prev_l >= 0.0f) max_vy = std::max(max_vy, std::abs(l - prev_l));
                prev_l = l;
            }
            out_max_vstep = max_vy;
            metality_rt_scene_destroy(scene_m);
        };

        for (int pk_i = 0; pk_i < 2; ++pk_i) {
            MetalityPackHandle pk = (pk_i == 0) ? pack_dusk : pack_zenith;
            const char* tag = (pk_i == 0) ? "Dusk" : "Zenith";
            float cr = 0, cb = 0, cv = 0, gr = 0, gb = 0, gv = 0;
            eval_double_glass_beam(pk, tag, false, cr, cb, cv);
            eval_double_glass_beam(pk, tag, true, gr, gb, gv);
            std::printf("[Test M DoubleGlassBeam %-6s] Clear=(R=%.3f,B=%.3f) | RedGlass=(R=%.3f,B=%.3f) MaxVertStep=%.4f\n",
                        tag, cr, cb, gr, gb, gv);
            REQUIRE(gr > gb * 1.25f && (gr - gb) > (cr - cb) + 0.08f,
                    "Double-height red stained glass must tint downstream sunbeam crimson");
            REQUIRE(gv < 0.08f, "Double-height stained glass beam must be vertically uniform across stacked block boundary");
        }
    }

    // -------------------------------------------------------------------------
    // Regression Test N: High-Altitude Ocean & Dry Island From cam_y = 145 (Outside 3D Voxel Y-Slice)
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_n = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_n != nullptr, "Failed to create Test N scene");
        std::vector<MetalityBlockMaterialEntry> pal_n = palette;
        pal_n.resize(5);
        for (int f = 0; f < 6; ++f) pal_n[4].face_sprites[f] = 4;
        pal_n[4].layer = METALITY_LAYER_TRANSLUCENT;
        pal_n[4].is_opaque_full_cube = 0;
        pal_n[4].wave_tag = 3;
        pal_n[4].emissive_level = 8;
        pal_n[4].alpha = 180;

        std::vector<uint16_t> blocks_ocean(METALITY_PADDED_VOLUME, 0u);
        std::vector<uint16_t> blocks_island(METALITY_PADDED_VOLUME, 0u);
        for (int pz = 1; pz <= 16; ++pz) {
            for (int px = 1; px <= 16; ++px) {
                blocks_ocean[pad_idx(px, 13, pz)] = 1u;  // seabed y=60
                blocks_ocean[pad_idx(px, 15, pz)] = 4u;  // sea-level ocean water y=62
                blocks_island[pad_idx(px, 13, pz)] = 1u;
                blocks_island[pad_idx(px, 15, pz)] = 1u;
                blocks_island[pad_idx(px, 16, pz)] = 1u; // dry island above sea level y=63..64
            }
        }
        // Populate ocean in sec_x in [-2..-1], dry island in sec_x in [0..1], across sec_z in [-2..0] (world z=[-32..16])
        for (int sz = -2; sz <= 0; ++sz) {
            for (int sx = -2; sx <= 1; ++sx) {
                MetalityChunkSectionInput in_n{};
                in_n.block_material_ids = (sx < 0) ? blocks_ocean.data() : blocks_island.data();
                in_n.packed_light = light.data();
                in_n.biome_tint_rgb = tint.data();
                in_n.palette_table = pal_n.data();
                in_n.palette_size = static_cast<uint32_t>(pal_n.size());
                in_n.enable_greedy_merge = 1;
                REQUIRE(metality_mesh_chunk_section_neon(&in_n, &mout), "Test N meshing failed");
                REQUIRE(metality_rt_scene_update_section_v2(
                            ctx, scene_n, sx, 3, sz,
                            q_solid.data(), mout.solid_quad_count,
                            q_cutout.data(), mout.cutout_quad_count,
                            q_trans.data(), mout.translucent_quad_count),
                        "Test N update failed");
            }
        }
        // Camera at (0, 145, 180): y=62 is outside the 128-tall 3D voxel slice [81..208] centered on cam_y=145!
        // At row = 3*h/16 (uv.y=0.1875, vy/(-vz)=-0.4375), rays hit y=62.889 at dz=187.7m -> world z=-7.7m (inside sec_z=-1)!
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_n, 0.0f, 145.0f, 180.0f), "Test N TLAS failed");

        auto eval_high_alt_ocean = [&](MetalityPackHandle pk, const char* tag) {
            const uint64_t fid = metality_begin_frame(ctx);
            metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.25f, 0.45f, 0.90f, 1.0f, dtex, 0.0);
            // Left lower half (world x < 0): seabed below sea plane (Z_view=220m > dz_sea=187.7m -> hits ocean plane)
            metality_cmd_clear_color_depth_rect(ctx, ctex_hdr, 0.18f, 0.36f, 0.48f, 1.0f, dtex, 0.05f / 220.0f, 0, 0, 0, w / 2u, h / 2u);
            // Right lower half (world x > 0): dry grass island above sea level (Z_view=184m -> y=64.5 > sea_level=63)
            metality_cmd_clear_color_depth_rect(ctx, ctex_hdr, 0.24f, 0.68f, 0.18f, 1.0f, dtex, 0.05f / 184.0f, 0, w / 2u, 0, w / 2u, h / 2u);

            MetalityWorldFrameUniforms fu = make_high_uniforms(0.0f, 145.0f, 180.0f, 0.28f, 0.78f, -0.56f);
            REQUIRE(metality_pack_execute(ctx, pk, scene_n, ctex_hdr, dtex, &fu), "Test N execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test N timeout");

            const FrameHealthStats st_ocean = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, (w / 2u) - 12u, (h * 3u) / 16u - 4u, 8u, 8u, 0.08f, 0.0f);
            const FrameHealthStats st_island = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, (w / 2u) + 4u, (h * 3u) / 16u - 4u, 8u, 8u, 0.08f, 0.0f);
            std::printf("[Test N HighAltOcean %-6s] OceanRGB=(%.3f,%.3f,%.3f) | IslandRGB=(%.3f,%.3f,%.3f)\n",
                        tag, st_ocean.roi_avg_r, st_ocean.roi_avg_g, st_ocean.roi_avg_b,
                        st_island.roi_avg_r, st_island.roi_avg_g, st_island.roi_avg_b);
            REQUIRE(st_ocean.roi_avg_b > st_ocean.roi_avg_r, "High-altitude ocean at cam_y=145 must render blue water");
            REQUIRE(st_island.roi_avg_g > st_island.roi_avg_b && st_island.roi_avg_g > st_island.roi_avg_r,
                    "Dry island above sea level must remain grass-green and not be flooded by sea-plane water");
        };
        eval_high_alt_ocean(pack_dusk, "Dusk");
        eval_high_alt_ocean(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_n);
    }

    // -------------------------------------------------------------------------
    // Regression Test O: Dusk & Zenith Point-Light Articulated Entity OBB Soft Shadow
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_o = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_o != nullptr, "Failed to create Test O scene");
        std::vector<uint16_t> blocks_o(METALITY_PADDED_VOLUME, 0u);
        for (int py = 1; py <= 16; ++py) {
            for (int px = 1; px <= 16; ++px) {
                blocks_o[pad_idx(px, py, 5)] = 1u; // receiving back wall at world z=-11.0 (in section (0,3,-1))
            }
        }
        MetalityChunkSectionInput in_o{};
        in_o.block_material_ids = blocks_o.data();
        in_o.packed_light = light.data();
        in_o.biome_tint_rgb = tint.data();
        in_o.palette_table = palette.data();
        in_o.palette_size = static_cast<uint32_t>(palette.size());
        in_o.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_o, &mout), "Test O meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_o, 0, 3, -1,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test O update failed");

        const float light_o[8] = {8.0f, 56.0f, -5.0f, 42.0f, 1.0f, 0.84f, 0.52f, 0.30f};
        metality_rt_scene_set_dynamic_lights(scene_o, light_o, 1u);
        const float caster_o[8] = {8.0f, 56.0f, -8.0f, 1.15f, 1.35f, 0.45f, 0.0f, 0.0f};

        auto eval_entity_point_shadow = [&](MetalityPackHandle pk, const char* tag, bool with_caster, float& out_center, float& out_side) {
            if (with_caster) {
                metality_rt_scene_set_entity_shadows(scene_o, caster_o, 1u);
            } else {
                metality_rt_scene_set_entity_shadows(scene_o, nullptr, 0u);
            }
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_o, 8.0f, 56.0f, 0.0f), "Test O TLAS failed");

            const uint64_t fid = metality_begin_frame(ctx);
            metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.52f, 0.52f, 0.52f, 1.0f, dtex, 0.05f / 11.0f);
            MetalityWorldFrameUniforms fu = make_high_uniforms(8.0f, 56.0f, 0.0f, 0.0f, -0.85f, -0.52f);
            fu.user_params[0] = 0.0f;
            REQUIRE(metality_pack_execute(ctx, pk, scene_o, ctex_hdr, dtex, &fu), "Test O execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test O timeout");

            const FrameHealthStats st_c = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, (w * 15u) / 32u, (h * 15u) / 32u, w / 16u, h / 16u, 0.02f, 0.0f);
            const FrameHealthStats st_s = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, (w * 11u) / 32u, (h * 15u) / 32u, w / 16u, h / 16u, 0.05f, 0.0f);
            out_center = st_c.roi_avg_luma;
            out_side = st_s.roi_avg_luma;
        };

        for (int pk_i = 0; pk_i < 2; ++pk_i) {
            MetalityPackHandle pk = (pk_i == 0) ? pack_dusk : pack_zenith;
            const char* tag = (pk_i == 0) ? "Dusk" : "Zenith";
            float c_no = 0, s_no = 0, c_ent = 0, s_ent = 0;
            eval_entity_point_shadow(pk, tag, false, c_no, s_no);
            eval_entity_point_shadow(pk, tag, true, c_ent, s_ent);
            std::printf("[Test O EntityPointShadow %-6s] NoEntityCenter=%.3f | WithEntityCenter=%.3f | UnoccludedSide=%.3f\n",
                        tag, c_no, c_ent, s_ent);
            REQUIRE(c_ent < c_no * 0.78f && s_ent > c_ent * 1.20f,
                    "Entity shadow caster OBB must cast soft shadow from point light onto wall in both Dusk and Zenith");
        }
        metality_rt_scene_destroy(scene_o);
    }

    // -------------------------------------------------------------------------
    // Regression Test P: Full Environment & Submersion Matrix (Night, Rain, Sunset, Nether 3-Biomes, End, Underwater TIR, Lava Sky+Terrain)
    // -------------------------------------------------------------------------
    {
        auto eval_env_mode = [&](MetalityPackHandle pk, const char* label, const MetalityWorldFrameUniforms& fu_in,
                                 FrameHealthStats& out_sky, FrameHealthStats& out_terr) {
            const uint64_t fid = metality_begin_frame(ctx);
            metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.25f, 0.45f, 0.90f, 1.0f, dtex, 0.0);
            metality_cmd_clear_color_depth_rect(ctx, ctex_hdr, 0.42f, 0.46f, 0.38f, 1.0f, dtex, 0.008, 0, 0, 0, w, h / 2u);
            MetalityWorldFrameUniforms fu = fu_in;
            REQUIRE(metality_pack_execute(ctx, pk, rt_scene, ctex_hdr, dtex, &fu), "Test P execute failed");
            metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
            metality_end_frame(ctx, false);
            REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test P timeout");

            out_sky  = assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 4u, (h * 9u) / 16u, w / 2u, h / 3u, 0.003f, 0.0f);
            out_terr = assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 4u, h / 8u, w / 2u, h / 4u, 0.003f, 0.0f);
        };

        for (int pk_i = 0; pk_i < 2; ++pk_i) {
            MetalityPackHandle pk = (pk_i == 0) ? pack_dusk : pack_zenith;
            const char* ptag = (pk_i == 0) ? "Dusk" : "Zenith";
            FrameHealthStats s_sky{}, s_terr{};

            auto log_env = [&](const char* mode_name) {
                std::printf("[Test P %-6s %-13s] Sky=(%.3f,%.3f,%.3f L=%.3f min=%.3f) | Terr=(%.3f,%.3f,%.3f L=%.3f)\n",
                            ptag, mode_name,
                            s_sky.roi_avg_r, s_sky.roi_avg_g, s_sky.roi_avg_b, s_sky.roi_avg_luma, s_sky.roi_min_luma,
                            s_terr.roi_avg_r, s_terr.roi_avg_g, s_terr.roi_avg_b, s_terr.roi_avg_luma);
            };

            // 0. Clear Day reference (for Night darkness comparison)
            MetalityWorldFrameUniforms u_day = make_high_uniforms(8.0f, 65.0f, 10.0f, 0.35f, 0.65f, -0.67f);
            eval_env_mode(pk, ptag, u_day, s_sky, s_terr);
            log_env("ClearDay");
            const float day_sky_luma = s_sky.roi_avg_luma;
            const float day_terr_luma = s_terr.roi_avg_luma;

            // 1. Night (Moon + Stars + Aurora + Milky Way; note ctex_hdr stores sRGB-encoded final_tonemap output)
            MetalityWorldFrameUniforms u_night = make_high_uniforms(8.0f, 65.0f, 10.0f, -0.25f, -0.68f, 0.68f);
            u_night.sky_color[0] = 0.02f; u_night.sky_color[1] = 0.03f; u_night.sky_color[2] = 0.08f; u_night.sky_color[3] = 1.0f;
            u_night.fog_color[0] = 0.04f; u_night.fog_color[1] = 0.06f; u_night.fog_color[2] = 0.12f; u_night.fog_color[3] = 0.25f;
            eval_env_mode(pk, ptag, u_night, s_sky, s_terr);
            log_env("Night");
            REQUIRE(s_sky.roi_avg_b >= s_sky.roi_avg_r && s_sky.roi_avg_luma < day_sky_luma && s_terr.roi_avg_luma < day_terr_luma,
                    "Night sky and terrain must be cooler and darker than Clear Day");

            // 2. Rain Storm (Puddles + Ripples + Storm Clouds)
            MetalityWorldFrameUniforms u_rain = make_high_uniforms(8.0f, 65.0f, 10.0f, 0.25f, 0.42f, -0.87f);
            u_rain.rain_strength = 1.0f;
            u_rain.thunder_strength = 0.6f;
            eval_env_mode(pk, ptag, u_rain, s_sky, s_terr);
            log_env("RainStorm");

            // 3. Golden Sunset (Godrays + Mie Horizon)
            MetalityWorldFrameUniforms u_set = make_high_uniforms(8.0f, 65.0f, 10.0f, 0.24f, 0.06f, -0.96f);
            u_set.sunrise_color[3] = 0.85f;
            eval_env_mode(pk, ptag, u_set, s_sky, s_terr);
            log_env("Sunset");
            REQUIRE(s_sky.roi_avg_r > s_sky.roi_avg_b, "Sunset sky must be warm amber-tinted");

            // 4. Nether Biomes: Crimson Wastes, Soul Sand Valley, Warped Forest
            MetalityWorldFrameUniforms u_crim = make_high_uniforms(8.0f, 65.0f, 10.0f, 0.2f, 0.5f, -0.8f);
            u_crim.dimension_id = -1;
            u_crim.fog_color[0] = 0.45f; u_crim.fog_color[1] = 0.08f; u_crim.fog_color[2] = 0.04f;
            eval_env_mode(pk, ptag, u_crim, s_sky, s_terr);
            log_env("NetherCrimson");
            REQUIRE(s_sky.roi_avg_r > s_sky.roi_avg_g && s_sky.roi_avg_r > s_sky.roi_avg_b, "Nether Crimson must be red-dominant");

            MetalityWorldFrameUniforms u_soul = u_crim;
            u_soul.fog_color[0] = 0.08f; u_soul.fog_color[1] = 0.36f; u_soul.fog_color[2] = 0.42f;
            eval_env_mode(pk, ptag, u_soul, s_sky, s_terr);
            log_env("NetherSoul");
            REQUIRE(s_sky.roi_avg_b > s_sky.roi_avg_r && s_sky.roi_avg_g > s_sky.roi_avg_r, "Nether Soul Sand Valley must be cyan/teal");

            MetalityWorldFrameUniforms u_warp = u_crim;
            u_warp.fog_color[0] = 0.28f; u_warp.fog_color[1] = 0.06f; u_warp.fog_color[2] = 0.34f;
            eval_env_mode(pk, ptag, u_warp, s_sky, s_terr);
            log_env("NetherWarped");
            REQUIRE(s_sky.roi_avg_b > s_sky.roi_avg_g, "Nether Warped Forest must retain violet-teal spectrum");

            // 5. The End (Gargantua Black Hole)
            MetalityWorldFrameUniforms u_end = make_high_uniforms(8.0f, 65.0f, 10.0f, 0.2f, 0.5f, -0.8f);
            u_end.dimension_id = 1;
            u_end.fog_color[0] = 0.14f; u_end.fog_color[1] = 0.06f; u_end.fog_color[2] = 0.24f;
            eval_env_mode(pk, ptag, u_end, s_sky, s_terr);
            log_env("TheEnd");
            REQUIRE(s_sky.roi_avg_b >= s_sky.roi_avg_g, "The End Gargantua sky must retain violet-blue spectrum");

            // 6. Underwater (submerged_mode == 1) — Snell's Window + Grazing TIR Ceiling Ring (cosU < 0.67 NaN Regression Guard!)
            MetalityWorldFrameUniforms u_uw = make_high_uniforms(8.0f, 61.2f, 10.0f, 0.28f, 0.78f, -0.56f);
            u_uw.submerged_mode = 1;
            eval_env_mode(pk, ptag, u_uw, s_sky, s_terr);
            log_env("UnderwaterTIR");
            const float uw_tir_min_luma = s_sky.roi_min_luma;
            REQUIRE(uw_tir_min_luma >= 0.02f && s_sky.roi_avg_b > s_sky.roi_avg_r,
                    "Underwater TIR ceiling and Snell window must have zero NaN/black pixels and teal-blue chroma");
            REQUIRE(s_terr.roi_avg_b > s_terr.roi_avg_r, "Underwater terrain must receive teal-blue absorption fog");

            // 7. Lava Submersion (submerged_mode == 2) — Both Sky and Terrain Pixels Must Receive Dense Orange-Red Lava Fog
            MetalityWorldFrameUniforms u_lava = make_high_uniforms(8.0f, 61.2f, 10.0f, 0.28f, 0.78f, -0.56f);
            u_lava.submerged_mode = 2;
            eval_env_mode(pk, ptag, u_lava, s_sky, s_terr);
            log_env("LavaSubmerged");
            REQUIRE(s_sky.roi_avg_r > s_sky.roi_avg_g * 1.25f && s_sky.roi_avg_g > s_sky.roi_avg_b,
                    "Lava submersion (submergedMode==2) must apply fiery orange-red lava fog to sky pixels too");
            REQUIRE(s_terr.roi_avg_r > s_terr.roi_avg_g * 1.25f && s_terr.roi_avg_g > s_terr.roi_avg_b,
                    "Lava submersion (submergedMode==2) must apply fiery orange-red lava fog to terrain pixels");
        }
    }

    // -------------------------------------------------------------------------
    // Regression Test Q: 300-Light Dense Lush Cave Tile-Cull Stress Test
    // 297 occluded cave lights (indices 0..296) + 3 local wall lights at worst-case tail indices (297..299).
    // Verifies deterministic Top-32 per-tile priority sorting yields ZERO black tile dropouts across Dusk & Zenith.
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_q = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_q != nullptr, "Failed to create Test Q scene");

        std::vector<MetalityBlockMaterialEntry> pal_q = palette;
        pal_q[2].emissive_level = 13; // each isolated 6-face cube -> pwr ~47.9, maxR ~27.4m, higher cam-dist score than tail wall lights

        // 1. Sealed cave chamber in sec_z=0 (world z=[0..15]), sec_y=3 (world y=[48..63]), sec_x in [-1..1]:
        //    Visible front stone wall at pz=4 (world z=[3..4], front face z=4.0),
        //    solid occluder wall behind camera at pz=16 (world z=[15..16]) blocking all 297 lights in sec_z=1,
        //    plus roof at py=16 (world y=63) and floor at py=1 (world y=48).
        std::vector<uint16_t> blocks_chamber(METALITY_PADDED_VOLUME, 0u);
        for (int py = 1; py <= 16; ++py) {
            for (int px = 1; px <= 16; ++px) {
                blocks_chamber[pad_idx(px, py, 4)] = 1u;  // visible wall facing +Z at world z=4.0
                blocks_chamber[pad_idx(px, py, 16)] = 1u; // solid occluder behind camera at world z=15..16
            }
        }
        for (int pz = 4; pz <= 16; ++pz) {
            for (int px = 1; px <= 16; ++px) {
                blocks_chamber[pad_idx(px, 1, pz)] = 1u;  // cave floor y=48
                blocks_chamber[pad_idx(px, 16, pz)] = 1u; // cave roof y=63 (pixelUnderRoof == true)
            }
        }
        MetalityChunkSectionInput in_ch{};
        in_ch.block_material_ids = blocks_chamber.data();
        in_ch.packed_light = light.data();
        in_ch.biome_tint_rgb = tint.data();
        in_ch.palette_table = pal_q.data();
        in_ch.palette_size = static_cast<uint32_t>(pal_q.size());
        in_ch.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_ch, &mout), "Test Q chamber meshing failed");
        for (int sx = -1; sx <= 1; ++sx) {
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, scene_q, sx, 3, 0,
                        q_solid.data(), mout.solid_quad_count,
                        q_cutout.data(), mout.cutout_quad_count,
                        q_trans.data(), mout.translucent_quad_count),
                    "Test Q chamber update failed");
        }

        // 2. Exactly 297 isolated emissive cubes in sec_z=1 (world z=[17.5..25.5]), each in its own 2x2x2 cluster bucket,
        //    within maxR (~27.4m) of every tile on the visible wall z=4.0, yet 100% occluded by the z=15..16 stone wall.
        std::vector<uint16_t> blocks_emit(METALITY_PADDED_VOLUME, 0u);
        uint32_t placed_emit = 0u;
        for (int lz = 1; lz <= 9 && placed_emit < 297u; lz += 2) {
            for (int ly = 0; ly <= 14 && placed_emit < 297u; ly += 2) {
                for (int lx = 0; lx <= 14 && placed_emit < 297u; lx += 2) {
                    blocks_emit[pad_idx(lx + 1, ly + 1, lz + 1)] = 2u;
                    ++placed_emit;
                }
            }
        }
        REQUIRE(placed_emit == 297u, "Expected exactly 297 occluded emissive blocks in Test Q");
        MetalityChunkSectionInput in_em{};
        in_em.block_material_ids = blocks_emit.data();
        in_em.packed_light = light.data();
        in_em.biome_tint_rgb = tint.data();
        in_em.palette_table = pal_q.data();
        in_em.palette_size = static_cast<uint32_t>(pal_q.size());
        in_em.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_em, &mout), "Test Q emissive meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_q, 0, 3, 1,
                    q_solid.data(), mout.solid_quad_count,
                    q_cutout.data(), mout.cutout_quad_count,
                    q_trans.data(), mout.translucent_quad_count),
                "Test Q emissive section update failed");

        // 3. 3 visible warm local wall lights at z=5.5 (1.5m in front of wall z=4.0, 8.5m from cam z=14.0).
        //    Their camera-distance score (~0.46..0.68) is strictly below all 297 occluded blocks (>=0.76),
        //    forcing them into worst-case tail indices 297, 298, 299 of emissiveLights[300],
        //    while their per-tile irradiance priority on z=4.0 ranks Top-3 in every wall tile!
        const float wall_lights_q[3 * 8] = {
            2.0f,  56.0f, 5.5f, 13.0f, 1.0f, 0.85f, 0.55f, 0.45f,
            8.0f,  56.0f, 5.5f, 13.0f, 1.0f, 0.85f, 0.55f, 0.45f,
            14.0f, 56.0f, 5.5f, 13.0f, 1.0f, 0.85f, 0.55f, 0.45f
        };
        metality_rt_scene_set_dynamic_lights(scene_q, wall_lights_q, 3u);
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_q, 8.0f, 56.0f, 14.0f), "Test Q TLAS failed");

        auto eval_300_light_cave = [&](MetalityPackHandle pk, const char* tag, float shadow_q) {
            for (int iter = 0; iter < 2; ++iter) {
                const uint64_t fid = metality_begin_frame(ctx);
                const float d_wall = 0.05f / 10.0f; // cam_z=14.0 -> world z=4.0 (front face of visible stone wall)
                metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.55f, 0.55f, 0.55f, 1.0f, dtex, d_wall);
                MetalityWorldFrameUniforms fu = make_high_uniforms(8.0f, 56.0f, 14.0f, 0.0f, -0.80f, -0.60f);
                fu.user_params[0] = shadow_q;
                fu.lighting_params[3] = 0.0f; // disable distance haze to isolate point lights
                REQUIRE(metality_pack_execute(ctx, pk, scene_q, ctex_hdr, dtex, &fu), "Test Q execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test Q timeout");
            }

            // ROI spans 170x72 px across 18 distinct 32x32 screen tiles on the lit cave wall
            const FrameHealthStats st = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, w / 6u, h / 4u, (w * 2u) / 3u, h / 2u, 0.10f, 0.0f);
            std::printf("[Test Q 300LightCave %-6s] MinLuma=%.3f AvgLuma=%.3f RGB=(%.3f,%.3f,%.3f) BlackRatio=%.4f\n",
                        tag, st.roi_min_luma, st.roi_avg_luma, st.roi_avg_r, st.roi_avg_g, st.roi_avg_b, st.roi_black_ratio);
            REQUIRE(st.roi_min_luma >= 0.08f,
                    "300-light dense cave must have ZERO black tile dropouts across the entire wall ROI");
            REQUIRE(st.roi_avg_r > st.roi_avg_b,
                    "Warm local wall lights at tail indices 297..299 must not be evicted by 297 occluded cave lights");
        };
        eval_300_light_cave(pack_dusk, "Dusk", 0.85f);
        eval_300_light_cave(pack_zenith, "Zenith", 1.0f);
        metality_rt_scene_destroy(scene_q);
    }

    // -------------------------------------------------------------------------
    // Regression Test Q2: Dense Recessed-Ceiling Overhang & Beam Tile-Cull Stress Test
    // Flat -Y wooden overhang/ceiling underside (y=57.0, normal=(0,-1,0)) directly below
    // 48 closer (2.5..8m) back-facing/occluded recessed ceiling lamps (y=59.5) +
    // 2 farther (6.5..11m) visible warm lower lights (y=50.5).
    // Verifies normal-aware soft-demotion + Top-63 + cheap-reject decoupled scan yields
    // ZERO 32x32 screen-tile black dropouts or tile-boundary staircases across Dusk & Zenith.
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_q2 = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_q2 != nullptr, "Failed to create Test Q2 scene");

        std::vector<MetalityBlockMaterialEntry> pal_q2 = palette;
        pal_q2[2].emissive_level = 15; // bright recessed ceiling lamps directly above the overhang

        std::vector<uint16_t> blocks_q2_wing(METALITY_PADDED_VOLUME, 0u);
        for (int pz = 1; pz <= 16; ++pz) {
            for (int px = 1; px <= 16; ++px) {
                blocks_q2_wing[pad_idx(px, 1, pz)] = 1u;  // floor y=48 (top y=49.0)
                blocks_q2_wing[pad_idx(px, 10, pz)] = 1u; // overhang slab y=57 (bottom face y=57.0, normal -Y)
                blocks_q2_wing[pad_idx(px, 16, pz)] = 1u; // outer sealed roof y=63
            }
        }
        for (int py = 1; py <= 16; ++py) {
            for (int px = 1; px <= 16; ++px) {
                blocks_q2_wing[pad_idx(px, py, 2)] = 1u;  // back wall z=1 (front face z=2.0)
                blocks_q2_wing[pad_idx(px, py, 16)] = 1u; // rear wall behind camera
            }
        }

        std::vector<uint16_t> blocks_q2_center = blocks_q2_wing;
        uint32_t placed_recessed = 0u;
        for (int pz = 3; pz <= 13; pz += 2) {
            for (int px = 1; px <= 15; px += 2) {
                blocks_q2_center[pad_idx(px, 12, pz)] = 2u; // world y=59.5 (2.5m above overhang y=57.0)
                ++placed_recessed;
            }
        }
        REQUIRE(placed_recessed == 48u, "Expected exactly 48 recessed ceiling lamps in Test Q2");

        auto upload_q2_sec = [&](int sx, const std::vector<uint16_t>& blks) {
            MetalityChunkSectionInput in_s{};
            in_s.block_material_ids = blks.data();
            in_s.packed_light = light.data();
            in_s.biome_tint_rgb = tint.data();
            in_s.palette_table = pal_q2.data();
            in_s.palette_size = static_cast<uint32_t>(pal_q2.size());
            in_s.enable_greedy_merge = 1;
            REQUIRE(metality_mesh_chunk_section_neon(&in_s, &mout), "Test Q2 meshing failed");
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, scene_q2, sx, 3, 0,
                        q_solid.data(), mout.solid_quad_count,
                        q_cutout.data(), mout.cutout_quad_count,
                        q_trans.data(), mout.translucent_quad_count),
                    "Test Q2 section update failed");
        };
        upload_q2_sec(-1, blocks_q2_wing);
        upload_q2_sec(0, blocks_q2_center);
        upload_q2_sec(1, blocks_q2_wing);

        // 2 farther visible warm lower lights below the overhang (y=50.5, 6.5..11m from overhang tiles)
        const float lower_lights_q2[2 * 8] = {
            2.5f,  50.5f, 7.0f, 38.0f, 1.0f, 0.84f, 0.54f, 0.55f,
            13.5f, 50.5f, 7.0f, 38.0f, 1.0f, 0.84f, 0.54f, 0.55f
        };
        metality_rt_scene_set_dynamic_lights(scene_q2, lower_lights_q2, 2u);

        // Camera 1.5m below y=57.0 overhang places the 90-degree back-wall crease at py~85,
        // keeping ty=2 (py=64..95) a true mixed-normal (+Z & -Y) tile row while moving py=96 11px away from the corner gradient.
        const float cam_q2x = 8.0f, cam_q2y = 55.5f, cam_q2z = 14.0f;
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_q2, cam_q2x, cam_q2y, cam_q2z), "Test Q2 TLAS failed");

        const std::vector<TestAabbBox> boxes_q2 = {
            {-16.0f, 57.0f, 1.0f, 32.0f, 58.0f, 15.0f, 0.55f, 0.45f, 0.34f, 1}, // -Y wooden overhang underside (y=57.0)
            {-16.0f, 48.0f, 1.0f, 32.0f, 49.0f, 15.0f, 0.45f, 0.45f, 0.45f, 2}, // floor (y=49.0)
            {-16.0f, 49.0f, 1.0f, 32.0f, 57.0f,  2.0f, 0.48f, 0.48f, 0.48f, 3}, // back wall (z=2.0)
        };
        std::vector<uint16_t> color_q2;
        std::vector<float> depth_q2;
        std::vector<int> tag_q2;
        rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_q2x, cam_q2y, cam_q2z, boxes_q2, color_q2, depth_q2, tag_q2);

        auto eval_q2_overhang = [&](MetalityPackHandle pk, const char* tag) {
            for (int iter = 0; iter < 3; ++iter) {
                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, color_q2.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, depth_q2.data(), w * 4u);
                MetalityWorldFrameUniforms fu = make_high_uniforms(cam_q2x, cam_q2y, cam_q2z, 0.0f, -0.80f, -0.60f);
                fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                fu.user_params[0] = 0.0f; // disable bloom to isolate pure tile-culled lighting
                fu.lighting_params[3] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pk, scene_q2, ctex_hdr, dtex, &fu), "Test Q2 execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test Q2 timeout");
            }
            (void)assert_frame_healthy(rb_hdr_ptr, w, h, tag, w / 8u, (h * 3u) / 4u, (w * 3u) / 4u, h / 6u, 0.12f, 0.0f);

            const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
            auto px_luma = [&](uint32_t x, uint32_t y) -> float {
                const size_t i = static_cast<size_t>(y) * w + x;
                return 0.2126f * decode_fp16(p16[i * 4u + 0u]) +
                       0.7152f * decode_fp16(p16[i * 4u + 1u]) +
                       0.0722f * decode_fp16(p16[i * 4u + 2u]);
            };
            double l_sum = 0.0, r_sum = 0.0, b_sum = 0.0;
            float l_min = 1e9f, max_tile_step_x = 0.0f, max_tile_step_y = 0.0f;
            uint32_t cnt = 0u, blk = 0u;
            for (uint32_t py = 2; py + 2 < h; ++py) {
                for (uint32_t px = 2; px + 2 < w; ++px) {
                    const size_t pidx = static_cast<size_t>(py) * w + px;
                    if (tag_q2[pidx] != 1) continue;
                    if (tag_q2[pidx - 2u] != 1 || tag_q2[pidx + 2u] != 1 ||
                        tag_q2[pidx - 2u * w] != 1 || tag_q2[pidx + 2u * w] != 1) {
                        continue;
                    }
                    const float r = decode_fp16(p16[pidx * 4u + 0u]);
                    const float g = decode_fp16(p16[pidx * 4u + 1u]);
                    const float b = decode_fp16(p16[pidx * 4u + 2u]);
                    const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    l_sum += l;
                    r_sum += r;
                    b_sum += b;
                    l_min = std::min(l_min, l);
                    if (l < 0.03f) ++blk;
                    ++cnt;
                    if ((px & 31u) == 0u) {
                        max_tile_step_x = std::max(max_tile_step_x, std::fabs(px_luma(px + 1u, py) - px_luma(px - 1u, py)));
                    }
                    if ((py & 31u) == 0u) {
                        max_tile_step_y = std::max(max_tile_step_y, std::fabs(px_luma(px, py + 1u) - px_luma(px, py - 1u)));
                    }
                }
            }
            const float avg_l = (cnt > 0u) ? static_cast<float>(l_sum / cnt) : 0.0f;
            const float blk_ratio = (cnt > 0u) ? (static_cast<float>(blk) / static_cast<float>(cnt)) : 1.0f;
            const float max_tile_step = std::max(max_tile_step_x, max_tile_step_y);
            std::printf("[Test Q2 Overhang48L %-6s] MinLuma=%.3f AvgLuma=%.3f BlackRatio=%.4f TileStepX=%.4f TileStepY=%.4f (n=%u)\n",
                        tag, l_min, avg_l, blk_ratio, max_tile_step_x, max_tile_step_y, cnt);
            REQUIRE(cnt >= 4000u, "Test Q2 must resolve multi-tile -Y overhang ROI");
            REQUIRE(blk == 0u && l_min >= 0.12f,
                    "48 closer back-facing recessed ceiling lamps must NEVER evict visible lower lights on a -Y overhang");
            REQUIRE(max_tile_step < 0.04f,
                    "32x32 screen-tile boundaries on -Y overhang must be smooth with ZERO pixel-block staircase jumps");
            REQUIRE(r_sum > b_sum,
                    "Overhang underside must receive warm lower-light illumination across all 32x32 tiles");
        };
        eval_q2_overhang(pack_dusk, "Dusk");
        eval_q2_overhang(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_q2);
    }

    // -------------------------------------------------------------------------
    // Regression Test J2: True 3D Concave Floor-Wall & Ceiling-Wall Crease Light-Leak Guard
    //                     + Framed Stained-Glass Sunbeam Penumbra Chroma Purity (Zero White Halo)
    // -------------------------------------------------------------------------
    {
        // J2a: Sealed 1-block-walled room with true 3D floor-wall & ceiling-wall 90-degree creases
        // and intense adjacent-room orange lights 0.45m outside the 1-block front wall.
        MetalityRtSceneHandle scene_j2a = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_j2a != nullptr, "Failed to create Test J2a scene");

        std::vector<uint16_t> blocks_j2a(METALITY_PADDED_VOLUME, 0u);
        for (int pz = 5; pz <= 15; ++pz) {
            for (int px = 4; px <= 13; ++px) {
                blocks_j2a[pad_idx(px, 5, pz)] = 1u;  // floor y=52 (top face y=53.0)
                blocks_j2a[pad_idx(px, 9, pz)] = 1u;  // ceiling y=56 (bottom face y=56.0)
                for (int py = 6; py <= 8; ++py) {
                    if (px == 4 || px == 13 || pz == 5 || pz == 15) {
                        blocks_j2a[pad_idx(px, py, pz)] = 1u; // 1-block front wall at z=[4..5]
                    }
                }
            }
        }
        std::vector<MetalityPackedQuad> q_s_j2(4096), q_c_j2(4096), q_t_j2(4096);
        MetalityMeshedSectionOutput mout_j2{};
        mout_j2.solid_quads = q_s_j2.data(); mout_j2.solid_quad_capacity = 4096;
        mout_j2.cutout_quads = q_c_j2.data(); mout_j2.cutout_quad_capacity = 4096;
        mout_j2.translucent_quads = q_t_j2.data(); mout_j2.translucent_quad_capacity = 4096;

        MetalityChunkSectionInput in_j2a{};
        in_j2a.block_material_ids = blocks_j2a.data();
        in_j2a.packed_light = light.data();
        in_j2a.biome_tint_rgb = tint.data();
        in_j2a.palette_table = palette.data();
        in_j2a.palette_size = static_cast<uint32_t>(palette.size());
        in_j2a.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_j2a, &mout_j2), "Test J2a meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_j2a, 0, 3, 0,
                    q_s_j2.data(), mout_j2.solid_quad_count,
                    q_c_j2.data(), mout_j2.cutout_quad_count,
                    q_t_j2.data(), mout_j2.translucent_quad_count),
                "Test J2a update failed");

        const float lights_j2a[3 * 8] = {
            8.0f, 53.25f, 3.55f, 65.0f, 1.0f, 0.48f, 0.12f, 0.45f,
            8.0f, 55.75f, 3.55f, 65.0f, 1.0f, 0.48f, 0.12f, 0.45f,
            8.0f, 54.50f, 3.55f, 65.0f, 1.0f, 0.48f, 0.12f, 0.45f
        };
        metality_rt_scene_set_dynamic_lights(scene_j2a, lights_j2a, 3u);
        const float cam_j2a_x = 8.0f, cam_j2a_y = 54.5f, cam_j2a_z = 8.2f;
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_j2a, cam_j2a_x, cam_j2a_y, cam_j2a_z), "Test J2a TLAS failed");

        const std::vector<TestAabbBox> boxes_j2a = {
            {3.0f, 52.0f, 4.0f, 13.0f, 57.0f, 5.0f, 0.50f, 0.50f, 0.50f, 1}, // front wall z=5.0
            {3.0f, 52.0f, 5.0f, 13.0f, 53.0f, 14.0f, 0.50f, 0.50f, 0.50f, 2}, // floor y=53.0
            {3.0f, 56.0f, 5.0f, 13.0f, 57.0f, 14.0f, 0.50f, 0.50f, 0.50f, 3}, // ceiling y=56.0
            {3.0f, 52.0f, 5.0f,  4.0f, 57.0f, 14.0f, 0.50f, 0.50f, 0.50f, 4}, // left wall
            {12.0f, 52.0f, 5.0f, 13.0f, 57.0f, 14.0f, 0.50f, 0.50f, 0.50f, 4}, // right wall
        };
        std::vector<uint16_t> color_j2a;
        std::vector<float> depth_j2a;
        std::vector<int> tag_j2a;
        rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_j2a_x, cam_j2a_y, cam_j2a_z,
                             boxes_j2a, color_j2a, depth_j2a, tag_j2a);

        const float f_j2 = 1.0f / std::tan(1.22f * 0.5f);
        const float aspect_j2 = static_cast<float>(w) / static_cast<float>(h);

        auto eval_j2a_creases = [&](MetalityPackHandle pk, const char* tag) {
            for (int iter = 0; iter < 2; ++iter) {
                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, color_j2a.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, depth_j2a.data(), w * 4u);
                MetalityWorldFrameUniforms fu = make_high_uniforms(cam_j2a_x, cam_j2a_y, cam_j2a_z, 0.0f, -0.75f, -0.66f);
                fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                fu.user_params[0] = 0.0f;
                fu.lighting_params[3] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pk, scene_j2a, ctex_hdr, dtex, &fu), "Test J2a execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test J2a timeout");
            }
            (void)assert_frame_healthy(rb_hdr_ptr, w, h, tag, w / 4u, h / 4u, w / 2u, h / 2u, 0.0f, 1.0f);

            const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
            float floor_max_l = 0.0f, ceil_max_l = 0.0f, wall_max_l = 0.0f;
            float floor_max_rb = 0.0f, ceil_max_rb = 0.0f;
            uint32_t floor_cnt = 0u, ceil_cnt = 0u;
            for (uint32_t py = 2; py + 2 < h; ++py) {
                const float dy = (((static_cast<float>(py) + 0.5f) / static_cast<float>(h)) * 2.0f - 1.0f) / f_j2;
                for (uint32_t px = w / 4u; px < (w * 3u) / 4u; ++px) {
                    const size_t pidx = static_cast<size_t>(py) * w + px;
                    if (depth_j2a[pidx] <= 0.0f) continue;
                    const float t_z = 0.05f / depth_j2a[pidx];
                    const float dx = (((static_cast<float>(px) + 0.5f) / static_cast<float>(w)) * 2.0f - 1.0f) * (aspect_j2 / f_j2);
                    const float wx = cam_j2a_x + t_z * dx;
                    const float wy = cam_j2a_y + t_z * dy;
                    const float wz = cam_j2a_z - t_z;
                    const float r = decode_fp16(p16[pidx * 4u + 0u]);
                    const float g = decode_fp16(p16[pidx * 4u + 1u]);
                    const float b = decode_fp16(p16[pidx * 4u + 2u]);
                    const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    if (wx >= 5.5f && wx <= 10.5f) {
                        if (std::fabs(wy - 53.0f) < 0.25f && std::fabs(wz - 5.0f) < 0.25f) {
                            floor_max_l = std::max(floor_max_l, l);
                            floor_max_rb = std::max(floor_max_rb, r - b);
                            ++floor_cnt;
                        } else if (std::fabs(wy - 56.0f) < 0.25f && std::fabs(wz - 5.0f) < 0.25f) {
                            ceil_max_l = std::max(ceil_max_l, l);
                            ceil_max_rb = std::max(ceil_max_rb, r - b);
                            ++ceil_cnt;
                        } else if (tag_j2a[pidx] == 1) {
                            wall_max_l = std::max(wall_max_l, l);
                        }
                    }
                }
            }
            std::printf("[Test J2a CreaseLeak %-6s] FloorCreaseMaxL=%.3f(n=%u,rb=%.3f) | CeilCreaseMaxL=%.3f(n=%u,rb=%.3f) | WallMaxL=%.3f\n",
                        tag, floor_max_l, floor_cnt, floor_max_rb, ceil_max_l, ceil_cnt, ceil_max_rb, wall_max_l);
            REQUIRE(floor_cnt >= 40u && ceil_cnt >= 40u, "Test J2a must sample both 90-degree floor-wall and ceiling-wall creases");
            REQUIRE(floor_max_l < 0.22f && ceil_max_l < 0.22f && wall_max_l < 0.22f,
                    "1-block wall concave floor-wall and ceiling-wall creases must block intense adjacent-room lights");
            REQUIRE(floor_max_rb < 0.08f && ceil_max_rb < 0.08f,
                    "Concave 90-degree creases must not show orange warm-light leak seams from behind a 1-block wall");
        };
        eval_j2a_creases(pack_dusk, "Dusk");
        eval_j2a_creases(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_j2a);

        // J2b: Opaque-framed 2x2 red stained-glass sunbeam casting onto a neutral gray wall —
        // verifies crimson beam chroma AND zero neutral white halo pixels around the penumbra border.
        MetalityRtSceneHandle scene_j2b = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_j2b != nullptr, "Failed to create Test J2b scene");

        std::vector<MetalityBlockMaterialEntry> pal_j2b = palette;
        pal_j2b.resize(4);
        for (int f = 0; f < 6; ++f) pal_j2b[3].face_sprites[f] = 3;
        pal_j2b[3].layer = METALITY_LAYER_TRANSLUCENT;
        pal_j2b[3].is_opaque_full_cube = 0;
        pal_j2b[3].wave_tag = 8;
        pal_j2b[3].emissive_level = 2; // red stained glass
        pal_j2b[3].alpha = 165;

        std::vector<uint16_t> blocks_j2b(METALITY_PADDED_VOLUME, 0u);
        for (int pz = 5; pz <= 11; ++pz) {
            for (int px = 1; px <= 16; ++px) {
                blocks_j2b[pad_idx(px, 1, pz)] = 1u;  // floor y=48
                blocks_j2b[pad_idx(px, 16, pz)] = 1u; // roof y=63
            }
        }
        for (int py = 2; py <= 15; ++py) {
            for (int px = 1; px <= 16; ++px) {
                blocks_j2b[pad_idx(px, py, 5)] = 1u; // receiving back wall z=[4..5], interior face z=5.0
                if (px >= 8 && px <= 9 && py >= 8 && py <= 9) {
                    blocks_j2b[pad_idx(px, py, 11)] = 3u; // 2x2 red stained-glass window at x=[7..8], y=[55..56], z=10
                } else {
                    blocks_j2b[pad_idx(px, py, 11)] = 1u; // opaque frame surrounding stained glass
                }
            }
            for (int pz = 6; pz <= 10; ++pz) {
                blocks_j2b[pad_idx(1, py, pz)] = 1u;
                blocks_j2b[pad_idx(16, py, pz)] = 1u;
            }
        }
        MetalityChunkSectionInput in_j2b{};
        in_j2b.block_material_ids = blocks_j2b.data();
        in_j2b.packed_light = light.data();
        in_j2b.biome_tint_rgb = tint.data();
        in_j2b.palette_table = pal_j2b.data();
        in_j2b.palette_size = static_cast<uint32_t>(pal_j2b.size());
        in_j2b.enable_greedy_merge = 1;
        REQUIRE(metality_mesh_chunk_section_neon(&in_j2b, &mout_j2), "Test J2b meshing failed");
        REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, scene_j2b, 0, 3, 0,
                    q_s_j2.data(), mout_j2.solid_quad_count,
                    q_c_j2.data(), mout_j2.cutout_quad_count,
                    q_t_j2.data(), mout_j2.translucent_quad_count),
                "Test J2b update failed");

        const float cam_j2b_x = 8.0f, cam_j2b_y = 54.64f, cam_j2b_z = 8.6f;
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_j2b, cam_j2b_x, cam_j2b_y, cam_j2b_z), "Test J2b TLAS failed");

        auto eval_j2b_penumbra = [&](MetalityPackHandle pk, const char* tag) {
            for (int iter = 0; iter < 2; ++iter) {
                const uint64_t fid = metality_begin_frame(ctx);
                metality_cmd_clear_color_depth(ctx, ctex_hdr, 0.55f, 0.55f, 0.55f, 1.0f, dtex, 0.05f / 3.6f);
                MetalityWorldFrameUniforms fu = make_high_uniforms(cam_j2b_x, cam_j2b_y, cam_j2b_z, 0.0f, 0.24f, 0.97f);
                fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                fu.user_params[0] = 0.0f;
                fu.lighting_params[3] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pk, scene_j2b, ctex_hdr, dtex, &fu), "Test J2b execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test J2b timeout");
            }
            const FrameHealthStats st_ctr = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, (w / 2u) - 8u, (h / 2u) - 8u, 16u, 16u, 0.08f, 0.0f);
            const FrameHealthStats st_shd = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, w / 8u, h / 8u, 16u, 16u, 0.001f, 0.0f);

            const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
            uint32_t lit_cnt = 0u, white_halo_cnt = 0u;
            for (uint32_t py = h / 6u; py < (h * 5u) / 6u; ++py) {
                for (uint32_t px = w / 6u; px < (w * 5u) / 6u; ++px) {
                    const size_t base = (static_cast<size_t>(py) * w + px) * 4u;
                    const float r = decode_fp16(p16[base + 0u]);
                    const float g = decode_fp16(p16[base + 1u]);
                    const float b = decode_fp16(p16[base + 2u]);
                    const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    if (l > st_shd.roi_avg_luma + 0.06f) {
                        ++lit_cnt;
                        if (r <= b * 1.06f) {
                            ++white_halo_cnt;
                        }
                    }
                }
            }
            std::printf("[Test J2b GlassPenumbra %-6s] CtrRGB=(%.3f,%.3f,%.3f L=%.3f) | ShdL=%.3f | LitPx=%u WhiteHaloPx=%u\n",
                        tag, st_ctr.roi_avg_r, st_ctr.roi_avg_g, st_ctr.roi_avg_b, st_ctr.roi_avg_luma,
                        st_shd.roi_avg_luma, lit_cnt, white_halo_cnt);
            REQUIRE(st_ctr.roi_avg_luma > st_shd.roi_avg_luma + 0.05f && st_ctr.roi_avg_r > st_ctr.roi_avg_b * 1.20f,
                    "Framed red stained-glass sunbeam must illuminate center wall with strong crimson tint");
            REQUIRE(lit_cnt >= 60u && white_halo_cnt == 0u,
                    "Stained-glass sunbeam penumbra border must preserve red chroma with ZERO neutral white halo pixels");
        };
        eval_j2b_penumbra(pack_dusk, "Dusk");
        eval_j2b_penumbra(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_j2b);
    }

    // -------------------------------------------------------------------------
    // Regression Test R: Recessed 1x2 Cobblestone Doorway — Closed Door (Zero Exterior Bowtie Leak
    //                    + Clean Interior Lighting) vs Open Door (Full Exterior Light Pass-Through)
    // -------------------------------------------------------------------------
    {
        MetalityRtSceneHandle scene_r = metality_rt_scene_create(ctx, 16u, false);
        REQUIRE(scene_r != nullptr, "Failed to create Test R scene");

        std::vector<MetalityBlockMaterialEntry> pal_r = palette;
        pal_r.resize(5);
        // Material 3: Closed wooden door (-Z flush 3/16m panel: tag=14, sub=5)
        for (int f = 0; f < 6; ++f) pal_r[3].face_sprites[f] = 3;
        pal_r[3].layer = METALITY_LAYER_CUTOUT;
        pal_r[3].is_opaque_full_cube = 0;
        pal_r[3].wave_tag = 14;
        pal_r[3].emissive_level = 5;
        pal_r[3].alpha = 255;
        // Material 4: Open wooden door (-X flush 3/16m panel swung onto left jamb: tag=14, sub=1)
        for (int f = 0; f < 6; ++f) pal_r[4].face_sprites[f] = 3;
        pal_r[4].layer = METALITY_LAYER_CUTOUT;
        pal_r[4].is_opaque_full_cube = 0;
        pal_r[4].wave_tag = 14;
        pal_r[4].emissive_level = 1;
        pal_r[4].alpha = 255;

        std::vector<MetalityPackedQuad> q_s_r(4096), q_c_r(4096), q_t_r(4096);
        MetalityMeshedSectionOutput mout_r{};
        mout_r.solid_quads = q_s_r.data(); mout_r.solid_quad_capacity = 4096;
        mout_r.cutout_quads = q_c_r.data(); mout_r.cutout_quad_capacity = 4096;
        mout_r.translucent_quads = q_t_r.data(); mout_r.translucent_quad_capacity = 4096;

        auto upload_doorway_scene = [&](uint16_t door_mat_id) {
            std::vector<uint16_t> blocks_r(METALITY_PADDED_VOLUME, 0u);
            for (int pz = 6; pz <= 13; ++pz) {
                for (int px = 5; px <= 13; ++px) {
                    blocks_r[pad_idx(px, 6, pz)] = 1u;  // floor y=53 (top y=54.0)
                    blocks_r[pad_idx(px, 11, pz)] = 1u; // ceiling y=58 (bottom y=58.0)
                    for (int py = 7; py <= 10; ++py) {
                        if (px == 5 || px == 13 || pz == 6 || pz == 13) {
                            blocks_r[pad_idx(px, py, pz)] = 1u;
                        }
                    }
                }
            }
            // Recessed 1x2 doorway at world x=8, y=54..55, z=5 (px=9, py=7..8, pz=6)
            blocks_r[pad_idx(9, 7, 6)] = door_mat_id;
            blocks_r[pad_idx(9, 8, 6)] = door_mat_id;
            // Exterior back wall at z=2 (pz=3) so rays through open doorway hit solid geometry
            for (int py = 6; py <= 11; ++py) {
                for (int px = 5; px <= 13; ++px) {
                    blocks_r[pad_idx(px, py, 3)] = 1u;
                }
            }
            MetalityChunkSectionInput in_r{};
            in_r.block_material_ids = blocks_r.data();
            in_r.packed_light = light.data();
            in_r.biome_tint_rgb = tint.data();
            in_r.palette_table = pal_r.data();
            in_r.palette_size = static_cast<uint32_t>(pal_r.size());
            in_r.enable_greedy_merge = 1;
            REQUIRE(metality_mesh_chunk_section_neon(&in_r, &mout_r), "Test R meshing failed");
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, scene_r, 0, 3, 0,
                        q_s_r.data(), mout_r.solid_quad_count,
                        q_c_r.data(), mout_r.cutout_quad_count,
                        q_t_r.data(), mout_r.translucent_quad_count),
                    "Test R update failed");
        };

        const float cam_rx = 8.5f, cam_ry = 55.0f, cam_rz = 7.6f;
        const std::vector<TestAabbBox> boxes_r_closed = {
            {4.0f, 53.0f, 5.0f,  8.0f, 58.0f,  6.0f, 0.48f, 0.48f, 0.48f, 1}, // left wall + left doorway jamb
            {9.0f, 53.0f, 5.0f, 13.0f, 58.0f,  6.0f, 0.48f, 0.48f, 0.48f, 1}, // right wall + right doorway jamb
            {4.0f, 53.0f, 5.0f, 13.0f, 54.0f, 12.0f, 0.48f, 0.48f, 0.48f, 1}, // floor + doorway threshold
            {4.0f, 56.0f, 5.0f, 13.0f, 58.0f,  6.0f, 0.48f, 0.48f, 0.48f, 1}, // doorway lintel + upper wall
            {4.0f, 58.0f, 6.0f, 13.0f, 59.0f, 12.0f, 0.48f, 0.48f, 0.48f, 3}, // ceiling
            {8.0f, 54.0f, 5.0f,  9.0f, 56.0f,  5.1875f, 0.52f, 0.38f, 0.24f, 2}, // closed wooden door (-Z panel)
        };
        const std::vector<TestAabbBox> boxes_r_open = {
            {4.0f, 53.0f, 5.0f,  8.0f, 58.0f,  6.0f, 0.48f, 0.48f, 0.48f, 1}, // left wall
            {9.0f, 53.0f, 5.0f, 13.0f, 58.0f,  6.0f, 0.48f, 0.48f, 0.48f, 1}, // right wall + right jamb
            {4.0f, 53.0f, 3.0f, 13.0f, 54.0f, 12.0f, 0.48f, 0.48f, 0.48f, 1}, // floor + threshold
            {4.0f, 56.0f, 5.0f, 13.0f, 58.0f,  6.0f, 0.48f, 0.48f, 0.48f, 1}, // lintel
            {4.0f, 58.0f, 6.0f, 13.0f, 59.0f, 12.0f, 0.48f, 0.48f, 0.48f, 3}, // ceiling
            {8.0f, 54.0f, 5.0f,  8.1875f, 56.0f, 6.0f, 0.52f, 0.38f, 0.24f, 2}, // open wooden door (-X panel)
            {4.0f, 53.0f, 2.0f, 13.0f, 58.0f,  3.0f, 0.48f, 0.48f, 0.48f, 4}, // exterior back wall
        };
        std::vector<uint16_t> color_rc, color_ro;
        std::vector<float> depth_rc, depth_ro;
        std::vector<int> tag_rc, tag_ro;
        rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_rx, cam_ry, cam_rz, boxes_r_closed, color_rc, depth_rc, tag_rc);
        rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_rx, cam_ry, cam_rz, boxes_r_open, color_ro, depth_ro, tag_ro);

        struct DoorwayStats {
            float jamb_avg = 0.0f, jamb_min = 1e9f, jamb_max = 0.0f, jamb_avg_r = 0.0f, jamb_avg_b = 0.0f;
            float door_avg = 0.0f, door_min = 1e9f, door_max = 0.0f, door_avg_r = 0.0f, door_avg_b = 0.0f;
            uint32_t jamb_cnt = 0u, door_cnt = 0u;
        };

        auto run_doorway_case = [&](MetalityPackHandle pk, const char* label,
                                    uint16_t door_mat, const std::vector<uint16_t>& cimg,
                                    const std::vector<float>& dimg, const std::vector<int>& timg,
                                    const float* dlights, uint32_t num_lights, float z_min_jamb) -> DoorwayStats {
            upload_doorway_scene(door_mat);
            metality_rt_scene_set_dynamic_lights(scene_r, dlights, num_lights);
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_r, cam_rx, cam_ry, cam_rz), "Test R TLAS failed");

            for (int iter = 0; iter < 2; ++iter) {
                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, cimg.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, dimg.data(), w * 4u);
                MetalityWorldFrameUniforms fu = make_high_uniforms(cam_rx, cam_ry, cam_rz, 0.0f, -0.75f, -0.66f);
                fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                fu.user_params[0] = 0.0f;
                fu.lighting_params[3] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pk, scene_r, ctex_hdr, dtex, &fu), "Test R execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test R timeout");
            }
            (void)assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 4u, h / 4u, w / 2u, h / 2u, 0.0f, 1.0f);

            const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
            DoorwayStats st{};
            double j_l = 0.0, j_r = 0.0, j_b = 0.0;
            double d_l = 0.0, d_r = 0.0, d_b = 0.0;
            for (uint32_t py = 2; py + 2 < h; ++py) {
                for (uint32_t px = 2; px + 2 < w; ++px) {
                    const size_t pidx = static_cast<size_t>(py) * w + px;
                    if (dimg[pidx] <= 0.0f) continue;
                    const float wz = cam_rz - (0.05f / dimg[pidx]);
                    const float r = decode_fp16(p16[pidx * 4u + 0u]);
                    const float g = decode_fp16(p16[pidx * 4u + 1u]);
                    const float b = decode_fp16(p16[pidx * 4u + 2u]);
                    const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    if (timg[pidx] == 1 && wz > z_min_jamb && wz < 5.96f) {
                        j_l += l; j_r += r; j_b += b;
                        st.jamb_min = std::min(st.jamb_min, l);
                        st.jamb_max = std::max(st.jamb_max, l);
                        ++st.jamb_cnt;
                    } else if (timg[pidx] == 2) {
                        d_l += l; d_r += r; d_b += b;
                        st.door_min = std::min(st.door_min, l);
                        st.door_max = std::max(st.door_max, l);
                        ++st.door_cnt;
                    }
                }
            }
            if (st.jamb_cnt > 0u) {
                st.jamb_avg = static_cast<float>(j_l / st.jamb_cnt);
                st.jamb_avg_r = static_cast<float>(j_r / st.jamb_cnt);
                st.jamb_avg_b = static_cast<float>(j_b / st.jamb_cnt);
            }
            if (st.door_cnt > 0u) {
                st.door_avg = static_cast<float>(d_l / st.door_cnt);
                st.door_avg_r = static_cast<float>(d_r / st.door_cnt);
                st.door_avg_b = static_cast<float>(d_b / st.door_cnt);
            }
            return st;
        };

        const float ext_flank_torches[2 * 8] = {
            7.4f, 55.0f, 4.4f, 50.0f, 1.0f, 0.60f, 0.18f, 0.30f,
            9.6f, 55.0f, 4.4f, 50.0f, 1.0f, 0.60f, 0.18f, 0.30f
        };
        const float int_torch[8] = {8.5f, 55.2f, 7.0f, 28.0f, 1.0f, 0.72f, 0.32f, 0.30f};
        const float ext_center_torch[8] = {8.5f, 55.0f, 3.8f, 42.0f, 1.0f, 0.72f, 0.32f, 0.30f};

        for (int pk_i = 0; pk_i < 2; ++pk_i) {
            MetalityPackHandle pk = (pk_i == 0) ? pack_dusk : pack_zenith;
            const char* ptag = (pk_i == 0) ? "Dusk" : "Zenith";

            const DoorwayStats r1 = run_doorway_case(pk, ptag, 3u, color_rc, depth_rc, tag_rc, ext_flank_torches, 2u, 5.21f);
            const DoorwayStats r2 = run_doorway_case(pk, ptag, 3u, color_rc, depth_rc, tag_rc, int_torch, 1u, 5.21f);
            const DoorwayStats r3 = run_doorway_case(pk, ptag, 4u, color_ro, depth_ro, tag_ro, ext_center_torch, 1u, 5.05f);

            std::printf("[Test R Doorway %-6s] ClosedExt Jamb(avg=%.3f,max=%.3f,n=%u) Door(max=%.3f,n=%u) | "
                        "ClosedInt Jamb(avg=%.3f,min=%.3f) Door(avg=%.3f,min=%.3f) | OpenExt Jamb(avg=%.3f,R=%.3f,B=%.3f)\n",
                        ptag, r1.jamb_avg, r1.jamb_max, r1.jamb_cnt, r1.door_max, r1.door_cnt,
                        r2.jamb_avg, r2.jamb_min, r2.door_avg, r2.door_min,
                        r3.jamb_avg, r3.jamb_avg_r, r3.jamb_avg_b);

            REQUIRE(r1.jamb_cnt >= 200u && r1.door_cnt >= 400u && r3.jamb_cnt >= 150u,
                    "Test R must resolve all inner doorway jambs and recessed door surface");
            REQUIRE(r1.jamb_avg < 0.14f && r1.jamb_max < 0.22f && r1.door_max < 0.22f,
                    "Closed thin wooden door must block exterior flanking torches with ZERO bowtie leak on inner doorway faces");
            REQUIRE(r2.jamb_avg > 0.16f && r2.jamb_min >= 0.04f && r2.door_avg > 0.20f && r2.door_min >= 0.06f && r2.door_avg_r > r2.door_avg_b,
                    "Interior torch must warmly illuminate recessed inner doorway faces and closed door (no phantom z=6.0 front quad)");
            REQUIRE(r3.jamb_avg > r1.jamb_avg + 0.12f && r3.jamb_avg_r > r3.jamb_avg_b,
                    "Open thin wooden door must allow exterior torch light to pass cleanly onto inner doorway jambs");
        }
        metality_rt_scene_destroy(scene_r);
    }

    // -------------------------------------------------------------------------
    // Regression Test S: Daytime Dense Spruce Forest Canopy (Non-Crushed Floor/Trunk Ambient)
    //                    + Shaded Forest Pond vs Open Sunlit Pond Water Occlusion & Smoothness
    // -------------------------------------------------------------------------
    {
        std::vector<MetalityBlockMaterialEntry> pal_s = palette;
        pal_s.resize(5);
        // Material 3: Spruce leaves (tag=2 cutout)
        for (int f = 0; f < 6; ++f) pal_s[3].face_sprites[f] = 3;
        pal_s[3].layer = METALITY_LAYER_CUTOUT;
        pal_s[3].is_opaque_full_cube = 0;
        pal_s[3].wave_tag = 2;
        pal_s[3].emissive_level = 0;
        pal_s[3].alpha = 255;
        // Material 4: Still pond water (tag=3, sub=8 translucent)
        for (int f = 0; f < 6; ++f) pal_s[4].face_sprites[f] = 4;
        pal_s[4].layer = METALITY_LAYER_TRANSLUCENT;
        pal_s[4].is_opaque_full_cube = 0;
        pal_s[4].wave_tag = 3;
        pal_s[4].emissive_level = 8;
        pal_s[4].alpha = 180;

        std::vector<MetalityPackedQuad> q_s_s(4096), q_c_s(4096), q_t_s(4096);
        MetalityMeshedSectionOutput mout_s{};
        mout_s.solid_quads = q_s_s.data(); mout_s.solid_quad_capacity = 4096;
        mout_s.cutout_quads = q_c_s.data(); mout_s.cutout_quad_capacity = 4096;
        mout_s.translucent_quads = q_t_s.data(); mout_s.translucent_quad_capacity = 4096;

        // S1: 3x1x3 (48x16x48m) dense spruce canopy overhead + solid forest floor & spruce trunk
        MetalityRtSceneHandle scene_s1 = metality_rt_scene_create(ctx, 32u, false);
        REQUIRE(scene_s1 != nullptr, "Failed to create Test S1 scene");
        for (int sz = -1; sz <= 1; ++sz) {
            for (int sx = -1; sx <= 1; ++sx) {
                std::vector<uint16_t> blocks_s1(METALITY_PADDED_VOLUME, 0u);
                for (int pz = 1; pz <= 16; ++pz) {
                    for (int px = 1; px <= 16; ++px) {
                        blocks_s1[pad_idx(px, 2, pz)] = 1u; // soil y=49
                        blocks_s1[pad_idx(px, 3, pz)] = 1u; // forest floor y=50 (top y=51.0)
                        for (int py = 8; py <= 15; ++py) {
                            blocks_s1[pad_idx(px, py, pz)] = 3u; // 8-layer dense spruce canopy y=55..62
                        }
                    }
                }
                if (sx == 0 && sz == 0) {
                    for (int py = 4; py <= 15; ++py) {
                        blocks_s1[pad_idx(9, py, 5)] = 1u; // center spruce trunk x=8, z=4, y=51..62
                    }
                }
                MetalityChunkSectionInput in_s1{};
                in_s1.block_material_ids = blocks_s1.data();
                in_s1.packed_light = light.data();
                in_s1.biome_tint_rgb = tint.data();
                in_s1.palette_table = pal_s.data();
                in_s1.palette_size = static_cast<uint32_t>(pal_s.size());
                in_s1.enable_greedy_merge = 1;
                REQUIRE(metality_mesh_chunk_section_neon(&in_s1, &mout_s), "Test S1 meshing failed");
                REQUIRE(metality_rt_scene_update_section_v2(
                            ctx, scene_s1, sx, 3, sz,
                            q_s_s.data(), mout_s.solid_quad_count,
                            q_c_s.data(), mout_s.cutout_quad_count,
                            q_t_s.data(), mout_s.translucent_quad_count),
                        "Test S1 update failed");
            }
        }
        const float cam_s1x = 8.5f, cam_s1y = 52.6f, cam_s1z = 8.2f;
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_s1, cam_s1x, cam_s1y, cam_s1z), "Test S1 TLAS failed");

        const std::vector<TestAabbBox> boxes_s1 = {
            {-16.0f, 49.0f, -16.0f, 32.0f, 51.0f, 32.0f, 0.30f, 0.42f, 0.24f, 1}, // mossy forest floor y=51.0
            {  8.0f, 51.0f,   4.0f,  9.0f, 63.0f,  5.0f, 0.36f, 0.26f, 0.18f, 2}, // spruce trunk z=5.0
            {-16.0f, 55.0f, -16.0f, 32.0f, 63.0f, 32.0f, 0.16f, 0.28f, 0.14f, 3}, // spruce canopy underside y=55.0
        };
        std::vector<uint16_t> color_s1;
        std::vector<float> depth_s1;
        std::vector<int> tag_s1;
        rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_s1x, cam_s1y, cam_s1z, boxes_s1, color_s1, depth_s1, tag_s1);

        auto eval_s1_canopy = [&](MetalityPackHandle pk, const char* tag) {
            for (int iter = 0; iter < 2; ++iter) {
                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, color_s1.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, depth_s1.data(), w * 4u);
                MetalityWorldFrameUniforms fu = make_high_uniforms(cam_s1x, cam_s1y, cam_s1z, 0.25f, 0.88f, -0.40f);
                fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                fu.user_params[0] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pk, scene_s1, ctex_hdr, dtex, &fu), "Test S1 execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test S1 timeout");
            }
            const FrameHealthStats st_floor = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, 24u, 16u, 72u, 36u, 0.04f, 0.0f);
            const FrameHealthStats st_trunk = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, 118u, 40u, 20u, 52u, 0.04f, 0.0f);
            std::printf("[Test S1 SpruceCanopy %-6s] Floor(avg=%.3f,min=%.3f,blk=%.4f) | Trunk(avg=%.3f,min=%.3f,blk=%.4f)\n",
                        tag, st_floor.roi_avg_luma, st_floor.roi_min_luma, st_floor.roi_black_ratio,
                        st_trunk.roi_avg_luma, st_trunk.roi_min_luma, st_trunk.roi_black_ratio);
            REQUIRE(st_floor.roi_min_luma >= 0.015f && st_floor.roi_black_ratio == 0.0f,
                    "Daytime spruce forest floor under foliage canopy must not crush to pitch-black cave shadows");
            REQUIRE(st_trunk.roi_min_luma >= 0.015f && st_trunk.roi_black_ratio == 0.0f,
                    "Daytime shaded spruce tree trunk under foliage canopy must retain ambient detail with zero black pixels");
        };
        eval_s1_canopy(pack_dusk, "Dusk");
        eval_s1_canopy(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_s1);

        // S2: Side-by-side canopy-shaded forest pond (left x <= 8) vs open sunlit pond (right x >= 11)
        MetalityRtSceneHandle scene_s2 = metality_rt_scene_create(ctx, 32u, false);
        REQUIRE(scene_s2 != nullptr, "Failed to create Test S2 scene");
        for (int sz = -1; sz <= 1; ++sz) {
            for (int sx = -1; sx <= 1; ++sx) {
                std::vector<uint16_t> blocks_s2(METALITY_PADDED_VOLUME, 0u);
                for (int pz = 1; pz <= 16; ++pz) {
                    for (int px = 1; px <= 16; ++px) {
                        const int wx = sx * 16 + (px - 1);
                        blocks_s2[pad_idx(px, 1, pz)] = 1u; // riverbed y=48
                        blocks_s2[pad_idx(px, 2, pz)] = 1u; // riverbed y=49 (top y=50.0)
                        blocks_s2[pad_idx(px, 3, pz)] = 4u; // pond water y=50
                        blocks_s2[pad_idx(px, 4, pz)] = 4u; // pond water top y=51 (surface y=51.889)
                        if (wx <= 8) {
                            for (int py = 8; py <= 15; ++py) {
                                blocks_s2[pad_idx(px, py, pz)] = 3u; // spruce canopy over left half of pond
                            }
                        }
                    }
                }
                MetalityChunkSectionInput in_s2{};
                in_s2.block_material_ids = blocks_s2.data();
                in_s2.packed_light = light.data();
                in_s2.biome_tint_rgb = tint.data();
                in_s2.palette_table = pal_s.data();
                in_s2.palette_size = static_cast<uint32_t>(pal_s.size());
                in_s2.enable_greedy_merge = 1;
                REQUIRE(metality_mesh_chunk_section_neon(&in_s2, &mout_s), "Test S2 meshing failed");
                REQUIRE(metality_rt_scene_update_section_v2(
                            ctx, scene_s2, sx, 3, sz,
                            q_s_s.data(), mout_s.solid_quad_count,
                            q_c_s.data(), mout_s.cutout_quad_count,
                            q_t_s.data(), mout_s.translucent_quad_count),
                        "Test S2 update failed");
            }
        }
        const float cam_s2x = 9.5f, cam_s2y = 53.6f, cam_s2z = 13.5f;
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_s2, cam_s2x, cam_s2y, cam_s2z), "Test S2 TLAS failed");

        // Opaque G-buffer renders the riverbed y=50.0 (and high canopy underside on left); water is raymarched in composite
        const std::vector<TestAabbBox> boxes_s2 = {
            {-16.0f, 48.0f, -16.0f, 32.0f, 50.0f, 32.0f, 0.42f, 0.38f, 0.30f, 1}, // sandy riverbed y=50.0
            {-16.0f, 55.0f, -16.0f,  9.0f, 63.0f, 32.0f, 0.16f, 0.28f, 0.14f, 2}, // overhanging left spruce canopy
        };
        std::vector<uint16_t> color_s2;
        std::vector<float> depth_s2;
        std::vector<int> tag_s2;
        rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_s2x, cam_s2y, cam_s2z, boxes_s2, color_s2, depth_s2, tag_s2);

        auto eval_s2_pond = [&](MetalityPackHandle pk, const char* tag) {
            for (int iter = 0; iter < 2; ++iter) {
                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, color_s2.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, depth_s2.data(), w * 4u);
                // Sun from (-X, +Y, -Z) so left pond x<=8 is deeply shadowed by canopy while right pond x>=11 is fully sunlit
                MetalityWorldFrameUniforms fu = make_high_uniforms(cam_s2x, cam_s2y, cam_s2z, -0.30f, 0.88f, -0.36f);
                fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                fu.user_params[0] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pk, scene_s2, ctex_hdr, dtex, &fu), "Test S2 execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test S2 timeout");
            }
            const FrameHealthStats st_shaded = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, 16u, h / 6u, 56u, h / 6u, 0.015f, 0.0f);
            const FrameHealthStats st_sunlit = assert_frame_healthy(
                rb_hdr_ptr, w, h, tag, 184u, h / 6u, 56u, h / 6u, 0.08f, 0.0f);

            const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
            const uint32_t scan_y = h / 4u;
            float max_step_x = 0.0f, prev_l = -1.0f;
            for (uint32_t x = 20u; x <= 76u; ++x) {
                const size_t base = (static_cast<size_t>(scan_y) * w + x) * 4u;
                const float l = 0.2126f * decode_fp16(p16[base]) + 0.7152f * decode_fp16(p16[base + 1u]) + 0.0722f * decode_fp16(p16[base + 2u]);
                if (prev_l >= 0.0f) max_step_x = std::max(max_step_x, std::abs(l - prev_l));
                prev_l = l;
            }
            std::printf("[Test S2 ForestPond %-6s] ShadedPond(avg=%.3f,min=%.3f,RGB=%.3f/%.3f/%.3f) | SunlitPond(avg=%.3f) | MaxStepX=%.4f\n",
                        tag, st_shaded.roi_avg_luma, st_shaded.roi_min_luma,
                        st_shaded.roi_avg_r, st_shaded.roi_avg_g, st_shaded.roi_avg_b,
                        st_sunlit.roi_avg_luma, max_step_x);
            REQUIRE(st_shaded.roi_avg_luma < st_sunlit.roi_avg_luma * 0.80f,
                    "Canopy-shaded forest pond water must darken significantly relative to open sunlit pond water");
            REQUIRE(st_shaded.roi_min_luma >= 0.015f && st_shaded.roi_black_ratio == 0.0f,
                    "Canopy-shaded forest pond water must not collapse to pitch-black pixels");
            REQUIRE(max_step_x < 0.14f,
                    "Forest pond water shadow transition must remain smooth across 1x1 block boundaries (no grid seam spikes)");
        };
        eval_s2_pond(pack_dusk, "Dusk");
        eval_s2_pond(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_s2);
    }

    // -------------------------------------------------------------------------
    // Regression Test T: Non-Full & Sub-1.0 Blocks (dirt_path, daylight_detector,
    //                    chest, outer corner stair, short_grass) + Adjacent Calm
    //                    Lake Shoreline Water (Zero Black Self-Shadow & Flat Shoreline)
    // -------------------------------------------------------------------------
    {
        std::vector<MetalityBlockMaterialEntry> pal_t = palette;
        pal_t.resize(10);
        // Material 3: dirt_path (tag=14, sub=9 -> 15/16m height [0..0.9375])
        for (int f = 0; f < 6; ++f) pal_t[3].face_sprites[f] = 3;
        pal_t[3].layer = METALITY_LAYER_CUTOUT;
        pal_t[3].is_opaque_full_cube = 0;
        pal_t[3].wave_tag = 14;
        pal_t[3].emissive_level = 9;
        pal_t[3].alpha = 255;
        // Material 4: daylight_detector (tag=14, sub=11 -> 6/16m height [0..0.375])
        for (int f = 0; f < 6; ++f) pal_t[4].face_sprites[f] = 4;
        pal_t[4].layer = METALITY_LAYER_CUTOUT;
        pal_t[4].is_opaque_full_cube = 0;
        pal_t[4].wave_tag = 14;
        pal_t[4].emissive_level = 11;
        pal_t[4].alpha = 255;
        // Material 5: chest (tag=14, sub=12 -> centered 14x14x14 [0.0625..0.9375, 0..0.875, 0.0625..0.9375])
        for (int f = 0; f < 6; ++f) pal_t[5].face_sprites[f] = 5;
        pal_t[5].layer = METALITY_LAYER_CUTOUT;
        pal_t[5].is_opaque_full_cube = 0;
        pal_t[5].wave_tag = 14;
        pal_t[5].emissive_level = 12;
        pal_t[5].alpha = 255;
        // Material 6: outer corner stair (tag=15, sub=8 -> bottom base + NW(-X,-Z) upper quadrant)
        for (int f = 0; f < 6; ++f) pal_t[6].face_sprites[f] = 6;
        pal_t[6].layer = METALITY_LAYER_CUTOUT;
        pal_t[6].is_opaque_full_cube = 0;
        pal_t[6].wave_tag = 15;
        pal_t[6].emissive_level = 8;
        pal_t[6].alpha = 255;
        // Material 7: short_grass (tag=14, sub=15 -> pass-through vegetation)
        for (int f = 0; f < 6; ++f) pal_t[7].face_sprites[f] = 7;
        pal_t[7].layer = METALITY_LAYER_CUTOUT;
        pal_t[7].is_opaque_full_cube = 0;
        pal_t[7].wave_tag = 14;
        pal_t[7].emissive_level = 15;
        pal_t[7].alpha = 255;
        // Material 8: calm still lake water (tag=3, sub=8 translucent)
        for (int f = 0; f < 6; ++f) pal_t[8].face_sprites[f] = 8;
        pal_t[8].layer = METALITY_LAYER_TRANSLUCENT;
        pal_t[8].is_opaque_full_cube = 0;
        pal_t[8].wave_tag = 3;
        pal_t[8].emissive_level = 8;
        pal_t[8].alpha = 180;
        // Material 9: warm torch / campfire sprite (tag=9, emit=15)
        for (int f = 0; f < 6; ++f) pal_t[9].face_sprites[f] = 9;
        pal_t[9].layer = METALITY_LAYER_CUTOUT;
        pal_t[9].is_opaque_full_cube = 0;
        pal_t[9].wave_tag = 9;
        pal_t[9].emissive_level = 15;
        pal_t[9].alpha = 255;

        MetalityRtSceneHandle scene_t = metality_rt_scene_create(ctx, 32u, false);
        REQUIRE(scene_t != nullptr, "Failed to create Test T scene");

        std::vector<MetalityPackedQuad> q_s_t(4096), q_c_t(4096), q_t_t(4096);
        MetalityMeshedSectionOutput mout_t{};
        mout_t.solid_quads = q_s_t.data(); mout_t.solid_quad_capacity = 4096;
        mout_t.cutout_quads = q_c_t.data(); mout_t.cutout_quad_capacity = 4096;
        mout_t.translucent_quads = q_t_t.data(); mout_t.translucent_quad_capacity = 4096;

        for (int sz = -1; sz <= 1; ++sz) {
            for (int sx = -1; sx <= 1; ++sx) {
                std::vector<uint16_t> blocks_t(METALITY_PADDED_VOLUME, 0u);
                for (int pz = 0; pz < 18; ++pz) {
                    for (int px = 0; px < 18; ++px) {
                        const int wx = sx * 16 + (px - 1);
                        blocks_t[pad_idx(px, 1, pz)] = 1u; // base y=48
                        blocks_t[pad_idx(px, 2, pz)] = 1u; // riverbed/soil y=49 (top y=50.0)
                        if (wx <= 6) {
                            blocks_t[pad_idx(px, 3, pz)] = 8u; // lake water y=50
                            blocks_t[pad_idx(px, 4, pz)] = 8u; // lake surface y=51 (surface y=51.889)
                        } else if (wx <= 9) {
                            blocks_t[pad_idx(px, 3, pz)] = 1u; // bank soil y=50
                            blocks_t[pad_idx(px, 4, pz)] = 3u; // dirt_path y=51 (top y=51.9375)
                        } else {
                            blocks_t[pad_idx(px, 3, pz)] = 1u; // bank soil y=50
                            blocks_t[pad_idx(px, 4, pz)] = 1u; // grass ground y=51 (top y=52.0)
                        }
                    }
                }
                if (sx == 0 && sz == 0) {
                    // Place sub-1.0 blocks & short_grass cleanly in foreground row wz=8..9, wy=52 (py=5)
                    blocks_t[pad_idx(11, 5, 9)] = 4u;  // daylight_detector (wx=10, wz=8)
                    blocks_t[pad_idx(11, 5, 10)] = 4u; // daylight_detector (wx=10, wz=9)
                    blocks_t[pad_idx(12, 5, 9)] = 6u;  // outer corner stair NW (wx=11, wz=8)
                    blocks_t[pad_idx(13, 5, 9)] = 5u;  // chest (wx=12, wz=8)
                    blocks_t[pad_idx(14, 5, 9)] = 7u;  // short_grass (wx=13, wz=8)
                    blocks_t[pad_idx(10, 5, 7)] = 9u;  // warm torch/campfire nearby (wx=9, wz=6)
                }
                MetalityChunkSectionInput in_t{};
                in_t.block_material_ids = blocks_t.data();
                in_t.packed_light = light.data();
                in_t.biome_tint_rgb = tint.data();
                in_t.palette_table = pal_t.data();
                in_t.palette_size = static_cast<uint32_t>(pal_t.size());
                in_t.enable_greedy_merge = 1;
                REQUIRE(metality_mesh_chunk_section_neon(&in_t, &mout_t), "Test T meshing failed");
                REQUIRE(metality_rt_scene_update_section_v2(
                            ctx, scene_t, sx, 3, sz,
                            q_s_t.data(), mout_t.solid_quad_count,
                            q_c_t.data(), mout_t.cutout_quad_count,
                            q_t_t.data(), mout_t.translucent_quad_count),
                        "Test T update failed");
            }
        }

        const float cam_tx = 9.5f, cam_ty = 53.6f, cam_tz = 13.0f;
        REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_t, cam_tx, cam_ty, cam_tz), "Test T TLAS failed");

        const std::vector<TestAabbBox> boxes_t = {
            {-16.0f, 48.0f, -16.0f,  7.0f, 50.0f,    32.0f, 0.42f, 0.38f, 0.30f, 1}, // sandy riverbed y=50.0
            {  7.0f, 50.0f, -16.0f, 32.0f, 51.0f,    32.0f, 0.36f, 0.30f, 0.22f, 1}, // shoreline soil bank y=50..51
            {  7.0f, 51.0f, -16.0f, 10.0f, 51.9375f, 32.0f, 0.56f, 0.44f, 0.28f, 2}, // dirt_path (tag=14,sub=9)
            { 10.0f, 51.0f, -16.0f, 32.0f, 52.0f,    32.0f, 0.36f, 0.50f, 0.26f, 1}, // grass terrain y=52.0
            { 10.0f, 52.0f,   8.0f, 11.0f, 52.375f,  10.0f, 0.52f, 0.50f, 0.46f, 3}, // daylight_detector (tag=14,sub=11)
            { 11.0f, 52.0f,   8.0f, 12.0f, 52.50f,    9.0f, 0.54f, 0.46f, 0.34f, 4}, // outer stair open step (tag=15,sub=8)
            { 11.0f, 52.5f,   8.0f, 11.5f, 53.00f,    8.5f, 0.54f, 0.46f, 0.34f, 5}, // outer stair NW quadrant
            { 12.0625f, 52.0f, 8.0625f, 12.9375f, 52.875f, 8.9375f, 0.56f, 0.40f, 0.22f, 6}, // chest (tag=14,sub=12)
            { 13.12f, 52.0f,  8.46f, 13.88f, 52.85f,  8.54f, 0.34f, 0.58f, 0.24f, 7}, // short_grass (tag=14,sub=15)
        };
        std::vector<uint16_t> color_t;
        std::vector<float> depth_t;
        std::vector<int> tag_t;
        rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_tx, cam_ty, cam_tz, boxes_t, color_t, depth_t, tag_t);

        struct SubBlockTagStat {
            float avg_l = 0.0f, min_l = 1e9f;
            uint32_t cnt = 0u, black_cnt = 0u;
        };

        auto eval_t_subblocks = [&](MetalityPackHandle pk, const char* label) {
            for (int iter = 0; iter < 2; ++iter) {
                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, color_t.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, depth_t.data(), w * 4u);
                // High midday sun from (+Y, +Z) so all foreground sub-1.0 blocks & open NW stair step are directly sunlit
                MetalityWorldFrameUniforms fu = make_high_uniforms(cam_tx, cam_ty, cam_tz, 0.0f, 0.94f, 0.34f);
                fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                fu.user_params[0] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pk, scene_t, ctex_hdr, dtex, &fu), "Test T execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test T timeout");
            }
            (void)assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 4u, h / 8u, w / 2u, h / 3u, 0.06f, 0.0f);

            const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
            SubBlockTagStat stats[8]{};
            double sum_l[8]{};
            for (uint32_t py = 2; py + 2 < h; ++py) {
                for (uint32_t px = 2; px + 2 < w; ++px) {
                    const size_t pidx = static_cast<size_t>(py) * w + px;
                    const int tg = tag_t[pidx];
                    if (tg < 2 || tg > 7) continue;
                    // Skip 1px silhouette edge pixels where depth-derivative normal spans background
                    if (tag_t[pidx - 1u] != tg || tag_t[pidx + 1u] != tg ||
                        tag_t[pidx - w] != tg || tag_t[pidx + w] != tg) {
                        continue;
                    }
                    const float r = decode_fp16(p16[pidx * 4u + 0u]);
                    const float g = decode_fp16(p16[pidx * 4u + 1u]);
                    const float b = decode_fp16(p16[pidx * 4u + 2u]);
                    const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                    sum_l[tg] += l;
                    stats[tg].min_l = std::min(stats[tg].min_l, l);
                    if (l < 0.015f) ++stats[tg].black_cnt;
                    ++stats[tg].cnt;
                }
            }
            for (int tg = 2; tg <= 7; ++tg) {
                if (stats[tg].cnt > 0u) {
                    stats[tg].avg_l = static_cast<float>(sum_l[tg] / stats[tg].cnt);
                }
            }

            // Scan calm lake water horizontally right up to the dirt_path shoreline (x ~ 4.5..6.8)
            const uint32_t scan_y = 36u;
            float max_w_step_x = 0.0f, min_w_l = 1e9f, prev_wl = -1.0f;
            uint32_t w_black = 0u;
            for (uint32_t px = 24u; px <= 70u; ++px) {
                const size_t base = (static_cast<size_t>(scan_y) * w + px) * 4u;
                const float l = 0.2126f * decode_fp16(p16[base]) + 0.7152f * decode_fp16(p16[base + 1u]) + 0.0722f * decode_fp16(p16[base + 2u]);
                min_w_l = std::min(min_w_l, l);
                if (l < 0.015f) ++w_black;
                if (prev_wl >= 0.0f) max_w_step_x = std::max(max_w_step_x, std::abs(l - prev_wl));
                prev_wl = l;
            }

            std::printf("[Test T SubBlocks %-6s] DirtPath(avg=%.3f,min=%.3f,blk=%u,n=%u) | "
                        "DayDet(avg=%.3f,min=%.3f,n=%u) | OuterStair(avg=%.3f,min=%.3f,n=%u) | "
                        "Chest(avg=%.3f,min=%.3f,n=%u) | Grass(avg=%.3f,min=%.3f,n=%u) | "
                        "ShoreWater(min=%.3f,stepX=%.4f,blk=%u)\n",
                        label,
                        stats[2].avg_l, stats[2].min_l, stats[2].black_cnt, stats[2].cnt,
                        stats[3].avg_l, stats[3].min_l, stats[3].cnt,
                        stats[4].avg_l, stats[4].min_l, stats[4].cnt,
                        stats[6].avg_l, stats[6].min_l, stats[6].cnt,
                        stats[7].avg_l, stats[7].min_l, stats[7].cnt,
                        min_w_l, max_w_step_x, w_black);

            REQUIRE(stats[2].cnt >= 200u && stats[3].cnt >= 60u && stats[4].cnt >= 40u &&
                    stats[6].cnt >= 60u && stats[7].cnt >= 40u,
                    "Test T must resolve interior pixels for dirt_path, daylight_detector, outer stair, chest, and short_grass");
            REQUIRE(stats[2].black_cnt == 0u && stats[2].min_l >= 0.04f && stats[2].avg_l >= 0.12f,
                    "Sunlit dirt_path (15/16m) must have ZERO pitch-black self-shadowing");
            REQUIRE(stats[3].black_cnt == 0u && stats[3].min_l >= 0.04f && stats[3].avg_l >= 0.10f,
                    "Sunlit daylight_detector (6/16m) must have ZERO black self-shadowing");
            REQUIRE(stats[4].black_cnt == 0u && stats[4].min_l >= 0.03f && stats[4].avg_l >= 0.10f,
                    "Sunlit outer corner stair open step (sub=8) must not be self-shadowed by phantom full-cube/straight geometry");
            REQUIRE(stats[6].black_cnt == 0u && stats[6].min_l >= 0.03f && stats[6].avg_l >= 0.10f,
                    "Sunlit chest (14x14x14) must have ZERO black self-shadowing");
            REQUIRE(stats[7].black_cnt == 0u && stats[7].min_l >= 0.03f && stats[7].avg_l >= 0.08f,
                    "Pass-through short_grass (tag=14,sub=15) must receive natural daylight without black occlusion crush");
            REQUIRE(w_black == 0u && min_w_l >= 0.03f && max_w_step_x < 0.14f,
                    "Calm lake water directly adjacent to dirt_path must remain flat and smooth with zero shoreline seam spikes");
        };
        eval_t_subblocks(pack_dusk, "Dusk");
        eval_t_subblocks(pack_zenith, "Zenith");
        metality_rt_scene_destroy(scene_t);
    }

    // -------------------------------------------------------------------------
    // Regression Test U: Indoor Chandelier Wrapped in Grated Iron Trapdoors,
    //                    Greedy 4x4 Sea Lantern Wall Bounded Radiance &
    //                    Distant Dynamic-Light Wall Isolation (Zero Teleport)
    // -------------------------------------------------------------------------
    {
        std::vector<MetalityBlockMaterialEntry> pal_u = palette;
        pal_u.resize(6);
        // Material 3: Sea Lantern (tag=6, emit=15 solid full cube)
        for (int f = 0; f < 6; ++f) pal_u[3].face_sprites[f] = 3;
        pal_u[3].layer = METALITY_LAYER_SOLID;
        pal_u[3].is_opaque_full_cube = 1;
        pal_u[3].wave_tag = 6;
        pal_u[3].emissive_level = 15;
        pal_u[3].alpha = 255;
        // Material 4: Grated iron_trapdoor (tag=14, sub=0 non-occluding cutout)
        for (int f = 0; f < 6; ++f) pal_u[4].face_sprites[f] = 4;
        pal_u[4].layer = METALITY_LAYER_CUTOUT;
        pal_u[4].is_opaque_full_cube = 0;
        pal_u[4].wave_tag = 14;
        pal_u[4].emissive_level = 0;
        pal_u[4].alpha = 255;
        // Material 5: Invisible minecraft:light (tag=14, sub=14 non-occluding soft light)
        for (int f = 0; f < 6; ++f) pal_u[5].face_sprites[f] = 5;
        pal_u[5].layer = METALITY_LAYER_CUTOUT;
        pal_u[5].is_opaque_full_cube = 0;
        pal_u[5].wave_tag = 14;
        pal_u[5].emissive_level = 14;
        pal_u[5].alpha = 255;

        // Aqua-white tint (0xBBGGRR) for Sea Lantern blocks so they classify into AquaWhite
        std::vector<uint32_t> tint_u(16u * 16u * 16u, 0xFFE8D0u);

        std::vector<MetalityPackedQuad> q_s_u(4096), q_c_u(4096), q_t_u(4096);
        MetalityMeshedSectionOutput mout_u{};
        mout_u.solid_quads = q_s_u.data(); mout_u.solid_quad_capacity = 4096;
        mout_u.cutout_quads = q_c_u.data(); mout_u.cutout_quad_capacity = 4096;
        mout_u.translucent_quads = q_t_u.data(); mout_u.translucent_quad_capacity = 4096;

        // U1: Enclosed indoor room with a hanging 6-face Sea Lantern wrapped on 4 sides by grated iron_trapdoor (tag=14,sub=0)
        {
            MetalityRtSceneHandle scene_u1 = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_u1 != nullptr, "Failed to create Test U1 scene");

            std::vector<uint16_t> blocks_u1(METALITY_PADDED_VOLUME, 0u);
            for (int pz = 3; pz <= 14; ++pz) {
                for (int px = 4; px <= 14; ++px) {
                    blocks_u1[pad_idx(px, 3, pz)] = 1u;  // floor y=50 (top y=51.0)
                    blocks_u1[pad_idx(px, 10, pz)] = 1u; // ceiling y=57 (bottom y=57.0)
                    for (int py = 4; py <= 9; ++py) {
                        if (px == 4 || px == 14 || pz == 3 || pz == 14) {
                            blocks_u1[pad_idx(px, py, pz)] = 1u; // enclosed walls
                        }
                    }
                }
            }
            // Hanging 6-face Sea Lantern at world (8, 55, 6) -> (px=9, py=8, pz=7)
            blocks_u1[pad_idx(9, 8, 7)] = 3u;
            // Wrapped on all 4 horizontal sides by grated iron_trapdoors (tag=14, sub=0)
            blocks_u1[pad_idx(8, 8, 7)] = 4u;
            blocks_u1[pad_idx(10, 8, 7)] = 4u;
            blocks_u1[pad_idx(9, 8, 6)] = 4u;
            blocks_u1[pad_idx(9, 8, 8)] = 4u;

            MetalityChunkSectionInput in_u1{};
            in_u1.block_material_ids = blocks_u1.data();
            in_u1.packed_light = light.data();
            in_u1.biome_tint_rgb = tint_u.data();
            in_u1.palette_table = pal_u.data();
            in_u1.palette_size = static_cast<uint32_t>(pal_u.size());
            in_u1.enable_greedy_merge = 1;
            REQUIRE(metality_mesh_chunk_section_neon(&in_u1, &mout_u), "Test U1 meshing failed");
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, scene_u1, 0, 3, 0,
                        q_s_u.data(), mout_u.solid_quad_count,
                        q_c_u.data(), mout_u.cutout_quad_count,
                        q_t_u.data(), mout_u.translucent_quad_count),
                    "Test U1 update failed");

            const float cam_u1x = 8.5f, cam_u1y = 53.0f, cam_u1z = 11.2f;
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_u1, cam_u1x, cam_u1y, cam_u1z), "Test U1 TLAS failed");

            const std::vector<TestAabbBox> boxes_u1 = {
                {4.0f, 50.0f, 3.0f, 13.0f, 51.0f, 13.0f, 0.48f, 0.48f, 0.48f, 1}, // floor y=51.0
                {4.0f, 51.0f, 2.0f, 13.0f, 57.0f,  3.0f, 0.48f, 0.48f, 0.48f, 2}, // back wall z=3.0
                {3.0f, 51.0f, 3.0f,  4.0f, 57.0f, 13.0f, 0.48f, 0.48f, 0.48f, 3}, // left wall
                {13.0f, 51.0f, 3.0f, 14.0f, 57.0f, 13.0f, 0.48f, 0.48f, 0.48f, 3}, // right wall
                {4.0f, 57.0f, 3.0f, 13.0f, 58.0f, 13.0f, 0.48f, 0.48f, 0.48f, 3}, // ceiling y=57.0
                {8.0f, 55.0f, 6.0f,  9.0f, 56.0f,  7.0f, 0.78f, 0.90f, 0.96f, 4}, // hanging Sea Lantern
            };
            std::vector<uint16_t> color_u1;
            std::vector<float> depth_u1;
            std::vector<int> tag_u1;
            rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_u1x, cam_u1y, cam_u1z, boxes_u1, color_u1, depth_u1, tag_u1);

            auto eval_u1_chandelier = [&](MetalityPackHandle pk, const char* label) {
                for (int iter = 0; iter < 2; ++iter) {
                    const uint64_t fid = metality_begin_frame(ctx);
                    metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, color_u1.data(), w * 8u);
                    metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, depth_u1.data(), w * 4u);
                    MetalityWorldFrameUniforms fu = make_high_uniforms(cam_u1x, cam_u1y, cam_u1z, 0.0f, -0.75f, -0.66f);
                    fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                    if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                    fu.user_params[0] = 0.0f;
                    fu.lighting_params[3] = 0.0f;
                    REQUIRE(metality_pack_execute(ctx, pk, scene_u1, ctex_hdr, dtex, &fu), "Test U1 execute failed");
                    metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                    metality_end_frame(ctx, false);
                    REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test U1 timeout");
                }
                (void)assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 4u, h / 4u, w / 2u, h / 2u, 0.04f, 0.0f);

                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                double fl_sum = 0.0, wl_sum = 0.0;
                float fl_min = 1e9f, fl_max = 0.0f, wl_min = 1e9f, wl_max = 0.0f;
                uint32_t fl_cnt = 0u, wl_cnt = 0u, fl_blk = 0u;
                for (uint32_t py = 2; py + 2 < h; ++py) {
                    for (uint32_t px = 2; px + 2 < w; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        const int tg = tag_u1[pidx];
                        if (tg != 1 && tg != 2) continue;
                        if (tag_u1[pidx - 1u] != tg || tag_u1[pidx + 1u] != tg ||
                            tag_u1[pidx - w] != tg || tag_u1[pidx + w] != tg) {
                            continue;
                        }
                        const float l = 0.2126f * decode_fp16(p16[pidx * 4u]) +
                                        0.7152f * decode_fp16(p16[pidx * 4u + 1u]) +
                                        0.0722f * decode_fp16(p16[pidx * 4u + 2u]);
                        if (tg == 1) {
                            fl_sum += l;
                            fl_min = std::min(fl_min, l);
                            fl_max = std::max(fl_max, l);
                            if (l < 0.015f) ++fl_blk;
                            ++fl_cnt;
                        } else {
                            wl_sum += l;
                            wl_min = std::min(wl_min, l);
                            wl_max = std::max(wl_max, l);
                            ++wl_cnt;
                        }
                    }
                }
                const float fl_avg = (fl_cnt > 0u) ? static_cast<float>(fl_sum / fl_cnt) : 0.0f;
                const float wl_avg = (wl_cnt > 0u) ? static_cast<float>(wl_sum / wl_cnt) : 0.0f;

                float max_step_x = 0.0f, prev_l = -1.0f;
                const uint32_t scan_y = 24u;
                for (uint32_t px = 48u; px <= 208u; ++px) {
                    const size_t pidx = static_cast<size_t>(scan_y) * w + px;
                    if (tag_u1[pidx] != 1) { prev_l = -1.0f; continue; }
                    const float l = 0.2126f * decode_fp16(p16[pidx * 4u]) +
                                    0.7152f * decode_fp16(p16[pidx * 4u + 1u]) +
                                    0.0722f * decode_fp16(p16[pidx * 4u + 2u]);
                    if (prev_l >= 0.0f) max_step_x = std::max(max_step_x, std::abs(l - prev_l));
                    prev_l = l;
                }
                std::printf("[Test U1 Chandelier %-6s] Floor(avg=%.3f,min=%.3f,max=%.3f,blk=%u,n=%u) | "
                            "BackWall(avg=%.3f,min=%.3f,max=%.3f,n=%u) | StepX=%.4f\n",
                            label, fl_avg, fl_min, fl_max, fl_blk, fl_cnt,
                            wl_avg, wl_min, wl_max, wl_cnt, max_step_x);
                REQUIRE(fl_cnt >= 300u && wl_cnt >= 300u, "Test U1 must resolve indoor floor and back wall");
                REQUIRE(fl_blk == 0u && fl_avg >= 0.06f && fl_min >= 0.02f && wl_avg >= 0.05f && wl_min >= 0.015f,
                        "Grated iron_trapdoors (tag=14,sub=0) must NOT cage or black-out hanging Sea Lantern chandelier light");
                REQUIRE(fl_max < 1.35f && wl_max < 1.35f && max_step_x < 0.14f,
                        "Hanging 6-face Sea Lantern must stay smooth and bounded (no 69W double-count spike or diagonal self-shadow seam)");
            };
            eval_u1_chandelier(pack_dusk, "Dusk");
            eval_u1_chandelier(pack_zenith, "Zenith");
            metality_rt_scene_destroy(scene_u1);
        }

        // U2: Greedy-merged 4x4 Sea Lantern wall strip + invisible minecraft:light (tag=14,sub=14)
        {
            MetalityRtSceneHandle scene_u2 = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_u2 != nullptr, "Failed to create Test U2 scene");

            std::vector<uint16_t> blocks_u2(METALITY_PADDED_VOLUME, 0u);
            for (int pz = 3; pz <= 14; ++pz) {
                for (int px = 4; px <= 14; ++px) {
                    blocks_u2[pad_idx(px, 3, pz)] = 1u;  // floor y=50 (top y=51.0)
                    blocks_u2[pad_idx(px, 10, pz)] = 1u; // ceiling y=57 (bottom y=57.0)
                    for (int py = 4; py <= 9; ++py) {
                        if (px == 4 || px == 14 || pz == 3 || pz == 14) {
                            blocks_u2[pad_idx(px, py, pz)] = 1u;
                        }
                    }
                }
            }
            // Embed greedy 4x4 Sea Lantern strip flush into left wall wx=3 (px=4), backed externally at wx=2 (px=3)
            for (int py = 5; py <= 8; ++py) {
                for (int pz = 6; pz <= 9; ++pz) {
                    blocks_u2[pad_idx(4, py, pz)] = 3u;
                    blocks_u2[pad_idx(3, py, pz)] = 1u;
                }
            }
            // Place invisible minecraft:light (tag=14,sub=14) in open mid-room at world (9, 53, 7) -> (px=10, py=6, pz=8)
            blocks_u2[pad_idx(10, 6, 8)] = 5u;

            MetalityChunkSectionInput in_u2{};
            in_u2.block_material_ids = blocks_u2.data();
            in_u2.packed_light = light.data();
            in_u2.biome_tint_rgb = tint_u.data();
            in_u2.palette_table = pal_u.data();
            in_u2.palette_size = static_cast<uint32_t>(pal_u.size());
            in_u2.enable_greedy_merge = 1;
            REQUIRE(metality_mesh_chunk_section_neon(&in_u2, &mout_u), "Test U2 meshing failed");
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, scene_u2, 0, 3, 0,
                        q_s_u.data(), mout_u.solid_quad_count,
                        q_c_u.data(), mout_u.cutout_quad_count,
                        q_t_u.data(), mout_u.translucent_quad_count),
                    "Test U2 update failed");

            const float cam_u2x = 8.5f, cam_u2y = 53.5f, cam_u2z = 11.2f;
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_u2, cam_u2x, cam_u2y, cam_u2z), "Test U2 TLAS failed");

            const std::vector<TestAabbBox> boxes_u2 = {
                {4.0f, 50.0f, 3.0f, 13.0f, 51.0f, 13.0f, 0.48f, 0.48f, 0.48f, 1}, // floor y=51.0
                {4.0f, 51.0f, 2.0f, 13.0f, 57.0f,  3.0f, 0.48f, 0.48f, 0.48f, 2}, // back wall z=3.0
                {3.0f, 51.0f, 3.0f,  4.0f, 57.0f, 13.0f, 0.48f, 0.48f, 0.48f, 3}, // left wall
                {13.0f, 51.0f, 3.0f, 14.0f, 57.0f, 13.0f, 0.48f, 0.48f, 0.48f, 2}, // right wall (opposing 4x4 strip)
                {4.0f, 57.0f, 3.0f, 13.0f, 58.0f, 13.0f, 0.48f, 0.48f, 0.48f, 3}, // ceiling
            };
            std::vector<uint16_t> color_u2;
            std::vector<float> depth_u2;
            std::vector<int> tag_u2;
            rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_u2x, cam_u2y, cam_u2z, boxes_u2, color_u2, depth_u2, tag_u2);

            auto eval_u2_greedy_wall = [&](MetalityPackHandle pk, const char* label) {
                for (int iter = 0; iter < 2; ++iter) {
                    const uint64_t fid = metality_begin_frame(ctx);
                    metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, color_u2.data(), w * 8u);
                    metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, depth_u2.data(), w * 4u);
                    MetalityWorldFrameUniforms fu = make_high_uniforms(cam_u2x, cam_u2y, cam_u2z, 0.0f, -0.75f, -0.66f);
                    fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                    if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                    fu.user_params[0] = 0.0f;
                    fu.lighting_params[3] = 0.0f;
                    REQUIRE(metality_pack_execute(ctx, pk, scene_u2, ctex_hdr, dtex, &fu), "Test U2 execute failed");
                    metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                    metality_end_frame(ctx, false);
                    REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test U2 timeout");
                }
                (void)assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 4u, h / 4u, w / 2u, h / 2u, 0.06f, 0.0f);

                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                double opp_sum = 0.0;
                float opp_min = 1e9f, opp_max = 0.0f;
                uint32_t opp_cnt = 0u, opp_blk = 0u;
                for (uint32_t py = 2; py + 2 < h; ++py) {
                    for (uint32_t px = 2; px + 2 < w; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        const int tg = tag_u2[pidx];
                        if (tg != 1 && tg != 2) continue;
                        if (tag_u2[pidx - 1u] != tg || tag_u2[pidx + 1u] != tg ||
                            tag_u2[pidx - w] != tg || tag_u2[pidx + w] != tg) {
                            continue;
                        }
                        const float l = 0.2126f * decode_fp16(p16[pidx * 4u]) +
                                        0.7152f * decode_fp16(p16[pidx * 4u + 1u]) +
                                        0.0722f * decode_fp16(p16[pidx * 4u + 2u]);
                        opp_sum += l;
                        opp_min = std::min(opp_min, l);
                        opp_max = std::max(opp_max, l);
                        if (l < 0.015f) ++opp_blk;
                        ++opp_cnt;
                    }
                }
                const float opp_avg = (opp_cnt > 0u) ? static_cast<float>(opp_sum / opp_cnt) : 0.0f;
                std::printf("[Test U2 Greedy4x4 %-6s] Room(avg=%.3f,min=%.3f,max=%.3f,blk=%u,n=%u)\n",
                            label, opp_avg, opp_min, opp_max, opp_blk, opp_cnt);
                REQUIRE(opp_cnt >= 600u && opp_blk == 0u && opp_avg >= 0.08f && opp_min >= 0.025f,
                        "Greedy 4x4 Sea Lantern wall + invisible minecraft:light must smoothly light the room with ZERO phantom shadow cube");
                REQUIRE(opp_max < 1.35f,
                        "Greedy 4x4 Sea Lantern strip must split into bounded clusters (no 230W mega-spike whiteout)");
            };
            eval_u2_greedy_wall(pack_dusk, "Dusk");
            eval_u2_greedy_wall(pack_zenith, "Zenith");
            metality_rt_scene_destroy(scene_u2);
        }

        // U3: Distant dynamic light embedded inside a solid concrete pillar behind its exterior face (ZERO wall-teleport spotlight)
        {
            MetalityRtSceneHandle scene_u3 = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_u3 != nullptr, "Failed to create Test U3 scene");

            std::vector<uint16_t> blocks_u3(METALITY_PADDED_VOLUME, 0u);
            for (int pz = 1; pz <= 16; ++pz) {
                for (int px = 1; px <= 16; ++px) {
                    blocks_u3[pad_idx(px, 2, pz)] = 1u; // outdoor ground y=49 (top y=50.0)
                }
            }
            // Solid 3x7x2 concrete pillar/wall at world x=7..9, y=50..56, z=4..5 (front face z=6.0)
            for (int py = 3; py <= 9; ++py) {
                for (int pz = 5; pz <= 6; ++pz) {
                    for (int px = 8; px <= 10; ++px) {
                        blocks_u3[pad_idx(px, py, pz)] = 1u;
                    }
                }
            }

            MetalityChunkSectionInput in_u3{};
            in_u3.block_material_ids = blocks_u3.data();
            in_u3.packed_light = light.data();
            in_u3.biome_tint_rgb = tint.data();
            in_u3.palette_table = pal_u.data();
            in_u3.palette_size = static_cast<uint32_t>(pal_u.size());
            in_u3.enable_greedy_merge = 1;
            REQUIRE(metality_mesh_chunk_section_neon(&in_u3, &mout_u), "Test U3 meshing failed");
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, scene_u3, 0, 3, 0,
                        q_s_u.data(), mout_u.solid_quad_count,
                        q_c_u.data(), mout_u.cutout_quad_count,
                        q_t_u.data(), mout_u.translucent_quad_count),
                    "Test U3 update failed");

            // Distant intense warm amber dynamic lights: one INSIDE solid pillar (z=5.5, dist=5.0m > 1.2m),
            // one behind solid pillar (z=3.5, dist=7.0m)
            const float hidden_dlights[2 * 8] = {
                8.5f, 53.0f, 5.5f, 48.0f, 1.0f, 0.55f, 0.12f, 0.35f,
                8.5f, 53.0f, 3.5f, 48.0f, 1.0f, 0.55f, 0.12f, 0.35f,
            };
            metality_rt_scene_set_dynamic_lights(scene_u3, hidden_dlights, 2u);

            const float cam_u3x = 8.5f, cam_u3y = 53.0f, cam_u3z = 10.5f;
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_u3, cam_u3x, cam_u3y, cam_u3z), "Test U3 TLAS failed");

            const std::vector<TestAabbBox> boxes_u3 = {
                {0.0f, 49.0f, 0.0f, 16.0f, 50.0f, 16.0f, 0.42f, 0.42f, 0.42f, 1}, // ground y=50.0
                {7.0f, 50.0f, 4.0f, 10.0f, 57.0f,  6.0f, 0.52f, 0.52f, 0.52f, 2}, // solid concrete pillar front z=6.0
            };
            std::vector<uint16_t> color_u3;
            std::vector<float> depth_u3;
            std::vector<int> tag_u3;
            rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_u3x, cam_u3y, cam_u3z, boxes_u3, color_u3, depth_u3, tag_u3);

            auto eval_u3_pillar_isolation = [&](MetalityPackHandle pk, const char* label) {
                for (int iter = 0; iter < 2; ++iter) {
                    const uint64_t fid = metality_begin_frame(ctx);
                    metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, color_u3.data(), w * 8u);
                    metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, depth_u3.data(), w * 4u);
                    // Nighttime with moon behind the pillar (+Y, -Z) so exterior front face (+Z) is completely unlit
                    MetalityWorldFrameUniforms fu = make_high_uniforms(cam_u3x, cam_u3y, cam_u3z, 0.0f, -0.75f, 0.66f);
                    fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                    if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                    fu.user_params[0] = 0.0f;
                    fu.lighting_params[3] = 0.0f;
                    REQUIRE(metality_pack_execute(ctx, pk, scene_u3, ctex_hdr, dtex, &fu), "Test U3 execute failed");
                    metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                    metality_end_frame(ctx, false);
                    REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test U3 timeout");
                }
                (void)assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 3u, h / 4u, w / 3u, h / 2u, 0.0f, 1.0f);

                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                double p_l = 0.0, p_r = 0.0, p_b = 0.0;
                float p_max = 0.0f;
                uint32_t p_cnt = 0u;
                for (uint32_t py = 2; py + 2 < h; ++py) {
                    for (uint32_t px = 2; px + 2 < w; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        if (tag_u3[pidx] != 2) continue;
                        if (tag_u3[pidx - 1u] != 2 || tag_u3[pidx + 1u] != 2 ||
                            tag_u3[pidx - w] != 2 || tag_u3[pidx + w] != 2) {
                            continue;
                        }
                        const float r = decode_fp16(p16[pidx * 4u + 0u]);
                        const float g = decode_fp16(p16[pidx * 4u + 1u]);
                        const float b = decode_fp16(p16[pidx * 4u + 2u]);
                        const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                        p_l += l;
                        p_r += r;
                        p_b += b;
                        p_max = std::max(p_max, l);
                        ++p_cnt;
                    }
                }
                const float avg_l = (p_cnt > 0u) ? static_cast<float>(p_l / p_cnt) : 0.0f;
                const float avg_r = (p_cnt > 0u) ? static_cast<float>(p_r / p_cnt) : 0.0f;
                const float avg_b = (p_cnt > 0u) ? static_cast<float>(p_b / p_cnt) : 0.0f;
                std::printf("[Test U3 DynLightIsol %-6s] PillarFront(avg=%.3f,max=%.3f,R=%.3f,B=%.3f,n=%u)\n",
                            label, avg_l, p_max, avg_r, avg_b, p_cnt);
                REQUIRE(p_cnt >= 600u, "Test U3 must resolve exterior concrete pillar front face");
                REQUIRE(avg_l < 0.12f && p_max < 0.18f && (avg_r - avg_b) < 0.03f,
                        "Distant dynamic light embedded inside solid pillar must NEVER teleport forward to create an exterior warm spotlight");
            };
            eval_u3_pillar_isolation(pack_dusk, "Dusk");
            eval_u3_pillar_isolation(pack_zenith, "Zenith");
            metality_rt_scene_destroy(scene_u3);
        }
    }

    // -------------------------------------------------------------------------
    // Regression Test V (Round 10.6 Dusk Cave Lava Fall & Overhang Artifacts):
    //   V1: Tall 18-block lava fall self-occlusion, long 3D diagonal DDA range
    //       (>32 L1 steps), and sub-pixel camera-jitter stability on small faces.
    //   V2: Protruding cave overhang with back/side lava light — zero pitch-black
    //       interior surrounded by a bright 2-3 texel glowing edge border.
    // -------------------------------------------------------------------------
    {
        std::vector<MetalityBlockMaterialEntry> pal_v = palette;
        pal_v.resize(4);
        // Material 3: Molten Lava (tag=4, emit=15)
        for (int f = 0; f < 6; ++f) pal_v[3].face_sprites[f] = 3;
        pal_v[3].layer = METALITY_LAYER_SOLID;
        pal_v[3].is_opaque_full_cube = 1;
        pal_v[3].wave_tag = 4;
        pal_v[3].emissive_level = 15;
        pal_v[3].alpha = 255;

        std::vector<uint32_t> tint_v(16u * 16u * 16u, 0x0048A0FFu); // warm lava tint (0xBBGGRR)
        std::vector<MetalityPackedQuad> q_s_v(4096), q_c_v(4096), q_t_v(4096);
        MetalityMeshedSectionOutput mout_v{};
        mout_v.solid_quads = q_s_v.data(); mout_v.solid_quad_capacity = 4096;
        mout_v.cutout_quads = q_c_v.data(); mout_v.cutout_quad_capacity = 4096;
        mout_v.translucent_quads = q_t_v.data(); mout_v.translucent_quad_capacity = 4096;

        auto upload_sec_v = [&](MetalityRtSceneHandle sc, int sx, int sy, int sz, const std::vector<uint16_t>& blks) {
            MetalityChunkSectionInput inv{};
            inv.block_material_ids = blks.data();
            inv.packed_light = light.data();
            inv.biome_tint_rgb = tint_v.data();
            inv.palette_table = pal_v.data();
            inv.palette_size = static_cast<uint32_t>(pal_v.size());
            inv.enable_greedy_merge = 1;
            REQUIRE(metality_mesh_chunk_section_neon(&inv, &mout_v), "Test V meshing failed");
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, sc, sx, sy, sz,
                        q_s_v.data(), mout_v.solid_quad_count,
                        q_c_v.data(), mout_v.cutout_quad_count,
                        q_t_v.data(), mout_v.translucent_quad_count),
                    "Test V section upload failed");
        };

        // V1: Freestanding 18-block tall lava fall across (0,2,0) & (0,3,0) lighting:
        //     - Near vertical wall (grazing vertically across lava column -> tests LAVA_SELF)
        //     - Small 1x1x1 cave step face (tests sub-pixel camera-shift / Bayer-jitter stability)
        //     - Far 3D diagonal cave wall at ~24m & ~40 L1 steps (tests DDA step budget > 32)
        {
            MetalityRtSceneHandle scene_v1 = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_v1 != nullptr, "Failed to create Test V1 scene");

            // Section (0,2,0): world x=0..15, y=32..47, z=0..15
            std::vector<uint16_t> b02(METALITY_PADDED_VOLUME, 0u);
            // Section (0,3,0): world x=0..15, y=48..63, z=0..15
            std::vector<uint16_t> b03(METALITY_PADDED_VOLUME, 0u);
            // Section (1,2,0): world x=16..31, y=32..47, z=0..15
            std::vector<uint16_t> b12(METALITY_PADDED_VOLUME, 0u);

            // Sealed floor (y=36 -> py=5 in sy=2) and ceiling (y=62 -> py=15 in sy=3)
            for (int px = 1; px <= 16; ++px) {
                for (int pz = 1; pz <= 16; ++pz) {
                    b02[pad_idx(px, 5, pz)] = 1u;
                    b12[pad_idx(px, 5, pz)] = 1u;
                    b03[pad_idx(px, 15, pz)] = 1u;
                }
            }
            // Freestanding 18-block tall lava fall at world (x=3, z=13, y=44..61)
            for (int wy = 44; wy <= 47; ++wy) b02[pad_idx(4, wy - 32 + 1, 14)] = 3u;
            for (int wy = 48; wy <= 61; ++wy) b03[pad_idx(4, wy - 48 + 1, 14)] = 3u;

            // Near vertical stone wall at world x=6..10, y=41..52, z=7 (front face z=8.0)
            for (int wx = 6; wx <= 10; ++wx) {
                for (int wy = 41; wy <= 47; ++wy) b02[pad_idx(wx + 1, wy - 32 + 1, 8)] = 1u;
                for (int wy = 48; wy <= 52; ++wy) b03[pad_idx(wx + 1, wy - 48 + 1, 8)] = 1u;
            }
            // Small 1x1x1 stone staircase face at world (x=12, y=45, z=7) (front face z=8.0)
            b02[pad_idx(13, 45 - 32 + 1, 8)] = 1u;

            // Far 3D diagonal stone wall in section (1,2,0): world x=20..25, y=38..44, z=1 (front face z=2.0)
            for (int wx = 20; wx <= 25; ++wx) {
                for (int wy = 38; wy <= 44; ++wy) b12[pad_idx(wx - 16 + 1, wy - 32 + 1, 2)] = 1u;
            }

            upload_sec_v(scene_v1, 0, 2, 0, b02);
            upload_sec_v(scene_v1, 0, 3, 0, b03);
            upload_sec_v(scene_v1, 1, 2, 0, b12);

            const float cam_v1x = 14.0f, cam_v1y = 46.5f, cam_v1z = 23.0f;
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_v1, cam_v1x, cam_v1y, cam_v1z), "Test V1 TLAS failed");

            const std::vector<TestAabbBox> boxes_v1 = {
                {0.0f, 36.0f, 0.0f, 32.0f, 37.0f, 24.0f, 0.42f, 0.42f, 0.42f, 4}, // floor
                {3.0f, 44.0f, 13.0f, 4.0f, 62.0f, 14.0f, 0.95f, 0.42f, 0.08f, 5}, // tall lava fall
                {6.0f, 41.0f,  7.0f, 11.0f, 53.0f, 8.0f, 0.46f, 0.46f, 0.46f, 1}, // near wall (tag 1)
                {12.0f, 45.0f, 7.0f, 13.0f, 46.0f, 8.0f, 0.46f, 0.46f, 0.46f, 3}, // small 1x1x1 step face (tag 3)
                {20.0f, 38.0f, 1.0f, 26.0f, 45.0f, 2.0f, 0.46f, 0.46f, 0.46f, 2}, // far diagonal wall (tag 2)
            };

            auto run_v1_pose = [&](MetalityPackHandle pk, float cx, float cy, float cz, int frame_idx,
                                   float& near_avg, float& near_min, uint32_t& near_blk, uint32_t& near_n,
                                   float& far_avg, float& far_min, uint32_t& far_blk, uint32_t& far_n,
                                   float& step_avg, uint32_t& step_n) {
                std::vector<uint16_t> col;
                std::vector<float> dep;
                std::vector<int> tag;
                rasterize_aabb_scene(w, h, 1.22f, 0.05f, cx, cy, cz, boxes_v1, col, dep, tag);

                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, col.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, dep.data(), w * 4u);
                MetalityWorldFrameUniforms fv = make_high_uniforms(cx, cy, cz, 0.0f, -0.75f, -0.66f);
                fv.cam_world_pos[3] = static_cast<float>(frame_idx);
                if (frame_idx == 1) fv.prev_view_proj[12] = 999.0f;
                fv.user_params[0] = 0.0f;
                fv.lighting_params[3] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pk, scene_v1, ctex_hdr, dtex, &fv), "Test V1 execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test V1 timeout");

                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                double n_sum = 0.0, f_sum = 0.0, s_sum = 0.0;
                near_min = 1e9f; far_min = 1e9f;
                near_blk = 0u; far_blk = 0u;
                near_n = 0u; far_n = 0u; step_n = 0u;
                for (uint32_t py = 2; py + 2 < h; ++py) {
                    for (uint32_t px = 2; px + 2 < w; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        const int tg = tag[pidx];
                        if (tg != 1 && tg != 2 && tg != 3) continue;
                        if (tag[pidx - 1u] != tg || tag[pidx + 1u] != tg ||
                            tag[pidx - w] != tg || tag[pidx + w] != tg) continue;
                        const float l = 0.2126f * decode_fp16(p16[pidx * 4u]) +
                                        0.7152f * decode_fp16(p16[pidx * 4u + 1u]) +
                                        0.0722f * decode_fp16(p16[pidx * 4u + 2u]);
                        if (tg == 1) {
                            n_sum += l; near_min = std::min(near_min, l);
                            if (l < 0.015f) ++near_blk;
                            ++near_n;
                        } else if (tg == 2) {
                            f_sum += l; far_min = std::min(far_min, l);
                            if (l < 0.008f) ++far_blk;
                            ++far_n;
                        } else {
                            s_sum += l; ++step_n;
                        }
                    }
                }
                near_avg = (near_n > 0u) ? static_cast<float>(n_sum / near_n) : 0.0f;
                far_avg  = (far_n  > 0u) ? static_cast<float>(f_sum / far_n)  : 0.0f;
                step_avg = (step_n > 0u) ? static_cast<float>(s_sum / step_n) : 0.0f;
            };

            float n1 = 0, nmin1 = 0, f1 = 0, fmin1 = 0, s1 = 0;
            uint32_t nb1 = 0, nn1 = 0, fb1 = 0, fn1 = 0, sn1 = 0;
            run_v1_pose(pack_dusk, cam_v1x, cam_v1y, cam_v1z, 1, n1, nmin1, nb1, nn1, f1, fmin1, fb1, fn1, s1, sn1);
            float n2 = 0, nmin2 = 0, f2 = 0, fmin2 = 0, s2 = 0;
            uint32_t nb2 = 0, nn2 = 0, fb2 = 0, fn2 = 0, sn2 = 0;
            run_v1_pose(pack_dusk, cam_v1x + 0.05f, cam_v1y + 0.03f, cam_v1z, 2, n2, nmin2, nb2, nn2, f2, fmin2, fb2, fn2, s2, sn2);

            const float d_near = std::abs(n2 - n1);
            const float d_far  = std::abs(f2 - f1);
            const float d_step = std::abs(s2 - s1);
            std::printf("[Test V1 LavaFall   Dusk  ] Near(avg=%.3f,min=%.3f,blk=%u,n=%u) | "
                        "FarDiag(avg=%.3f,min=%.3f,blk=%u,n=%u) | SmallStep(avg=%.3f,n=%u) | "
                        "SubPxDelta(near=%.4f,far=%.4f,step=%.4f)\n",
                        n1, nmin1, nb1, nn1, f1, fmin1, fb1, fn1, s1, sn1, d_near, d_far, d_step);
            REQUIRE(nn1 >= 300u && fn1 >= 150u && sn1 >= 10u, "Test V1 must resolve near wall, far diagonal wall, and small step face");
            REQUIRE(nb1 == 0u && nb2 == 0u && n1 >= 0.06f && nmin1 >= 0.025f,
                    "Tall freestanding lava fall must NOT self-occlude or black out nearby cave walls in Dusk");
            REQUIRE(fb1 == 0u && fb2 == 0u && f1 >= 0.018f && fmin1 >= 0.008f,
                    "Far 3D diagonal cave wall (>32 L1 voxel steps) must NOT suffer DDA budget-exhaustion blackout in Dusk");
            REQUIRE(d_near < 0.012f && d_far < 0.012f && d_step < 0.025f,
                    "Sub-pixel camera shift near tall lava fall must NOT trigger lighting flicker/strobing in Dusk");

            metality_rt_scene_destroy(scene_v1);
        }

        // V2: Protruding cave overhang with back/side lava light (Image 3 geometry)
        //     Asserts ZERO pitch-black interior surrounded by a glowing 2-3 texel border.
        {
            MetalityRtSceneHandle scene_v2 = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_v2 != nullptr, "Failed to create Test V2 scene");

            std::vector<uint16_t> b_v2(METALITY_PADDED_VOLUME, 0u);
            // Sealed cave room in section (0,3,0): world x=0..15, y=48..63, z=0..15
            for (int px = 1; px <= 16; ++px) {
                for (int pz = 1; pz <= 16; ++pz) {
                    b_v2[pad_idx(px, 2, pz)] = 1u;  // floor y=49
                    b_v2[pad_idx(px, 15, pz)] = 1u; // ceiling y=62
                }
            }
            for (int px = 1; px <= 16; ++px) {
                for (int py = 3; py <= 14; ++py) {
                    b_v2[pad_idx(px, py, 2)] = 1u;  // back wall z=1
                }
            }
            // Protruding 2x2 stone overhang block ledge at world x=7..8, y=53..54, z=7 (front face z=8.0, open air on -X/+X/-Y/+Y/+Z)
            for (int wx = 7; wx <= 8; ++wx) {
                for (int wy = 53; wy <= 54; ++wy) {
                    b_v2[pad_idx(wx + 1, wy - 48 + 1, 8)] = 1u;
                }
            }
            // Warm lava source slightly behind the z=8.0 plane & to the left/below: world (x=4, y=51..52, z=6)
            // -> centroid (4.5, 52.0, 6.5), giving ndL ≈ -0.35 on the overhang +Z face (old wrapBounce killer zone!)
            b_v2[pad_idx(5, 51 - 48 + 1, 7)] = 3u;
            b_v2[pad_idx(5, 52 - 48 + 1, 7)] = 3u;

            upload_sec_v(scene_v2, 0, 3, 0, b_v2);

            const float cam_v2x = 7.9f, cam_v2y = 53.8f, cam_v2z = 12.2f;
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_v2, cam_v2x, cam_v2y, cam_v2z), "Test V2 TLAS failed");

            const std::vector<TestAabbBox> boxes_v2 = {
                {0.0f, 49.0f, 0.0f, 16.0f, 50.0f, 16.0f, 0.42f, 0.42f, 0.42f, 2}, // floor
                {0.0f, 50.0f, 1.0f, 16.0f, 62.0f,  2.0f, 0.42f, 0.42f, 0.42f, 3}, // back wall
                {4.0f, 51.0f, 6.0f,  5.0f, 53.0f,  7.0f, 0.95f, 0.42f, 0.08f, 4}, // lava source
                {7.0f, 53.0f, 7.0f,  9.0f, 55.0f,  8.0f, 0.46f, 0.46f, 0.46f, 1}, // overhang +Z face (tag 1)
            };
            std::vector<uint16_t> color_v2;
            std::vector<float> depth_v2;
            std::vector<int> tag_v2;
            rasterize_aabb_scene(w, h, 1.22f, 0.05f, cam_v2x, cam_v2y, cam_v2z, boxes_v2, color_v2, depth_v2, tag_v2);

            auto eval_v2_overhang = [&](MetalityPackHandle pk, const char* label) {
                for (int iter = 0; iter < 2; ++iter) {
                    const uint64_t fid = metality_begin_frame(ctx);
                    metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, color_v2.data(), w * 8u);
                    metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, depth_v2.data(), w * 4u);
                    MetalityWorldFrameUniforms fv2 = make_high_uniforms(cam_v2x, cam_v2y, cam_v2z, 0.0f, -0.75f, -0.66f);
                    fv2.cam_world_pos[3] = static_cast<float>(iter + 1);
                    if (iter == 0) fv2.prev_view_proj[12] = 999.0f;
                    fv2.user_params[0] = 0.0f;
                    fv2.lighting_params[3] = 0.0f;
                    REQUIRE(metality_pack_execute(ctx, pk, scene_v2, ctex_hdr, dtex, &fv2), "Test V2 execute failed");
                    metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                    metality_end_frame(ctx, false);
                    REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test V2 timeout");
                }
                (void)assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 4u, h / 4u, w / 2u, h / 2u, 0.01f, 0.0f);

                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                uint32_t min_x = w, max_x = 0, min_y = h, max_y = 0;
                for (uint32_t py = 1; py + 1 < h; ++py) {
                    for (uint32_t px = 1; px + 1 < w; ++px) {
                        if (tag_v2[static_cast<size_t>(py) * w + px] == 1) {
                            min_x = std::min(min_x, px); max_x = std::max(max_x, px);
                            min_y = std::min(min_y, py); max_y = std::max(max_y, py);
                        }
                    }
                }
                double edge_sum = 0.0, int_sum = 0.0, left_rim_sum = 0.0, left_adj_sum = 0.0;
                float edge_max = 0.0f, int_min = 1e9f, int_max = 0.0f;
                uint32_t edge_n = 0u, int_n = 0u, left_rim_n = 0u, left_adj_n = 0u;
                // Measure outer convex -X/-Y rim facing the lava (py=0 is bottom in rasterize_aabb_scene)
                for (uint32_t py = min_y + 1u; py + 1u <= max_y; ++py) {
                    for (uint32_t px = min_x + 1u; px + 1u <= max_x; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        if (tag_v2[pidx] != 1) continue;
                        const float l = 0.2126f * decode_fp16(p16[pidx * 4u]) +
                                        0.7152f * decode_fp16(p16[pidx * 4u + 1u]) +
                                        0.0722f * decode_fp16(p16[pidx * 4u + 2u]);
                        const bool near_left_or_bottom_rim = (px <= min_x + 4u) || (py <= min_y + 4u);
                        const bool deep_interior = (px >= min_x + 8u && px + 6u <= max_x &&
                                                    py >= min_y + 8u && py + 6u <= max_y);
                        if (near_left_or_bottom_rim) {
                            edge_sum += l;
                            edge_max = std::max(edge_max, l);
                            ++edge_n;
                        } else if (deep_interior) {
                            int_sum += l;
                            int_min = std::min(int_min, l);
                            int_max = std::max(int_max, l);
                            ++int_n;
                        }
                        if (py >= min_y + 8u && py + 6u <= max_y) {
                            if (px <= min_x + 4u) { left_rim_sum += l; ++left_rim_n; }
                            else if (px >= min_x + 8u && px <= min_x + 14u) { left_adj_sum += l; ++left_adj_n; }
                        }
                    }
                }
                const float edge_avg = (edge_n > 0u) ? static_cast<float>(edge_sum / edge_n) : 0.0f;
                const float int_avg  = (int_n  > 0u) ? static_cast<float>(int_sum  / int_n)  : 0.0f;
                const float left_rim_avg = (left_rim_n > 0u) ? static_cast<float>(left_rim_sum / left_rim_n) : 0.0f;
                const float left_adj_avg = (left_adj_n > 0u) ? static_cast<float>(left_adj_sum / left_adj_n) : 0.0f;
                const float left_rim_spike = left_rim_avg - left_adj_avg;
                const float edge_to_int = edge_avg / std::max(int_avg, 0.005f);
                std::printf("[Test V2 OverhangRim %-6s] Edge(avg=%.3f,max=%.3f,n=%u) | "
                            "Interior(avg=%.3f,min=%.3f,max=%.3f,n=%u) | LeftRimSpike=%.4f | Edge/Int=%.2fx\n",
                            label, edge_avg, edge_max, edge_n,
                            int_avg, int_min, int_max, int_n, left_rim_spike, edge_to_int);
                REQUIRE(edge_n >= 80u && int_n >= 200u && left_rim_n >= 40u && left_adj_n >= 80u,
                        "Test V2 must resolve overhang rim and interior pixels");
                REQUIRE(int_avg >= 0.065f && int_min >= 0.045f,
                        "Back/side-lit cave overhang interior must NOT collapse to pitch black while adjacent cave is lava-lit");
                REQUIRE(left_rim_spike < 0.035f && edge_to_int < 1.55f,
                        "Back/side-lit cave overhang must NOT exhibit a glowing 2-3 texel edge rim around a darker interior");
            };
            eval_v2_overhang(pack_dusk, "Dusk");
            eval_v2_overhang(pack_zenith, "Zenith");

            metality_rt_scene_destroy(scene_v2);
        }
    }

    // -------------------------------------------------------------------------
    // Regression Test W1 & W2 (Round 10.7 Bug 1: Dusk Deep-Cave -Y Ceiling &
    //   Far Sunward Caster Sunlight Leak Guard + Narrow Daytime 1-Block-Roof
    //   Quartz Room with Stained-Glass Windows & Open Porch Control)
    // -------------------------------------------------------------------------
    {
        std::vector<MetalityBlockMaterialEntry> pal_w = palette;
        pal_w.resize(5);
        // Material 3: Quartz Block (tag=12, sub=8 smooth PBR solid)
        for (int f = 0; f < 6; ++f) pal_w[3].face_sprites[f] = 3;
        pal_w[3].layer = METALITY_LAYER_SOLID;
        pal_w[3].is_opaque_full_cube = 1;
        pal_w[3].wave_tag = 12;
        pal_w[3].emissive_level = 8;
        pal_w[3].alpha = 255;
        // Material 4: Red Stained Glass (tag=8, sub=2 translucent)
        for (int f = 0; f < 6; ++f) pal_w[4].face_sprites[f] = 4;
        pal_w[4].layer = METALITY_LAYER_TRANSLUCENT;
        pal_w[4].is_opaque_full_cube = 0;
        pal_w[4].wave_tag = 8;
        pal_w[4].emissive_level = 2;
        pal_w[4].alpha = 165;

        std::vector<MetalityPackedQuad> q_s_w(4096), q_c_w(4096), q_t_w(4096);
        MetalityMeshedSectionOutput mout_w{};
        mout_w.solid_quads = q_s_w.data(); mout_w.solid_quad_capacity = 4096;
        mout_w.cutout_quads = q_c_w.data(); mout_w.cutout_quad_capacity = 4096;
        mout_w.translucent_quads = q_t_w.data(); mout_w.translucent_quad_capacity = 4096;

        auto upload_sec_w = [&](MetalityRtSceneHandle sc, int sx, int sy, int sz, const std::vector<uint16_t>& blks) {
            MetalityChunkSectionInput inw{};
            inw.block_material_ids = blks.data();
            inw.packed_light = light.data();
            inw.biome_tint_rgb = tint.data();
            inw.palette_table = pal_w.data();
            inw.palette_size = static_cast<uint32_t>(pal_w.size());
            inw.enable_greedy_merge = 1;
            REQUIRE(metality_mesh_chunk_section_neon(&inw, &mout_w), "Test W meshing failed");
            REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, sc, sx, sy, sz,
                        q_s_w.data(), mout_w.solid_quad_count,
                        q_c_w.data(), mout_w.cutout_quad_count,
                        q_t_w.data(), mout_w.translucent_quad_count),
                    "Test W section upload failed");
        };

        // W1a: Deep underground cave whose ceiling section has ONLY -Y ceiling faces
        // (upper padded layer py=17 is solid stone, culling every +Y face; no surface section above!)
        {
            MetalityRtSceneHandle scene_w1a = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_w1a != nullptr, "Failed to create Test W1a scene");
            std::vector<uint16_t> b_cave(METALITY_PADDED_VOLUME, 0u);
            for (int px = 0; px < 18; ++px) {
                for (int pz = 0; pz < 18; ++pz) {
                    b_cave[pad_idx(px, 0, pz)] = 1u;
                    b_cave[pad_idx(px, 1, pz)] = 1u;
                    b_cave[pad_idx(px, 2, pz)] = 1u; // floor wy=1 (top y=2.0)
                    for (int py = 10; py < 18; ++py) {
                        b_cave[pad_idx(px, py, pz)] = 1u; // solid rock wy=9..15 + padded py=17 -> ONLY -Y ceiling faces!
                    }
                    if (px <= 1 || px >= 16 || pz <= 1 || pz >= 16) {
                        for (int py = 3; py <= 9; ++py) {
                            b_cave[pad_idx(px, py, pz)] = 1u;
                        }
                    }
                }
            }
            upload_sec_w(scene_w1a, 0, 0, 0, b_cave);

            const float cx = 8.0f, cy = 4.2f, cz = 12.0f;
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_w1a, cx, cy, cz), "Test W1a TLAS failed");
            const std::vector<TestAabbBox> boxes_w1a = {
                {1.0f, 1.0f, 1.0f, 15.0f, 2.0f, 15.0f, 0.48f, 0.48f, 0.48f, 1}, // cave floor y=2.0
                {1.0f, 2.0f, 0.0f, 15.0f, 9.0f,  1.0f, 0.44f, 0.44f, 0.44f, 2}, // back cave wall z=1.0
                {0.0f, 2.0f, 1.0f,  1.0f, 9.0f, 15.0f, 0.44f, 0.44f, 0.44f, 2}, // left wall
                {15.0f, 2.0f, 1.0f, 16.0f, 9.0f, 15.0f, 0.44f, 0.44f, 0.44f, 2}, // right wall
                {1.0f, 9.0f, 1.0f, 15.0f, 10.0f, 15.0f, 0.44f, 0.44f, 0.44f, 3}, // -Y cave ceiling
            };
            std::vector<uint16_t> col_w1a;
            std::vector<float> dep_w1a;
            std::vector<int> tag_w1a;
            rasterize_aabb_scene(w, h, 1.22f, 0.05f, cx, cy, cz, boxes_w1a, col_w1a, dep_w1a, tag_w1a);

            auto eval_w1a = [&](MetalityPackHandle pk, const char* label) {
                for (int iter = 0; iter < 2; ++iter) {
                    const uint64_t fid = metality_begin_frame(ctx);
                    metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, col_w1a.data(), w * 8u);
                    metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, dep_w1a.data(), w * 4u);
                    // Blazing midday sun directly overhead (+Y)
                    MetalityWorldFrameUniforms fu = make_high_uniforms(cx, cy, cz, 0.14f, 0.96f, -0.24f);
                    fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                    if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                    fu.user_params[0] = 0.0f;
                    fu.lighting_params[3] = 0.0f;
                    REQUIRE(metality_pack_execute(ctx, pk, scene_w1a, ctex_hdr, dtex, &fu), "Test W1a execute failed");
                    metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                    metality_end_frame(ctx, false);
                    REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test W1a timeout");
                }
                (void)assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 4u, h / 4u, w / 2u, h / 2u, 0.0f, 1.0f);
                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                double fl_sum = 0.0;
                float fl_max = 0.0f;
                float fl_max_wx = 0.0f, fl_max_wz = 0.0f;
                uint32_t fl_cnt = 0u, fl_leak_cnt = 0u;
                const float f_w1 = 1.0f / std::tan(1.22f * 0.5f);
                const float aspect_w1 = static_cast<float>(w) / static_cast<float>(h);
                for (uint32_t py = 2; py + 2 < h; ++py) {
                    for (uint32_t px = 2; px + 2 < w; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        if (tag_w1a[pidx] != 1) continue;
                        if (tag_w1a[pidx - 1u] != 1 || tag_w1a[pidx + 1u] != 1 ||
                            tag_w1a[pidx - w] != 1 || tag_w1a[pidx + w] != 1) continue;
                        const float l = 0.2126f * decode_fp16(p16[pidx * 4u]) +
                                        0.7152f * decode_fp16(p16[pidx * 4u + 1u]) +
                                        0.0722f * decode_fp16(p16[pidx * 4u + 2u]);
                        fl_sum += l;
                        if (l > 0.14f) ++fl_leak_cnt;
                        if (l > fl_max) {
                            fl_max = l;
                            const float t_z = 0.05f / dep_w1a[pidx];
                            const float dx = (((static_cast<float>(px) + 0.5f) / static_cast<float>(w)) * 2.0f - 1.0f) * (aspect_w1 / f_w1);
                            fl_max_wx = cx + t_z * dx;
                            fl_max_wz = cz - t_z;
                        }
                        ++fl_cnt;
                    }
                }
                const float fl_avg = (fl_cnt > 0u) ? static_cast<float>(fl_sum / fl_cnt) : 0.0f;
                std::printf("[Test W1a Cave-YCeil %-6s] Floor(avg=%.3f,max=%.3f@wx=%.2f,wz=%.2f,leak=%u,n=%u)\n",
                            label, fl_avg, fl_max, fl_max_wx, fl_max_wz, fl_leak_cnt, fl_cnt);
                REQUIRE(fl_cnt >= 400u, "Test W1a must resolve cave floor pixels");
                REQUIRE(fl_avg < 0.09f && fl_max < 0.14f,
                        "Deep cave with -Y-only ceiling faces must block 100% of direct midday sunlight (no backface-cull or PCSS wall-void leak)");
            };
            eval_w1a(pack_dusk, "Dusk");
            eval_w1a(pack_zenith, "Zenith");
            metality_rt_scene_destroy(scene_w1a);
        }

        // W1b: Far sunward caster (>180m along sun ray L, sz_c < -z_cull_rad) relying on MTLDepthClipModeClamp
        {
            MetalityRtSceneHandle scene_w1b = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_w1b != nullptr, "Failed to create Test W1b scene");
            std::vector<uint16_t> b_floor(METALITY_PADDED_VOLUME, 0u);
            for (int px = 1; px <= 16; ++px) {
                for (int pz = 1; pz <= 16; ++pz) {
                    b_floor[pad_idx(px, 2, pz)] = 1u; // open ground wy=1 (top y=2.0)
                }
            }
            upload_sec_w(scene_w1b, 0, 0, 0, b_floor);

            const float cx = 8.0f, cy = 4.2f, cz = 12.0f;
            const std::vector<TestAabbBox> boxes_w1b = {
                {0.0f, 1.0f, 0.0f, 16.0f, 2.0f, 16.0f, 0.50f, 0.50f, 0.50f, 1},
            };
            std::vector<uint16_t> col_w1b;
            std::vector<float> dep_w1b;
            std::vector<int> tag_w1b;
            rasterize_aabb_scene(w, h, 1.22f, 0.05f, cx, cy, cz, boxes_w1b, col_w1b, dep_w1b, tag_w1b);

            auto measure_w1b_floor = [&]() -> float {
                REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_w1b, cx, cy, cz), "Test W1b TLAS failed");
                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, col_w1b.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, dep_w1b.data(), w * 4u);
                // 45-deg sun toward (0, +0.7071, -0.7071)
                MetalityWorldFrameUniforms fu = make_high_uniforms(cx, cy, cz, 0.0f, 0.7071f, -0.7071f);
                fu.user_params[0] = 0.0f;
                fu.lighting_params[3] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pack_dusk, scene_w1b, ctex_hdr, dtex, &fu), "Test W1b execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test W1b timeout");
                const FrameHealthStats st = assert_frame_healthy(
                    rb_hdr_ptr, w, h, "W1b", w / 3u, h / 8u, w / 3u, h / 6u, 0.01f, 0.0f);
                return st.roi_avg_luma;
            };
            const float lit_luma = measure_w1b_floor();

            // Place a solid roof slab 212m away along sun ray L=(0, +0.7071, -0.7071):
            // sec (0, 9, -9) covers wx=[0..15], wy=[144..159], wz=[-144..-129] -> sz_c ≈ -206m < -176m!
            std::vector<uint16_t> b_far_roof(METALITY_PADDED_VOLUME, 0u);
            for (int px = 1; px <= 16; ++px) {
                for (int pz = 1; pz <= 16; ++pz) {
                    b_far_roof[pad_idx(px, 8, pz)] = 1u;
                    b_far_roof[pad_idx(px, 9, pz)] = 1u;
                }
            }
            upload_sec_w(scene_w1b, 0, 9, -9, b_far_roof);
            upload_sec_w(scene_w1b, 0, 9, -10, b_far_roof);
            const float shd_luma = measure_w1b_floor();
            std::printf("[Test W1b FarSunClamp Dusk ] Sunlit=%.3f | ClampedFarRoof(206m)=%.3f (ratio=%.2fx)\n",
                        lit_luma, shd_luma, lit_luma / std::max(shd_luma, 0.001f));
            REQUIRE(shd_luma < lit_luma * 0.72f,
                    "Far sunward caster (>180m along L) must NOT be CPU-culled before MTLDepthClipModeClamp in Dusk C0");
            metality_rt_scene_destroy(scene_w1b);
        }

        // W2: Daytime narrow (5x4 interior) above-ground 1-block-roof Quartz hallway with
        //     stained-glass windows (Image 1 repro) + open-sided outdoor porch overhang control
        {
            MetalityRtSceneHandle scene_w2 = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_w2 != nullptr, "Failed to create Test W2 scene");

            // W2a: Sealed narrow 5x4 above-ground quartz room on flat outdoor ground with 2 stained-glass windows on right wall
            std::vector<uint16_t> b_room(METALITY_PADDED_VOLUME, 0u);
            for (int px = 1; px <= 16; ++px) {
                for (int pz = 1; pz <= 16; ++pz) {
                    b_room[pad_idx(px, 2, pz)] = 1u; // flat outdoor ground wy=49 (top y=50.0)
                }
            }
            // Narrow quartz hallway: wx=[5..11] (interior wx=[6..10] = 5 blocks wide), wy=[49..54] (interior wy=[50..53] = 4 blocks tall), wz=[2..14]
            for (int wx = 5; wx <= 11; ++wx) {
                for (int wz = 2; wz <= 14; ++wz) {
                    b_room[pad_idx(wx + 1, 2, wz + 1)] = 3u; // quartz floor wy=49 (top y=50.0)
                    b_room[pad_idx(wx + 1, 7, wz + 1)] = 3u; // 1-block thick solid quartz roof wy=54 (underside y=54.0, topSolidY=55)
                    for (int wy = 50; wy <= 53; ++wy) {
                        if (wx == 5 || wx == 11 || wz == 2 || wz == 14) {
                            const bool is_win = (wx == 11 && wy >= 50 && wy <= 52 && (wz == 6 || wz == 9));
                            b_room[pad_idx(wx + 1, wy - 48 + 1, wz + 1)] = is_win ? 4u : 3u;
                        }
                    }
                }
            }
            upload_sec_w(scene_w2, 0, 3, 0, b_room);

            const float cx = 8.5f, cy = 51.8f, cz = 12.6f;
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_w2, cx, cy, cz), "Test W2a TLAS failed");

            const std::vector<TestAabbBox> boxes_w2a = {
                {6.0f, 49.0f, 3.0f, 11.0f, 50.0f, 14.0f, 0.85f, 0.85f, 0.85f, 1}, // quartz floor y=50.0
                {5.0f, 50.0f, 3.0f,  6.0f, 54.0f, 14.0f, 0.85f, 0.85f, 0.85f, 2}, // shaded left quartz wall x=6.0
                {6.0f, 54.0f, 3.0f, 11.0f, 55.0f, 14.0f, 0.85f, 0.85f, 0.85f, 3}, // 1-block roof ceiling y=54.0
                {6.0f, 50.0f, 2.0f, 11.0f, 54.0f,  3.0f, 0.85f, 0.85f, 0.85f, 4}, // shaded back quartz wall z=3.0
                {11.0f, 50.0f, 3.0f, 12.0f, 54.0f, 14.0f, 0.85f, 0.85f, 0.85f, 5}, // right quartz wall x=11.0
            };
            std::vector<uint16_t> col_w2a;
            std::vector<float> dep_w2a;
            std::vector<int> tag_w2a;
            rasterize_aabb_scene(w, h, 1.22f, 0.05f, cx, cy, cz, boxes_w2a, col_w2a, dep_w2a, tag_w2a);

            const float f_w2 = 1.0f / std::tan(1.22f * 0.5f);
            const float aspect_w2 = static_cast<float>(w) / static_cast<float>(h);

            auto eval_w2a = [&](MetalityPackHandle pk, const char* label) {
                for (int iter = 0; iter < 2; ++iter) {
                    const uint64_t fid = metality_begin_frame(ctx);
                    metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, col_w2a.data(), w * 8u);
                    metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, dep_w2a.data(), w * 4u);
                    // Daytime sun from +X/+Y shining through the right-wall red stained-glass windows onto the floor
                    MetalityWorldFrameUniforms fu = make_high_uniforms(cx, cy, cz, 0.72f, 0.68f, -0.14f);
                    fu.cam_world_pos[3] = static_cast<float>(iter + 1);
                    if (iter == 0) fu.prev_view_proj[12] = 999.0f;
                    fu.user_params[0] = 0.0f;
                    fu.lighting_params[3] = 0.0f;
                    REQUIRE(metality_pack_execute(ctx, pk, scene_w2, ctex_hdr, dtex, &fu), "Test W2a execute failed");
                    metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                    metality_end_frame(ctx, false);
                    REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test W2a timeout");
                }
                (void)assert_frame_healthy(rb_hdr_ptr, w, h, label, w / 4u, h / 4u, w / 2u, h / 2u, 0.0f, 1.0f);
                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                double lw_sum = 0.0, cl_sum = 0.0, bw_sum = 0.0, edge_sum = 0.0, beam_l_sum = 0.0, beam_r_sum = 0.0, beam_b_sum = 0.0;
                uint32_t lw_n = 0u, cl_n = 0u, bw_n = 0u, edge_n = 0u, beam_n = 0u;
                for (uint32_t py = 2; py + 2 < h; ++py) {
                    for (uint32_t px = 2; px + 2 < w; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        const int tg = tag_w2a[pidx];
                        if (tg < 1 || tg > 4) continue;
                        if (tag_w2a[pidx - 1u] != tg || tag_w2a[pidx + 1u] != tg ||
                            tag_w2a[pidx - w] != tg || tag_w2a[pidx + w] != tg) continue;
                        const float r = decode_fp16(p16[pidx * 4u]);
                        const float g = decode_fp16(p16[pidx * 4u + 1u]);
                        const float b = decode_fp16(p16[pidx * 4u + 2u]);
                        const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                        if (tg == 2) { lw_sum += l; ++lw_n; }
                        else if (tg == 3) { cl_sum += l; ++cl_n; }
                        else if (tg == 4) { bw_sum += l; ++bw_n; }
                        else if (tg == 1 && dep_w2a[pidx] > 0.0f) {
                            const float t_z = 0.05f / dep_w2a[pidx];
                            const float dx = (((static_cast<float>(px) + 0.5f) / static_cast<float>(w)) * 2.0f - 1.0f) * (aspect_w2 / f_w2);
                            const float wx = cx + t_z * dx;
                            const float wz = cz - t_z;
                            // Shaded floor edge within 1.5m of left exterior wall (away from window sunbeams)
                            if (wx < 7.5f && wz < 5.5f) {
                                edge_sum += l; ++edge_n;
                            }
                            // Crimson stained-glass sunbeam hitting floor opposite wz=9 window
                            if (wx >= 8.2f && wx <= 10.6f && wz >= 8.8f && wz <= 9.9f && r > b * 1.12f) {
                                beam_l_sum += l; beam_r_sum += r; beam_b_sum += b; ++beam_n;
                            }
                        }
                    }
                }
                const float lw_avg = (lw_n > 0u) ? static_cast<float>(lw_sum / lw_n) : 0.0f;
                const float cl_avg = (cl_n > 0u) ? static_cast<float>(cl_sum / cl_n) : 0.0f;
                const float bw_avg = (bw_n > 0u) ? static_cast<float>(bw_sum / bw_n) : 0.0f;
                const float edge_avg = (edge_n > 0u) ? static_cast<float>(edge_sum / edge_n) : 0.0f;
                const float beam_l = (beam_n > 0u) ? static_cast<float>(beam_l_sum / beam_n) : 0.0f;
                const float beam_r = (beam_n > 0u) ? static_cast<float>(beam_r_sum / beam_n) : 0.0f;
                const float beam_b = (beam_n > 0u) ? static_cast<float>(beam_b_sum / beam_n) : 0.0f;
                std::printf("[Test W2a QuartzRoom %-6s] LeftWall=%.3f(n=%u) | Ceil=%.3f(n=%u) | BackWall=%.3f(n=%u) | FloorEdge=%.3f(n=%u) | Beam(L=%.3f,R=%.3f,B=%.3f,n=%u)\n",
                            label, lw_avg, lw_n, cl_avg, cl_n, bw_avg, bw_n, edge_avg, edge_n, beam_l, beam_r, beam_b, beam_n);
                REQUIRE(lw_n >= 200u && cl_n >= 200u && bw_n >= 200u && edge_n >= 40u && beam_n >= 30u,
                        "Test W2a must resolve quartz walls, 1-block ceiling, floor edge, and stained-glass sunbeam");
                REQUIRE(lw_avg < 0.22f && cl_avg < 0.22f && bw_avg < 0.22f && edge_avg < 0.22f,
                        "Daytime narrow 1-block-roof quartz room walls, ceiling & floor edges must stay dark (no 2D heightmap sky leak)");
                REQUIRE(beam_l > edge_avg + 0.08f && beam_r > beam_b * 1.20f,
                        "Stained-glass sunbeam on quartz room floor must remain bright and vividly tinted");
            };
            eval_w2a(pack_dusk, "Dusk");
            eval_w2a(pack_zenith, "Zenith");

            // W2b: Open-sided outdoor porch overhang control (same 1-block roof wy=54, NO side/front walls)
            std::vector<uint16_t> b_porch(METALITY_PADDED_VOLUME, 0u);
            for (int px = 1; px <= 16; ++px) {
                for (int pz = 1; pz <= 16; ++pz) {
                    b_porch[pad_idx(px, 2, pz)] = 1u; // open outdoor ground wy=49
                }
            }
            for (int wx = 5; wx <= 11; ++wx) {
                for (int wz = 2; wz <= 14; ++wz) {
                    b_porch[pad_idx(wx + 1, 7, wz + 1)] = 3u; // open-sided 1-block porch roof wy=54
                    if (wz == 2) {
                        for (int wy = 50; wy <= 53; ++wy) {
                            b_porch[pad_idx(wx + 1, wy - 48 + 1, wz + 1)] = 3u; // back house wall only
                        }
                    }
                }
            }
            upload_sec_w(scene_w2, 0, 3, 0, b_porch);
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_w2, cx, cy, cz), "Test W2b TLAS failed");
            const std::vector<TestAabbBox> boxes_w2b = {
                {0.0f, 49.0f, 0.0f, 16.0f, 50.0f, 16.0f, 0.65f, 0.65f, 0.65f, 1}, // porch floor y=50.0
                {5.0f, 50.0f, 2.0f, 12.0f, 54.0f,  3.0f, 0.65f, 0.65f, 0.65f, 2}, // back wall
                {5.0f, 54.0f, 2.0f, 12.0f, 55.0f, 15.0f, 0.65f, 0.65f, 0.65f, 3}, // porch roof
            };
            std::vector<uint16_t> col_w2b;
            std::vector<float> dep_w2b;
            std::vector<int> tag_w2b;
            rasterize_aabb_scene(w, h, 1.22f, 0.05f, cx, cy, cz, boxes_w2b, col_w2b, dep_w2b, tag_w2b);
            {
                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, col_w2b.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, dep_w2b.data(), w * 4u);
                // High midday sun so porch floor directly under roof is shaded from direct sun, lit by side open sky!
                MetalityWorldFrameUniforms fu = make_high_uniforms(cx, cy, cz, 0.08f, 0.98f, -0.16f);
                fu.user_params[0] = 0.0f;
                fu.lighting_params[3] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pack_dusk, scene_w2, ctex_hdr, dtex, &fu), "Test W2b execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test W2b timeout");
                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                double porch_sum = 0.0;
                uint32_t porch_n = 0u;
                for (uint32_t py = 2; py + 2 < h; ++py) {
                    for (uint32_t px = w / 3u; px < (w * 2u) / 3u; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        if (tag_w2b[pidx] != 1) continue;
                        const float l = 0.2126f * decode_fp16(p16[pidx * 4u]) +
                                        0.7152f * decode_fp16(p16[pidx * 4u + 1u]) +
                                        0.0722f * decode_fp16(p16[pidx * 4u + 2u]);
                        porch_sum += l;
                        ++porch_n;
                    }
                }
                const float porch_avg = (porch_n > 0u) ? static_cast<float>(porch_sum / porch_n) : 0.0f;
                std::printf("[Test W2b OpenPorch Dusk  ] ShadedPorchFloor=%.3f(n=%u)\n", porch_avg, porch_n);
                REQUIRE(porch_n >= 200u && porch_avg >= 0.14f,
                        "Open-sided outdoor porch overhang must retain natural side-sky daylight (not crush to sealed-room black)");
            }
            metality_rt_scene_destroy(scene_w2);
        }

        // W3: Dusk Point-Light Flat-Floor Radial Monotonicity (Glow Lichen & Torch — ZERO Circular Ring / Trench!)
        //     Reproduces Round 10.7 Image 3 (glow lichen ndL=0.12 circle at r≈4.14m) & Image 4 (torch falloff at r≈4.55m).
        {
            MetalityRtSceneHandle scene_w3 = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_w3 != nullptr, "Failed to create Test W3 scene");
            std::vector<uint16_t> b_w3(METALITY_PADDED_VOLUME, 0u);
            for (int px = 1; px <= 16; ++px) {
                for (int pz = 1; pz <= 16; ++pz) {
                    b_w3[pad_idx(px, 2, pz)] = 1u;  // cave floor wy=49 (top y=50.0)
                    b_w3[pad_idx(px, 15, pz)] = 1u; // sealed cave roof wy=62
                }
            }
            for (int px = 1; px <= 16; ++px) {
                for (int py = 3; py <= 14; ++py) {
                    b_w3[pad_idx(px, py, 1)] = 1u;  // back wall wz=0
                    b_w3[pad_idx(px, py, 16)] = 1u; // rear wall wz=15
                }
            }
            upload_sec_w(scene_w3, 0, 3, 0, b_w3);

            const float cx = 8.0f, cy = 53.8f, cz = 13.0f;
            const std::vector<TestAabbBox> boxes_w3 = {
                {0.0f, 49.0f, 0.0f, 16.0f, 50.0f, 16.0f, 0.34f, 0.34f, 0.36f, 1}, // dark deepslate cave floor y=50.0
                {0.0f, 50.0f, 0.0f, 16.0f, 62.0f,  1.0f, 0.34f, 0.34f, 0.36f, 2}, // back wall
                {0.0f, 62.0f, 0.0f, 16.0f, 63.0f, 16.0f, 0.34f, 0.34f, 0.36f, 3}, // ceiling
            };
            std::vector<uint16_t> col_w3;
            std::vector<float> dep_w3;
            std::vector<int> tag_w3;
            rasterize_aabb_scene(w, h, 1.22f, 0.05f, cx, cy, cz, boxes_w3, col_w3, dep_w3, tag_w3);

            const float f_w3 = 1.0f / std::tan(1.22f * 0.5f);
            const float aspect_w3 = static_cast<float>(w) / static_cast<float>(h);

            auto eval_w3_radial = [&](const char* case_name, float lx, float ly, float lz,
                                      float pwr, float lr, float lg, float lb, float lrad,
                                      float min_mid_luma) {
                const float dl[8] = {lx, ly, lz, pwr, lr, lg, lb, lrad};
                metality_rt_scene_set_dynamic_lights(scene_w3, dl, 1u);
                REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_w3, cx, cy, cz), "Test W3 TLAS failed");

                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, col_w3.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, dep_w3.data(), w * 4u);
                MetalityWorldFrameUniforms fu = make_high_uniforms(cx, cy, cz, 0.0f, -0.75f, -0.66f);
                fu.user_params[0] = 0.0f;
                fu.lighting_params[3] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pack_dusk, scene_w3, ctex_hdr, dtex, &fu), "Test W3 execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test W3 timeout");
                (void)assert_frame_healthy(rb_hdr_ptr, w, h, case_name, w / 4u, h / 8u, w / 2u, h / 3u, 0.005f, 0.0f);

                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                // 0.5m radial bins from r=0.5m to r=7.5m (bins 1..14, directly spanning ndL=0.12 @ r≈4.1..4.6m)
                double bin_sum[16] = {};
                uint32_t bin_cnt[16] = {};
                for (uint32_t py = 2; py + 2 < h; ++py) {
                    for (uint32_t px = 2; px + 2 < w; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        if (tag_w3[pidx] != 1 || dep_w3[pidx] <= 0.0f) continue;
                        if (tag_w3[pidx - 1u] != 1 || tag_w3[pidx + 1u] != 1 ||
                            tag_w3[pidx - w] != 1 || tag_w3[pidx + w] != 1) continue;
                        const float t_z = 0.05f / dep_w3[pidx];
                        const float dx = (((static_cast<float>(px) + 0.5f) / static_cast<float>(w)) * 2.0f - 1.0f) * (aspect_w3 / f_w3);
                        const float wx = cx + t_z * dx;
                        const float wz = cz - t_z;
                        const float rad_h = std::hypot(wx - lx, wz - lz);
                        const int b = static_cast<int>(rad_h * 2.0f);
                        if (b >= 1 && b <= 14) {
                            const float l = 0.2126f * decode_fp16(p16[pidx * 4u]) +
                                            0.7152f * decode_fp16(p16[pidx * 4u + 1u]) +
                                            0.0722f * decode_fp16(p16[pidx * 4u + 2u]);
                            bin_sum[b] += l;
                            ++bin_cnt[b];
                        }
                    }
                }
                float bin_avg[16] = {};
                float max_up_jump = 0.0f;
                int worst_bin = -1;
                uint32_t valid_bins = 0u;
                for (int b = 1; b <= 14; ++b) {
                    if (bin_cnt[b] >= 15u) {
                        bin_avg[b] = static_cast<float>(bin_sum[b] / bin_cnt[b]);
                        ++valid_bins;
                    }
                }
                for (int b = 1; b < 14; ++b) {
                    if (bin_cnt[b] >= 15u && bin_cnt[b + 1] >= 15u) {
                        const float diff = bin_avg[b + 1] - bin_avg[b];
                        if (diff > max_up_jump) {
                            max_up_jump = diff;
                            worst_bin = b;
                        }
                    }
                }
                std::printf("[Test W3 RadialMono %-10s] r1.0=%.3f r2.5=%.3f r3.5=%.3f r4.0=%.3f r4.5=%.3f r5.0=%.3f r6.5=%.3f | MaxUpJump=%.5f@b%d (bins=%u)\n",
                            case_name, bin_avg[2], bin_avg[5], bin_avg[7], bin_avg[8], bin_avg[9], bin_avg[10], bin_avg[13],
                            max_up_jump, worst_bin, valid_bins);
                REQUIRE(valid_bins >= 12u, "Test W3 must resolve radial floor bins across r=0.5..7.5m");
                REQUIRE(bin_avg[2] > bin_avg[7] && bin_avg[7] > bin_avg[13],
                        "Point-light radial profile on flat cave floor must decay cleanly with distance");
                REQUIRE(max_up_jump < 0.004f,
                        "Point light on flat cave floor must NEVER exhibit a circular dark trench / upward step ring (ndL=0.12 cliff)");
                REQUIRE(bin_avg[7] >= min_mid_luma,
                        "Point-light mid-range (r=3.5m) must remain warmly lit instead of crashing into a dark muddy moat");
            };

            eval_w3_radial("GlowLichen", 4.5f, 50.50f, 6.5f,  5.71f, 0.625f, 1.000f, 0.772f, 0.28f, 0.045f);
            eval_w3_radial("TorchFloor", 4.5f, 50.55f, 6.5f, 14.57f, 1.000f, 0.520f, 0.140f, 0.28f, 0.095f);
            metality_rt_scene_destroy(scene_w3);
        }

        // W4: Dusk 1-Block Cave Pillar Soft Shadow Penumbra Gradient Width & Zero-Flicker Stability (Image 4 repro)
        {
            MetalityRtSceneHandle scene_w4 = metality_rt_scene_create(ctx, 16u, false);
            REQUIRE(scene_w4 != nullptr, "Failed to create Test W4 scene");
            std::vector<uint16_t> b_w4(METALITY_PADDED_VOLUME, 0u);
            for (int px = 1; px <= 16; ++px) {
                for (int pz = 1; pz <= 16; ++pz) {
                    b_w4[pad_idx(px, 2, pz)] = 1u;  // floor wy=49 (top y=50.0)
                    b_w4[pad_idx(px, 15, pz)] = 1u; // sealed cave roof wy=62
                }
            }
            for (int px = 1; px <= 16; ++px) {
                for (int py = 3; py <= 14; ++py) {
                    b_w4[pad_idx(px, py, 1)] = 1u;  // back wall wz=0
                    b_w4[pad_idx(px, py, 16)] = 1u; // front wall wz=15 (blocks moonlight)
                }
            }
            for (int pz = 1; pz <= 16; ++pz) {
                for (int py = 3; py <= 14; ++py) {
                    b_w4[pad_idx(1, py, pz)] = 1u;  // left wall wx=0
                    b_w4[pad_idx(16, py, pz)] = 1u; // right wall wx=15
                }
            }
            // 1-block solid stone shadow caster at world (wx=7, wy=50, wz=6) -> pad(8, 3, 7)
            b_w4[pad_idx(8, 3, 7)] = 1u;
            upload_sec_w(scene_w4, 0, 3, 0, b_w4);

            // Warm torch on floor left of the 1-block caster at (5.2, 50.55, 6.5)
            const float torch_w4[8] = {5.2f, 50.55f, 6.5f, 14.57f, 1.000f, 0.520f, 0.140f, 0.28f};
            metality_rt_scene_set_dynamic_lights(scene_w4, torch_w4, 1u);

            const float cx = 9.5f, cy = 53.0f, cz = 14.5f;
            REQUIRE(metality_rt_scene_rebuild_tlas(ctx, scene_w4, cx, cy, cz), "Test W4 TLAS failed");

            const std::vector<TestAabbBox> boxes_w4 = {
                {1.0f, 49.0f, 1.0f, 15.0f, 50.0f, 15.0f, 0.44f, 0.44f, 0.44f, 1}, // cave floor y=50.0
                {1.0f, 50.0f, 0.0f, 15.0f, 62.0f,  1.0f, 0.44f, 0.44f, 0.44f, 2}, // back wall
                {7.0f, 50.0f, 6.0f,  8.0f, 51.0f,  7.0f, 0.44f, 0.44f, 0.44f, 3}, // 1-block stone caster
            };
            const float f_w4 = 1.0f / std::tan(1.22f * 0.5f);
            const float aspect_w4 = static_cast<float>(w) / static_cast<float>(h);

            auto run_w4_pose = [&](float camx, float camy, float camz, int fidx,
                                   float& umbra_avg, float& lit_avg, float& pen_avg,
                                   uint32_t& pen_mid_cnt, uint32_t& umbra_n, uint32_t& lit_n, uint32_t& pen_n) {
                std::vector<uint16_t> col;
                std::vector<float> dep;
                std::vector<int> tag;
                rasterize_aabb_scene(w, h, 1.22f, 0.05f, camx, camy, camz, boxes_w4, col, dep, tag);

                const uint64_t fid = metality_begin_frame(ctx);
                metality_texture_upload_region(ctx, ctex_hdr, 0, 0, 0, w, h, col.data(), w * 8u);
                metality_texture_upload_region(ctx, dtex, 0, 0, 0, w, h, dep.data(), w * 4u);
                MetalityWorldFrameUniforms fu = make_high_uniforms(camx, camy, camz, 0.0f, -0.75f, -0.66f);
                fu.cam_world_pos[3] = static_cast<float>(fidx);
                fu.user_params[0] = 0.0f;
                fu.lighting_params[3] = 0.0f;
                REQUIRE(metality_pack_execute(ctx, pack_dusk, scene_w4, ctex_hdr, dtex, &fu), "Test W4 execute failed");
                metality_cmd_copy_texture_to_buffer(ctx, ctex_hdr, rb_hdr_buf, 0, w * 8u, w, h);
                metality_end_frame(ctx, false);
                REQUIRE(metality_wait_frame_complete(ctx, fid, 4000), "Test W4 timeout");
                (void)assert_frame_healthy(rb_hdr_ptr, w, h, "W4", w / 4u, h / 8u, w / 2u, h / 3u, 0.005f, 0.0f);

                const uint16_t* p16 = static_cast<const uint16_t*>(rb_hdr_ptr);
                double u_sum = 0.0, l_sum = 0.0, p_sum = 0.0;
                umbra_n = 0u; lit_n = 0u; pen_n = 0u; pen_mid_cnt = 0u;
                std::vector<float> pen_vals;
                for (uint32_t py = 2; py + 2 < h; ++py) {
                    for (uint32_t px = 2; px + 2 < w; ++px) {
                        const size_t pidx = static_cast<size_t>(py) * w + px;
                        if (tag[pidx] != 1 || dep[pidx] <= 0.0f) continue;
                        if (tag[pidx - 1u] != 1 || tag[pidx + 1u] != 1 ||
                            tag[pidx - w] != 1 || tag[pidx + w] != 1) continue;
                        const float t_z = 0.05f / dep[pidx];
                        const float dx = (((static_cast<float>(px) + 0.5f) / static_cast<float>(w)) * 2.0f - 1.0f) * (aspect_w4 / f_w4);
                        const float wx = camx + t_z * dx;
                        const float wz = camz - t_z;
                        if (wx < 8.8f || wx > 10.6f) continue;
                        const float l = 0.2126f * decode_fp16(p16[pidx * 4u]) +
                                        0.7152f * decode_fp16(p16[pidx * 4u + 1u]) +
                                        0.0722f * decode_fp16(p16[pidx * 4u + 2u]);
                        if (wz >= 6.1f && wz <= 6.8f) {
                            u_sum += l; ++umbra_n;
                        } else if (wz >= 8.8f && wz <= 9.8f) {
                            l_sum += l; ++lit_n;
                        } else if (wz >= 7.0f && wz <= 8.6f) {
                            p_sum += l; ++pen_n;
                            pen_vals.push_back(l);
                        }
                    }
                }
                umbra_avg = (umbra_n > 0u) ? static_cast<float>(u_sum / umbra_n) : 0.0f;
                lit_avg   = (lit_n   > 0u) ? static_cast<float>(l_sum / lit_n)   : 0.0f;
                pen_avg   = (pen_n   > 0u) ? static_cast<float>(p_sum / pen_n)   : 0.0f;
                const float span = std::max(lit_avg - umbra_avg, 0.001f);
                const float lo = umbra_avg + 0.20f * span;
                const float hi = umbra_avg + 0.80f * span;
                for (float v : pen_vals) {
                    if (v >= lo && v <= hi) ++pen_mid_cnt;
                }
            };

            float u1 = 0, l1 = 0, p1 = 0, u2 = 0, l2 = 0, p2 = 0;
            uint32_t pm1 = 0, un1 = 0, ln1 = 0, pn1 = 0, pm2 = 0, un2 = 0, ln2 = 0, pn2 = 0;
            run_w4_pose(cx, cy, cz, 1, u1, l1, p1, pm1, un1, ln1, pn1);
            run_w4_pose(cx + 0.04f, cy + 0.02f, cz, 2, u2, l2, p2, pm2, un2, ln2, pn2);
            const float pen_subpx_delta = std::fabs(p2 - p1);
            const float pen_mid_ratio = (pn1 > 0u) ? (static_cast<float>(pm1) / static_cast<float>(pn1)) : 0.0f;
            std::printf("[Test W4 SoftPenumbra Dusk ] Umbra=%.3f(n=%u) | Lit=%.3f(n=%u) | PenMid=%u/%u(%.1f%%) | SubPxDelta=%.5f\n",
                        u1, un1, l1, ln1, pm1, pn1, pen_mid_ratio * 100.0f, pen_subpx_delta);
            REQUIRE(un1 >= 40u && ln1 >= 60u && pn1 >= 80u, "Test W4 must resolve umbra, lit, and penumbra floor regions");
            REQUIRE(l1 > u1 + 0.04f, "1-block stone pillar must cast a visible shadow from floor torch");
            REQUIRE(pm1 >= 25u && pen_mid_ratio >= 0.20f,
                    "Dusk point-light shadow edge must exhibit a smooth multi-step soft penumbra (not a razor-hard binary cliff)");
            REQUIRE(pen_subpx_delta < 0.010f,
                    "Dusk soft point-light penumbra must remain flicker-free under sub-pixel camera jitter");
            metality_rt_scene_destroy(scene_w4);
        }
    }

    metality_buffer_retire(ctx, rb_hdr_buf);
    metality_texture_destroy(ctx, ctex_hdr);
    metality_buffer_retire(ctx, rb_buf);
    metality_rt_scene_destroy(rt_scene);
    metality_pack_destroy(pack_zenith);
    metality_pack_destroy(pack_dusk);
    metality_context_destroy(ctx);
    std::printf("=== PHASE 5 METEOR ZENITH & METEOR DUSK TESTS PASSED ===\n");
    return 0;
}

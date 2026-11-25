#include "metality/metality_c_api.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <numeric>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#ifndef METALITY_METEOR_ZENITH_DIR
#define METALITY_METEOR_ZENITH_DIR ""
#endif
#ifndef METALITY_METEOR_DUSK_DIR
#define METALITY_METEOR_DUSK_DIR ""
#endif

#define BENCH_REQUIRE(cond, msg)                                                  \
    do {                                                                          \
        if (!(cond)) {                                                            \
            std::fprintf(stderr, "[BENCH FAIL] %s (%s:%d)\n", msg, __FILE__, __LINE__); \
            std::exit(1);                                                         \
        }                                                                         \
    } while (0)

namespace {

using Clock = std::chrono::steady_clock;

inline int pad_idx(int px, int py, int pz) {
    return (py * 18 + pz) * 18 + px;
}

inline int interior_idx(int x, int y, int z) {
    return (y * 16 + z) * 16 + x;
}

struct SampleStats {
    double avg = 0.0;
    double min = 0.0;
    double max = 0.0;
    double p50 = 0.0;
    double p95 = 0.0;
    double p99 = 0.0;
};

SampleStats compute_stats(std::vector<double> values) {
    SampleStats s{};
    if (values.empty()) {
        return s;
    }
    std::sort(values.begin(), values.end());
    const double sum = std::accumulate(values.begin(), values.end(), 0.0);
    s.avg = sum / static_cast<double>(values.size());
    s.min = values.front();
    s.max = values.back();
    auto pct = [&](double p) -> double {
        if (values.size() == 1) return values[0];
        const double idx = p * static_cast<double>(values.size() - 1);
        const size_t lo = static_cast<size_t>(std::floor(idx));
        const size_t hi = std::min(values.size() - 1, lo + 1);
        const double frac = idx - static_cast<double>(lo);
        return values[lo] * (1.0 - frac) + values[hi] * frac;
    };
    s.p50 = pct(0.50);
    s.p95 = pct(0.95);
    s.p99 = pct(0.99);
    return s;
}

std::string json_escape(const std::string& in) {
    std::ostringstream oss;
    for (char c : in) {
        switch (c) {
            case '"': oss << "\\\""; break;
            case '\\': oss << "\\\\"; break;
            case '\n': oss << "\\n"; break;
            default: oss << c; break;
        }
    }
    return oss.str();
}

void fill_identity(float m[16]) {
    std::memset(m, 0, sizeof(float) * 16);
    m[0] = m[5] = m[10] = m[15] = 1.0f;
}

void fill_perspective_reversed_z(float fov_rad, float aspect, float z_near, float proj[16], float inv_proj[16]) {
    const float f = 1.0f / std::tan(fov_rad * 0.5f);
    std::memset(proj, 0, sizeof(float) * 16);
    proj[0] = f / aspect;
    proj[5] = f;
    proj[10] = 0.0f;
    proj[11] = -1.0f;
    proj[14] = z_near;

    std::memset(inv_proj, 0, sizeof(float) * 16);
    inv_proj[0] = aspect / f;
    inv_proj[5] = 1.0f / f;
    inv_proj[11] = 1.0f / z_near;
    inv_proj[14] = -1.0f;
}

void fill_standard_perspective_vp(
    float fov_rad,
    float aspect,
    float z_near,
    float z_far,
    float yaw_rad,
    float pitch_rad,
    float out_vp[16]
) {
    // View-rotation-only (camera at origin in camera-relative coords) looking along yaw/pitch
    const float cy = std::cos(yaw_rad), sy = std::sin(yaw_rad);
    const float cp = std::cos(pitch_rad), sp = std::sin(pitch_rad);
    const float f[3] = { sy * cp, sp, -cy * cp };
    const float r[3] = { cy, 0.0f, sy };
    const float u[3] = {
        f[1] * r[2] - f[2] * r[1],
        f[2] * r[0] - f[0] * r[2],
        f[0] * r[1] - f[1] * r[0]
    };

    float v[16]{};
    // Column-major view matrix (rows: r, u, -f)
    v[0] = r[0]; v[4] = r[1]; v[8]  = r[2];
    v[1] = u[0]; v[5] = u[1]; v[9]  = u[2];
    v[2] = -f[0]; v[6] = -f[1]; v[10] = -f[2];
    v[15] = 1.0f;

    const float tan_half = std::tan(fov_rad * 0.5f);
    const float sy_p = 1.0f / tan_half;
    const float sx_p = sy_p / aspect;
    float p[16]{};
    p[0] = sx_p;
    p[5] = sy_p;
    p[10] = z_far / (z_near - z_far);
    p[11] = -1.0f;
    p[14] = (z_far * z_near) / (z_near - z_far);

    for (int col = 0; col < 4; ++col) {
        for (int row = 0; row < 4; ++row) {
            float acc = 0.0f;
            for (int k = 0; k < 4; ++k) {
                acc += p[k * 4 + row] * v[col * 4 + k];
            }
            out_vp[col * 4 + row] = acc;
        }
    }
}

void build_lookat_inv_vp(
    const float eye[3],
    const float center[3],
    float fov_rad,
    float aspect,
    float z_near,
    float z_far,
    float out_inv_vp[16]
) {
    float fwd[3] = {center[0] - eye[0], center[1] - eye[1], center[2] - eye[2]};
    const float fl = std::sqrt(fwd[0]*fwd[0] + fwd[1]*fwd[1] + fwd[2]*fwd[2]);
    fwd[0] /= fl; fwd[1] /= fl; fwd[2] /= fl;
    const float up_h[3] = {0.0f, 1.0f, 0.0f};
    float right[3] = {
        fwd[1]*up_h[2] - fwd[2]*up_h[1],
        fwd[2]*up_h[0] - fwd[0]*up_h[2],
        fwd[0]*up_h[1] - fwd[1]*up_h[0]
    };
    const float rl = std::sqrt(right[0]*right[0] + right[1]*right[1] + right[2]*right[2]);
    right[0] /= rl; right[1] /= rl; right[2] /= rl;
    float up[3] = {
        right[1]*fwd[2] - right[2]*fwd[1],
        right[2]*fwd[0] - right[0]*fwd[2],
        right[0]*fwd[1] - right[1]*fwd[0]
    };

    const float tan_h = std::tan(fov_rad * 0.5f);
    float c2w[16]{};
    c2w[0] = right[0]; c2w[4] = up[0]; c2w[8]  = -fwd[0]; c2w[12] = eye[0];
    c2w[1] = right[1]; c2w[5] = up[1]; c2w[9]  = -fwd[1]; c2w[13] = eye[1];
    c2w[2] = right[2]; c2w[6] = up[2]; c2w[10] = -fwd[2]; c2w[14] = eye[2];
    c2w[15] = 1.0f;

    float inv_p[16]{};
    inv_p[0] = aspect * tan_h;
    inv_p[5] = tan_h;
    inv_p[11] = (z_near - z_far) / (z_far * z_near);
    inv_p[14] = -1.0f;
    inv_p[15] = 1.0f / z_near;

    for (int c = 0; c < 4; ++c) {
        for (int r = 0; r < 4; ++r) {
            float acc = 0.0f;
            for (int k = 0; k < 4; ++k) {
                acc += c2w[k * 4 + r] * inv_p[c * 4 + k];
            }
            out_inv_vp[c * 4 + r] = acc;
        }
    }
}

struct MaterialPaletteSetup {
    std::vector<MetalityBlockMaterialEntry> entries;
    // Indices:
    // 0 = air
    // 1 = stone (solid)
    // 2 = grass (solid)
    // 3 = oak_leaves (cutout, wave=2)
    // 4 = water (translucent, wave=3, emissive=8 -> full depth water)
    // 5 = pearlescent_froglight / sea_lantern (solid, wave=5, emissive=15)
    // 6 = iron_block (solid, wave=12, emissive=1 -> PBR iron)
    // 7 = red_stained_glass (translucent, wave=8, emissive=2 -> red stained glass)
    // 8 = diamond_ore (solid, wave=12, emissive=11 -> glowing crystal ore)
    MaterialPaletteSetup() {
        entries.resize(9);
        entries[0] = {};
        entries[0].layer = METALITY_LAYER_EMPTY;
        entries[1] = {{1, 1, 1, 1, 1, 1}, METALITY_LAYER_SOLID, 1, 0, 0, 255, 0};
        entries[2] = {{2, 2, 1, 3, 2, 2}, METALITY_LAYER_SOLID, 1, 0, 0, 255, 0};
        entries[3] = {{4, 4, 4, 4, 4, 4}, METALITY_LAYER_CUTOUT, 0, 2, 0, 255, 0};
        entries[4] = {{5, 5, 5, 5, 5, 5}, METALITY_LAYER_TRANSLUCENT, 0, 3, 8, 180, 0};
        entries[5] = {{6, 6, 6, 6, 6, 6}, METALITY_LAYER_SOLID, 1, 5, 15, 255, 0};
        entries[6] = {{7, 7, 7, 7, 7, 7}, METALITY_LAYER_SOLID, 1, 12, 1, 255, 0};
        entries[7] = {{8, 8, 8, 8, 8, 8}, METALITY_LAYER_TRANSLUCENT, 0, 8, 2, 165, 0};
        entries[8] = {{9, 9, 9, 9, 9, 9}, METALITY_LAYER_SOLID, 1, 12, 11, 255, 0};
    }
};

struct SectionVoxelData {
    std::vector<uint16_t> blocks;
    std::vector<uint8_t> light;
    std::vector<uint32_t> tint;

    SectionVoxelData()
        : blocks(METALITY_PADDED_VOLUME, 0u),
          light(METALITY_PADDED_VOLUME, 0xF0u),
          tint(4096u, 0x80B070u) {}
};

SectionVoxelData make_flat_slab_section() {
    SectionVoxelData d;
    for (int py = 1; py <= 4; ++py) {
        for (int pz = 1; pz <= 16; ++pz) {
            for (int px = 1; px <= 16; ++px) {
                d.blocks[pad_idx(px, py, pz)] = (py == 4) ? 2u : 1u;
            }
        }
    }
    return d;
}

SectionVoxelData make_complex_world_section(int seed) {
    SectionVoxelData d;
    for (int py = 0; py < 18; ++py) {
        for (int pz = 0; pz < 18; ++pz) {
            for (int px = 0; px < 18; ++px) {
                const int h = 7 + ((px * 5 + pz * 3 + seed) % 5) - ((px ^ pz) & 1);
                if (py < h - 1) {
                    const bool cave = ((px * 13 + py * 7 + pz * 11 + seed) % 19) == 0;
                    if (!cave) {
                        const bool ore = ((px + py * 3 + pz * 5) % 23) == 0;
                        d.blocks[pad_idx(px, py, pz)] = ore ? 8u : 1u;
                    }
                } else if (py == h - 1) {
                    d.blocks[pad_idx(px, py, pz)] = (h <= 7) ? 1u : 2u;
                } else if (py <= 7) {
                    d.blocks[pad_idx(px, py, pz)] = 4u; // river water
                } else if (py == h && ((px + pz + seed) % 7 == 0)) {
                    d.blocks[pad_idx(px, py, pz)] = 3u; // cutout foliage
                }
            }
        }
    }
    // Add PBR iron arch, red stained glass window, and emissive froglight block
    for (int py = 8; py <= 11; ++py) {
        d.blocks[pad_idx(5, py, 5)] = 6u;
        d.blocks[pad_idx(6, py, 5)] = 7u;
    }
    d.blocks[pad_idx(8, 8, 8)] = 5u;
    d.tint[interior_idx(7, 7, 7)] = 0xFFD8F0u;
    d.tint[interior_idx(4, 7, 4)] = 0xD0D8E0u;
    d.tint[interior_idx(5, 7, 4)] = 0x3828E8u;
    return d;
}

SectionVoxelData make_waterfall_section() {
    SectionVoxelData d;
    for (int pz = 1; pz <= 16; ++pz) {
        for (int px = 1; px <= 16; ++px) {
            d.blocks[pad_idx(px, 2, pz)] = 1u;
            d.blocks[pad_idx(px, 3, pz)] = 4u; // lower water pool
            if (pz <= 5) {
                d.blocks[pad_idx(px, 12, pz)] = 1u;
            }
        }
    }
    // 1-block free-falling & stepped waterfall column
    for (int py = 4; py <= 14; ++py) {
        d.blocks[pad_idx(8, py, 6)] = 4u;
        d.blocks[pad_idx(9, py, 6)] = 4u;
        if (py >= 10) {
            d.blocks[pad_idx(8, py, 5)] = 4u;
            d.blocks[pad_idx(9, py, 5)] = 4u;
        }
    }
    d.blocks[pad_idx(7, 5, 6)] = 5u;
    d.blocks[pad_idx(10, 5, 6)] = 8u;
    return d;
}

SectionVoxelData make_dense_cutout_checkerboard() {
    SectionVoxelData d;
    for (int py = 1; py <= 16; ++py) {
        for (int pz = 1; pz <= 16; ++pz) {
            for (int px = 1; px <= 16; ++px) {
                if (((px + py + pz) & 1) == 0) {
                    d.blocks[pad_idx(px, py, pz)] = 3u;
                }
            }
        }
    }
    return d;
}

// Result data model for JSON & Markdown exports
struct MesherArchetypeResult {
    std::string name;
    uint32_t unmerged_quads = 0;
    uint32_t greedy_quads = 0;
    uint32_t solid_quads = 0;
    uint32_t cutout_quads = 0;
    uint32_t trans_quads = 0;
    double us_per_section_1t = 0.0;
    double sections_per_sec_1t = 0.0;
    double mquads_per_sec_1t = 0.0;
    double us_per_section_mt = 0.0;
    double sections_per_sec_mt = 0.0;
    double vanilla_kb = 0.0;
    double sodium_kb = 0.0;
    double metality_kb = 0.0;
    double vram_ratio_vs_vanilla = 0.0;
};

struct TbdrResResult {
    std::string label;
    uint32_t width = 0;
    uint32_t height = 0;
    double compile_us = 0.0;
    uint32_t passes = 0;
    uint32_t fused_groups = 0;
    uint32_t memoryless = 0;
    double saved_mb_frame = 0.0;
    double saved_gbs_60fps = 0.0;
    double saved_gbs_120fps = 0.0;
};

struct TerrainGpuResult {
    uint32_t active_sections = 0;
    uint64_t total_quads = 0;
    double cpu_encode_us = 0.0;
    double gpu_hw_ms = 0.0;
    double sync_wall_ms = 0.0;
};

struct RtSceneBuildResult {
    uint32_t sections = 0;
    double ingest_blas_ms_total = 0.0;
    double ingest_blas_ms_per_sec = 0.0;
    double tlas_rebuild_ms = 0.0;
    uint32_t active_blas = 0;
};

struct RtUpscalerModeResult {
    std::string name;
    double gi_gpu_ms = 0.0;
    double upscale_gpu_ms = 0.0;
    double total_gpu_ms = 0.0;
    double effective_fps = 0.0;
};

struct ShaderScenarioResult {
    std::string category; // "Scenario", "Profile", "Resolution"
    std::string name;
    uint32_t width = 0;
    uint32_t height = 0;
    double cpu_submit_ms = 0.0;
    SampleStats gpu_hw_ms;
    SampleStats sync_wall_ms;
    double pipelined_fps = 0.0;
    double pipelined_ms = 0.0;
    double mpix_per_sec = 0.0;
    double tbdr_saved_mb = 0.0;
};

struct FullBenchmarkReport {
    MetalityDeviceCaps caps{};
    MetalityUmaPressureInfo uma{};
    uint32_t hw_threads = 1;
    bool full_mode = false;

    // Suite 2: Arena
    uint32_t arena_ops = 0;
    double arena_alloc_mops = 0.0;
    double arena_free_mops = 0.0;
    double arena_ns_per_pair = 0.0;
    uint32_t arena_final_spans = 0;

    // Suite 3: Mesher
    std::vector<MesherArchetypeResult> mesher_results;

    // Suite 4: TBDR
    std::vector<TbdrResResult> tbdr_results;

    // Suite 5: Pack compile cold vs warm
    uint32_t pack_passes = 0;
    uint32_t pack_psos = 0;
    uint32_t pack_memoryless = 0;
    double pack_cold_ms = 0.0;
    double pack_warm_ms = 0.0;

    // Suite 6: Terrain ICB
    std::vector<TerrainGpuResult> terrain_results;

    // Suite 7: RT Scene Build
    std::vector<RtSceneBuildResult> rt_build_results;

    // Suite 8: RT Mode + MetalFX
    std::vector<RtUpscalerModeResult> rt_mode_results;

    // Suite 9 & 10: Meteor Shader
    std::vector<ShaderScenarioResult> shader_results;
};

// Synchronize GPU frame and let addCompletedHandler populate exact hardware GPUEndTime-GPUStartTime
void finish_and_measure_frame(
    MetalityContext* ctx,
    uint64_t fid,
    const Clock::time_point& t_submit_start,
    const Clock::time_point& t_submit_end,
    double& out_cpu_submit_ms,
    double& out_sync_wall_ms,
    double& out_hw_gpu_ms
) {
    metality_end_frame(ctx, false);
    const auto t_wait0 = Clock::now();
    BENCH_REQUIRE(metality_wait_frame_complete(ctx, fid, 15000), "GPU frame completion timeout");
    const auto t_wait1 = Clock::now();

    out_cpu_submit_ms = std::chrono::duration<double, std::milli>(t_submit_end - t_submit_start).count();
    out_sync_wall_ms = std::chrono::duration<double, std::milli>(t_wait1 - t_wait0).count();

    // Allow Metal's completion handler thread to finish writing CB.GPUEndTime - CB.GPUStartTime.
    std::this_thread::sleep_for(std::chrono::microseconds(600));
    out_hw_gpu_ms = metality_last_gpu_frame_ms(ctx);
}

void write_json_report(const std::string& path, const FullBenchmarkReport& r) {
    std::ofstream f(path);
    if (!f) {
        std::fprintf(stderr, "Warning: could not open JSON output path: %s\n", path.c_str());
        return;
    }
    f << std::fixed << std::setprecision(4);
    f << "{\n";
    f << "  \"hardware\": {\n";
    f << "    \"device_name\": \"" << json_escape(r.caps.device_name) << "\",\n";
    f << "    \"apple_gpu_family\": " << r.caps.apple_gpu_family << ",\n";
    f << "    \"recommended_working_set_gb\": " << (static_cast<double>(r.caps.recommended_max_working_set_bytes) / (1024.0 * 1024.0 * 1024.0)) << ",\n";
    f << "    \"tile_memory_kb\": " << (r.caps.max_tile_memory_bytes / 1024.0) << ",\n";
    f << "    \"unified_memory\": " << (r.caps.has_unified_memory ? "true" : "false") << ",\n";
    f << "    \"hardware_raytracing\": " << (r.caps.supports_hardware_raytracing ? "true" : "false") << ",\n";
    f << "    \"metalfx_temporal\": " << (r.caps.supports_metalfx_temporal ? "true" : "false") << ",\n";
    f << "    \"metal4_queue\": " << (r.caps.supports_metal4_command_queue ? "true" : "false") << ",\n";
    f << "    \"hw_threads\": " << r.hw_threads << ",\n";
    f << "    \"uma_utilization_ratio\": " << r.uma.utilization_ratio << ",\n";
    f << "    \"thermal_state\": " << r.uma.thermal_state_level << "\n";
    f << "  },\n";

    f << "  \"arena\": {\n";
    f << "    \"ops\": " << r.arena_ops << ",\n";
    f << "    \"alloc_mops\": " << r.arena_alloc_mops << ",\n";
    f << "    \"free_mops\": " << r.arena_free_mops << ",\n";
    f << "    \"ns_per_pair\": " << r.arena_ns_per_pair << ",\n";
    f << "    \"final_free_spans\": " << r.arena_final_spans << "\n";
    f << "  },\n";

    f << "  \"mesher\": [\n";
    for (size_t i = 0; i < r.mesher_results.size(); ++i) {
        const auto& m = r.mesher_results[i];
        f << "    {\"name\": \"" << json_escape(m.name) << "\", "
          << "\"unmerged_quads\": " << m.unmerged_quads << ", "
          << "\"greedy_quads\": " << m.greedy_quads << ", "
          << "\"us_per_section_1t\": " << m.us_per_section_1t << ", "
          << "\"sections_per_sec_1t\": " << m.sections_per_sec_1t << ", "
          << "\"us_per_section_mt\": " << m.us_per_section_mt << ", "
          << "\"sections_per_sec_mt\": " << m.sections_per_sec_mt << ", "
          << "\"vanilla_kb\": " << m.vanilla_kb << ", "
          << "\"sodium_kb\": " << m.sodium_kb << ", "
          << "\"metality_kb\": " << m.metality_kb << ", "
          << "\"compression_vs_vanilla\": " << m.vram_ratio_vs_vanilla << "}"
          << (i + 1 < r.mesher_results.size() ? "," : "") << "\n";
    }
    f << "  ],\n";

    f << "  \"tbdr\": [\n";
    for (size_t i = 0; i < r.tbdr_results.size(); ++i) {
        const auto& t = r.tbdr_results[i];
        f << "    {\"label\": \"" << json_escape(t.label) << "\", "
          << "\"width\": " << t.width << ", \"height\": " << t.height << ", "
          << "\"compile_us\": " << t.compile_us << ", "
          << "\"passes\": " << t.passes << ", \"fused_groups\": " << t.fused_groups << ", "
          << "\"memoryless\": " << t.memoryless << ", "
          << "\"saved_mb_per_frame\": " << t.saved_mb_frame << ", "
          << "\"saved_gbs_120fps\": " << t.saved_gbs_120fps << "}"
          << (i + 1 < r.tbdr_results.size() ? "," : "") << "\n";
    }
    f << "  ],\n";

    f << "  \"shader_pack_compile\": {\n";
    f << "    \"passes\": " << r.pack_passes << ", \"psos\": " << r.pack_psos << ", "
      << "\"memoryless\": " << r.pack_memoryless << ", "
      << "\"cold_ms\": " << r.pack_cold_ms << ", \"warm_ms\": " << r.pack_warm_ms << "\n";
    f << "  },\n";

    f << "  \"terrain_icb\": [\n";
    for (size_t i = 0; i < r.terrain_results.size(); ++i) {
        const auto& tr = r.terrain_results[i];
        f << "    {\"sections\": " << tr.active_sections << ", \"quads\": " << tr.total_quads << ", "
          << "\"cpu_encode_us\": " << tr.cpu_encode_us << ", \"gpu_hw_ms\": " << tr.gpu_hw_ms << ", "
          << "\"sync_wall_ms\": " << tr.sync_wall_ms << "}"
          << (i + 1 < r.terrain_results.size() ? "," : "") << "\n";
    }
    f << "  ],\n";

    f << "  \"rt_scene_build\": [\n";
    for (size_t i = 0; i < r.rt_build_results.size(); ++i) {
        const auto& b = r.rt_build_results[i];
        f << "    {\"sections\": " << b.sections << ", \"ingest_blas_ms\": " << b.ingest_blas_ms_total << ", "
          << "\"ms_per_section\": " << b.ingest_blas_ms_per_sec << ", \"tlas_rebuild_ms\": " << b.tlas_rebuild_ms << "}"
          << (i + 1 < r.rt_build_results.size() ? "," : "") << "\n";
    }
    f << "  ],\n";

    f << "  \"rt_upscaler_modes\": [\n";
    for (size_t i = 0; i < r.rt_mode_results.size(); ++i) {
        const auto& u = r.rt_mode_results[i];
        f << "    {\"mode\": \"" << json_escape(u.name) << "\", \"gi_gpu_ms\": " << u.gi_gpu_ms << ", "
          << "\"upscale_gpu_ms\": " << u.upscale_gpu_ms << ", \"total_gpu_ms\": " << u.total_gpu_ms << ", "
          << "\"fps\": " << u.effective_fps << "}"
          << (i + 1 < r.rt_mode_results.size() ? "," : "") << "\n";
    }
    f << "  ],\n";

    f << "  \"meteor_shader\": [\n";
    for (size_t i = 0; i < r.shader_results.size(); ++i) {
        const auto& s = r.shader_results[i];
        f << "    {\"category\": \"" << json_escape(s.category) << "\", \"name\": \"" << json_escape(s.name) << "\", "
          << "\"width\": " << s.width << ", \"height\": " << s.height << ", "
          << "\"cpu_submit_ms\": " << s.cpu_submit_ms << ", "
          << "\"gpu_avg_ms\": " << s.gpu_hw_ms.avg << ", \"gpu_p50_ms\": " << s.gpu_hw_ms.p50 << ", "
          << "\"gpu_p95_ms\": " << s.gpu_hw_ms.p95 << ", \"gpu_p99_ms\": " << s.gpu_hw_ms.p99 << ", "
          << "\"sync_avg_ms\": " << s.sync_wall_ms.avg << ", "
          << "\"pipelined_fps\": " << s.pipelined_fps << ", \"pipelined_ms\": " << s.pipelined_ms << ", "
          << "\"mpix_per_sec\": " << s.mpix_per_sec << ", \"tbdr_saved_mb\": " << s.tbdr_saved_mb << "}"
          << (i + 1 < r.shader_results.size() ? "," : "") << "\n";
    }
    f << "  ]\n";
    f << "}\n";
}

void write_markdown_report(const std::string& path, const FullBenchmarkReport& r) {
    std::ofstream f(path);
    if (!f) {
        std::fprintf(stderr, "Warning: could not open Markdown output path: %s\n", path.c_str());
        return;
    }
    f << std::fixed << std::setprecision(2);
    f << "# Metality Engine & Meteor Shader Benchmark Report\n\n";
    f << "## 1. Hardware & UMA Capabilities\n";
    f << "- **Device**: " << r.caps.device_name << " (Apple GPU Family " << r.caps.apple_gpu_family << ")\n";
    f << "- **Recommended Working Set**: " << (static_cast<double>(r.caps.recommended_max_working_set_bytes) / (1024.0 * 1024.0 * 1024.0)) << " GB\n";
    f << "- **TBDR Tile Memory**: " << (r.caps.max_tile_memory_bytes / 1024.0) << " KB\n";
    f << "- **Features**: UMA=" << (int)r.caps.has_unified_memory
      << " | HWRT=" << (int)r.caps.supports_hardware_raytracing
      << " | MetalFX=" << (int)r.caps.supports_metalfx_temporal
      << " | Metal4Queue=" << (int)r.caps.supports_metal4_command_queue
      << " | ArgBufT2=" << (int)r.caps.supports_argument_buffers_tier2 << "\n";
    f << "- **CPU Threads**: " << r.hw_threads << " | Thermal Level: " << r.uma.thermal_state_level << "\n\n";

    f << "## 2. UMA Chunk Arena Allocator\n";
    f << "- **Interleaved Alloc/Free Pairs**: " << r.arena_ops
      << " | Alloc: " << r.arena_alloc_mops << " M ops/s"
      << " | Free+Coalesce: " << r.arena_free_mops << " M ops/s"
      << " | Latency: " << r.arena_ns_per_pair << " ns/pair"
      << " | Final Free Spans: " << r.arena_final_spans << "\n\n";

    f << "## 3. ARM NEON SIMD Greedy Mesher & VRAM Compression\n";
    f << "| Archetype | Unmerged -> Greedy Quads | 1T Speed (us/sec) | 1T Throughput | Multi-Thread Throughput | Metality / Sodium / Vanilla VRAM |\n";
    f << "|---|---:|---:|---:|---:|---|\n";
    for (const auto& m : r.mesher_results) {
        f << "| " << m.name << " | " << m.unmerged_quads << " -> " << m.greedy_quads
          << " | " << m.us_per_section_1t << " us | " << std::setprecision(0) << m.sections_per_sec_1t
          << " sec/s | " << m.sections_per_sec_mt << " sec/s | " << std::setprecision(2)
          << m.metality_kb << " KB vs " << m.sodium_kb << " KB vs " << m.vanilla_kb
          << " KB (**" << m.vram_ratio_vs_vanilla << "x**) |\n";
    }
    f << "\n";

    f << "## 4. TBDR Render Graph & Memoryless DRAM Savings\n";
    f << "| Resolution | Graph Compile | Pass Fusion | Memoryless | DRAM Saved / Frame | Bandwidth Saved @120Hz |\n";
    f << "|---|---:|---:|---:|---:|---:|\n";
    for (const auto& t : r.tbdr_results) {
        f << "| " << t.label << " (" << t.width << "x" << t.height << ") | "
          << t.compile_us << " us | " << t.passes << " -> " << t.fused_groups << " groups | "
          << t.memoryless << " tex | " << t.saved_mb_frame << " MB | " << t.saved_gbs_120fps << " GB/s |\n";
    }
    f << "\n";

    f << "## 5. Meteor Shader Pack Compile (Cold vs Warm MTLBinaryArchive)\n";
    f << "- **Passes**: " << r.pack_passes << " | **Compiled PSOs**: " << r.pack_psos
      << " | **Memoryless**: " << r.pack_memoryless
      << " | **Cold Compile**: " << r.pack_cold_ms << " ms"
      << " | **Warm Archive Load**: " << r.pack_warm_ms << " ms\n\n";

    f << "## 6. GPU Compute Frustum Cull & ICB Terrain Draw\n";
    f << "| Active Sections | Visible Quads | CPU Encode | GPU Kernel | Sync Wall |\n";
    f << "|---:|---:|---:|---:|---:|\n";
    for (const auto& tr : r.terrain_results) {
        f << "| " << tr.active_sections << " | " << tr.total_quads << " | "
          << tr.cpu_encode_us << " us | " << tr.gpu_hw_ms << " ms | " << tr.sync_wall_ms << " ms |\n";
    }
    f << "\n";

    f << "## 7. Hardware RT Scene Build (BLAS + TLAS + 512x128x512 Voxel Ring)\n";
    f << "| Sections | Ingest+BLAS Total | Per-Section | TLAS+Lights+Ripples Rebuild |\n";
    f << "|---:|---:|---:|---:|\n";
    for (const auto& b : r.rt_build_results) {
        f << "| " << b.sections << " | " << b.ingest_blas_ms_total << " ms | "
          << b.ingest_blas_ms_per_sec << " ms/sec | " << b.tlas_rebuild_ms << " ms |\n";
    }
    f << "\n";

    f << "## 8. Ray Tracing Mode & MetalFX Upscaler (1280x720 -> 2560x1440)\n";
    f << "| Configuration | RT Dispatch GPU | MetalFX Encode GPU | Total GPU | Effective FPS |\n";
    f << "|---|---:|---:|---:|---:|\n";
    for (const auto& u : r.rt_mode_results) {
        f << "| " << u.name << " | " << u.gi_gpu_ms << " ms | " << u.upscale_gpu_ms
          << " ms | " << u.total_gpu_ms << " ms | " << u.effective_fps << " FPS |\n";
    }
    f << "\n";

    f << "## 9. Meteor Shader 17-Pass Pipeline (Scenarios, Profiles & Resolutions)\n";
    f << "| Category | Benchmark | Resolution | CPU Submit | GPU Avg / P50 / P95 | Pipelined FPS | Pixel Rate | TBDR Saved |\n";
    f << "|---|---|---:|---:|---:|---:|---:|---:|\n";
    for (const auto& s : r.shader_results) {
        f << "| " << s.category << " | " << s.name << " | " << s.width << "x" << s.height
          << " | " << s.cpu_submit_ms << " ms | "
          << s.gpu_hw_ms.avg << " / " << s.gpu_hw_ms.p50 << " / " << s.gpu_hw_ms.p95 << " ms | "
          << std::setprecision(1) << s.pipelined_fps << " FPS | "
          << std::setprecision(1) << s.mpix_per_sec << " MPix/s | "
          << std::setprecision(2) << s.tbdr_saved_mb << " MB |\n";
    }
}

} // namespace

int main(int argc, char** argv) {
    std::string meteor_zenith_dir = METALITY_METEOR_ZENITH_DIR;
    std::string meteor_dusk_dir = METALITY_METEOR_DUSK_DIR;
    bool full_mode = false;
    std::string json_out_path;
    std::string md_out_path;

    int pos_arg = 0;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--full") {
            full_mode = true;
        } else if (arg == "--json" && i + 1 < argc) {
            json_out_path = argv[++i];
        } else if (arg == "--markdown" && i + 1 < argc) {
            md_out_path = argv[++i];
        } else if (!arg.empty() && arg[0] != '-') {
            if (pos_arg == 0) meteor_zenith_dir = arg;
            else if (pos_arg == 1) meteor_dusk_dir = arg;
            pos_arg++;
        }
    }

    BENCH_REQUIRE(!meteor_zenith_dir.empty() && std::filesystem::exists(meteor_zenith_dir + "/pack.ts"),
                  "Missing shaderpacks/meteor-zenith directory");
    BENCH_REQUIRE(!meteor_dusk_dir.empty() && std::filesystem::exists(meteor_dusk_dir + "/pack.ts"),
                  "Missing shaderpacks/meteor-dusk directory");

    const std::string cache_dir = "/tmp/metality_benchmark_cache";
    std::filesystem::remove_all(cache_dir);

    MetalityConfig cfg{};
    cfg.cache_directory_utf8 = cache_dir.c_str();
    cfg.frames_in_flight = 3;
    cfg.enable_validation = 0; // Disable Metal API validation overhead in benchmark mode
    cfg.enable_edr_hdr = 1;

    MetalityContext* ctx = metality_context_create(&cfg);
    BENCH_REQUIRE(ctx != nullptr, "Failed to create Metal context");

    FullBenchmarkReport report;
    report.full_mode = full_mode;
    report.hw_threads = std::max(1u, std::thread::hardware_concurrency());
    metality_query_capabilities(ctx, &report.caps);
    metality_query_uma_pressure(ctx, &report.uma);

    std::printf("====================================================================================================\n");
    std::printf("  METALITY ENGINE & METEOR SHADER COMPREHENSIVE BENCHMARK (%s MODE)\n",
                full_mode ? "FULL" : "FAST-CTEST");
    std::printf("====================================================================================================\n");
    std::printf("[Suite 1] GPU: %s | Apple%u | UMA=%.1f GB | TileMem=%u KB | HWRT=%u | MetalFX=%u | Metal4=%u | Cores=%u\n",
                report.caps.device_name,
                report.caps.apple_gpu_family,
                static_cast<double>(report.caps.recommended_max_working_set_bytes) / (1024.0 * 1024.0 * 1024.0),
                report.caps.max_tile_memory_bytes / 1024u,
                report.caps.supports_hardware_raytracing,
                report.caps.supports_metalfx_temporal,
                report.caps.supports_metal4_command_queue,
                report.hw_threads);

    // -------------------------------------------------------------------------
    // Suite 2: UMA Chunk Arena Allocator
    // -------------------------------------------------------------------------
    {
        const uint32_t kOps = full_mode ? 32768u : 4096u;
        MetalityArenaHandle arena = metality_arena_create(256ULL * 1024ULL * 1024ULL, 256u);
        BENCH_REQUIRE(arena != nullptr, "Failed to create 256MB UMA arena");

        struct Span { uint64_t off; uint64_t sz; };
        std::vector<Span> spans(kOps);

        const auto a0 = Clock::now();
        for (uint32_t i = 0; i < kOps; ++i) {
            const uint64_t req = static_cast<uint64_t>(((i * 199u) % 15u + 1u) * 512u);
            BENCH_REQUIRE(metality_arena_alloc(arena, req, &spans[i].off, &spans[i].sz), "Arena alloc failed");
        }
        const auto a1 = Clock::now();

        // Prime-strided out-of-order deallocation to stress buddy/span coalescing
        const auto f0 = Clock::now();
        for (uint32_t i = 0; i < kOps; i += 2) {
            metality_arena_free(arena, spans[i].off, spans[i].sz);
        }
        for (uint32_t i = 1; i < kOps; i += 2) {
            metality_arena_free(arena, spans[i].off, spans[i].sz);
        }
        const auto f1 = Clock::now();

        report.arena_ops = kOps;
        const double alloc_us = std::chrono::duration<double, std::micro>(a1 - a0).count();
        const double free_us = std::chrono::duration<double, std::micro>(f1 - f0).count();
        report.arena_alloc_mops = kOps / std::max(alloc_us, 1e-6);
        report.arena_free_mops = kOps / std::max(free_us, 1e-6);
        report.arena_ns_per_pair = ((alloc_us + free_us) * 1000.0) / kOps;
        report.arena_final_spans = metality_arena_free_span_count(arena);
        BENCH_REQUIRE(metality_arena_used_bytes(arena) == 0, "Arena leak detected");
        BENCH_REQUIRE(report.arena_final_spans == 1u, "Arena failed to coalesce to 1 span");
        metality_arena_destroy(arena);

        std::printf("[Suite 2] UMA Chunk Arena (%u interleaved spans): Alloc=%.2f M ops/s | Free=%.2f M ops/s | %.1f ns/pair | Spans=%u\n",
                    report.arena_ops, report.arena_alloc_mops, report.arena_free_mops,
                    report.arena_ns_per_pair, report.arena_final_spans);
    }

    // -------------------------------------------------------------------------
    // Suite 3: ARM NEON SIMD Greedy Chunk Mesher (Single-Core & Multi-Core)
    // -------------------------------------------------------------------------
    MaterialPaletteSetup pal;
    {
        struct ArchSpec {
            std::string name;
            SectionVoxelData vox;
        };
        std::vector<ArchSpec> archetypes;
        archetypes.push_back({"Flat Stone+Grass Slab", make_flat_slab_section()});
        archetypes.push_back({"Complex World (Caves+Ores+Water+PBR+Glass)", make_complex_world_section(7)});
        archetypes.push_back({"Multi-Layer Waterfall & River", make_waterfall_section()});
        archetypes.push_back({"3D Checkerboard Dense Cutout", make_dense_cutout_checkerboard()});

        const uint32_t single_iters = full_mode ? 2000u : 250u;
        const uint32_t mt_iters_per_thread = full_mode ? 1500u : 200u;
        const uint32_t workers = std::min<uint32_t>(report.hw_threads, 16u);

        std::printf("\n[Suite 3] ARM NEON SIMD Greedy Chunk Mesher (%u hardware threads):\n", workers);
        for (const auto& arch : archetypes) {
            std::vector<MetalityPackedQuad> sq(16384), cq(24576), tq(16384);
            MetalityChunkSectionInput in{};
            in.block_material_ids = arch.vox.blocks.data();
            in.packed_light = arch.vox.light.data();
            in.biome_tint_rgb = arch.vox.tint.data();
            in.palette_table = pal.entries.data();
            in.palette_size = static_cast<uint32_t>(pal.entries.size());

            MetalityMeshedSectionOutput out{};
            out.solid_quads = sq.data(); out.solid_quad_capacity = static_cast<uint32_t>(sq.size());
            out.cutout_quads = cq.data(); out.cutout_quad_capacity = static_cast<uint32_t>(cq.size());
            out.translucent_quads = tq.data(); out.translucent_quad_capacity = static_cast<uint32_t>(tq.size());

            in.enable_greedy_merge = 0;
            BENCH_REQUIRE(metality_mesh_chunk_section_neon(&in, &out), "Unmerged mesher failed");
            const uint32_t unmerged = out.solid_quad_count + out.cutout_quad_count + out.translucent_quad_count;

            in.enable_greedy_merge = 1;
            BENCH_REQUIRE(metality_mesh_chunk_section_neon(&in, &out), "Greedy mesher failed");
            const uint32_t greedy = out.solid_quad_count + out.cutout_quad_count + out.translucent_quad_count;

            const auto t0 = Clock::now();
            for (uint32_t i = 0; i < single_iters; ++i) {
                metality_mesh_chunk_section_neon(&in, &out);
            }
            const auto t1 = Clock::now();
            const double us_1t = std::chrono::duration<double, std::micro>(t1 - t0).count() / single_iters;

            // Parallel multi-core throughput test (lock-free thread-local scratch)
            const auto mt0 = Clock::now();
            std::vector<std::thread> threads;
            threads.reserve(workers);
            for (uint32_t w = 0; w < workers; ++w) {
                threads.emplace_back([&]() {
                    std::vector<MetalityPackedQuad> lsq(16384), lcq(24576), ltq(16384);
                    MetalityMeshedSectionOutput lout{};
                    lout.solid_quads = lsq.data(); lout.solid_quad_capacity = 16384u;
                    lout.cutout_quads = lcq.data(); lout.cutout_quad_capacity = 24576u;
                    lout.translucent_quads = ltq.data(); lout.translucent_quad_capacity = 16384u;
                    MetalityChunkSectionInput lin = in;
                    for (uint32_t k = 0; k < mt_iters_per_thread; ++k) {
                        metality_mesh_chunk_section_neon(&lin, &lout);
                    }
                });
            }
            for (auto& th : threads) th.join();
            const auto mt1 = Clock::now();
            const double mt_wall_us = std::chrono::duration<double, std::micro>(mt1 - mt0).count();
            const double total_mt_sections = static_cast<double>(workers) * mt_iters_per_thread;

            MesherArchetypeResult res;
            res.name = arch.name;
            res.unmerged_quads = unmerged;
            res.greedy_quads = greedy;
            res.solid_quads = out.solid_quad_count;
            res.cutout_quads = out.cutout_quad_count;
            res.trans_quads = out.translucent_quad_count;
            res.us_per_section_1t = us_1t;
            res.sections_per_sec_1t = 1e6 / us_1t;
            res.mquads_per_sec_1t = (greedy * res.sections_per_sec_1t) / 1e6;
            res.sections_per_sec_mt = total_mt_sections / (mt_wall_us / 1e6);
            res.us_per_section_mt = 1e6 / res.sections_per_sec_mt;
            res.vanilla_kb = (unmerged * 144.0) / 1024.0;
            res.sodium_kb = (unmerged * 80.0) / 1024.0;
            res.metality_kb = (greedy * 16.0) / 1024.0;
            res.vram_ratio_vs_vanilla = res.vanilla_kb / std::max(res.metality_kb, 0.001);
            report.mesher_results.push_back(res);

            std::printf("  %-44s | %5u -> %5u quads | 1T: %6.2f us (%6.0f sec/s) | %uT: %7.0f sec/s | VRAM: %.1f KB (%.1fx vs Vanilla)\n",
                        res.name.c_str(), unmerged, greedy, us_1t, res.sections_per_sec_1t,
                        workers, res.sections_per_sec_mt, res.metality_kb, res.vram_ratio_vs_vanilla);
        }
    }

    // -------------------------------------------------------------------------
    // Suite 4: TBDR Render Graph Compiler & Memoryless Bandwidth Savings
    // -------------------------------------------------------------------------
    {
        struct Res { const char* name; uint32_t w; uint32_t h; };
        const std::vector<Res> resolutions = {
            {"1080p FHD", 1920, 1080},
            {"1440p QHD", 2560, 1440},
            {"3200x1800 Retina", 3200, 1800},
            {"4K UHD", 3840, 2160}
        };
        const uint32_t kGraphIters = full_mode ? 2000u : 200u;
        std::printf("\n[Suite 4] TBDR Render Graph Compiler (Pass Fusion & Memoryless Tile Promotion):\n");
        for (const auto& r : resolutions) {
            MetalityGraphPlanStats st{};
            const auto g0 = Clock::now();
            for (uint32_t i = 0; i < kGraphIters; ++i) {
                MetalityGraphHandle g = metality_graph_create();
                const uint32_t albedo = metality_graph_declare_texture(g, "gAlbedo", METALITY_FORMAT_RGBA8_UNORM, r.w, r.h, false);
                const uint32_t normal = metality_graph_declare_texture(g, "gNormal", METALITY_FORMAT_RGBA16_FLOAT, r.w, r.h, false);
                const uint32_t depth  = metality_graph_declare_texture(g, "gDepth", METALITY_FORMAT_DEPTH32_FLOAT, r.w, r.h, false);
                const uint32_t hdr    = metality_graph_declare_texture(g, "hdrColor", METALITY_FORMAT_RGBA16_FLOAT, r.w, r.h, true);
                const uint32_t g_writes[3] = {albedo, normal, depth};
                metality_graph_add_pass(g, "GBufferSolid", nullptr, 0, false, g_writes, 3);
                const uint32_t c_reads[3] = {albedo, normal, depth};
                const uint32_t c_writes[1] = {hdr};
                metality_graph_add_pass(g, "DeferredLight", c_reads, 3, false, c_writes, 1);
                metality_graph_add_pass(g, "TranslucentWater", c_writes, 1, false, c_writes, 1);
                metality_graph_add_pass(g, "BloomComposite", c_writes, 1, true, c_writes, 1);
                metality_graph_compile_tbdr(g, &st);
                metality_graph_destroy(g);
            }
            const auto g1 = Clock::now();
            TbdrResResult tr;
            tr.label = r.name;
            tr.width = r.w;
            tr.height = r.h;
            tr.compile_us = std::chrono::duration<double, std::micro>(g1 - g0).count() / kGraphIters;
            tr.passes = st.total_passes;
            tr.fused_groups = st.fused_encoder_groups;
            tr.memoryless = st.memoryless_textures;
            tr.saved_mb_frame = static_cast<double>(st.saved_dram_bytes_per_frame) / (1024.0 * 1024.0);
            tr.saved_gbs_60fps = (tr.saved_mb_frame * 60.0) / 1024.0;
            tr.saved_gbs_120fps = (tr.saved_mb_frame * 120.0) / 1024.0;
            report.tbdr_results.push_back(tr);

            std::printf("  %-18s | Compile: %6.2f us | Passes: %u -> %u Fused | Memoryless: %u | Saved: %6.1f MB/frame (%.1f GB/s @120Hz)\n",
                        tr.label.c_str(), tr.compile_us, tr.passes, tr.fused_groups,
                        tr.memoryless, tr.saved_mb_frame, tr.saved_gbs_120fps);
        }
    }

    // -------------------------------------------------------------------------
    // Suite 5: Slang-to-MSL & Shader Pack Compilation (Meteor Zenith vs Meteor Dusk)
    // -------------------------------------------------------------------------
    {
        MetalityPackManifestSummary sum{};
        char err[1024]{};
        const auto c0 = Clock::now();
        MetalityPackHandle p_cold = metality_pack_load_directory(
            ctx, meteor_zenith_dir.c_str(), 1920, 1080, 1.0f, &sum, err, sizeof(err));
        const auto c1 = Clock::now();
        if (!p_cold) std::fprintf(stderr, "Zenith compile error: %s\n", err);
        BENCH_REQUIRE(p_cold != nullptr, "Failed cold load of meteor-zenith");
        BENCH_REQUIRE(metality_flush_pipeline_cache(ctx), "Failed to flush binary archive");
        metality_pack_destroy(p_cold);

        const auto w0 = Clock::now();
        MetalityPackHandle p_warm = metality_pack_load_directory(
            ctx, meteor_zenith_dir.c_str(), 1920, 1080, 1.0f, &sum, err, sizeof(err));
        const auto w1 = Clock::now();
        BENCH_REQUIRE(p_warm != nullptr, "Failed warm load of meteor-zenith");
        metality_pack_destroy(p_warm);

        MetalityPackManifestSummary sum_dusk{};
        const auto d0 = Clock::now();
        MetalityPackHandle p_dusk = metality_pack_load_directory(
            ctx, meteor_dusk_dir.c_str(), 1920, 1080, 1.0f, &sum_dusk, err, sizeof(err));
        const auto d1 = Clock::now();
        if (!p_dusk) std::fprintf(stderr, "Dusk compile error: %s\n", err);
        BENCH_REQUIRE(p_dusk != nullptr, "Failed load of meteor-dusk");
        metality_pack_destroy(p_dusk);

        report.pack_passes = sum.total_pass_count;
        report.pack_psos = sum.compiled_pso_count;
        report.pack_memoryless = sum.memoryless_texture_count;
        report.pack_cold_ms = std::chrono::duration<double, std::milli>(c1 - c0).count();
        report.pack_warm_ms = std::chrono::duration<double, std::milli>(w1 - w0).count();
        const double dusk_ms = std::chrono::duration<double, std::milli>(d1 - d0).count();

        std::printf("\n[Suite 5] Shader Pack Slang->MSL->Metal Compile:\n");
        std::printf("  Meteor Zenith (RT)    : %u passes | %u PSOs | Cold=%.1f ms | Warm=%.1f ms\n",
                    report.pack_passes, report.pack_psos, report.pack_cold_ms, report.pack_warm_ms);
        std::printf("  Meteor Dusk   (Non-RT): %u passes | %u PSOs | Cold=%.1f ms\n",
                    sum_dusk.total_pass_count, sum_dusk.compiled_pso_count, dusk_ms);
    }

    // -------------------------------------------------------------------------
    // Suite 6: GPU Compute Frustum Culling & ICB Terrain Rasterization
    // -------------------------------------------------------------------------
    {
        SectionVoxelData complex = make_complex_world_section(11);
        std::vector<MetalityPackedQuad> sq(8192), cq(4096), tq(4096);
        MetalityChunkSectionInput minp{};
        minp.block_material_ids = complex.blocks.data();
        minp.packed_light = complex.light.data();
        minp.biome_tint_rgb = complex.tint.data();
        minp.palette_table = pal.entries.data();
        minp.palette_size = static_cast<uint32_t>(pal.entries.size());
        minp.enable_greedy_merge = 1;
        MetalityMeshedSectionOutput mout{};
        mout.solid_quads = sq.data(); mout.solid_quad_capacity = 8192;
        mout.cutout_quads = cq.data(); mout.cutout_quad_capacity = 4096;
        mout.translucent_quads = tq.data(); mout.translucent_quad_capacity = 4096;
        BENCH_REQUIRE(metality_mesh_chunk_section_neon(&minp, &mout), "Terrain template mesh failed");

        const uint32_t max_secs = 2048u;
        char err[512]{};
        MetalityTerrainHandle terrain = metality_terrain_pipeline_create(
            ctx, max_secs, 32ULL * 1024ULL * 1024ULL, err, sizeof(err));
        if (!terrain) std::fprintf(stderr, "Terrain pipeline error: %s\n", err);
        BENCH_REQUIRE(terrain != nullptr, "Failed to create GPU ICB terrain pipeline");

        void* pool_ptr = metality_terrain_quad_pool_cpu_ptr(terrain);
        BENCH_REQUIRE(pool_ptr != nullptr, "Terrain quad pool null");
        std::memcpy(pool_ptr, sq.data(), mout.solid_quad_count * sizeof(MetalityPackedQuad));
        if (mout.cutout_quad_count > 0) {
            std::memcpy(static_cast<MetalityPackedQuad*>(pool_ptr) + mout.solid_quad_count,
                        cq.data(), mout.cutout_quad_count * sizeof(MetalityPackedQuad));
        }

        std::vector<MetalitySpriteUvEntry> sprites(32, {0.0f, 0.0f, 0.25f, 0.25f});
        metality_terrain_update_sprites(terrain, sprites.data(), static_cast<uint32_t>(sprites.size()));

        const int grid_side = 32; // 32 * 64 = 2048 sections
        for (uint32_t idx = 0; idx < max_secs; ++idx) {
            const int gx = static_cast<int>(idx % grid_side) - grid_side / 2;
            const int gz = static_cast<int>((idx / grid_side) % grid_side) - grid_side / 2;
            const int gy = static_cast<int>(idx / (grid_side * grid_side));
            MetalitySectionGpuRecord rec{};
            rec.chunk_sec_x = gx;
            rec.chunk_sec_y = gy;
            rec.chunk_sec_z = gz;
            rec.solid_quad_offset = 0;
            rec.solid_quad_count = mout.solid_quad_count;
            rec.cutout_quad_offset = mout.solid_quad_count;
            rec.cutout_quad_count = mout.cutout_quad_count;
            std::memcpy(rec.aabb_min, mout.aabb_min, sizeof(rec.aabb_min));
            std::memcpy(rec.aabb_max, mout.aabb_max, sizeof(rec.aabb_max));
            metality_terrain_update_section(terrain, idx, &rec);
        }

        const uint32_t tw = 1920, th = 1080;
        MetalityTextureDesc ctd{tw, th, 1, METALITY_FORMAT_RGBA16_FLOAT, METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED};
        MetalityTextureDesc dtd{tw, th, 1, METALITY_FORMAT_DEPTH32_FLOAT, METALITY_TEX_USAGE_RENDER_TARGET};
        MetalityTextureDesc atd{64, 64, 1, METALITY_FORMAT_RGBA8_SRGB, METALITY_TEX_USAGE_SAMPLED};
        MetalityTextureHandle ctex = metality_texture_create(ctx, &ctd);
        MetalityTextureHandle dtex = metality_texture_create(ctx, &dtd);
        MetalityTextureHandle atlas = metality_texture_create(ctx, &atd);
        std::vector<uint32_t> px(64u * 64u, 0xFF88AA99u);
        metality_texture_upload_region(ctx, atlas, 0, 0, 0, 64, 64, px.data(), 64 * 4);
        MetalitySamplerDesc sd{0, 0, 1, 1};
        MetalitySamplerHandle samp = metality_sampler_get_or_create(ctx, &sd);

        // Wide frustum planes keeping local sections visible for deterministic GPU load
        const float frustum[24] = {
            1,0,0,512,  -1,0,0,512,
            0,1,0,512,   0,-1,0,512,
            0,0,1,512,   0,0,-1,512
        };
        const float cam[3] = {0.0f, 36.0f, 48.0f};
        float vp[16]{};
        fill_standard_perspective_vp(1.22f, static_cast<float>(tw) / th, 0.1f, 512.0f, 0.0f, -0.35f, vp);

        const std::vector<uint32_t> counts = {256u, 512u, 1024u, 2048u};
        const uint32_t kWarmups = 2u;
        const uint32_t kIters = full_mode ? 15u : 4u;
        std::printf("\n[Suite 6] GPU Compute Frustum Cull & Indirect Command Buffer Terrain Draw (%ux%u):\n", tw, th);
        for (uint32_t cnt : counts) {
            std::vector<double> cpu_us, gpu_ms, sync_ms;
            for (uint32_t it = 0; it < kWarmups + kIters; ++it) {
                const uint64_t fid = metality_begin_frame(ctx);
                const auto e0 = Clock::now();
                metality_terrain_encode_gpu_cull(ctx, terrain, frustum, cam, cnt);

                MetalityRenderPassBeginInfo rp{};
                rp.color_target = ctex;
                rp.depth_target = dtex;
                rp.clear_color[0] = 0.25f; rp.clear_color[1] = 0.45f; rp.clear_color[2] = 0.85f; rp.clear_color[3] = 1.0f;
                rp.clear_depth = 1.0;
                rp.should_clear_color = 1;
                rp.should_clear_depth = 1;
                rp.store_color = 1;
                rp.store_depth = 0;
                BENCH_REQUIRE(metality_cmd_begin_render_pass(ctx, &rp), "Terrain render pass failed");
                metality_cmd_set_viewport(ctx, 0, 0, tw, th, 0.0f, 1.0f);
                metality_terrain_encode_icb_draw(ctx, terrain, vp, cam, atlas, samp, METALITY_LAYER_SOLID, cnt);
                metality_terrain_encode_icb_draw(ctx, terrain, vp, cam, atlas, samp, METALITY_LAYER_CUTOUT, cnt);
                metality_cmd_end_render_pass(ctx);
                const auto e1 = Clock::now();

                double cs_ms = 0, sw_ms = 0, hw_ms = 0;
                finish_and_measure_frame(ctx, fid, e0, e1, cs_ms, sw_ms, hw_ms);
                if (it >= kWarmups) {
                    cpu_us.push_back(cs_ms * 1000.0);
                    gpu_ms.push_back(hw_ms);
                    sync_ms.push_back(sw_ms);
                }
            }
            TerrainGpuResult tr;
            tr.active_sections = cnt;
            tr.total_quads = static_cast<uint64_t>(cnt) * (mout.solid_quad_count + mout.cutout_quad_count);
            tr.cpu_encode_us = compute_stats(cpu_us).avg;
            tr.gpu_hw_ms = compute_stats(gpu_ms).avg;
            tr.sync_wall_ms = compute_stats(sync_ms).avg;
            report.terrain_results.push_back(tr);

            std::printf("  %4u Sections (%8llu quads) | CPU Encode: %6.1f us | GPU Kernel: %5.3f ms | Sync: %5.3f ms\n",
                        tr.active_sections, static_cast<unsigned long long>(tr.total_quads),
                        tr.cpu_encode_us, tr.gpu_hw_ms, tr.sync_wall_ms);
        }

        metality_texture_destroy(ctx, atlas);
        metality_texture_destroy(ctx, dtex);
        metality_texture_destroy(ctx, ctex);
        metality_terrain_pipeline_destroy(ctx, terrain);
    }

    // -------------------------------------------------------------------------
    // Prepare Rich Multi-Chunk 3D World for Suites 7, 8, 9, 10
    // -------------------------------------------------------------------------
    SectionVoxelData rich_world = make_complex_world_section(17);
    SectionVoxelData water_world = make_waterfall_section();

    std::vector<MetalityPackedQuad> r_sq(8192), r_cq(4096), r_tq(4096);
    MetalityChunkSectionInput rinp{};
    rinp.block_material_ids = rich_world.blocks.data();
    rinp.packed_light = rich_world.light.data();
    rinp.biome_tint_rgb = rich_world.tint.data();
    rinp.palette_table = pal.entries.data();
    rinp.palette_size = static_cast<uint32_t>(pal.entries.size());
    rinp.enable_greedy_merge = 1;
    MetalityMeshedSectionOutput rmout{};
    rmout.solid_quads = r_sq.data(); rmout.solid_quad_capacity = 8192;
    rmout.cutout_quads = r_cq.data(); rmout.cutout_quad_capacity = 4096;
    rmout.translucent_quads = r_tq.data(); rmout.translucent_quad_capacity = 4096;
    BENCH_REQUIRE(metality_mesh_chunk_section_neon(&rinp, &rmout), "Rich world mesh failed");

    std::vector<MetalityPackedQuad> w_sq(8192), w_cq(4096), w_tq(4096);
    MetalityChunkSectionInput winp = rinp;
    winp.block_material_ids = water_world.blocks.data();
    winp.packed_light = water_world.light.data();
    winp.biome_tint_rgb = water_world.tint.data();
    MetalityMeshedSectionOutput wmout{};
    wmout.solid_quads = w_sq.data(); wmout.solid_quad_capacity = 8192;
    wmout.cutout_quads = w_cq.data(); wmout.cutout_quad_capacity = 4096;
    wmout.translucent_quads = w_tq.data(); wmout.translucent_quad_capacity = 4096;
    BENCH_REQUIRE(metality_mesh_chunk_section_neon(&winp, &wmout), "Waterfall world mesh failed");

    // -------------------------------------------------------------------------
    // Suite 7: Hardware RT Scene Build (BLAS + TLAS + 512x128x512 Voxel Ring)
    // -------------------------------------------------------------------------
    {
        const std::vector<uint32_t> sec_counts = full_mode
            ? std::vector<uint32_t>{16u, 48u, 96u}
            : std::vector<uint32_t>{16u, 48u};

        std::vector<float> dyn_lights(32u * 8u, 0.0f);
        for (uint32_t i = 0; i < 32u; ++i) {
            dyn_lights[i * 8 + 0] = static_cast<float>((i % 8) * 6 - 20);
            dyn_lights[i * 8 + 1] = 66.0f + static_cast<float>(i % 4);
            dyn_lights[i * 8 + 2] = static_cast<float>((i / 8) * 6 - 10);
            dyn_lights[i * 8 + 3] = 14.0f;
            dyn_lights[i * 8 + 4] = 1.0f;
            dyn_lights[i * 8 + 5] = (i & 1u) ? 0.55f : 0.80f;
            dyn_lights[i * 8 + 6] = (i & 1u) ? 0.20f : 0.95f;
            dyn_lights[i * 8 + 7] = 0.45f;
        }
        std::vector<float> ripples(16u * 8u, 0.0f);
        for (uint32_t i = 0; i < 16u; ++i) {
            ripples[i * 8 + 0] = static_cast<float>(i) * 2.5f - 10.0f;
            ripples[i * 8 + 1] = 63.0f;
            ripples[i * 8 + 2] = 6.0f + static_cast<float>(i % 4);
            ripples[i * 8 + 3] = 0.25f + 0.1f * static_cast<float>(i % 5);
            ripples[i * 8 + 4] = 0.7f;
            ripples[i * 8 + 5] = 0.7f;
            ripples[i * 8 + 6] = 1.0f;
            ripples[i * 8 + 7] = (i % 2u == 0u) ? 0.0f : 1.0f;
        }
        std::vector<float> casters(16u * 8u, 0.0f);
        for (uint32_t i = 0; i < 16u; ++i) {
            casters[i * 8 + 0] = static_cast<float>(i * 3) - 18.0f;
            casters[i * 8 + 1] = 64.0f;
            casters[i * 8 + 2] = 12.0f;
            casters[i * 8 + 3] = 0.35f;
            casters[i * 8 + 4] = 0.95f;
            casters[i * 8 + 5] = 0.35f;
            casters[i * 8 + 6] = 0.25f * static_cast<float>(i);
            casters[i * 8 + 7] = 0.0f;
        }

        std::printf("\n[Suite 7] Hardware RT Scene Build (BLAS + TLAS + 512x128x512 Voxel Ring + Macro-Water 8x8x8):\n");
        for (uint32_t cnt : sec_counts) {
            MetalityRtSceneHandle rt = metality_rt_scene_create(ctx, std::max(128u, cnt * 2u), false);
            BENCH_REQUIRE(rt != nullptr, "Failed to create RT scene");

            const int side = static_cast<int>(std::ceil(std::sqrt(static_cast<double>(cnt))));
            const auto i0 = Clock::now();
            for (uint32_t s = 0; s < cnt; ++s) {
                const int sx = static_cast<int>(s % side) - side / 2;
                const int sz = static_cast<int>(s / side) - side / 2;
                const bool use_wf = (s % 4u == 0u);
                const auto& sq = use_wf ? w_sq : r_sq;
                const auto& cq = use_wf ? w_cq : r_cq;
                const auto& tq = use_wf ? w_tq : r_tq;
                const auto& mo = use_wf ? wmout : rmout;
                BENCH_REQUIRE(metality_rt_scene_update_section_v2(
                    ctx, rt, sx, 3, sz,
                    sq.data(), mo.solid_quad_count,
                    cq.data(), mo.cutout_quad_count,
                    tq.data(), mo.translucent_quad_count), "RT section update v2 failed");
            }
            const auto i1 = Clock::now();

            metality_rt_scene_set_dynamic_lights(rt, dyn_lights.data(), 32u);
            metality_rt_scene_set_water_ripples(rt, ripples.data(), 16u);
            metality_rt_scene_set_entity_shadows(rt, casters.data(), 16u);

            // Warmup rebuild + timed per-frame incremental TLAS & voxel ring rebuild
            BENCH_REQUIRE(metality_rt_scene_rebuild_tlas(ctx, rt, 0.0f, 65.0f, 0.0f), "Warmup TLAS rebuild failed");
            const uint32_t kRebuilds = full_mode ? 10u : 3u;
            const auto t0 = Clock::now();
            for (uint32_t r = 0; r < kRebuilds; ++r) {
                BENCH_REQUIRE(metality_rt_scene_rebuild_tlas(
                    ctx, rt, static_cast<float>(r) * 0.5f, 65.0f, static_cast<float>(r) * 0.5f),
                    "Timed TLAS rebuild failed");
            }
            const auto t1 = Clock::now();

            RtSceneBuildResult br;
            br.sections = cnt;
            br.ingest_blas_ms_total = std::chrono::duration<double, std::milli>(i1 - i0).count();
            br.ingest_blas_ms_per_sec = br.ingest_blas_ms_total / cnt;
            br.tlas_rebuild_ms = std::chrono::duration<double, std::milli>(t1 - t0).count() / kRebuilds;
            br.active_blas = metality_rt_scene_active_blas_count(rt);
            report.rt_build_results.push_back(br);

            std::printf("  %3u Sections (Active BLAS=%3u) | Ingest+CPU Build: %6.2f ms (%.3f ms/sec) | TLAS+Voxel+32Lights Rebuild: %5.2f ms\n",
                        br.sections, br.active_blas, br.ingest_blas_ms_total, br.ingest_blas_ms_per_sec, br.tlas_rebuild_ms);
            metality_rt_scene_destroy(rt);
        }
    }

    // Build shared 6x6 = 36-section rich RT scene used across Suite 8, 9 & 10
    MetalityRtSceneHandle shared_rt = metality_rt_scene_create(ctx, 128u, false);
    BENCH_REQUIRE(shared_rt != nullptr, "Failed to create shared RT scene");
    for (int sz = -3; sz < 3; ++sz) {
        for (int sx = -3; sx < 3; ++sx) {
            const bool is_wf = ((sx + sz) & 3) == 0;
            const auto& mo = is_wf ? wmout : rmout;
            BENCH_REQUIRE(metality_rt_scene_update_section_v2(
                ctx, shared_rt, sx, 3, sz,
                is_wf ? w_sq.data() : r_sq.data(), mo.solid_quad_count,
                is_wf ? w_cq.data() : r_cq.data(), mo.cutout_quad_count,
                is_wf ? w_tq.data() : r_tq.data(), mo.translucent_quad_count),
                "Shared RT scene populate failed");
        }
    }
    std::vector<float> scene_lights = {
        4.0f, 65.5f, 4.0f, 16.0f, 1.0f, 0.72f, 0.42f, 0.5f,
       -4.0f, 65.5f, 4.0f, 14.0f, 0.35f, 0.90f, 0.95f, 0.5f,
        6.0f, 66.0f, -6.0f, 15.0f, 1.0f, 0.85f, 0.35f, 0.5f,
       -6.0f, 64.5f, -5.0f, 12.0f, 0.45f, 0.95f, 0.65f, 0.4f,
        2.0f, 63.5f, 10.0f, 12.0f, 0.95f, 0.55f, 0.85f, 0.45f,
        0.0f, 67.0f, -12.0f, 18.0f, 1.0f, 0.78f, 0.32f, 0.5f
    };
    std::vector<float> scene_ripples = {
        3.0f, 63.0f, 5.0f, 0.3f, 0.8f, 0.6f, 1.2f, 0.0f,
       -2.0f, 63.0f, 8.0f, 0.6f, -0.5f, 0.8f, 1.0f, 1.0f,
        7.0f, 63.0f, -2.0f, 0.2f, 0.0f, 1.0f, 1.4f, 0.0f,
       -5.0f, 63.0f, -6.0f, 0.9f, 0.3f, -0.9f, 0.9f, 1.0f
    };
    std::vector<float> scene_casters = {
        2.5f, 64.0f, 6.0f, 0.35f, 0.95f, 0.35f, 0.4f, 0.0f,
       -3.5f, 64.0f, 7.0f, 0.40f, 0.80f, 0.40f, 1.1f, 0.0f,
        5.0f, 64.0f, -4.0f, 0.45f, 0.90f, 0.45f, 2.2f, 0.0f
    };
    metality_rt_scene_set_dynamic_lights(shared_rt, scene_lights.data(), 6u);
    metality_rt_scene_set_water_ripples(shared_rt, scene_ripples.data(), 4u);
    metality_rt_scene_set_entity_shadows(shared_rt, scene_casters.data(), 3u);
    BENCH_REQUIRE(metality_rt_scene_rebuild_tlas(ctx, shared_rt, 0.0f, 65.0f, 4.0f), "Shared RT TLAS build failed");

    // -------------------------------------------------------------------------
    // Suite 8: HWRT BVH vs Voxel DDA GI + MetalFX Upscaling Modes
    // -------------------------------------------------------------------------
    {
        const uint32_t in_w = 1280, in_h = 720;
        const uint32_t out_w = 2560, out_h = 1440;
        const uint32_t io_usage = METALITY_TEX_USAGE_SAMPLED | METALITY_TEX_USAGE_STORAGE_WRITE;
        const MetalityTextureDesc rad_td{in_w, in_h, 1, METALITY_FORMAT_RGBA16_FLOAT, io_usage | METALITY_TEX_USAGE_RENDER_TARGET};
        const MetalityTextureDesc dep_td{in_w, in_h, 1, METALITY_FORMAT_R32_FLOAT, io_usage};
        const MetalityTextureDesc mot_td{in_w, in_h, 1, METALITY_FORMAT_RG16_FLOAT, io_usage};
        const MetalityTextureDesc dif_td{in_w, in_h, 1, METALITY_FORMAT_RGBA8_UNORM, io_usage};
        const MetalityTextureDesc nrm_td{in_w, in_h, 1, METALITY_FORMAT_RGBA16_FLOAT, io_usage};
        const MetalityTextureDesc rgh_td{in_w, in_h, 1, METALITY_FORMAT_R16_FLOAT, io_usage};
        const MetalityTextureDesc out_td{out_w, out_h, 1, METALITY_FORMAT_RGBA16_FLOAT, io_usage | METALITY_TEX_USAGE_RENDER_TARGET};
        MetalityTextureHandle t_rad = metality_texture_create(ctx, &rad_td);
        MetalityTextureHandle t_dep = metality_texture_create(ctx, &dep_td);
        MetalityTextureHandle t_mot = metality_texture_create(ctx, &mot_td);
        MetalityTextureHandle t_dif = metality_texture_create(ctx, &dif_td);
        MetalityTextureHandle t_spc = metality_texture_create(ctx, &dif_td);
        MetalityTextureHandle t_nrm = metality_texture_create(ctx, &nrm_td);
        MetalityTextureHandle t_rgh = metality_texture_create(ctx, &rgh_td);
        MetalityTextureHandle t_out = metality_texture_create(ctx, &out_td);

        // Create Voxel DDA fallback RT scene mirroring 16 sections around origin
        MetalityRtSceneHandle dda_rt = metality_rt_scene_create(ctx, 64u, true);
        for (int sz = -2; sz < 2; ++sz) {
            for (int sx = -2; sx < 2; ++sx) {
                metality_rt_scene_update_section_v2(
                    ctx, dda_rt, sx, 3, sz,
                    r_sq.data(), rmout.solid_quad_count,
                    r_cq.data(), rmout.cutout_quad_count,
                    r_tq.data(), rmout.translucent_quad_count);
            }
        }
        metality_rt_scene_rebuild_tlas(ctx, dda_rt, 0.0f, 65.0f, 4.0f);

        const float eye[3] = {0.0f, 66.0f, 8.0f};
        const float target[3] = {0.0f, 62.0f, -6.0f};
        const float sun_dir[3] = {0.42f, 0.72f, -0.55f};
        float inv_vp[16]{};
        build_lookat_inv_vp(eye, target, 1.22f, static_cast<float>(in_w) / in_h, 0.1f, 256.0f, inv_vp);

        struct RtUpscaleCase {
            const char* name;
            MetalityRtSceneHandle rt;
            MetalityUpscaleMode mode;
        };
        const std::vector<RtUpscaleCase> cases = {
            {"HWRT BVH + MetalFX Spatial (720p->1440p)", shared_rt, METALITY_UPSCALE_SPATIAL},
            {"HWRT BVH + MetalFX Temporal (720p->1440p)", shared_rt, METALITY_UPSCALE_TEMPORAL},
            {"HWRT BVH + MetalFX Temporal Denoised (720p->1440p)", shared_rt, METALITY_UPSCALE_TEMPORAL_DENOISED},
            {"Voxel DDA Fallback + MetalFX Temporal Denoised", dda_rt, METALITY_UPSCALE_TEMPORAL_DENOISED}
        };

        const uint32_t kIters = full_mode ? 20u : 5u;
        std::printf("\n[Suite 8] Ray Tracing GI + MetalFX Upscaling Modes (1280x720 -> 2560x1440):\n");
        for (const auto& c : cases) {
            MetalityUpscalerDesc ud{};
            ud.mode = c.mode;
            ud.input_width = in_w; ud.input_height = in_h;
            ud.output_width = out_w; ud.output_height = out_h;
            ud.color_format = METALITY_FORMAT_RGBA16_FLOAT;
            ud.depth_format = METALITY_FORMAT_R32_FLOAT;
            ud.motion_format = METALITY_FORMAT_RG16_FLOAT;
            ud.output_format = METALITY_FORMAT_RGBA16_FLOAT;
            ud.auto_exposure = 1;
            ud.depth_reversed = 0;
            MetalityUpscalerHandle up = metality_upscaler_create(ctx, &ud);
            BENCH_REQUIRE(up != nullptr, "Failed to create MetalFX upscaler");

            std::vector<double> gi_times, tot_times;
            const uint32_t kWarmups = 2u;
            // Batch A: Isolate RT GI dispatch GPU time across consecutive identical frames
            for (uint32_t i = 0; i < kWarmups + kIters; ++i) {
                const uint64_t f_gi = metality_begin_frame(ctx);
                const auto s0 = Clock::now();
                BENCH_REQUIRE(metality_rt_dispatch_gi(ctx, c.rt, inv_vp, eye, sun_dir, in_w, in_h,
                                                      t_rad, t_dep, t_mot, t_dif, t_spc, t_nrm, t_rgh),
                              "RT GI dispatch failed");
                const auto s1 = Clock::now();
                double cs1, sw1, hw_gi = 0.0;
                finish_and_measure_frame(ctx, f_gi, s0, s1, cs1, sw1, hw_gi);
                if (i >= kWarmups) {
                    gi_times.push_back(hw_gi);
                }
            }

            // Batch B: Combined RT GI + MetalFX encode GPU time across consecutive identical frames
            for (uint32_t i = 0; i < kWarmups + kIters; ++i) {
                const uint64_t f_all = metality_begin_frame(ctx);
                const auto a0 = Clock::now();
                BENCH_REQUIRE(metality_rt_dispatch_gi(ctx, c.rt, inv_vp, eye, sun_dir, in_w, in_h,
                                                      t_rad, t_dep, t_mot, t_dif, t_spc, t_nrm, t_rgh),
                              "RT GI dispatch failed");
                float jx = 0.0f, jy = 0.0f;
                metality_upscaler_halton_jitter(i, in_w, out_w, &jx, &jy);
                if (c.mode == METALITY_UPSCALE_TEMPORAL_DENOISED) {
                    MetalityDenoisedUpscaleDispatchInfo di{};
                    di.color_tex = t_rad; di.depth_tex = t_dep; di.motion_tex = t_mot;
                    di.diffuse_albedo_tex = t_dif; di.specular_albedo_tex = t_spc;
                    di.normal_tex = t_nrm; di.roughness_tex = t_rgh; di.output_tex = t_out;
                    di.jitter_x = jx; di.jitter_y = jy;
                    di.motion_scale_x = 1.0f; di.motion_scale_y = 1.0f;
                    di.reset_history = (i == 0) ? 1 : 0;
                    BENCH_REQUIRE(metality_upscaler_encode_denoised(ctx, up, &di), "MetalFX denoised failed");
                } else {
                    MetalityUpscaleDispatchInfo ui{};
                    ui.color_tex = t_rad; ui.depth_tex = t_dep; ui.motion_tex = t_mot;
                    ui.output_tex = t_out;
                    ui.content_width = in_w; ui.content_height = in_h;
                    ui.jitter_x = jx; ui.jitter_y = jy;
                    ui.motion_scale_x = 1.0f; ui.motion_scale_y = 1.0f;
                    ui.reset_history = (i == 0) ? 1 : 0;
                    BENCH_REQUIRE(metality_upscaler_encode(ctx, up, &ui), "MetalFX encode failed");
                }
                const auto a1 = Clock::now();
                double cs2, sw2, hw_tot = 0.0;
                finish_and_measure_frame(ctx, f_all, a0, a1, cs2, sw2, hw_tot);
                if (i >= kWarmups) {
                    tot_times.push_back(hw_tot);
                }
            }
            metality_upscaler_destroy(up);

            RtUpscalerModeResult mr;
            mr.name = c.name;
            mr.gi_gpu_ms = compute_stats(gi_times).avg;
            mr.total_gpu_ms = compute_stats(tot_times).avg;
            mr.upscale_gpu_ms = std::max(0.0, mr.total_gpu_ms - mr.gi_gpu_ms);
            mr.effective_fps = 1000.0 / std::max(mr.total_gpu_ms, 0.05);
            report.rt_mode_results.push_back(mr);

            std::printf("  %-52s | RT GI: %5.2f ms | MetalFX: %5.2f ms | Total GPU: %5.2f ms (%6.1f FPS)\n",
                        mr.name.c_str(), mr.gi_gpu_ms, mr.upscale_gpu_ms, mr.total_gpu_ms, mr.effective_fps);
        }

        metality_rt_scene_destroy(dda_rt);
        metality_texture_destroy(ctx, t_rad);
        metality_texture_destroy(ctx, t_dep);
        metality_texture_destroy(ctx, t_mot);
        metality_texture_destroy(ctx, t_dif);
        metality_texture_destroy(ctx, t_spc);
        metality_texture_destroy(ctx, t_nrm);
        metality_texture_destroy(ctx, t_rgh);
        metality_texture_destroy(ctx, t_out);
    }

    // Helper for Suites 9, 10 & 11: Run Meteor Zenith (RT) or Meteor Dusk (Non-RT) Benchmark
    auto run_meteor_pack_benchmark = [&](const std::string& pack_dir,
                                         const std::string& category,
                                         const std::string& label,
                                         uint32_t w, uint32_t h,
                                         const MetalityWorldFrameUniforms& base_u,
                                         bool split_scene_depth,
                                         float render_scale = 1.0f,
                                         MetalityRtSceneHandle custom_rt = nullptr) {
        MetalityPackManifestSummary sum{};
        char err[1024]{};
        MetalityPackHandle pack = metality_pack_load_directory(
            ctx, pack_dir.c_str(), w, h, 1.0f, &sum, err, sizeof(err));
        BENCH_REQUIRE(pack != nullptr, "Failed to load shader pack for benchmark");
        MetalityRtSceneHandle active_rt = custom_rt ? custom_rt : shared_rt;
        metality_set_shader_render_scale(ctx, render_scale);

        const MetalityTextureDesc ctex_td{
            w, h, 1, METALITY_FORMAT_RGBA8_UNORM,
            METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED | METALITY_TEX_USAGE_STORAGE_WRITE
        };
        const MetalityTextureDesc dtex_td{
            w, h, 1, METALITY_FORMAT_DEPTH32_FLOAT,
            METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED
        };
        MetalityTextureHandle ctex = metality_texture_create(ctx, &ctex_td);
        MetalityTextureHandle dtex = metality_texture_create(ctx, &dtex_td);
        BENCH_REQUIRE(ctex != 0 && dtex != 0, "Failed to create shader benchmark targets");

        auto populate_targets = [&](MetalityWorldFrameUniforms& fu, uint32_t frame_i) {
            fill_perspective_reversed_z(1.22f, static_cast<float>(w) / h, 0.05f, fu.proj, fu.inv_proj);
            fill_identity(fu.view_rot);
            fill_identity(fu.inv_view_rot);
            std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
            std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
            fu.width = w;
            fu.height = h;
            fu.cam_world_pos[3] = static_cast<float>(frame_i % 64u);
            fu.time_seconds += static_cast<float>(frame_i) * 0.016f;

            // Populate realistic multi-zone G-Buffer: sky top half, terrain bottom-left, water/reflective bottom-right
            metality_cmd_clear_color_depth(ctx, ctex, 0.25f, 0.45f, 0.90f, 1.0f, dtex, 0.0);
            if (split_scene_depth) {
                // Close terrain & cave walls (reversed-Z depth 0.008 -> ~6.25m)
                metality_cmd_clear_color_depth_rect(ctx, ctex, 0.36f, 0.56f, 0.30f, 1.0f, dtex, 0.008, 0, 0, 0, w / 2u, h / 2u);
                // Water / rain puddle / PBR surface zone (reversed-Z depth 0.006 -> ~8.33m)
                metality_cmd_clear_color_depth_rect(ctx, ctex, 0.16f, 0.44f, 0.88f, 1.0f, dtex, 0.006, 0, w / 2u, 0, w / 2u, h / 2u);
            } else {
                // Full lower-hemisphere geometry for cave/water stress
                metality_cmd_clear_color_depth_rect(ctx, ctex, 0.28f, 0.36f, 0.44f, 1.0f, dtex, 0.007, 0, 0, 0, w, h * 3u / 4u);
            }
        };

        // 1) Warmup temporal history buffers (4 frames)
        for (uint32_t wu = 0; wu < 4u; ++wu) {
            const uint64_t fid = metality_begin_frame(ctx);
            MetalityWorldFrameUniforms fu = base_u;
            populate_targets(fu, wu);
            BENCH_REQUIRE(metality_pack_execute(ctx, pack, active_rt, ctex, dtex, &fu), "Warmup pack execute failed");
            metality_end_frame(ctx, false);
            BENCH_REQUIRE(metality_wait_frame_complete(ctx, fid, 15000), "Warmup wait failed");
        }

        // 2) Sequential hardware per-frame timing (isolated GPU kernel + CPU submit)
        const uint32_t kSeqFrames = full_mode ? 18u : 5u;
        std::vector<double> cpu_ms, hw_ms, sync_ms;
        for (uint32_t it = 0; it < kSeqFrames; ++it) {
            const uint64_t fid = metality_begin_frame(ctx);
            MetalityWorldFrameUniforms fu = base_u;
            populate_targets(fu, it + 4u);
            const auto t0 = Clock::now();
            BENCH_REQUIRE(metality_pack_execute(ctx, pack, active_rt, ctex, dtex, &fu), "Seq pack execute failed");
            const auto t1 = Clock::now();
            double cs = 0, sw = 0, hw = 0;
            finish_and_measure_frame(ctx, fid, t0, t1, cs, sw, hw);
            cpu_ms.push_back(cs);
            sync_ms.push_back(sw);
            hw_ms.push_back(hw > 0.0 ? hw : sw);
        }

        // 3) 3-Frames-in-Flight sustained pipelined throughput burst
        const uint32_t kPipeFrames = full_mode ? 24u : 8u;
        uint64_t pipe_last_fid = 0;
        const auto p0 = Clock::now();
        for (uint32_t it = 0; it < kPipeFrames; ++it) {
            pipe_last_fid = metality_begin_frame(ctx);
            MetalityWorldFrameUniforms fu = base_u;
            populate_targets(fu, it + 30u);
            BENCH_REQUIRE(metality_pack_execute(ctx, pack, active_rt, ctex, dtex, &fu), "Pipelined pack execute failed");
            metality_end_frame(ctx, false);
        }
        BENCH_REQUIRE(metality_wait_frame_complete(ctx, pipe_last_fid, 20000), "Pipelined wait failed");
        const auto p1 = Clock::now();
        const double pipe_total_ms = std::chrono::duration<double, std::milli>(p1 - p0).count();

        ShaderScenarioResult sr;
        sr.category = category;
        sr.name = label;
        sr.width = w;
        sr.height = h;
        sr.cpu_submit_ms = compute_stats(cpu_ms).avg;
        sr.gpu_hw_ms = compute_stats(hw_ms);
        sr.sync_wall_ms = compute_stats(sync_ms);
        sr.pipelined_ms = pipe_total_ms / kPipeFrames;
        sr.pipelined_fps = 1000.0 / std::max(sr.pipelined_ms, 0.01);
        sr.mpix_per_sec = (static_cast<double>(w) * h / 1e6) * sr.pipelined_fps;
        sr.tbdr_saved_mb = static_cast<double>(sum.saved_dram_bytes_per_frame) / (1024.0 * 1024.0);
        report.shader_results.push_back(sr);

        std::printf("  [%-10s] %-38s | %4ux%-4u | CPU:%5.2fms | GPU:%5.2fms (p95=%5.2f) | Pipe:%6.1f FPS (%5.1f MPix/s)\n",
                    category.c_str(), label.c_str(), w, h,
                    sr.cpu_submit_ms, sr.gpu_hw_ms.avg, sr.gpu_hw_ms.p95,
                    sr.pipelined_fps, sr.mpix_per_sec);

        metality_set_shader_render_scale(ctx, 1.0f);
        metality_texture_destroy(ctx, ctex);
        metality_texture_destroy(ctx, dtex);
        metality_pack_destroy(pack);
    };

    auto make_profile_uniforms = [](int profile_id) -> MetalityWorldFrameUniforms {
        // Matches shaderpacks/meteor-shader/pack.ts LOW/MEDIUM/HIGH/ULTRA profiles verbatim
        MetalityWorldFrameUniforms u{};
        u.cam_world_pos[0] = 0.0f; u.cam_world_pos[1] = 65.0f; u.cam_world_pos[2] = 4.0f; u.cam_world_pos[3] = 1.0f;
        u.sun_world_dir[0] = 0.35f; u.sun_world_dir[1] = 0.65f; u.sun_world_dir[2] = -0.67f; u.sun_world_dir[3] = 1.0f;
        u.moon_world_dir[0] = -0.35f; u.moon_world_dir[1] = -0.65f; u.moon_world_dir[2] = 0.67f; u.moon_world_dir[3] = 0.0f;
        u.sky_color[0] = 0.45f; u.sky_color[1] = 0.68f; u.sky_color[2] = 1.0f; u.sky_color[3] = 0.0f;
        u.fog_color[0] = 0.62f; u.fog_color[1] = 0.74f; u.fog_color[2] = 0.88f; u.fog_color[3] = 0.25f;
        u.sunrise_color[0] = 0.90f; u.sunrise_color[1] = 0.45f; u.sunrise_color[2] = 0.22f; u.sunrise_color[3] = 0.0f;
        u.time_seconds = 14.0f;
        u.sea_level = 63.0f;
        u.edr_headroom = 2.0f;
        u.dimension_id = 0;
        if (profile_id == 0) { // LOW (Features skipped: shadows, SSR, water, clouds, godrays, bloom, GI/AO, PBR/rain)
            u.user_params[0]=0.0f; u.user_params[1]=0.0f; u.user_params[2]=0.90f; u.user_params[3]=0.5f;
            u.tonemap_params[0]=0.95f; u.tonemap_params[1]=1.10f; u.tonemap_params[2]=1.10f; u.tonemap_params[3]=1.35f;
            u.lighting_params[0]=1.10f; u.lighting_params[1]=1.05f; u.lighting_params[2]=1.05f; u.lighting_params[3]=0.45f;
            u.surface_params[0]=0.80f; u.surface_params[1]=0.0f; u.surface_params[2]=0.40f; u.surface_params[3]=0.0f;
            u.tonemap_mode = 0x100u;
        } else if (profile_id == 1) { // MEDIUM (Shadows 1c@1024 + Water + Clouds + Godrays + Bloom)
            u.user_params[0]=0.75f; u.user_params[1]=0.90f; u.user_params[2]=1.00f; u.user_params[3]=0.85f;
            u.tonemap_params[0]=0.92f; u.tonemap_params[1]=1.14f; u.tonemap_params[2]=1.14f; u.tonemap_params[3]=1.25f;
            u.lighting_params[0]=1.18f; u.lighting_params[1]=1.12f; u.lighting_params[2]=1.12f; u.lighting_params[3]=0.60f;
            u.surface_params[0]=1.05f; u.surface_params[1]=1.00f; u.surface_params[2]=0.90f; u.surface_params[3]=0.80f;
            u.tonemap_mode = 0x100u | 0x200u | 0x800u | 0x1000u | 0x2000u | 0x4000u | (1u << 17);
        } else if (profile_id == 2) { // HIGH (All features, Shadows 2c@1280)
            u.user_params[0]=0.92f; u.user_params[1]=1.15f; u.user_params[2]=1.15f; u.user_params[3]=1.0f;
            u.tonemap_params[0]=0.96f; u.tonemap_params[1]=1.16f; u.tonemap_params[2]=1.18f; u.tonemap_params[3]=1.20f;
            u.lighting_params[0]=1.22f; u.lighting_params[1]=1.15f; u.lighting_params[2]=1.16f; u.lighting_params[3]=0.65f;
            u.surface_params[0]=1.25f; u.surface_params[1]=1.20f; u.surface_params[2]=1.25f; u.surface_params[3]=1.20f;
            u.tonemap_mode = 0x1FF00u | (2u << 17);
        } else { // ULTRA (All features max, Shadows 2c@2048)
            u.user_params[0]=1.05f; u.user_params[1]=1.35f; u.user_params[2]=1.30f; u.user_params[3]=1.5f;
            u.tonemap_params[0]=0.98f; u.tonemap_params[1]=1.18f; u.tonemap_params[2]=1.20f; u.tonemap_params[3]=1.15f;
            u.lighting_params[0]=1.26f; u.lighting_params[1]=1.18f; u.lighting_params[2]=1.22f; u.lighting_params[3]=0.70f;
            u.surface_params[0]=1.35f; u.surface_params[1]=1.30f; u.surface_params[2]=1.35f; u.surface_params[3]=1.30f;
            u.tonemap_mode = 0x1FF00u | (3u << 17);
        }
        return u;
    };

    auto run_zenith_benchmark = [&](const std::string& category, const std::string& label,
                                      uint32_t w, uint32_t h, const MetalityWorldFrameUniforms& u, bool split,
                                      float render_scale = 1.0f, MetalityRtSceneHandle custom_rt = nullptr) {
        run_meteor_pack_benchmark(meteor_zenith_dir, category, label, w, h, u, split, render_scale, custom_rt);
    };
    auto run_dusk_benchmark = [&](const std::string& category, const std::string& label,
                                    uint32_t w, uint32_t h, const MetalityWorldFrameUniforms& u, bool split,
                                    float render_scale = 1.0f, MetalityRtSceneHandle custom_rt = nullptr) {
        run_meteor_pack_benchmark(meteor_dusk_dir, category, label, w, h, u, split, render_scale, custom_rt);
    };

    // Build shared scenario presets for Suites 9, 10 & 11
    MetalityWorldFrameUniforms u_day = make_profile_uniforms(2);

    MetalityWorldFrameUniforms u_sunset = make_profile_uniforms(2);
    u_sunset.sun_world_dir[0] = 0.25f; u_sunset.sun_world_dir[1] = 0.08f; u_sunset.sun_world_dir[2] = -0.96f;
    u_sunset.sunrise_color[3] = 0.85f;

    MetalityWorldFrameUniforms u_night = make_profile_uniforms(2);
    u_night.sun_world_dir[1] = -0.60f; u_night.sun_world_dir[3] = 0.0f;
    u_night.moon_world_dir[0] = -0.32f; u_night.moon_world_dir[1] = 0.52f; u_night.moon_world_dir[2] = -0.79f; u_night.moon_world_dir[3] = 1.0f;
    u_night.sky_color[0] = 0.02f; u_night.sky_color[1] = 0.03f; u_night.sky_color[2] = 0.08f; u_night.sky_color[3] = 1.0f;

    MetalityWorldFrameUniforms u_rain = u_night;
    u_rain.rain_strength = 1.0f;
    u_rain.thunder_strength = 0.6f;

    MetalityWorldFrameUniforms u_water = make_profile_uniforms(3);
    u_water.cam_world_pos[1] = 63.5f;
    u_water.submerged_mode = 1;

    MetalityWorldFrameUniforms u_river_shore = make_profile_uniforms(2);
    u_river_shore.cam_world_pos[0] = 8.0f;
    u_river_shore.cam_world_pos[1] = 65.5f;
    u_river_shore.cam_world_pos[2] = 14.0f;
    u_river_shore.submerged_mode = 0;

    MetalityWorldFrameUniforms u_glass_corridor = make_profile_uniforms(2);
    u_glass_corridor.cam_world_pos[0] = 5.5f;
    u_glass_corridor.cam_world_pos[1] = 55.5f;
    u_glass_corridor.cam_world_pos[2] = 9.0f;
    u_glass_corridor.sun_world_dir[0] = 0.38f;
    u_glass_corridor.sun_world_dir[1] = 0.52f;
    u_glass_corridor.sun_world_dir[2] = -0.76f;

    MetalityWorldFrameUniforms u_cave = make_profile_uniforms(2);
    u_cave.cam_world_pos[1] = 48.0f;
    u_cave.sun_world_dir[1] = -0.70f; u_cave.sun_world_dir[3] = 0.0f;
    u_cave.sky_color[3] = 0.0f;

    MetalityWorldFrameUniforms u_nether = make_profile_uniforms(2);
    u_nether.dimension_id = -1;
    u_nether.fog_color[0] = 0.42f; u_nether.fog_color[1] = 0.14f; u_nether.fog_color[2] = 0.10f; u_nether.fog_color[3] = 0.85f;

    MetalityWorldFrameUniforms u_end = make_profile_uniforms(2);
    u_end.dimension_id = 1;
    u_end.fog_color[0] = 0.14f; u_end.fog_color[1] = 0.06f; u_end.fog_color[2] = 0.24f; u_end.fog_color[3] = 0.55f;

    // Build 64-section stress scene with 32 dynamic lights, water ripples, stained-glass corridor & sealed cave geometry
    MetalityRtSceneHandle stress_rt = metality_rt_scene_create(ctx, 128u, false);
    BENCH_REQUIRE(stress_rt != nullptr, "Failed to create 64-section stress scene");
    for (int sz = -4; sz < 4; ++sz) {
        for (int sx = -4; sx < 4; ++sx) {
            const bool is_wf = ((sx + sz) & 3) == 0;
            const auto& mo = is_wf ? wmout : rmout;
            BENCH_REQUIRE(metality_rt_scene_update_section_v2(
                ctx, stress_rt, sx, 3, sz,
                is_wf ? w_sq.data() : r_sq.data(), mo.solid_quad_count,
                is_wf ? w_cq.data() : r_cq.data(), mo.cutout_quad_count,
                is_wf ? w_tq.data() : r_tq.data(), mo.translucent_quad_count),
                "Stress RT section update v2 failed");
        }
    }
    std::vector<float> stress_lights(32u * 8u, 0.0f);
    for (uint32_t li = 0u; li < 32u; ++li) {
        stress_lights[li * 8u + 0] = -14.0f + 4.0f * static_cast<float>(li % 8u);
        stress_lights[li * 8u + 1] = 64.5f + 1.2f * static_cast<float>((li / 8u) & 1u);
        stress_lights[li * 8u + 2] = -12.0f + 6.0f * static_cast<float>(li / 8u);
        stress_lights[li * 8u + 3] = 16.0f;
        stress_lights[li * 8u + 4] = 1.0f;
        stress_lights[li * 8u + 5] = (li % 3u == 0u) ? 0.85f : 0.65f;
        stress_lights[li * 8u + 6] = (li % 2u == 0u) ? 0.35f : 0.90f;
        stress_lights[li * 8u + 7] = 0.5f;
    }
    metality_rt_scene_set_dynamic_lights(stress_rt, stress_lights.data(), 32u);
    metality_rt_scene_set_water_ripples(stress_rt, scene_ripples.data(), 4u);
    metality_rt_scene_set_entity_shadows(stress_rt, scene_casters.data(), 3u);
    BENCH_REQUIRE(metality_rt_scene_rebuild_tlas(ctx, stress_rt, 0.0f, 65.0f, 4.0f), "Stress scene sync failed");

    // -------------------------------------------------------------------------
    // Suite 9: Meteor Zenith (15-Pass Ray Tracing) — Multi-Scenario & Quality Matrix (1920x1080 & 3024x1842)
    // -------------------------------------------------------------------------
    std::printf("\n[Suite 9] Meteor Zenith (15-Pass RT) — Multi-Scenario & Quality Profile Matrix:\n");
    {
        run_zenith_benchmark("Zenith-Scen", "Overworld Clear Day (3D Sun+Clouds)", 1920, 1080, u_day, true);
        run_zenith_benchmark("Zenith-Scen", "Overworld Golden Sunset (Godrays)", 1920, 1080, u_sunset, true);
        run_zenith_benchmark("Zenith-Scen", "Night (Moon+Aurora+Nebula+Stars)", 1920, 1080, u_night, true);
        run_zenith_benchmark("Zenith-Scen", "Rain Storm (Puddles+Ripples+Refl)", 1920, 1080, u_rain, true);
        run_zenith_benchmark("Zenith-Scen", "Water/Waterfall/Caustics Stress", 1920, 1080, u_water, false);
        run_zenith_benchmark("Zenith-Scen", "Above-Water River Shoreline 1080p", 1920, 1080, u_river_shore, false);
        run_zenith_benchmark("Zenith-Scen", "Indoor Stained-Glass Corridor 1080p", 1920, 1080, u_glass_corridor, false);
        run_zenith_benchmark("Zenith-Scen", "Cave (Glowing Ores+PBR+StainedGlass)", 1920, 1080, u_cave, false);
        run_zenith_benchmark("Zenith-Scen", "The Nether (Dim -1 Volumetric)", 1920, 1080, u_nether, true);
        run_zenith_benchmark("Zenith-Scen", "The End (Gargantua Black Hole)", 1920, 1080, u_end, true);

        run_zenith_benchmark("Zenith-Prof", "Zenith LOW (1080p Features Off)", 1920, 1080, make_profile_uniforms(0), true);
        run_zenith_benchmark("Zenith-Prof", "Zenith MEDIUM (1080p)", 1920, 1080, make_profile_uniforms(1), true);
        run_zenith_benchmark("Zenith-Prof", "Zenith HIGH (1080p)", 1920, 1080, make_profile_uniforms(2), true);
        run_zenith_benchmark("Zenith-Prof", "Zenith ULTRA (1080p)", 1920, 1080, make_profile_uniforms(3), true);

        run_zenith_benchmark("Zenith-Retina", "Zenith LOW (3024x1842)", 3024, 1842, make_profile_uniforms(0), true);
        run_zenith_benchmark("Zenith-Retina", "Zenith MEDIUM (3024x1842)", 3024, 1842, make_profile_uniforms(1), true);
        run_zenith_benchmark("Zenith-Retina", "Zenith HIGH (3024x1842)", 3024, 1842, make_profile_uniforms(2), true);
        run_zenith_benchmark("Zenith-Retina", "Zenith ULTRA (3024x1842)", 3024, 1842, make_profile_uniforms(3), true);

        run_zenith_benchmark("Zenith-Stress", "Zenith Above-Water River Shoreline 3024x1842", 3024, 1842, u_river_shore, false, 1.0f, stress_rt);
        run_zenith_benchmark("Zenith-Stress", "Zenith Stained-Glass Quartz Corridor 3024x1842", 3024, 1842, u_glass_corridor, false, 1.0f, stress_rt);
        run_zenith_benchmark("Zenith-Stress", "Zenith 64-Sec + 32-Light Dark Cave 3024x1842", 3024, 1842, u_cave, false, 1.0f, stress_rt);
    }

    // -------------------------------------------------------------------------
    // Suite 10: Meteor Zenith (RT) Multi-Resolution Scaling Matrix
    // -------------------------------------------------------------------------
    std::printf("\n[Suite 10] Meteor Zenith (15-Pass RT) Multi-Resolution Scaling Matrix (HIGH Profile):\n");
    {
        struct ResCase { const char* label; uint32_t w; uint32_t h; float scale; };
        const std::vector<ResCase> res_cases = full_mode
            ? std::vector<ResCase>{
                {"Zenith 720p HD", 1280, 720, 1.0f},
                {"Zenith 1080p Full HD", 1920, 1080, 1.0f},
                {"Zenith 1440p QHD", 2560, 1440, 1.0f},
                {"Zenith 3024x1842 Retina", 3024, 1842, 1.0f},
                {"Zenith 3024x1842 MetalFX 0.75x", 3024, 1842, 0.75f},
                {"Zenith 3200x1800 Retina", 3200, 1800, 1.0f},
                {"Zenith 4K UHD", 3840, 2160, 1.0f}
            }
            : std::vector<ResCase>{
                {"Zenith 720p HD", 1280, 720, 1.0f},
                {"Zenith 1080p Full HD", 1920, 1080, 1.0f},
                {"Zenith 1440p QHD", 2560, 1440, 1.0f},
                {"Zenith 3024x1842 Retina", 3024, 1842, 1.0f},
                {"Zenith 3024x1842 MetalFX 0.75x", 3024, 1842, 0.75f},
                {"Zenith 4K UHD", 3840, 2160, 1.0f}
            };
        const MetalityWorldFrameUniforms u_hi = make_profile_uniforms(2);
        for (const auto& rc : res_cases) {
            run_zenith_benchmark("Zenith-Res", rc.label, rc.w, rc.h, u_hi, true, rc.scale);
        }
    }

    // -------------------------------------------------------------------------
    // Suite 11: Meteor Dusk (10-Pass Non-RT Raster + PCSS + GTAO/SSGI + SSR) Benchmark
    // -------------------------------------------------------------------------
    std::printf("\n[Suite 11] Meteor Dusk (10-Pass Non-RT) — Scenarios, Profiles & Multi-Resolution Matrix:\n");
    {
        run_dusk_benchmark("Dusk-Scen", "Overworld Clear Day (2-Cascade PCSS)", 1920, 1080, u_day, true);
        run_dusk_benchmark("Dusk-Scen", "Overworld Golden Sunset (Godrays)", 1920, 1080, u_sunset, true);
        run_dusk_benchmark("Dusk-Scen", "Night (Moon+Aurora+Nebula+Stars)", 1920, 1080, u_night, true);
        run_dusk_benchmark("Dusk-Scen", "Rain Storm (Puddles+Ripples+SSR)", 1920, 1080, u_rain, true);
        run_dusk_benchmark("Dusk-Scen", "Water/Waterfall/Caustics + SSR", 1920, 1080, u_water, false);
        run_dusk_benchmark("Dusk-Scen", "Above-Water River Shoreline 1080p", 1920, 1080, u_river_shore, false);
        run_dusk_benchmark("Dusk-Scen", "Indoor Stained-Glass Corridor 1080p", 1920, 1080, u_glass_corridor, false);
        run_dusk_benchmark("Dusk-Scen", "Cave (Glowing Ores+PBR+Glass+GTAO)", 1920, 1080, u_cave, false);
        run_dusk_benchmark("Dusk-Scen", "The Nether (Dim -1 Volumetric)", 1920, 1080, u_nether, true);
        run_dusk_benchmark("Dusk-Scen", "The End (Gargantua Black Hole)", 1920, 1080, u_end, true);

        run_dusk_benchmark("Dusk-Prof", "Dusk LOW (1080p Features Off)", 1920, 1080, make_profile_uniforms(0), true);
        run_dusk_benchmark("Dusk-Prof", "Dusk MEDIUM (1080p 1c@1024)", 1920, 1080, make_profile_uniforms(1), true);
        run_dusk_benchmark("Dusk-Prof", "Dusk HIGH (1080p 2c@1280)", 1920, 1080, make_profile_uniforms(2), true);
        run_dusk_benchmark("Dusk-Prof", "Dusk ULTRA (1080p 2c@2048)", 1920, 1080, make_profile_uniforms(3), true);

        run_dusk_benchmark("Dusk-Retina", "Dusk LOW (3024x1842)", 3024, 1842, make_profile_uniforms(0), true);
        run_dusk_benchmark("Dusk-Retina", "Dusk MEDIUM (3024x1842)", 3024, 1842, make_profile_uniforms(1), true);
        run_dusk_benchmark("Dusk-Retina", "Dusk HIGH (3024x1842)", 3024, 1842, make_profile_uniforms(2), true);
        run_dusk_benchmark("Dusk-Retina", "Dusk ULTRA (3024x1842)", 3024, 1842, make_profile_uniforms(3), true);

        struct ResCase { const char* label; uint32_t w; uint32_t h; float scale; };
        const std::vector<ResCase> dusk_res = full_mode
            ? std::vector<ResCase>{
                {"Dusk 720p HD", 1280, 720, 1.0f},
                {"Dusk 1080p Full HD", 1920, 1080, 1.0f},
                {"Dusk 1440p QHD", 2560, 1440, 1.0f},
                {"Dusk 3024x1842 Retina", 3024, 1842, 1.0f},
                {"Dusk 3024x1842 MetalFX 0.75x", 3024, 1842, 0.75f},
                {"Dusk 3200x1800 Retina", 3200, 1800, 1.0f},
                {"Dusk 4K UHD", 3840, 2160, 1.0f}
            }
            : std::vector<ResCase>{
                {"Dusk 720p HD", 1280, 720, 1.0f},
                {"Dusk 1080p Full HD", 1920, 1080, 1.0f},
                {"Dusk 1440p QHD", 2560, 1440, 1.0f},
                {"Dusk 3024x1842 Retina", 3024, 1842, 1.0f},
                {"Dusk 3024x1842 MetalFX 0.75x", 3024, 1842, 0.75f},
                {"Dusk 4K UHD", 3840, 2160, 1.0f}
            };
        const MetalityWorldFrameUniforms u_hi = make_profile_uniforms(2);
        for (const auto& rc : dusk_res) {
            run_dusk_benchmark("Dusk-Res", rc.label, rc.w, rc.h, u_hi, true, rc.scale);
        }

        run_dusk_benchmark("Dusk-Stress", "Dusk Above-Water River Shoreline 3024x1842", 3024, 1842, u_river_shore, false, 1.0f, stress_rt);
        run_dusk_benchmark("Dusk-Stress", "Dusk Stained-Glass Quartz Corridor 3024x1842", 3024, 1842, u_glass_corridor, false, 1.0f, stress_rt);
        run_dusk_benchmark("Dusk-Stress", "Dusk 64-Sec Shadow + 32-Light Cave 1080p", 1920, 1080, u_cave, false, 1.0f, stress_rt);
        run_dusk_benchmark("Dusk-Stress", "Dusk 64-Sec Shadow + 32-Light Cave 3024x1842", 3024, 1842, u_cave, false, 1.0f, stress_rt);
    }
    metality_rt_scene_destroy(stress_rt);

    // -------------------------------------------------------------------------
    // Suite 12: Real-World RD=12 Cave + Dirty-Chunk Streaming Stress (3,125 Secs, 300+ Lights, 128 OBBs, 3D Cave Depth)
    // -------------------------------------------------------------------------
    std::printf("\n[Suite 12] Real-World RD=12 Cave + Dirty-Chunk Streaming Stress (3024x1842 Retina):\n");
    {
        constexpr uint32_t kRdW = 3024u;
        constexpr uint32_t kRdH = 1842u;
        MetalityRtSceneHandle rd12_rt = metality_rt_scene_create(ctx, 4096u, false);
        BENCH_REQUIRE(rd12_rt != nullptr, "Failed to create RD=12 stress RT scene");

        // Populate 25 x 5 x 25 = 3,125 sections (RD=12 overworld + deep caves + aquifers, >300 emissive clusters)
        for (int sy = 1; sy <= 5; ++sy) {
            for (int sz = -12; sz <= 12; ++sz) {
                for (int sx = -12; sx <= 12; ++sx) {
                    const bool is_wf = (((sx * 7 + sz * 13 + sy * 5) & 7) == 0);
                    const auto& mo = is_wf ? wmout : rmout;
                    BENCH_REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, rd12_rt, sx, sy, sz,
                        is_wf ? w_sq.data() : r_sq.data(), mo.solid_quad_count,
                        is_wf ? w_cq.data() : r_cq.data(), mo.cutout_quad_count,
                        is_wf ? w_tq.data() : r_tq.data(), mo.translucent_quad_count),
                        "RD=12 stress section update failed");
                }
            }
        }

        // 128 dynamic lights + 3,125 section emissive clusters -> saturates tile light grid with 300+ active cave lights
        std::vector<float> rd12_lights(128u * 8u, 0.0f);
        for (uint32_t li = 0u; li < 128u; ++li) {
            const float ang = static_cast<float>(li) * 0.49f;
            const float rad = 3.0f + static_cast<float>(li % 16u) * 2.2f;
            rd12_lights[li * 8u + 0] = std::cos(ang) * rad;
            rd12_lights[li * 8u + 1] = 46.5f + static_cast<float>((li * 3u) % 14u);
            rd12_lights[li * 8u + 2] = -4.0f - std::abs(std::sin(ang) * rad);
            rd12_lights[li * 8u + 3] = (li % 4u == 0u) ? 18.0f : 11.0f;
            rd12_lights[li * 8u + 4] = (li % 3u == 0u) ? 1.00f : 0.45f;
            rd12_lights[li * 8u + 5] = (li % 3u == 1u) ? 0.92f : 0.62f;
            rd12_lights[li * 8u + 6] = (li % 3u == 2u) ? 1.00f : 0.28f;
            rd12_lights[li * 8u + 7] = 0.45f;
        }
        metality_rt_scene_set_dynamic_lights(rd12_rt, rd12_lights.data(), 128u);

        // 128 entity OBB shadow casters around camera (nearest 32 sorted first)
        std::vector<float> rd12_entities(128u * 8u, 0.0f);
        for (uint32_t ei = 0u; ei < 128u; ++ei) {
            const float dist = 2.5f + static_cast<float>(ei) * 0.22f;
            const float ang = static_cast<float>(ei) * 0.73f;
            rd12_entities[ei * 8u + 0] = std::cos(ang) * dist;
            rd12_entities[ei * 8u + 1] = 48.0f + ((ei & 1u) ? 0.6f : -0.4f);
            rd12_entities[ei * 8u + 2] = 4.0f - std::abs(std::sin(ang)) * dist;
            rd12_entities[ei * 8u + 3] = 0.30f;
            rd12_entities[ei * 8u + 4] = 0.85f;
            rd12_entities[ei * 8u + 5] = 0.28f;
            rd12_entities[ei * 8u + 6] = ang;
            rd12_entities[ei * 8u + 7] = 0.0f;
        }
        metality_rt_scene_set_entity_shadows(rd12_rt, rd12_entities.data(), 128u);
        metality_rt_scene_set_water_ripples(rd12_rt, scene_ripples.data(), 4u);
        BENCH_REQUIRE(metality_rt_scene_rebuild_tlas(ctx, rd12_rt, 0.0f, 48.0f, 4.0f), "RD=12 initial TLAS build failed");

        // Synthesize realistic non-flat 3D cave tunnel + pillars depth buffer (reversed-Z: 0.05 / dist)
        std::vector<float> cave_depth_3d(static_cast<size_t>(kRdW) * kRdH, 0.0f);
        for (uint32_t y = 0u; y < kRdH; ++y) {
            const float ny = (static_cast<float>(y) + 0.5f) / static_cast<float>(kRdH) * 2.0f - 1.0f;
            for (uint32_t x = 0u; x < kRdW; ++x) {
                const float nx = (static_cast<float>(x) + 0.5f) / static_cast<float>(kRdW) * 2.0f - 1.0f;
                const float radial = std::sqrt(nx * nx + ny * ny);
                const float ridge = 1.6f * std::sin(nx * 9.0f) * std::cos(ny * 7.0f)
                                  + 0.7f * std::floor(std::sin((nx + ny) * 14.0f) * 2.0f);
                const float dist = std::clamp(22.0f - radial * 14.0f + ridge, 3.2f, 34.0f);
                cave_depth_3d[static_cast<size_t>(y) * kRdW + x] = 0.05f / dist;
            }
        }

        const MetalityTextureDesc ctex_td{
            kRdW, kRdH, 1, METALITY_FORMAT_RGBA8_UNORM,
            METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED | METALITY_TEX_USAGE_STORAGE_WRITE
        };
        const MetalityTextureDesc dtex_td{
            kRdW, kRdH, 1, METALITY_FORMAT_DEPTH32_FLOAT,
            METALITY_TEX_USAGE_RENDER_TARGET | METALITY_TEX_USAGE_SAMPLED
        };
        MetalityTextureHandle rd_ctex = metality_texture_create(ctx, &ctex_td);
        MetalityTextureHandle rd_dtex = metality_texture_create(ctx, &dtex_td);
        BENCH_REQUIRE(rd_ctex != 0 && rd_dtex != 0, "Failed to create RD=12 stress targets");
        metality_texture_upload_region(ctx, rd_dtex, 0, 0, 0, kRdW, kRdH, cave_depth_3d.data(), kRdW * sizeof(float));

        auto run_rd12_stress = [&](const std::string& pack_dir, const std::string& category,
                                   const std::string& label, bool use_tlas_rebuild) {
            MetalityPackManifestSummary sum{};
            char err[1024]{};
            MetalityPackHandle pack = metality_pack_load_directory(
                ctx, pack_dir.c_str(), kRdW, kRdH, 1.0f, &sum, err, sizeof(err));
            BENCH_REQUIRE(pack != nullptr, "Failed to load pack for RD=12 stress benchmark");
            metality_set_shader_render_scale(ctx, 1.0f);

            auto encode_rd12_frame = [&](uint32_t frame_i, bool dirty_chunk) {
                if (dirty_chunk) {
                    const bool alt = (frame_i & 1u) != 0u;
                    const auto& mo = alt ? wmout : rmout;
                    BENCH_REQUIRE(metality_rt_scene_update_section_v2(
                        ctx, rd12_rt, 0, 3, -1,
                        alt ? w_sq.data() : r_sq.data(), mo.solid_quad_count,
                        alt ? w_cq.data() : r_cq.data(), mo.cutout_quad_count,
                        alt ? w_tq.data() : r_tq.data(), mo.translucent_quad_count),
                        "RD=12 dirty chunk update failed");
                }
                MetalityWorldFrameUniforms fu = u_cave;
                fu.cam_world_pos[0] = 0.08f * std::sin(static_cast<float>(frame_i) * 0.25f);
                fu.cam_world_pos[1] = 48.0f + 0.04f * std::cos(static_cast<float>(frame_i) * 0.35f);
                fu.cam_world_pos[2] = 4.0f;
                fu.cam_world_pos[3] = static_cast<float>(frame_i % 64u);
                fu.time_seconds += static_cast<float>(frame_i) * 0.016f;
                fill_perspective_reversed_z(1.22f, static_cast<float>(kRdW) / kRdH, 0.05f, fu.proj, fu.inv_proj);
                fill_identity(fu.view_rot);
                fill_identity(fu.inv_view_rot);
                std::memcpy(fu.inv_view_proj, fu.inv_proj, sizeof(fu.inv_view_proj));
                std::memcpy(fu.prev_view_proj, fu.proj, sizeof(fu.prev_view_proj));
                fu.width = kRdW;
                fu.height = kRdH;

                if (use_tlas_rebuild) {
                    BENCH_REQUIRE(metality_rt_scene_rebuild_tlas(
                        ctx, rd12_rt, fu.cam_world_pos[0], fu.cam_world_pos[1], fu.cam_world_pos[2]),
                        "RD=12 per-frame TLAS sync failed");
                } else {
                    BENCH_REQUIRE(metality_rt_scene_sync_world_data(
                        ctx, rd12_rt, fu.cam_world_pos[0], fu.cam_world_pos[1], fu.cam_world_pos[2]),
                        "RD=12 per-frame world sync failed");
                }
                metality_cmd_clear_color_depth(ctx, rd_ctex, 0.22f, 0.26f, 0.30f, 1.0f, 0, 0.0);
                BENCH_REQUIRE(metality_pack_execute(ctx, pack, rd12_rt, rd_ctex, rd_dtex, &fu),
                              "RD=12 stress pack execute failed");
            };

            for (uint32_t wu = 0; wu < 4u; ++wu) {
                const uint64_t fid = metality_begin_frame(ctx);
                encode_rd12_frame(wu, false);
                metality_end_frame(ctx, false);
                BENCH_REQUIRE(metality_wait_frame_complete(ctx, fid, 15000), "RD=12 warmup wait failed");
            }

            const uint32_t kSeqFrames = full_mode ? 16u : 8u;
            std::vector<double> cpu_ms, hw_ms, sync_ms;
            for (uint32_t it = 0; it < kSeqFrames; ++it) {
                const uint64_t fid = metality_begin_frame(ctx);
                const auto t0 = Clock::now();
                encode_rd12_frame(it + 4u, true);
                const auto t1 = Clock::now();
                double cs = 0, sw = 0, hw = 0;
                finish_and_measure_frame(ctx, fid, t0, t1, cs, sw, hw);
                cpu_ms.push_back(cs);
                sync_ms.push_back(sw);
                hw_ms.push_back(hw > 0.0 ? hw : sw);
            }

            const uint32_t kPipeFrames = full_mode ? 24u : 12u;
            uint64_t pipe_last_fid = 0;
            const auto p0 = Clock::now();
            for (uint32_t it = 0; it < kPipeFrames; ++it) {
                pipe_last_fid = metality_begin_frame(ctx);
                encode_rd12_frame(it + 30u, true);
                metality_end_frame(ctx, false);
            }
            BENCH_REQUIRE(metality_wait_frame_complete(ctx, pipe_last_fid, 20000), "RD=12 pipe wait failed");
            const auto p1 = Clock::now();
            const double pipe_total_ms = std::chrono::duration<double, std::milli>(p1 - p0).count();

            ShaderScenarioResult sr;
            sr.category = category;
            sr.name = label;
            sr.width = kRdW;
            sr.height = kRdH;
            sr.cpu_submit_ms = compute_stats(cpu_ms).avg;
            sr.gpu_hw_ms = compute_stats(hw_ms);
            sr.sync_wall_ms = compute_stats(sync_ms);
            sr.pipelined_ms = pipe_total_ms / kPipeFrames;
            sr.pipelined_fps = 1000.0 / std::max(sr.pipelined_ms, 0.01);
            sr.mpix_per_sec = (static_cast<double>(kRdW) * kRdH / 1e6) * sr.pipelined_fps;
            sr.tbdr_saved_mb = static_cast<double>(sum.saved_dram_bytes_per_frame) / (1024.0 * 1024.0);
            report.shader_results.push_back(sr);

            std::printf("  [%-10s] %-38s | %4ux%-4u | CPU:%5.2fms | GPU:%5.2fms (p95=%5.2f) | Pipe:%6.1f FPS (%5.1f MPix/s)\n",
                        category.c_str(), label.c_str(), kRdW, kRdH,
                        sr.cpu_submit_ms, sr.gpu_hw_ms.avg, sr.gpu_hw_ms.p95,
                        sr.pipelined_fps, sr.mpix_per_sec);

            metality_pack_destroy(pack);
        };

        run_rd12_stress(meteor_dusk_dir, "RD12-Stress", "Dusk HIGH RD12 3125-Sec Cave+Dirty", false);
        run_rd12_stress(meteor_zenith_dir, "RD12-Stress", "Zenith HIGH RD12 3125-Sec Cave+Dirty", true);

        metality_texture_destroy(ctx, rd_ctex);
        metality_texture_destroy(ctx, rd_dtex);
        metality_rt_scene_destroy(rd12_rt);
    }

    metality_query_uma_pressure(ctx, &report.uma);
    std::printf("\n[Final Telemetry] UMA Allocated: %.1f MB (%.1f%% of recommended) | Thermal State: %u\n",
                static_cast<double>(report.uma.current_allocated_bytes) / (1024.0 * 1024.0),
                report.uma.utilization_ratio * 100.0f,
                report.uma.thermal_state_level);

    if (!json_out_path.empty()) {
        write_json_report(json_out_path, report);
        std::printf("[Export] JSON benchmark report written to: %s\n", json_out_path.c_str());
    }
    if (!md_out_path.empty()) {
        write_markdown_report(md_out_path, report);
        std::printf("[Export] Markdown benchmark report written to: %s\n", md_out_path.c_str());
    }

    metality_rt_scene_destroy(shared_rt);
    metality_context_destroy(ctx);
    std::filesystem::remove_all(cache_dir);
    std::printf("====================================================================================================\n");
    std::printf("  ALL METALITY ENGINE & METEOR SHADER BENCHMARKS COMPLETED SUCCESSFULLY\n");
    std::printf("====================================================================================================\n");
    return 0;
}

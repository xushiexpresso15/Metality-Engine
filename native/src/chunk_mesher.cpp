#include "metality/metality_c_api.h"

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

constexpr int kPadDim = 18;

inline constexpr int p_idx(int px, int py, int pz) {
    return (py * kPadDim + pz) * kPadDim + px;
}

inline constexpr int inner_idx(int x, int y, int z) {
    return (y * 16 + z) * 16 + x;
}

struct FaceBasis {
    int nx, ny, nz; // 法線方向 (-1 / 0 / +1)
    int ux, uy, uz; // 切線 U 軸 (對應 Greedy width w)
    int vx, vy, vz; // 副切線 V 軸 (對應 Greedy height h)
    int origin_offset_x, origin_offset_y, origin_offset_z; // 面片平面相對於方塊最小角的偏移 (0 或 1)
};

// 六個標準軸向面：0=-X, 1=+X, 2=-Y, 3=+Y, 4=-Z, 5=+Z
constexpr FaceBasis kFaceBases[6] = {
    {-1,  0,  0,   0, 0, 1,   0, 1, 0,   0, 0, 0}, // 0: -X (West)  : U=+Z, V=+Y, plane x=x
    {+1,  0,  0,   0, 0, 1,   0, 1, 0,   1, 0, 0}, // 1: +X (East)  : U=+Z, V=+Y, plane x=x+1
    { 0, -1,  0,   1, 0, 0,   0, 0, 1,   0, 0, 0}, // 2: -Y (Down)  : U=+X, V=+Z, plane y=y
    { 0, +1,  0,   1, 0, 0,   0, 0, 1,   0, 1, 0}, // 3: +Y (Up)    : U=+X, V=+Z, plane y=y+1
    { 0,  0, -1,   1, 0, 0,   0, 1, 0,   0, 0, 0}, // 4: -Z (North) : U=+X, V=+Y, plane z=z
    { 0,  0, +1,   1, 0, 0,   0, 1, 0,   0, 0, 1}, // 5: +Z (South) : U=+X, V=+Y, plane z=z+1
};

struct MergeKey {
    uint64_t lo = 0;
    uint64_t hi = 0;
    uint8_t layer = METALITY_LAYER_EMPTY;

    inline bool operator==(const MergeKey& o) const noexcept {
        return lo == o.lo && hi == o.hi && layer == o.layer;
    }
};

inline uint8_t sample_smooth_light(
    const uint8_t* packed_light,
    uint8_t emissive,
    int bx, int by, int bz,
    int s1x, int s1y, int s1z, bool occ1,
    int s2x, int s2y, int s2z, bool occ2,
    int cx, int cy, int cz
) noexcept {
    if (!packed_light) {
        const uint8_t blk = emissive & 0x0F;
        return static_cast<uint8_t>(0xF0u | blk);
    }
    uint8_t l0 = packed_light[p_idx(bx, by, bz)];
    uint8_t l1 = occ1 ? l0 : packed_light[p_idx(s1x, s1y, s1z)];
    uint8_t l2 = occ2 ? l0 : packed_light[p_idx(s2x, s2y, s2z)];
    uint8_t l3 = (occ1 && occ2) ? l0 : packed_light[p_idx(cx, cy, cz)];

    // 取四鄰格最大亮度平滑混合，並疊加材質自發光下限
    uint32_t sky_sum = (l0 >> 4) + (l1 >> 4) + (l2 >> 4) + (l3 >> 4);
    uint32_t blk_sum = (l0 & 0xF) + (l1 & 0xF) + (l2 & 0xF) + (l3 & 0xF);
    uint8_t sky_avg = static_cast<uint8_t>((sky_sum + 2u) >> 2);
    uint8_t blk_avg = std::max<uint8_t>(static_cast<uint8_t>((blk_sum + 2u) >> 2), emissive & 0x0F);
    return static_cast<uint8_t>((sky_avg << 4) | blk_avg);
}

inline void emit_quad_to_output(
    MetalityMeshedSectionOutput* out,
    uint8_t layer,
    MetalityPackedQuad quad
) noexcept {
    if (layer == METALITY_LAYER_SOLID) {
        if (out->solid_quads && out->solid_quad_count < out->solid_quad_capacity) {
            out->solid_quads[out->solid_quad_count] = quad;
            out->solid_quad_count += 1;
        }
    } else if (layer == METALITY_LAYER_CUTOUT) {
        if (out->cutout_quads && out->cutout_quad_count < out->cutout_quad_capacity) {
            out->cutout_quads[out->cutout_quad_count] = quad;
            out->cutout_quad_count += 1;
        }
    } else if (layer == METALITY_LAYER_TRANSLUCENT) {
        if (out->translucent_quads && out->translucent_quad_count < out->translucent_quad_capacity) {
            out->translucent_quads[out->translucent_quad_count] = quad;
            out->translucent_quad_count += 1;
        }
    }
}

} // namespace

extern "C" {

bool metality_mesh_chunk_section_neon(
    const MetalityChunkSectionInput* input,
    MetalityMeshedSectionOutput* output
) {
    if (!input || !output || !input->block_material_ids || !input->palette_table || input->palette_size == 0) {
        return false;
    }

    output->solid_quad_count = 0;
    output->cutout_quad_count = 0;
    output->translucent_quad_count = 0;
    output->aabb_min[0] = 16.0f;
    output->aabb_min[1] = 16.0f;
    output->aabb_min[2] = 16.0f;
    output->aabb_max[0] = 0.0f;
    output->aabb_max[1] = 0.0f;
    output->aabb_max[2] = 0.0f;

    const uint16_t* blocks = input->block_material_ids;
    const auto* palette = input->palette_table;
    const uint32_t palette_limit = input->palette_size;

    // Step 1: 使用 ARM NEON SIMD 快速掃描 18x18x18 鄰域：
    //   - 若整排 16 格皆為 0 (空氣)，以單條 UMAXV 指令 2 週期直接跳過！
    //   - 建立每個 Y-Z 列的 32-bit 實心遮擋遮罩 opaque_row_mask[18][18] (bit px = is_opaque_full_cube)
    //   - 建立本區段 16x16 列的非空方塊遮罩 non_empty_inner_mask[16][16] (bit x = layer != EMPTY)
    uint32_t opaque_row_mask[kPadDim][kPadDim] = {};
    uint16_t non_empty_inner_mask[16][16] = {};
    bool section_has_any_block = false;

    for (int py = 0; py < kPadDim; ++py) {
        for (int pz = 0; pz < kPadDim; ++pz) {
            const uint16_t* row = &blocks[p_idx(0, py, pz)];

#if defined(__aarch64__)
            // 以 NEON 128-bit 向量一次載入中心 16 格 (px = 1..16)，快速過濾純空氣排
            const uint16x8_t v_lo = vld1q_u16(row + 1);
            const uint16x8_t v_hi = vld1q_u16(row + 9);
            const uint16_t inner_max = vmaxvq_u16(vorrq_u16(v_lo, v_hi));
            if (inner_max == 0 && row[0] == 0 && row[17] == 0) {
                continue;
            }
#endif

            uint32_t op_mask = 0;
            uint16_t inner_vis = 0;
            for (int px = 0; px < kPadDim; ++px) {
                const uint16_t mat_id = row[px];
                if (mat_id == 0 || mat_id >= palette_limit) {
                    continue;
                }
                const auto& mat = palette[mat_id];
                if (mat.layer == METALITY_LAYER_EMPTY) {
                    continue;
                }
                if (mat.is_opaque_full_cube) {
                    op_mask |= (1u << px);
                }
                if (px >= 1 && px <= 16 && py >= 1 && py <= 16 && pz >= 1 && pz <= 16) {
                    inner_vis |= static_cast<uint16_t>(1u << (px - 1));
                }
            }
            opaque_row_mask[py][pz] = op_mask;
            if (py >= 1 && py <= 16 && pz >= 1 && pz <= 16 && inner_vis != 0) {
                non_empty_inner_mask[py - 1][pz - 1] = inner_vis;
                section_has_any_block = true;
            }
        }
    }

    if (!section_has_any_block) {
        output->aabb_min[0] = output->aabb_min[1] = output->aabb_min[2] = 0.0f;
        return true;
    }

    auto is_opaque_at = [&](int px, int py, int pz) noexcept -> bool {
        return (opaque_row_mask[py][pz] & (1u << px)) != 0;
    };

    const bool greedy = (input->enable_greedy_merge != 0);
    const bool rt_geometry_only = (input->enable_greedy_merge == 2u);

    // Step 2: 針對 6 個軸向面進行位元遮罩隱藏面剔除、4 頂點平滑 AO 計算與 2D Greedy 矩形合併
    for (int dir = 0; dir < 6; ++dir) {
        const FaceBasis& basis = kFaceBases[dir];

        // 每個 slice s (0..15) 代表沿法線軸的一個平面
        for (int s = 0; s < 16; ++s) {
            MergeKey plane_keys[16][16];
            uint16_t valid_row_bits[16] = {};
            bool slice_any_face = false;

            for (int v = 0; v < 16; ++v) {
                uint16_t row_bits = 0;
                for (int u = 0; u < 16; ++u) {
                    // 將 (s, u, v) 映射回區段內部座標 (x, y, z) in 0..15
                    int x = 0, y = 0, z = 0;
                    if (dir <= 1) {        // -X, +X: s=x, u=z, v=y
                        x = s; z = u; y = v;
                    } else if (dir <= 3) { // -Y, +Y: s=y, u=x, v=z
                        y = s; x = u; z = v;
                    } else {               // -Z, +Z: s=z, u=x, v=y
                        z = s; x = u; y = v;
                    }

                    if ((non_empty_inner_mask[y][z] & (1u << x)) == 0) {
                        continue;
                    }

                    const int px = x + 1;
                    const int py = y + 1;
                    const int pz = z + 1;
                    const uint16_t mat_id = blocks[p_idx(px, py, pz)];
                    const auto& mat = palette[mat_id];

                    // Non-occluding voxel-only markers (tag=14, sub=14 invisible minecraft:light; sub=15 pass-through grass/flowers)
                    // never enter BVH/CSM and only need a single +Y (dir == 3) quad to stamp voxel_blocks identically (6x fewer cutout quads).
                    const uint8_t sub_nibble = static_cast<uint8_t>(mat.emissive_level & 0xFu);
                    const bool is_voxel_marker = (mat.wave_tag == 14u && (sub_nibble == 14u || sub_nibble == 15u));
                    if (is_voxel_marker && dir != 3) {
                        continue;
                    }

                    // 檢查法線前方鄰格是否完全遮擋本面
                    // 非瀑布水源/淺水 (wave_tag == 3 && emissive_level < 9) 頂面高度為 8/9 (0.888889 < 1.0)，
                    // 僅於鄰近開放河岸懸崖（至少一個水平鄰格為非實心且其上方非實心，即真實河岸懸崖邊緣，而非地底封閉含水層頂蓋）
                    // 時保留 +Y 頂面 (dir == 3)，避免地底封閉水洞產生數千無效水體頂面與錯誤高水位標記
                    const int npx = px + basis.nx;
                    const int npy = py + basis.ny;
                    const int npz = pz + basis.nz;
                    bool keep_subfull_water_top = false;
                    if (dir == 3 && mat.wave_tag == 3 && (mat.emissive_level & 0xF) < 9 && is_opaque_at(npx, npy, npz)) {
                        const bool has_open_horiz =
                            !is_opaque_at(px - 1, py, pz) || !is_opaque_at(px + 1, py, pz) ||
                            !is_opaque_at(px, py, pz - 1) || !is_opaque_at(px, py, pz + 1);
                        const bool has_exposed_overhang =
                            !is_opaque_at(px - 1, npy, pz) || !is_opaque_at(px + 1, npy, pz) ||
                            !is_opaque_at(px, npy, pz - 1) || !is_opaque_at(px, npy, pz + 1);
                        keep_subfull_water_top = has_open_horiz && has_exposed_overhang;
                    }
                    if (!keep_subfull_water_top && !is_voxel_marker && is_opaque_at(npx, npy, npz)) {
                        continue;
                    }

                    // 半透明同材質內部接縫剔除（水鄰接水、同色玻璃鄰接同色玻璃不畫內側面）
                    const uint16_t neighbor_mat_id = blocks[p_idx(npx, npy, npz)];
                    if (mat.layer == METALITY_LAYER_TRANSLUCENT && neighbor_mat_id == mat_id) {
                        continue;
                    }

                    const uint32_t tint = input->biome_tint_rgb
                                              ? (input->biome_tint_rgb[inner_idx(x, y, z)] & 0xFFFFFFu)
                                              : 0xFFFFFFu;

                    MergeKey key;
                    key.layer = mat.layer;
                    if (rt_geometry_only) {
                        // RT/CSM 幾何專用 Greedy 模式：raytracing_scene.mm 僅讀取 (x,y,z,w,h,dir,wave_tag,emissive,tint)，
                        // 忽略 ao4 / flip_diag / lightmap4 / sprite_id / alpha，將 BVH 與 CSM 三角形數量縮減 ~6.5x！
                        key.lo = (0xFFULL << 26) |
                                 (static_cast<uint64_t>(mat.wave_tag & 0xF) << 35) |
                                 (static_cast<uint64_t>(mat.emissive_level & 0xF) << 39);
                        key.hi = 0xF0F0F0F0ULL |
                                 (static_cast<uint64_t>(tint) << 32) |
                                 (0xFFULL << 56);
                    } else {
                        // 計算四個角落 c0=(-1,-1), c1=(+1,-1), c2=(+1,+1), c3=(-1,+1) 的平滑 AO 與光照貼圖
                        static constexpr int kDu[4] = {-1, +1, +1, -1};
                        static constexpr int kDv[4] = {-1, -1, +1, +1};
                        uint32_t ao_packed = 0;
                        uint32_t light_packed = 0;
                        uint32_t ao_vals[4] = {};

                        for (int c = 0; c < 4; ++c) {
                            const int s1x = npx + kDu[c] * basis.ux;
                            const int s1y = npy + kDu[c] * basis.uy;
                            const int s1z = npz + kDu[c] * basis.uz;

                            const int s2x = npx + kDv[c] * basis.vx;
                            const int s2y = npy + kDv[c] * basis.vy;
                            const int s2z = npz + kDv[c] * basis.vz;

                            const int cx = npx + kDu[c] * basis.ux + kDv[c] * basis.vx;
                            const int cy = npy + kDu[c] * basis.uy + kDv[c] * basis.vy;
                            const int cz = npz + kDu[c] * basis.uz + kDv[c] * basis.vz;

                            const bool side1 = is_opaque_at(s1x, s1y, s1z);
                            const bool side2 = is_opaque_at(s2x, s2y, s2z);
                            const bool corner = is_opaque_at(cx, cy, cz);

                            const uint32_t ao = (side1 && side2)
                                                    ? 0u
                                                    : (3u - (static_cast<uint32_t>(side1) +
                                                             static_cast<uint32_t>(side2) +
                                                             static_cast<uint32_t>(corner)));
                            ao_vals[c] = ao;
                            ao_packed |= (ao << (c * 2));

                            const uint8_t lm = sample_smooth_light(
                                input->packed_light, mat.emissive_level,
                                npx, npy, npz,
                                s1x, s1y, s1z, side1,
                                s2x, s2y, s2z, side2,
                                cx, cy, cz
                            );
                            light_packed |= (static_cast<uint32_t>(lm) << (c * 8));
                        }

                        const uint64_t flip_diag = (ao_vals[0] + ao_vals[2] < ao_vals[1] + ao_vals[3]) ? 1ULL : 0ULL;
                        const uint16_t sprite = mat.face_sprites[dir];

                        // 組裝 128-bit Greedy 合併特徵鍵：所有視覺與光照屬性完全一致才允許合併，保證零畫質失真
                        key.lo = (static_cast<uint64_t>(ao_packed) << 26) |
                                 (flip_diag << 34) |
                                 (static_cast<uint64_t>(mat.wave_tag & 0xF) << 35) |
                                 (static_cast<uint64_t>(mat.emissive_level & 0xF) << 39) |
                                 (static_cast<uint64_t>(sprite) << 48);
                        key.hi = static_cast<uint64_t>(light_packed) |
                                 (static_cast<uint64_t>(tint) << 32) |
                                 (static_cast<uint64_t>(mat.alpha) << 56);
                    }

                    plane_keys[v][u] = key;
                    row_bits |= static_cast<uint16_t>(1u << u);
                }
                valid_row_bits[v] = row_bits;
                if (row_bits != 0) {
                    slice_any_face = true;
                }
            }

            if (!slice_any_face) {
                continue;
            }

            // Step 3: 位元遮罩加速之 2D Greedy Quad 擴張
            for (int v = 0; v < 16; ++v) {
                while (valid_row_bits[v] != 0) {
                    const int u = __builtin_ctz(valid_row_bits[v]);
                    const MergeKey target_key = plane_keys[v][u];

                    int w = 1;
                    int h = 1;

                    if (greedy) {
                        while (u + w < 16 &&
                               (valid_row_bits[v] & (1u << (u + w))) != 0 &&
                               plane_keys[v][u + w] == target_key) {
                            ++w;
                        }

                        const uint16_t span_mask = static_cast<uint16_t>(((1u << w) - 1u) << u);
                        while (v + h < 16 && (valid_row_bits[v + h] & span_mask) == span_mask) {
                            bool row_matches = true;
                            for (int dw = 0; dw < w; ++dw) {
                                if (!(plane_keys[v + h][u + dw] == target_key)) {
                                    row_matches = false;
                                    break;
                                }
                            }
                            if (!row_matches) {
                                break;
                            }
                            ++h;
                        }

                        for (int dh = 0; dh < h; ++dh) {
                            valid_row_bits[v + dh] &= static_cast<uint16_t>(~span_mask);
                        }
                    } else {
                        valid_row_bits[v] &= static_cast<uint16_t>(~(1u << u));
                    }

                    // 計算面片於 [0..16]^3 區段空間的起點座標 (qx, qy, qz)
                    int base_x = 0, base_y = 0, base_z = 0;
                    if (dir <= 1) {
                        base_x = s; base_z = u; base_y = v;
                    } else if (dir <= 3) {
                        base_y = s; base_x = u; base_z = v;
                    } else {
                        base_z = s; base_x = u; base_y = v;
                    }

                    const uint32_t qx = static_cast<uint32_t>(base_x + basis.origin_offset_x);
                    const uint32_t qy = static_cast<uint32_t>(base_y + basis.origin_offset_y);
                    const uint32_t qz = static_cast<uint32_t>(base_z + basis.origin_offset_z);

                    const uint32_t end_x = qx + static_cast<uint32_t>(w * basis.ux + h * basis.vx);
                    const uint32_t end_y = qy + static_cast<uint32_t>(w * basis.uy + h * basis.vy);
                    const uint32_t end_z = qz + static_cast<uint32_t>(w * basis.uz + h * basis.vz);

                    output->aabb_min[0] = std::min(output->aabb_min[0], static_cast<float>(std::min(qx, end_x)));
                    output->aabb_min[1] = std::min(output->aabb_min[1], static_cast<float>(std::min(qy, end_y)));
                    output->aabb_min[2] = std::min(output->aabb_min[2], static_cast<float>(std::min(qz, end_z)));
                    output->aabb_max[0] = std::max(output->aabb_max[0], static_cast<float>(std::max(qx, end_x)));
                    output->aabb_max[1] = std::max(output->aabb_max[1], static_cast<float>(std::max(qy, end_y)));
                    output->aabb_max[2] = std::max(output->aabb_max[2], static_cast<float>(std::max(qz, end_z)));

                    MetalityPackedQuad quad{};
                    quad.word0 = (static_cast<uint64_t>(qx & 0x1Fu) << 0) |
                                 (static_cast<uint64_t>(qy & 0x1Fu) << 5) |
                                 (static_cast<uint64_t>(qz & 0x1Fu) << 10) |
                                 (static_cast<uint64_t>((w - 1) & 0xFu) << 15) |
                                 (static_cast<uint64_t>((h - 1) & 0xFu) << 19) |
                                 (static_cast<uint64_t>(dir & 0x7u) << 23) |
                                 target_key.lo;
                    quad.word1 = target_key.hi;

                    emit_quad_to_output(output, target_key.layer, quad);
                }
            }
        }
    }

    if (output->solid_quad_count == 0 && output->cutout_quad_count == 0 && output->translucent_quad_count == 0) {
        output->aabb_min[0] = output->aabb_min[1] = output->aabb_min[2] = 0.0f;
    }

    return true;
}

} // extern "C"

#import "metality/metal_context.hpp"
#import <dispatch/dispatch.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {

constexpr int32_t kFaceNormals[6][3] = {
    {-1,  0,  0}, // 0: -X
    {+1,  0,  0}, // 1: +X
    { 0, -1,  0}, // 2: -Y
    { 0, +1,  0}, // 3: +Y
    { 0,  0, -1}, // 4: -Z
    { 0,  0, +1}, // 5: +Z
};

constexpr int32_t kFaceUBasis[6][3] = {
    {0, 0, 1}, // -X
    {0, 0, 1}, // +X
    {1, 0, 0}, // -Y
    {1, 0, 0}, // +Y
    {1, 0, 0}, // -Z
    {1, 0, 0}, // +Z
};

constexpr int32_t kFaceVBasis[6][3] = {
    {0, 1, 0}, // -X
    {0, 1, 0}, // +X
    {0, 0, 1}, // -Y
    {0, 0, 1}, // +Y
    {0, 1, 0}, // -Z
    {0, 1, 0}, // +Z
};

constexpr uint32_t kVoxelGridX = 512u;
constexpr uint32_t kVoxelGridY = 128u;
constexpr uint32_t kVoxelGridZ = 512u;
constexpr size_t kVoxelGridVolume = static_cast<size_t>(kVoxelGridX) * kVoxelGridY * kVoxelGridZ; // uint offset 0 (33,554,432 B)
constexpr size_t kWaterMacroBytes = 65536u;       // uint offset 8,388,608 (65,536 B)
constexpr size_t kGlassMacroBytes = 65536u;       // uint offset 8,404,992 (65,536 B)
constexpr size_t kTopHeightmapBytes = static_cast<size_t>(kVoxelGridX) * kVoxelGridZ * sizeof(uint16_t); // uint offset 8,421,376 (524,288 B, 512x512 uint16_t)
constexpr size_t kSeaWaterMaskBytes = (1024u * 1024u) / 8u; // uint offset 8,552,448 (131,072 B, 1024x1024 1-bit bitmask)
constexpr size_t kTopWaterHeightmapBytes = static_cast<size_t>(kVoxelGridX) * kVoxelGridZ * sizeof(uint16_t); // uint offset 8,585,216 (524,288 B, 512x512 uint16_t)
constexpr size_t kVoxelGridBufferBytes =
    kVoxelGridVolume + kWaterMacroBytes + kGlassMacroBytes +
    kTopHeightmapBytes + kSeaWaterMaskBytes + kTopWaterHeightmapBytes;
static_assert(kVoxelGridBufferBytes == 34865152u, "kVoxelGridBufferBytes must match 34,865,152-byte Slang contract");
constexpr uint32_t kMaxEmissiveLights = 1024u;
constexpr uint32_t kMaxWaterRipples = 64u;
constexpr uint32_t kMaxEntityShadowCasters = 256u;
constexpr uint32_t kRtRingSlots = 4u;

inline bool is_bvh_opaque_tag(uint32_t tag) {
    return (tag >= 1u && tag <= 7u && tag != 3u) || tag == 12u;
}

inline uint32_t classify_light_category(uint32_t tag, float r, float g, float b) {
    if (tag == 13u || (r > 0.65f && g < 0.28f && b < 0.25f)) {
        return 5u; // Ruby Red (Redstone Torch / Ore / Repeater)
    }
    if (tag == 6u || tag == 10u) {
        if (g > r * 1.15f && g > b * 1.10f) return 3u; // Emerald Green (Verdant Froglight / Glow Lichen)
        if (r > 0.38f && g > 0.75f && b > 0.82f) return 4u; // Aqua White (Sea Lantern / Conduit / Beacon)
        return 1u; // Soul Cyan (Soul Fire / Torch / Lantern / Campfire / Sculk)
    }
    if (tag == 7u || tag == 11u) {
        return 2u; // Violet / Pearl (End Rod / Pearlescent Froglight / Portal / Crying Obsidian)
    }
    if (r > 0.80f && g > 0.62f && b > 0.20f) {
        return 6u; // Gold / Ochre (Glowstone / Ochre Froglight / Lantern)
    }
    return 0u; // Warm Flame Amber (Torch / Campfire / Fire / Shroomlight / Candles)
}

struct alignas(16) RtVec4 {
    float x, y, z, w;
};

struct alignas(16) RtDispatchUniforms {
    float inv_view_proj[16];
    float cam_pos[4];      // xyz + frame_jitter_seed
    float sun_dir[4];      // xyz + unused
    int32_t voxel_origin[4]; // x, y, z, voxel_enabled
    uint32_t width;
    uint32_t height;
    uint32_t instance_count;
    uint32_t pad0;
};

struct SectionKey {
    int32_t x, y, z;
    bool operator==(const SectionKey& o) const noexcept {
        return x == o.x && y == o.y && z == o.z;
    }
};

struct SectionKeyHash {
    size_t operator()(const SectionKey& k) const noexcept {
        uint64_t h = static_cast<uint64_t>(static_cast<uint32_t>(k.x)) * 0x9E3779B185EBCA87ULL;
        h ^= static_cast<uint64_t>(static_cast<uint32_t>(k.y)) * 0xC2B2AE3D27D4EB4FULL;
        h ^= static_cast<uint64_t>(static_cast<uint32_t>(k.z)) * 0x165667B19E3779F9ULL;
        return static_cast<size_t>(h ^ (h >> 33));
    }
};

struct DecodedVoxelBlock {
    int16_t lx, ly, lz; // section-local block coord [0..15]
    uint32_t tint_rgb;
    uint8_t emissive;
    uint8_t semantic_tag;
    uint8_t face_mask = 0u;
};

struct SectionEmissiveCluster {
    float lx, ly, lz; // section-local centroid
    float power;
    float r, g, b;
    float radius;
};

struct DynamicLightEntry {
    float wx, wy, wz, power;
    float r, g, b, radius;
};

struct WaterRippleEntry {
    float wx, wy, wz, age;
    float dir_x, dir_z, amplitude, kind;
};

struct EntityShadowEntry {
    float wx, wy, wz, hx;
    float hy, hz, yaw_rad, pitch_rad;
};

struct SectionRtEntry {
    int32_t sec_x = 0;
    int32_t sec_y = 0;
    int32_t sec_z = 0;
    uint32_t quad_count = 0;
    uint32_t triangle_count = 0;
    id<MTLBuffer> vertex_buffer = nil;   // MTLPackedFloat3 (12B * 6 * quad_count)
    id<MTLBuffer> meta_buffer = nil;     // uint32_t per quad: [0..2]=face_dir, [3..6]=emissive, [8..31]=rgb24
    id<MTLAccelerationStructure> blas = nil;
    MTLPrimitiveAccelerationStructureDescriptor* blas_desc = nil;
    NSUInteger blas_scratch_size = 0;
    bool blas_needs_build = false;
    std::vector<uint32_t> packed_meta_cpu;
    std::vector<DecodedVoxelBlock> voxel_blocks;
    std::vector<SectionEmissiveCluster> emissive_clusters;
};

constexpr const char* kRayTracingGiMsl = R"(
#include <metal_stdlib>
#include <metal_raytracing>
using namespace metal;
using namespace metal::raytracing;

struct RtDispatchUniforms {
    float4x4 inv_view_proj;
    float4 cam_pos;
    float4 sun_dir;
    int4 voxel_origin;
    uint width;
    uint height;
    uint instance_count;
    uint pad0;
};

constant float3 kFaceNormals[6] = {
    float3(-1.0f,  0.0f,  0.0f),
    float3( 1.0f,  0.0f,  0.0f),
    float3( 0.0f, -1.0f,  0.0f),
    float3( 0.0f,  1.0f,  0.0f),
    float3( 0.0f,  0.0f, -1.0f),
    float3( 0.0f,  0.0f,  1.0f)
};

inline float3 eval_gi_lighting(float3 nrm, float3 sun_dir, float3 albedo, bool sun_visible, bool gi_sky_visible, float emissive) {
    float ndotl = max(dot(nrm, sun_dir), 0.0f);
    float3 direct = sun_visible ? (float3(4.5f, 4.2f, 3.6f) * ndotl) : float3(0.0f);
    float3 sky_amb = gi_sky_visible ? float3(0.45f, 0.58f, 0.85f) : float3(0.06f, 0.07f, 0.09f);
    float3 glow = float3(emissive * 2.5f);
    return albedo * (direct + sky_amb) + glow;
}

kernel void cs_metality_pathtrace_gi_hw(
    uint2 gid [[thread_position_in_grid]],
    constant RtDispatchUniforms& u [[buffer(0)]],
    instance_acceleration_structure tlas [[buffer(1)]],
    device const uint* quad_meta_flat [[buffer(2)]],
    device const uint* instance_meta_offsets [[buffer(3)]],
    texture2d<float, access::write> out_radiance [[texture(0)]],
    texture2d<float, access::write> out_depth [[texture(1)]],
    texture2d<float, access::write> out_motion [[texture(2)]],
    texture2d<float, access::write> out_diffuse_albedo [[texture(3)]],
    texture2d<float, access::write> out_specular_albedo [[texture(4)]],
    texture2d<float, access::write> out_normal [[texture(5)]],
    texture2d<float, access::write> out_roughness [[texture(6)]]
) {
    if (gid.x >= u.width || gid.y >= u.height) {
        return;
    }

    float2 uv = (float2(gid) + 0.5f) / float2(u.width, u.height);
    float2 ndc = float2(uv.x * 2.0f - 1.0f, 1.0f - uv.y * 2.0f);
    float4 clip_far = u.inv_view_proj * float4(ndc, 1.0f, 1.0f);
    float3 world_far = clip_far.xyz / max(clip_far.w, 1e-5f);
    float3 ray_dir = normalize(world_far - u.cam_pos.xyz);

    if (u.instance_count == 0u) {
        float sky_t = clamp(ray_dir.y * 0.5f + 0.5f, 0.0f, 1.0f);
        float3 sky = mix(float3(0.30f, 0.45f, 0.70f), float3(0.65f, 0.85f, 1.25f), sky_t);
        out_radiance.write(float4(sky, 1.0f), gid);
        out_depth.write(float4(1.0f, 0.0f, 0.0f, 1.0f), gid);
        out_motion.write(float4(0.0f), gid);
        out_diffuse_albedo.write(float4(0.0f, 0.0f, 0.0f, 1.0f), gid);
        out_specular_albedo.write(float4(0.0f), gid);
        out_normal.write(float4(0.0f, 1.0f, 0.0f, 1.0f), gid);
        out_roughness.write(float4(1.0f, 0.0f, 0.0f, 1.0f), gid);
        return;
    }

    ray primary_ray;
    primary_ray.origin = float3(0.0f);
    primary_ray.direction = ray_dir;
    primary_ray.min_distance = 0.01f;
    primary_ray.max_distance = 512.0f;

    intersector<triangle_data, instancing> inter;
    inter.assume_geometry_type(geometry_type::triangle);
    inter.force_opacity(forced_opacity::opaque);

    auto hit = inter.intersect(primary_ray, tlas);
    if (hit.type != intersection_type::triangle) {
        float sky_t = clamp(ray_dir.y * 0.5f + 0.5f, 0.0f, 1.0f);
        float3 sky = mix(float3(0.30f, 0.45f, 0.70f), float3(0.65f, 0.85f, 1.25f), sky_t);
        out_radiance.write(float4(sky, 1.0f), gid);
        out_depth.write(float4(1.0f, 0.0f, 0.0f, 1.0f), gid);
        out_motion.write(float4(0.0f), gid);
        out_diffuse_albedo.write(float4(0.0f, 0.0f, 0.0f, 1.0f), gid);
        out_specular_albedo.write(float4(0.0f), gid);
        out_normal.write(float4(0.0f, 1.0f, 0.0f, 1.0f), gid);
        out_roughness.write(float4(1.0f, 0.0f, 0.0f, 1.0f), gid);
        return;
    }

    uint quad_idx = instance_meta_offsets[hit.instance_id] + (hit.primitive_id >> 1);
    uint meta = quad_meta_flat[quad_idx];
    uint fdir = meta & 7u;
    float emissive = float((meta >> 3) & 0xFu) / 15.0f;
    float3 albedo = float3(float((meta >> 8) & 0xFFu),
                           float((meta >> 16) & 0xFFu),
                           float((meta >> 24) & 0xFFu)) / 255.0f * 0.75f + 0.05f;
    float3 nrm = kFaceNormals[min(fdir, 5u)];

    float3 hit_pos = ray_dir * hit.distance;
    float3 shadow_origin = hit_pos + nrm * 0.004f;
    float3 sun_dir = normalize(u.sun_dir.xyz);

    intersector<triangle_data, instancing> shadow_inter;
    shadow_inter.assume_geometry_type(geometry_type::triangle);
    shadow_inter.force_opacity(forced_opacity::opaque);
    shadow_inter.accept_any_intersection(true);

    bool sun_visible = true;
    if (dot(nrm, sun_dir) > 0.0f) {
        ray sray;
        sray.origin = shadow_origin;
        sray.direction = sun_dir;
        sray.min_distance = 0.005f;
        sray.max_distance = 256.0f;
        auto shit = shadow_inter.intersect(sray, tlas);
        if (shit.type != intersection_type::none) {
            sun_visible = false;
        }
    } else {
        sun_visible = false;
    }

    // 1-Bounce Hemisphere GI Visibility Ray
    float3 tangent = abs(nrm.y) < 0.9f ? normalize(cross(nrm, float3(0, 1, 0))) : float3(1, 0, 0);
    float3 bitangent = cross(nrm, tangent);
    uint h = (gid.x * 1973u + gid.y * 9277u + as_type<uint>(u.cam_pos.w) * 26699u) | 1u;
    h ^= h >> 15; h *= 0x85ebca6bu; h ^= h >> 13;
    float jx = float(h & 255u) / 255.0f - 0.5f;
    float jz = float((h >> 8) & 255u) / 255.0f - 0.5f;
    float3 bounce_dir = normalize(nrm + tangent * (jx * 0.65f) + bitangent * (jz * 0.65f));
    ray bray;
    bray.origin = shadow_origin;
    bray.direction = bounce_dir;
    bray.min_distance = 0.005f;
    bray.max_distance = 64.0f;
    auto bhit = shadow_inter.intersect(bray, tlas);
    bool gi_sky_visible = (bhit.type == intersection_type::none);

    float3 hdr = eval_gi_lighting(nrm, sun_dir, albedo, sun_visible, gi_sky_visible, emissive);

    out_radiance.write(float4(hdr, 1.0f), gid);
    out_depth.write(float4(clamp(hit.distance / 256.0f, 0.0f, 1.0f), 0.0f, 0.0f, 1.0f), gid);
    out_motion.write(float4(0.0f), gid);
    out_diffuse_albedo.write(float4(albedo, 1.0f), gid);
    out_specular_albedo.write(float4(0.04f, 0.04f, 0.04f, 1.0f), gid);
    out_normal.write(float4(nrm * 0.5f + 0.5f, 1.0f), gid);
    out_roughness.write(float4(0.85f, 0.0f, 0.0f, 1.0f), gid);
}

// M1/M2 GPU Voxel 3D Amanatides-Woo DDA 後備管線（零硬體光追依賴）
inline bool trace_voxel_dda(
    device const uchar* voxels,
    int3 grid_origin,
    float3 ray_orig_world,
    float3 ray_dir,
    float max_dist,
    thread float& out_dist,
    thread float3& out_normal
) {
    constexpr int3 kDims = int3(512, 128, 512);
    float3 pos = ray_orig_world - float3(grid_origin);

    int3 cell = int3(floor(pos));
    float3 inv_dir = float3(
        abs(ray_dir.x) > 1e-6f ? 1.0f / ray_dir.x : 1e6f,
        abs(ray_dir.y) > 1e-6f ? 1.0f / ray_dir.y : 1e6f,
        abs(ray_dir.z) > 1e-6f ? 1.0f / ray_dir.z : 1e6f
    );
    int3 step_v = int3(ray_dir.x >= 0.0f ? 1 : -1, ray_dir.y >= 0.0f ? 1 : -1, ray_dir.z >= 0.0f ? 1 : -1);
    float3 t_max = float3(
        ((cell.x + (step_v.x > 0 ? 1.0f : 0.0f)) - pos.x) * inv_dir.x,
        ((cell.y + (step_v.y > 0 ? 1.0f : 0.0f)) - pos.y) * inv_dir.y,
        ((cell.z + (step_v.z > 0 ? 1.0f : 0.0f)) - pos.z) * inv_dir.z
    );
    float3 t_delta = abs(inv_dir);

    float dist = 0.0f;
    float3 nrm = float3(0, 1, 0);

    for (int iter = 0; iter < 520; ++iter) {
        if (cell.x >= 0 && cell.x < kDims.x &&
            cell.y >= 0 && cell.y < kDims.y &&
            cell.z >= 0 && cell.z < kDims.z) {
            uint idx = uint((cell.y * kDims.z + cell.z) * kDims.x + cell.x);
            uchar v = voxels[idx];
            uint tag = uint(v) >> 4u;
            bool is_solid_occ = (v != 0u) && (tag != 3u) && (tag < 8u || tag == 12u);
            if (is_solid_occ && dist > 0.01f) {
                out_dist = dist;
                out_normal = nrm;
                return true;
            }
        }
        if (dist > max_dist) {
            return false;
        }
        if (t_max.x < t_max.y) {
            if (t_max.x < t_max.z) {
                dist = t_max.x;
                t_max.x += t_delta.x;
                cell.x += step_v.x;
                nrm = float3(-step_v.x, 0, 0);
            } else {
                dist = t_max.z;
                t_max.z += t_delta.z;
                cell.z += step_v.z;
                nrm = float3(0, 0, -step_v.z);
            }
        } else {
            if (t_max.y < t_max.z) {
                dist = t_max.y;
                t_max.y += t_delta.y;
                cell.y += step_v.y;
                nrm = float3(0, -step_v.y, 0);
            } else {
                dist = t_max.z;
                t_max.z += t_delta.z;
                cell.z += step_v.z;
                nrm = float3(0, 0, -step_v.z);
            }
        }
    }
    return false;
}

kernel void cs_metality_pathtrace_gi_voxel_dda(
    uint2 gid [[thread_position_in_grid]],
    constant RtDispatchUniforms& u [[buffer(0)]],
    device const uchar* voxels [[buffer(1)]],
    texture2d<float, access::write> out_radiance [[texture(0)]],
    texture2d<float, access::write> out_depth [[texture(1)]],
    texture2d<float, access::write> out_motion [[texture(2)]],
    texture2d<float, access::write> out_diffuse_albedo [[texture(3)]],
    texture2d<float, access::write> out_specular_albedo [[texture(4)]],
    texture2d<float, access::write> out_normal [[texture(5)]],
    texture2d<float, access::write> out_roughness [[texture(6)]]
) {
    if (gid.x >= u.width || gid.y >= u.height) {
        return;
    }
    float2 uv = (float2(gid) + 0.5f) / float2(u.width, u.height);
    float2 ndc = float2(uv.x * 2.0f - 1.0f, 1.0f - uv.y * 2.0f);
    float4 clip_far = u.inv_view_proj * float4(ndc, 1.0f, 1.0f);
    float3 world_far = clip_far.xyz / max(clip_far.w, 1e-5f);
    float3 ray_dir = normalize(world_far - u.cam_pos.xyz);

    float hit_dist = 0.0f;
    float3 nrm = float3(0, 1, 0);
    if (!trace_voxel_dda(voxels, u.voxel_origin.xyz, u.cam_pos.xyz, ray_dir, 256.0f, hit_dist, nrm)) {
        float sky_t = clamp(ray_dir.y * 0.5f + 0.5f, 0.0f, 1.0f);
        float3 sky = mix(float3(0.30f, 0.45f, 0.70f), float3(0.65f, 0.85f, 1.25f), sky_t);
        out_radiance.write(float4(sky, 1.0f), gid);
        out_depth.write(float4(1.0f, 0.0f, 0.0f, 1.0f), gid);
        out_motion.write(float4(0.0f), gid);
        out_diffuse_albedo.write(float4(0.0f, 0.0f, 0.0f, 1.0f), gid);
        out_specular_albedo.write(float4(0.0f), gid);
        out_normal.write(float4(0.0f, 1.0f, 0.0f, 1.0f), gid);
        out_roughness.write(float4(1.0f, 0.0f, 0.0f, 1.0f), gid);
        return;
    }

    float3 hit_world = u.cam_pos.xyz + ray_dir * hit_dist + nrm * 0.02f;
    float3 sun_dir = normalize(u.sun_dir.xyz);
    float dummy_d = 0.0f;
    float3 dummy_n = float3(0);
    bool sun_visible = (dot(nrm, sun_dir) > 0.0f) &&
                       !trace_voxel_dda(voxels, u.voxel_origin.xyz, hit_world, sun_dir, 128.0f, dummy_d, dummy_n);
    bool gi_sky_visible = !trace_voxel_dda(voxels, u.voxel_origin.xyz, hit_world, nrm, 32.0f, dummy_d, dummy_n);

    float3 albedo = float3(0.65f, 0.65f, 0.65f);
    float3 hdr = eval_gi_lighting(nrm, sun_dir, albedo, sun_visible, gi_sky_visible, 0.0f);

    out_radiance.write(float4(hdr, 1.0f), gid);
    out_depth.write(float4(clamp(hit_dist / 256.0f, 0.0f, 1.0f), 0.0f, 0.0f, 1.0f), gid);
    out_motion.write(float4(0.0f), gid);
    out_diffuse_albedo.write(float4(albedo, 1.0f), gid);
    out_specular_albedo.write(float4(0.04f, 0.04f, 0.04f, 1.0f), gid);
    out_normal.write(float4(nrm * 0.5f + 0.5f, 1.0f), gid);
    out_roughness.write(float4(0.85f, 0.0f, 0.0f, 1.0f), gid);
}
)";

id<MTLTexture> lookup_tex(MetalityContext* ctx, MetalityTextureHandle handle) {
    if (!ctx || handle == 0) return nil;
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    auto it = ctx->textures.find(handle);
    return it != ctx->textures.end() ? it->second.texture : nil;
}

const char* kDuskShadowRasterMsl = R"(
#include <metal_stdlib>
using namespace metal;

struct DuskCascadeMat {
    float4 rows[4];
};

struct DuskEntityShadowUniforms {
    float4 rows[4];
    float4 params; // x = entity count
};

constant float3 kUnitCubeTriVerts[36] = {
    // -X
    float3(-1,-1,-1), float3(-1,-1, 1), float3(-1, 1, 1),
    float3(-1,-1,-1), float3(-1, 1, 1), float3(-1, 1,-1),
    // +X
    float3( 1,-1, 1), float3( 1,-1,-1), float3( 1, 1,-1),
    float3( 1,-1, 1), float3( 1, 1,-1), float3( 1, 1, 1),
    // -Y
    float3(-1,-1,-1), float3( 1,-1,-1), float3( 1,-1, 1),
    float3(-1,-1,-1), float3( 1,-1, 1), float3(-1,-1, 1),
    // +Y
    float3(-1, 1, 1), float3( 1, 1, 1), float3( 1, 1,-1),
    float3(-1, 1, 1), float3( 1, 1,-1), float3(-1, 1,-1),
    // -Z
    float3( 1,-1,-1), float3(-1,-1,-1), float3(-1, 1,-1),
    float3( 1,-1,-1), float3(-1, 1,-1), float3( 1, 1,-1),
    // +Z
    float3(-1,-1, 1), float3( 1,-1, 1), float3( 1, 1, 1),
    float3(-1,-1, 1), float3( 1, 1, 1), float3(-1, 1, 1)
};

vertex float4 vs_dusk_shadow_clear(uint vid [[vertex_id]]) {
    float2 pos = float2((vid == 1u) ? 3.0f : -1.0f, (vid == 2u) ? 3.0f : -1.0f);
    return float4(pos, 1.0f, 1.0f);
}

vertex float4 vs_dusk_shadow_chunk(
    uint vid [[vertex_id]],
    device const packed_float3* verts [[buffer(0)]],
    constant DuskCascadeMat& m [[buffer(1)]],
    constant float4& sec_rel_origin [[buffer(2)]]
) {
    float4 p = float4(float3(verts[vid]) + sec_rel_origin.xyz, 1.0f);
    return float4(
        dot(m.rows[0], p),
        dot(m.rows[1], p),
        dot(m.rows[2], p),
        dot(m.rows[3], p)
    );
}

vertex float4 vs_dusk_shadow_entity(
    uint vid [[vertex_id]],
    uint iid [[instance_id]],
    device const float4* casters [[buffer(0)]],
    constant DuskEntityShadowUniforms& u [[buffer(1)]]
) {
    uint count = min(uint(casters[0].x + 0.5f), 256u);
    if (iid >= count) {
        return float4(0.0f, 0.0f, -2.0f, 1.0f);
    }
    float4 c0 = casters[1u + 2u * iid];
    float4 c1 = casters[2u + 2u * iid];
    float3 ext = float3(c0.w, c1.x, c1.y);
    float3 local_v = kUnitCubeTriVerts[vid] * ext;

    float sinY = sin(c1.z), cosY = cos(c1.z);
    float sinP = sin(c1.w), cosP = cos(c1.w);
    float y1 = local_v.y * cosP - local_v.z * sinP;
    float z1 = local_v.y * sinP + local_v.z * cosP;
    float3 world_off = float3(
        local_v.x * cosY - z1 * sinY,
        y1,
        local_v.x * sinY + z1 * cosY
    );
    float4 p = float4(c0.xyz + world_off, 1.0f);
    return float4(
        dot(u.rows[0], p),
        dot(u.rows[1], p),
        dot(u.rows[2], p),
        dot(u.rows[3], p)
    );
}
)";

struct alignas(16) DuskShadowUniforms {
    RtVec4 c0_rows[4];
    RtVec4 c1_rows[4];
    RtVec4 cascade_params;  // sMats[8]: x=c0_half_m, y=c1_half_m, z=inv_res, w=cascade_count
    RtVec4 c2_rows[4];      // sMats[9..12]
    RtVec4 cascade_params2; // sMats[13]: x=c2_half_m, y=c0_z_half_m, z=c1_z_half_m, w=c2_z_half_m
};
static_assert(sizeof(DuskShadowUniforms) == 224, "DuskShadowUniforms must stay 14 float4s (224 bytes)");

struct alignas(16) DuskCascadeMatPush {
    RtVec4 rows[4];
};

struct alignas(16) DuskEntityShadowPush {
    RtVec4 rows[4];
    RtVec4 params;
};

struct CachedEmissiveWorldCluster {
    float wx, wy, wz;
    float power;
    float r, g, b;
    float radius;
};

struct PendingCompactItem {
    SectionKey key;
    id<MTLAccelerationStructure> src_blas = nil;
};

struct PendingCompactBatch {
    uint64_t ready_frame_id = 0;
    id<MTLBuffer> sizes_buf = nil;
    std::vector<PendingCompactItem> items;
};

} // namespace

struct MetalityRayTracingSceneImpl {
    struct CachedTlasEntry {
        float wx = 0.0f;
        float wy = 0.0f;
        float wz = 0.0f;
        id<MTLAccelerationStructure> blas = nil;
    };

    id<MTLDevice> device = nil;
    id<MTLCommandQueue> queue = nil;
    bool hardware_bvh = false;
    uint32_t max_sections = 4096;

    std::unordered_map<SectionKey, SectionRtEntry, SectionKeyHash> sections;
    mutable std::mutex sections_mutex;
    std::mutex blas_build_mutex;

    // Hardware BVH state (topology-cached + ring-refitted TLAS)
    id<MTLAccelerationStructure> tlas = nil;
    id<MTLAccelerationStructure> tlas_ring[kRtRingSlots] = {nil, nil, nil, nil};
    id<MTLBuffer> instance_desc_ring[kRtRingSlots] = {nil, nil, nil, nil};
    uint64_t tlas_slot_last_frame[kRtRingSlots] = {0, 0, 0, 0};
    uint64_t tlas_ring_topo_gen[kRtRingSlots] = {0, 0, 0, 0};
    uint32_t active_tlas_slot = kRtRingSlots;
    NSUInteger tlas_ring_size = 0;
    NSUInteger instance_desc_ring_cap = 0;
    NSUInteger tlas_scratch_cap = 0;
    id<MTLBuffer> instance_desc_buf = nil;
    id<MTLBuffer> tlas_scratch_buf = nil;
    id<MTLBuffer> blas_scratch_buf = nil;
    NSUInteger blas_scratch_cap = 0;
    id<MTLBuffer> meta_flat_buf = nil;
    id<MTLBuffer> instance_meta_offset_buf = nil;
    std::vector<id<MTLAccelerationStructure>> active_blas_list;
    std::vector<__unsafe_unretained id<MTLResource>> active_blas_resources;
    std::vector<CachedTlasEntry> cached_tlas_entries;
    NSMutableArray<id<MTLAccelerationStructure>>* cached_blas_array = nil;
    std::deque<PendingCompactBatch> pending_compact_batches;
#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 150000
    id<MTLResidencySet> blas_residency_set API_AVAILABLE(macos(15.0)) = nil;
#endif
    bool residency_set_attached = false;
    uint64_t cached_tlas_sections_gen = 0;
    uint64_t last_tlas_topo_frame = 0;
    float last_tlas_build_cam[3] = {0.0f, 0.0f, 0.0f};
    float last_tlas_sync_cam[3] = {0.0f, 0.0f, 0.0f};
    bool has_last_tlas_cam = false;
    std::atomic<uint32_t> active_quad_section_count{0u};

    std::vector<DynamicLightEntry> dynamic_lights;
    std::vector<WaterRippleEntry> water_ripples;
    std::vector<EntityShadowEntry> entity_shadows;
    uint32_t active_instance_count = 0;

    // Semantic Voxel Grid (512x128x512), Direct Emissive NEE Light Buffer, Water Ripples & Entity Shadow Casters
    id<MTLBuffer> voxel_grid_ring[kRtRingSlots] = {nil, nil, nil, nil};
    uint64_t voxel_slot_last_frame[kRtRingSlots] = {0, 0, 0, 0};
    uint32_t active_voxel_slot = 0;

    id<MTLBuffer> emissive_light_ring[kRtRingSlots] = {nil, nil, nil, nil};
    id<MTLBuffer> water_ripple_ring[kRtRingSlots] = {nil, nil, nil, nil};
    id<MTLBuffer> entity_shadow_ring[kRtRingSlots] = {nil, nil, nil, nil};
    uint64_t dynamic_slot_last_frame[kRtRingSlots] = {0, 0, 0, 0};
    uint32_t active_dynamic_slot = 0;

    id<MTLBuffer> voxel_grid_buf = nil;
    id<MTLBuffer> emissive_light_buf = nil;
    id<MTLBuffer> water_ripple_buf = nil;
    id<MTLBuffer> entity_shadow_buf = nil;
    id<MTLBuffer> fallback_zero_buf = nil;
    int32_t voxel_origin[3] = {0, 0, 0};
    int32_t voxel_cam_ix = 0x3FFFFFFF;
    int32_t voxel_cam_iy = 0x3FFFFFFF;
    int32_t voxel_cam_iz = 0x3FFFFFFF;
    bool voxel_origin_init = false;
    bool near_section_dirty = true;
    uint32_t voxel_flags = 0u;
    uint64_t last_voxel_rebuild_frame = 0;
    uint64_t sections_generation = 1;
    uint64_t last_voxel_gen = 0;
    uint32_t active_emissive_light_count = 0;
    std::vector<uint16_t> leaf_top_scratch;
    std::vector<uint32_t> top_water_cells_scratch;
    std::vector<uint32_t> bottom_water_cells_scratch;
    std::vector<CachedEmissiveWorldCluster> cached_emissive_clusters;
    uint64_t emissive_cache_gen = 0;
    int32_t cached_max_near_wy = INT32_MIN;
    int32_t max_near_wy_cam_ix = 0x3FFFFFFF;
    int32_t max_near_wy_cam_iz = 0x3FFFFFFF;
    uint64_t max_near_wy_gen = 0;

    // Non-RT Dusk GPU-Rasterized 3-Cascade Orthographic Shadow Map state (Depth16Unorm + in-place staggered C1/C2 clear)
    id<MTLRenderPipelineState> dusk_shadow_chunk_pso = nil;
    id<MTLRenderPipelineState> dusk_shadow_entity_pso = nil;
    id<MTLRenderPipelineState> dusk_shadow_clear_pso = nil;
    id<MTLDepthStencilState> dusk_shadow_dss = nil;
    id<MTLDepthStencilState> dusk_shadow_clear_dss = nil;
    id<MTLTexture> dusk_shadow_atlas_tex = nil;
    id<MTLTexture> dusk_shadow_fallback_tex = nil;
    uint32_t dusk_shadow_res = 0;
    bool c1_cache_valid = false;
    uint64_t c1_cache_frame = 0;
    uint64_t c1_cache_sections_gen = 0;
    float c1_cache_cam[3] = {0.0f, 0.0f, 0.0f};
    float c1_cache_light_dir[3] = {0.0f, 0.0f, 0.0f};
    float c1_cache_r[3] = {1.0f, 0.0f, 0.0f};
    float c1_cache_u[3] = {0.0f, 1.0f, 0.0f};
    float c1_cache_snap_u = 0.0f;
    float c1_cache_snap_v = 0.0f;
    bool c2_cache_valid = false;
    uint64_t c2_cache_frame = 0;
    uint64_t c2_cache_sections_gen = 0;
    float c2_cache_cam[3] = {0.0f, 0.0f, 0.0f};
    float c2_cache_light_dir[3] = {0.0f, 0.0f, 0.0f};
    float c2_cache_r[3] = {1.0f, 0.0f, 0.0f};
    float c2_cache_u[3] = {0.0f, 1.0f, 0.0f};
    float c2_cache_snap_u = 0.0f;
    float c2_cache_snap_v = 0.0f;
    id<MTLBuffer> shadow_uniform_ring[kRtRingSlots] = {nil, nil, nil, nil};
    id<MTLBuffer> shadow_uniform_buf = nil;

    // Compute PSOs
    id<MTLComputePipelineState> hw_gi_pso = nil;
    id<MTLComputePipelineState> dda_gi_pso = nil;
};

extern "C" {

MetalityRtSceneHandle metality_rt_scene_create(
    MetalityContext* ctx,
    uint32_t max_sections,
    bool force_voxel_fallback
) {
    if (!ctx || !ctx->device) {
        return nullptr;
    }
    @autoreleasepool {
        auto* scene = new MetalityRayTracingSceneImpl();
        scene->device = ctx->device;
        scene->queue = ctx->command_queue;
        scene->max_sections = std::max<uint32_t>(16u, max_sections);
        scene->hardware_bvh = (ctx->device.supportsRaytracing == YES) && !force_voxel_fallback;

        MTLCompileOptions* opts = [[MTLCompileOptions alloc] init];
        opts.languageVersion = MTLLanguageVersion3_1;
        opts.mathMode = MTLMathModeFast;
        NSError* err = nil;
        id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:kRayTracingGiMsl]
                                                       options:opts
                                                         error:&err];
        if (!lib) {
            delete scene;
            return nullptr;
        }

        scene->voxel_grid_ring[0] = [ctx->device newBufferWithLength:kVoxelGridBufferBytes
                                                             options:MTLResourceStorageModeShared];
        if (!scene->voxel_grid_ring[0]) {
            delete scene;
            return nullptr;
        }
        std::memset(scene->voxel_grid_ring[0].contents, 0, kVoxelGridBufferBytes);
        scene->voxel_grid_buf = scene->voxel_grid_ring[0];

        const NSUInteger emissive_bytes = (2u + 2u * kMaxEmissiveLights) * sizeof(RtVec4);
        const NSUInteger ripple_bytes = (2u + 2u * kMaxWaterRipples) * sizeof(RtVec4);
        const NSUInteger entity_shadow_bytes = (2u + 2u * kMaxEntityShadowCasters) * sizeof(RtVec4);
        for (uint32_t rs = 0; rs < kRtRingSlots; ++rs) {
            scene->emissive_light_ring[rs] = [ctx->device newBufferWithLength:emissive_bytes
                                                                     options:MTLResourceStorageModeShared];
            scene->water_ripple_ring[rs] = [ctx->device newBufferWithLength:ripple_bytes
                                                                   options:MTLResourceStorageModeShared];
            scene->entity_shadow_ring[rs] = [ctx->device newBufferWithLength:entity_shadow_bytes
                                                                     options:MTLResourceStorageModeShared];
            if (!scene->emissive_light_ring[rs] || !scene->water_ripple_ring[rs] || !scene->entity_shadow_ring[rs]) {
                delete scene;
                return nullptr;
            }
            std::memset(scene->emissive_light_ring[rs].contents, 0, emissive_bytes);
            std::memset(scene->water_ripple_ring[rs].contents, 0, ripple_bytes);
            std::memset(scene->entity_shadow_ring[rs].contents, 0, entity_shadow_bytes);
        }
        scene->emissive_light_buf = scene->emissive_light_ring[0];
        scene->water_ripple_buf = scene->water_ripple_ring[0];
        scene->entity_shadow_buf = scene->entity_shadow_ring[0];

        const NSUInteger shadow_u_bytes = std::max<NSUInteger>(256u, sizeof(DuskShadowUniforms));
        for (uint32_t rs = 0; rs < kRtRingSlots; ++rs) {
            scene->shadow_uniform_ring[rs] = [ctx->device newBufferWithLength:shadow_u_bytes
                                                                      options:MTLResourceStorageModeShared];
            if (!scene->shadow_uniform_ring[rs]) {
                delete scene;
                return nullptr;
            }
            std::memset(scene->shadow_uniform_ring[rs].contents, 0, shadow_u_bytes);
        }
        scene->shadow_uniform_buf = scene->shadow_uniform_ring[0];

        // Compile depth-only GPU-rasterized shadow map pipelines for Non-RT packs (Meteor Dusk, Depth16Unorm for 50% bandwidth)
        {
            NSError* sh_err = nil;
            id<MTLLibrary> sh_lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:kDuskShadowRasterMsl]
                                                              options:opts
                                                                error:&sh_err];
            if (sh_lib) {
                id<MTLFunction> v_chunk = [sh_lib newFunctionWithName:@"vs_dusk_shadow_chunk"];
                id<MTLFunction> v_ent = [sh_lib newFunctionWithName:@"vs_dusk_shadow_entity"];
                id<MTLFunction> v_clr = [sh_lib newFunctionWithName:@"vs_dusk_shadow_clear"];
                MTLRenderPipelineDescriptor* spd = [[MTLRenderPipelineDescriptor alloc] init];
                spd.fragmentFunction = nil;
                spd.depthAttachmentPixelFormat = MTLPixelFormatDepth16Unorm;
                if (v_chunk) {
                    spd.vertexFunction = v_chunk;
                    scene->dusk_shadow_chunk_pso = [ctx->device newRenderPipelineStateWithDescriptor:spd error:&sh_err];
                }
                if (v_ent) {
                    spd.vertexFunction = v_ent;
                    scene->dusk_shadow_entity_pso = [ctx->device newRenderPipelineStateWithDescriptor:spd error:&sh_err];
                }
                if (v_clr) {
                    spd.vertexFunction = v_clr;
                    scene->dusk_shadow_clear_pso = [ctx->device newRenderPipelineStateWithDescriptor:spd error:&sh_err];
                }
            }
            MTLDepthStencilDescriptor* dsd = [[MTLDepthStencilDescriptor alloc] init];
            dsd.depthCompareFunction = MTLCompareFunctionLess;
            dsd.depthWriteEnabled = YES;
            scene->dusk_shadow_dss = [ctx->device newDepthStencilStateWithDescriptor:dsd];

            MTLDepthStencilDescriptor* cdsd = [[MTLDepthStencilDescriptor alloc] init];
            cdsd.depthCompareFunction = MTLCompareFunctionAlways;
            cdsd.depthWriteEnabled = YES;
            scene->dusk_shadow_clear_dss = [ctx->device newDepthStencilStateWithDescriptor:cdsd];

            MTLTextureDescriptor* ftd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth16Unorm
                                                                                           width:1
                                                                                          height:1
                                                                                       mipmapped:NO];
            ftd.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
            ftd.storageMode = MTLStorageModePrivate;
            scene->dusk_shadow_fallback_tex = [ctx->device newTextureWithDescriptor:ftd];
        }

        if (scene->hardware_bvh) {
            id<MTLFunction> fn = [lib newFunctionWithName:@"cs_metality_pathtrace_gi_hw"];
            scene->hw_gi_pso = [ctx->device newComputePipelineStateWithFunction:fn error:&err];
            if (!scene->hw_gi_pso) {
                delete scene;
                return nullptr;
            }
        } else {
            id<MTLFunction> fn = [lib newFunctionWithName:@"cs_metality_pathtrace_gi_voxel_dda"];
            scene->dda_gi_pso = [ctx->device newComputePipelineStateWithFunction:fn error:&err];
            if (!scene->dda_gi_pso) {
                delete scene;
                return nullptr;
            }
        }

        return scene;
    }
}

void metality_rt_scene_destroy(MetalityRtSceneHandle scene) {
    if (!scene) return;
    @autoreleasepool {
#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 150000
        if (@available(macOS 15.0, *)) {
            if (scene->blas_residency_set) {
                if (scene->residency_set_attached && scene->queue) {
                    [scene->queue removeResidencySet:scene->blas_residency_set];
                }
                [scene->blas_residency_set endResidency];
                scene->blas_residency_set = nil;
                scene->residency_set_attached = false;
            }
        }
#endif
    }
    delete scene;
}

bool metality_rt_scene_uses_hardware_bvh(const MetalityRtSceneHandle scene) {
    return scene && scene->hardware_bvh;
}

uint32_t metality_rt_scene_active_blas_count(const MetalityRtSceneHandle scene) {
    if (!scene) return 0u;
    return scene->active_quad_section_count.load(std::memory_order_relaxed);
}

bool metality_rt_scene_update_section_v2(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    int32_t sec_x,
    int32_t sec_y,
    int32_t sec_z,
    const MetalityPackedQuad* solid_quads,
    uint32_t solid_count,
    const MetalityPackedQuad* cutout_quads,
    uint32_t cutout_count,
    const MetalityPackedQuad* translucent_quads,
    uint32_t translucent_count
) {
    if (!ctx || !scene) {
        return false;
    }
    const SectionKey key{sec_x, sec_y, sec_z};
    const uint32_t total_all = solid_count + cutout_count + translucent_count;
    if (total_all == 0) {
        std::lock_guard<std::mutex> lock(scene->sections_mutex);
        scene->sections.erase(key);
        scene->sections_generation += 1;
        scene->near_section_dirty = true;
        return true;
    }

    @autoreleasepool {
        SectionRtEntry entry;
        entry.sec_x = sec_x;
        entry.sec_y = sec_y;
        entry.sec_z = sec_z;

        uint32_t bvh_quad_count = 0;
        auto count_bvh_quads = [&](const MetalityPackedQuad* qptr, uint32_t count, bool is_cutout) {
            if (!qptr) return;
            for (uint32_t i = 0; i < count; ++i) {
                const uint64_t w0 = qptr[i].word0;
                const uint32_t raw_tag = static_cast<uint32_t>((w0 >> 35) & 0xFull);
                const uint32_t emissive = static_cast<uint32_t>((w0 >> 39) & 0xFull);
                const uint32_t effective_tag = (raw_tag != 0u) ? raw_tag : ((emissive > 0u) ? 5u : 1u);
                if (is_bvh_opaque_tag(effective_tag) && !(is_cutout && effective_tag == 12u)) {
                    ++bvh_quad_count;
                }
            }
        };
        count_bvh_quads(solid_quads, solid_count, false);
        count_bvh_quads(cutout_quads, cutout_count, true);

        entry.quad_count = bvh_quad_count;
        entry.triangle_count = bvh_quad_count * 2u;

        std::vector<MTLPackedFloat3> verts(bvh_quad_count * 6u);
        entry.packed_meta_cpu.resize(bvh_quad_count);
        entry.voxel_blocks.reserve(std::min<uint32_t>(total_all * 2u, 4096u));

        int16_t voxel_slot[4096];
        std::fill(std::begin(voxel_slot), std::end(voxel_slot), static_cast<int16_t>(-1));
        uint32_t q_out = 0;

        // 預先依光譜分類聚類本區段內的發光源（按 2x2x2 子格拆分 greedy 面片、依唯一方塊數計算功率並於確認鄰格開放後才推前 +0.42m）
        constexpr uint32_t kNumLightCats = 7u;
        struct EmissiveBucket {
            float sx = 0.0f, sy = 0.0f, sz = 0.0f;
            float open_sx = 0.0f, open_sy = 0.0f, open_sz = 0.0f;
            float sr = 0.0f, sg = 0.0f, sb = 0.0f;
            float first_x = 0.0f, first_y = 0.0f, first_z = 0.0f;
            uint32_t cnt = 0;
            uint32_t open_cnt = 0;
            uint8_t block_mask = 0;
            uint8_t max_emit = 0;
            uint8_t any_sprite = 0;
            uint8_t all_sprite = 1;
            uint8_t has_first = 0;
        };
        struct PendingSolidEmitQuad {
            float qx, qy, qz;
            uint32_t w, h, fdir;
            uint32_t effective_tag;
            uint32_t actual_emit;
            float tr, tg, tb;
        };
        EmissiveBucket lava_buckets[8]{};
        std::vector<EmissiveBucket> small_buckets;
        std::vector<EmissiveBucket> weak_sprite_buckets;
        std::vector<EmissiveBucket> light_block_buckets;
        std::vector<PendingSolidEmitQuad> pending_solid_emits;

        auto append_quads = [&](const MetalityPackedQuad* qptr, uint32_t count, bool is_translucent, bool is_cutout) {
            if (!qptr) return;
            for (uint32_t i = 0; i < count; ++i) {
                const uint64_t w0 = qptr[i].word0;
                const uint64_t w1 = qptr[i].word1;

                const float qx = static_cast<float>(w0 & 0x1Full);
                const float qy = static_cast<float>((w0 >> 5) & 0x1Full);
                const float qz = static_cast<float>((w0 >> 10) & 0x1Full);
                const uint32_t w = static_cast<uint32_t>(((w0 >> 15) & 0xFull) + 1u);
                const uint32_t h = static_cast<uint32_t>(((w0 >> 19) & 0xFull) + 1u);
                const uint32_t fdir = static_cast<uint32_t>((w0 >> 23) & 0x7ull) % 6u;
                const uint32_t raw_tag = static_cast<uint32_t>((w0 >> 35) & 0xFull);
                const uint32_t emissive = static_cast<uint32_t>((w0 >> 39) & 0xFull);
                const uint32_t tint = static_cast<uint32_t>((w1 >> 32) & 0xFFFFFFull);

                const uint32_t raw_effective_tag = (raw_tag != 0u)
                        ? raw_tag
                        : (is_translucent ? ((emissive > 0u) ? 11u : 8u) : ((emissive > 0u) ? 5u : 1u));
                // Non-opaque cutout blocks must never be written as full solid tag=12 into BVH/CSM, voxelGrid, or top_hmap
                const bool demote_cutout_pbr = (is_cutout && raw_effective_tag == 12u);
                const uint32_t effective_tag = demote_cutout_pbr ? 14u : raw_effective_tag;
                const uint32_t effective_sub = demote_cutout_pbr ? 0u : emissive;
                // tag 3(Water), 8(Glass/Ice subtype), 12(PBR Metal/Gem subtype), 14/15(Sub-block geometry) 將 4-bit subtype 借存於 emissive 欄位中，不作為自發光源
                const uint32_t actual_emit = (effective_tag == 3u || effective_tag == 8u || effective_tag == 12u || effective_tag >= 14u) ? 0u : effective_sub;

                const float tr = static_cast<float>(tint & 0xFFu) / 255.0f;
                const float tg = static_cast<float>((tint >> 8) & 0xFFu) / 255.0f;
                const float tb = static_cast<float>((tint >> 16) & 0xFFu) / 255.0f;

                const float ux = static_cast<float>(kFaceUBasis[fdir][0]);
                const float uy = static_cast<float>(kFaceUBasis[fdir][1]);
                const float uz = static_cast<float>(kFaceUBasis[fdir][2]);

                const float vx = static_cast<float>(kFaceVBasis[fdir][0]);
                const float vy = static_cast<float>(kFaceVBasis[fdir][1]);
                const float vz = static_cast<float>(kFaceVBasis[fdir][2]);

                const float fw = static_cast<float>(w);
                const float fh = static_cast<float>(h);

                if (!is_translucent && is_bvh_opaque_tag(effective_tag) && !(is_cutout && effective_tag == 12u) && q_out < bvh_quad_count) {
                    MTLPackedFloat3 p00 = MTLPackedFloat3Make(qx, qy, qz);
                    MTLPackedFloat3 p10 = MTLPackedFloat3Make(qx + fw * ux, qy + fw * uy, qz + fw * uz);
                    MTLPackedFloat3 p11 = MTLPackedFloat3Make(qx + fw * ux + fh * vx, qy + fw * uy + fh * vy, qz + fw * uz + fh * vz);
                    MTLPackedFloat3 p01 = MTLPackedFloat3Make(qx + fh * vx, qy + fh * vy, qz + fh * vz);

                    const uint32_t vbase = q_out * 6u;
                    // Consistent outward CCW winding across all 6 faces (enables MTLCullModeBack in Dusk CSM; invariant to BVH)
                    const bool flip_wind = (fdir == 1u || fdir == 3u || fdir == 4u);
                    if (flip_wind) {
                        verts[vbase + 0] = p00;
                        verts[vbase + 1] = p11;
                        verts[vbase + 2] = p10;
                        verts[vbase + 3] = p00;
                        verts[vbase + 4] = p01;
                        verts[vbase + 5] = p11;
                    } else {
                        verts[vbase + 0] = p00;
                        verts[vbase + 1] = p10;
                        verts[vbase + 2] = p11;
                        verts[vbase + 3] = p00;
                        verts[vbase + 4] = p11;
                        verts[vbase + 5] = p01;
                    }

                    entry.packed_meta_cpu[q_out] = (fdir & 7u) | ((actual_emit & 0xFu) << 3) | ((effective_tag == 2u) ? 0x80u : 0u) | (tint << 8);
                    ++q_out;
                }

                // 記錄暴露表面實體光源面片，待 voxel_blocks 完整建立後按方塊逐一驗證鄰格開放性並分桶
                if (actual_emit > 0u && effective_tag < 9u) {
                    pending_solid_emits.push_back({
                        qx, qy, qz, w, h, fdir, effective_tag, actual_emit, tr, tg, tb
                    });
                }

                // 解碼並去重寫入 16x16x16 區段體素語意標籤與子型別/發光資訊
                const float inset_x = -0.5f * static_cast<float>(kFaceNormals[fdir][0]);
                const float inset_y = -0.5f * static_cast<float>(kFaceNormals[fdir][1]);
                const float inset_z = -0.5f * static_cast<float>(kFaceNormals[fdir][2]);
                for (uint32_t dv = 0; dv < h; ++dv) {
                    for (uint32_t du = 0; du < w; ++du) {
                        const float cx = qx + (static_cast<float>(du) + 0.5f) * ux + (static_cast<float>(dv) + 0.5f) * vx + inset_x;
                        const float cy = qy + (static_cast<float>(du) + 0.5f) * uy + (static_cast<float>(dv) + 0.5f) * vy + inset_y;
                        const float cz = qz + (static_cast<float>(du) + 0.5f) * uz + (static_cast<float>(dv) + 0.5f) * vz + inset_z;
                        const int bx = std::clamp(static_cast<int>(std::floor(cx)), 0, 15);
                        const int by = std::clamp(static_cast<int>(std::floor(cy)), 0, 15);
                        const int bz = std::clamp(static_cast<int>(std::floor(cz)), 0, 15);
                        const int vidx = (by * 16 + bz) * 16 + bx;
                        const int16_t slot = voxel_slot[vidx];
                        const uint8_t fbit = (fdir == 3u) ? 0x1u : ((fdir == 2u) ? 0x2u : 0x4u);
                        const uint8_t quad_sub = (effective_tag == 3u)
                            ? static_cast<uint8_t>((effective_sub != 0u) ? (effective_sub & 0xFu) : 8u)
                            : static_cast<uint8_t>(effective_sub & 0xFu);
                        if (slot < 0) {
                            voxel_slot[vidx] = static_cast<int16_t>(entry.voxel_blocks.size());
                            entry.voxel_blocks.push_back({
                                static_cast<int16_t>(bx),
                                static_cast<int16_t>(by),
                                static_cast<int16_t>(bz),
                                tint,
                                quad_sub,
                                static_cast<uint8_t>(effective_tag),
                                (effective_tag == 3u) ? fbit : static_cast<uint8_t>(0u)
                            });
                        } else {
                            auto& existing = entry.voxel_blocks[static_cast<size_t>(slot)];
                            if (existing.semantic_tag == 3u && effective_tag == 3u) {
                                existing.emissive = std::max<uint8_t>(existing.emissive, quad_sub);
                                existing.face_mask |= fbit;
                            } else {
                                const bool ex_is_full_or_glass =
                                    (existing.semantic_tag >= 1u && existing.semantic_tag <= 8u) ||
                                    (existing.semantic_tag == 12u) ||
                                    (existing.semantic_tag >= 14u);
                                const bool new_is_sprite_overlay =
                                    (effective_tag >= 9u && effective_tag <= 11u) || (effective_tag == 13u);
                                const bool ex_is_sprite_overlay =
                                    (existing.semantic_tag >= 9u && existing.semantic_tag <= 11u) || (existing.semantic_tag == 13u);
                                const bool new_is_full_or_glass =
                                    (effective_tag >= 1u && effective_tag <= 8u) ||
                                    (effective_tag == 12u) ||
                                    (effective_tag >= 14u);
                                if (!(ex_is_full_or_glass && new_is_sprite_overlay)) {
                                    const uint32_t ex_actual_emit =
                                        (existing.semantic_tag == 3u || existing.semantic_tag == 8u || existing.semantic_tag == 12u || existing.semantic_tag >= 14u)
                                        ? 0u : existing.emissive;
                                    if ((ex_is_sprite_overlay && new_is_full_or_glass) ||
                                        actual_emit > ex_actual_emit ||
                                        (actual_emit == ex_actual_emit && !is_translucent && existing.semantic_tag <= 1u && effective_tag != 1u)) {
                                        existing.tint_rgb = tint;
                                        existing.emissive = quad_sub;
                                        existing.semantic_tag = static_cast<uint8_t>(effective_tag);
                                        existing.face_mask = (effective_tag == 3u) ? fbit : static_cast<uint8_t>(0u);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        };

        append_quads(solid_quads, solid_count, false, false);
        append_quads(cutout_quads, cutout_count, false, true);
        append_quads(translucent_quads, translucent_count, true, false);

        // Emit exact sub-block AABB geometry (tag 14 sub 1..13: 1 box = 6 quads; tag 15 stairs sub 0..15: 2 boxes = 12 quads)
        // directly into the section BVH & Dusk CSM vertex buffer; skip sub 0/14/15 (non-occluding / light / vegetation).
        {
            uint32_t extra_sub_quads = 0u;
            for (const auto& blk : entry.voxel_blocks) {
                if (blk.semantic_tag == 14u && blk.emissive >= 1u && blk.emissive <= 13u) {
                    extra_sub_quads += 6u;
                } else if (blk.semantic_tag == 15u) {
                    extra_sub_quads += 12u;
                }
            }
            verts.resize(static_cast<size_t>(q_out) * 6u);
            entry.packed_meta_cpu.resize(q_out);
            if (extra_sub_quads > 0u) {
                verts.reserve(static_cast<size_t>(q_out + extra_sub_quads) * 6u);
                entry.packed_meta_cpu.reserve(static_cast<size_t>(q_out + extra_sub_quads));
                auto append_sub_face = [&](const MTLPackedFloat3& p00,
                                           const MTLPackedFloat3& p10,
                                           const MTLPackedFloat3& p11,
                                           const MTLPackedFloat3& p01,
                                           uint32_t fdir,
                                           uint32_t tint_rgb) {
                    const bool flip_wind = (fdir == 1u || fdir == 3u || fdir == 4u);
                    if (flip_wind) {
                        verts.push_back(p00);
                        verts.push_back(p11);
                        verts.push_back(p10);
                        verts.push_back(p00);
                        verts.push_back(p01);
                        verts.push_back(p11);
                    } else {
                        verts.push_back(p00);
                        verts.push_back(p10);
                        verts.push_back(p11);
                        verts.push_back(p00);
                        verts.push_back(p11);
                        verts.push_back(p01);
                    }
                    entry.packed_meta_cpu.push_back((fdir & 7u) | ((tint_rgb & 0xFFFFFFu) << 8));
                };
                auto append_sub_box = [&](float x0, float y0, float z0, float x1, float y1, float z1,
                                          uint32_t tint_rgb, uint32_t face_mask = 0x3Fu) {
                    // 0: -X
                    if (face_mask & (1u << 0u)) {
                        append_sub_face(MTLPackedFloat3Make(x0, y0, z0), MTLPackedFloat3Make(x0, y0, z1),
                                        MTLPackedFloat3Make(x0, y1, z1), MTLPackedFloat3Make(x0, y1, z0), 0u, tint_rgb);
                    }
                    // 1: +X
                    if (face_mask & (1u << 1u)) {
                        append_sub_face(MTLPackedFloat3Make(x1, y0, z0), MTLPackedFloat3Make(x1, y0, z1),
                                        MTLPackedFloat3Make(x1, y1, z1), MTLPackedFloat3Make(x1, y1, z0), 1u, tint_rgb);
                    }
                    // 2: -Y
                    if (face_mask & (1u << 2u)) {
                        append_sub_face(MTLPackedFloat3Make(x0, y0, z0), MTLPackedFloat3Make(x1, y0, z0),
                                        MTLPackedFloat3Make(x1, y0, z1), MTLPackedFloat3Make(x0, y0, z1), 2u, tint_rgb);
                    }
                    // 3: +Y
                    if (face_mask & (1u << 3u)) {
                        append_sub_face(MTLPackedFloat3Make(x0, y1, z0), MTLPackedFloat3Make(x1, y1, z0),
                                        MTLPackedFloat3Make(x1, y1, z1), MTLPackedFloat3Make(x0, y1, z1), 3u, tint_rgb);
                    }
                    // 4: -Z
                    if (face_mask & (1u << 4u)) {
                        append_sub_face(MTLPackedFloat3Make(x0, y0, z0), MTLPackedFloat3Make(x1, y0, z0),
                                        MTLPackedFloat3Make(x1, y1, z0), MTLPackedFloat3Make(x0, y1, z0), 4u, tint_rgb);
                    }
                    // 5: +Z
                    if (face_mask & (1u << 5u)) {
                        append_sub_face(MTLPackedFloat3Make(x0, y0, z1), MTLPackedFloat3Make(x1, y0, z1),
                                        MTLPackedFloat3Make(x1, y1, z1), MTLPackedFloat3Make(x0, y1, z1), 5u, tint_rgb);
                    }
                };
                // Cull internal hidden faces of full-XZ sub-blocks (slabs, dirt_path, farmland, snow, etc.)
                // against intra-section neighbors in voxel_slot (cross-section border faces stay sealed).
                auto compute_full_xz_face_mask = [&](int lx, int ly, int lz, uint8_t self_sub,
                                                     bool flush_bottom, bool flush_top) -> uint32_t {
                    auto side_occluded = [&](int nx, int ny, int nz) -> bool {
                        if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) return false;
                        const int16_t nslot = voxel_slot[(ny * 16 + nz) * 16 + nx];
                        if (nslot < 0) return false;
                        const auto& nb = entry.voxel_blocks[static_cast<size_t>(nslot)];
                        const uint8_t ntag = nb.semantic_tag;
                        return (ntag == 14u && nb.emissive == self_sub) || ntag == 1u || ntag == 2u || ntag == 12u;
                    };
                    auto cap_occluded = [&](int nx, int ny, int nz) -> bool {
                        if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) return false;
                        const int16_t nslot = voxel_slot[(ny * 16 + nz) * 16 + nx];
                        if (nslot < 0) return false;
                        const uint8_t ntag = entry.voxel_blocks[static_cast<size_t>(nslot)].semantic_tag;
                        return ntag == 1u || ntag == 12u;
                    };
                    uint32_t mask = 0x3Fu;
                    if (side_occluded(lx - 1, ly, lz)) mask &= ~(1u << 0u);
                    if (side_occluded(lx + 1, ly, lz)) mask &= ~(1u << 1u);
                    if (flush_bottom && cap_occluded(lx, ly - 1, lz)) mask &= ~(1u << 2u);
                    if (flush_top && cap_occluded(lx, ly + 1, lz)) mask &= ~(1u << 3u);
                    if (side_occluded(lx, ly, lz - 1)) mask &= ~(1u << 4u);
                    if (side_occluded(lx, ly, lz + 1)) mask &= ~(1u << 5u);
                    return mask;
                };
                for (const auto& blk : entry.voxel_blocks) {
                    const float bx = static_cast<float>(blk.lx);
                    const float by = static_cast<float>(blk.ly);
                    const float bz = static_cast<float>(blk.lz);
                    if (blk.semantic_tag == 14u && blk.emissive >= 1u && blk.emissive <= 13u) {
                        switch (blk.emissive) {
                            case 1u:  append_sub_box(bx, by, bz, bx + 0.1875f, by + 1.0f, bz + 1.0f, blk.tint_rgb); break;
                            case 2u:  append_sub_box(bx + 0.8125f, by, bz, bx + 1.0f, by + 1.0f, bz + 1.0f, blk.tint_rgb); break;
                            case 3u:  append_sub_box(bx, by, bz, bx + 1.0f, by + 0.1875f, bz + 1.0f, blk.tint_rgb); break;
                            case 4u:  append_sub_box(bx, by + 0.8125f, bz, bx + 1.0f, by + 1.0f, bz + 1.0f, blk.tint_rgb); break;
                            case 5u:  append_sub_box(bx, by, bz, bx + 1.0f, by + 1.0f, bz + 0.1875f, blk.tint_rgb); break;
                            case 6u:  append_sub_box(bx, by, bz + 0.8125f, bx + 1.0f, by + 1.0f, bz + 1.0f, blk.tint_rgb); break;
                            case 7u:  append_sub_box(bx, by, bz, bx + 1.0f, by + 0.5f, bz + 1.0f, blk.tint_rgb,
                                                     compute_full_xz_face_mask(blk.lx, blk.ly, blk.lz, 7u, true, false)); break;
                            case 8u:  append_sub_box(bx, by + 0.5f, bz, bx + 1.0f, by + 1.0f, bz + 1.0f, blk.tint_rgb,
                                                     compute_full_xz_face_mask(blk.lx, blk.ly, blk.lz, 8u, false, true)); break;
                            case 9u:  append_sub_box(bx, by, bz, bx + 1.0f, by + 0.9375f, bz + 1.0f, blk.tint_rgb,
                                                     compute_full_xz_face_mask(blk.lx, blk.ly, blk.lz, 9u, true, false)); break;
                            case 10u: append_sub_box(bx, by, bz, bx + 1.0f, by + 0.75f, bz + 1.0f, blk.tint_rgb,
                                                     compute_full_xz_face_mask(blk.lx, blk.ly, blk.lz, 10u, true, false)); break;
                            case 11u: append_sub_box(bx, by, bz, bx + 1.0f, by + 0.375f, bz + 1.0f, blk.tint_rgb,
                                                     compute_full_xz_face_mask(blk.lx, blk.ly, blk.lz, 11u, true, false)); break;
                            case 12u: append_sub_box(bx + 0.0625f, by, bz + 0.0625f, bx + 0.9375f, by + 0.875f, bz + 0.9375f, blk.tint_rgb); break;
                            case 13u: append_sub_box(bx + 0.0625f, by, bz + 0.0625f, bx + 0.9375f, by + 1.0f, bz + 0.9375f, blk.tint_rgb); break;
                            default: break;
                        }
                    } else if (blk.semantic_tag == 15u) {
                        const bool top = ((blk.emissive >> 2u) & 1u) != 0u;
                        const bool outer = ((blk.emissive >> 3u) & 1u) != 0u;
                        const float base_y0 = top ? (by + 0.5f) : by;
                        const float base_y1 = top ? (by + 1.0f) : (by + 0.5f);
                        const float step_y0 = top ? by : (by + 0.5f);
                        const float step_y1 = top ? (by + 0.5f) : (by + 1.0f);
                        append_sub_box(bx, base_y0, bz, bx + 1.0f, base_y1, bz + 1.0f, blk.tint_rgb);
                        if (!outer) {
                            switch (blk.emissive & 3u) {
                                case 0u: append_sub_box(bx, step_y0, bz, bx + 0.5f, step_y1, bz + 1.0f, blk.tint_rgb); break;
                                case 1u: append_sub_box(bx + 0.5f, step_y0, bz, bx + 1.0f, step_y1, bz + 1.0f, blk.tint_rgb); break;
                                case 2u: append_sub_box(bx, step_y0, bz, bx + 1.0f, step_y1, bz + 0.5f, blk.tint_rgb); break;
                                case 3u: append_sub_box(bx, step_y0, bz + 0.5f, bx + 1.0f, step_y1, bz + 1.0f, blk.tint_rgb); break;
                                default: break;
                            }
                        } else {
                            // Outer corner stairs: 0=NW(-X,-Z), 1=NE(+X,-Z), 2=SW(-X,+Z), 3=SE(+X,+Z)
                            switch (blk.emissive & 3u) {
                                case 0u: append_sub_box(bx, step_y0, bz, bx + 0.5f, step_y1, bz + 0.5f, blk.tint_rgb); break;
                                case 1u: append_sub_box(bx + 0.5f, step_y0, bz, bx + 1.0f, step_y1, bz + 0.5f, blk.tint_rgb); break;
                                case 2u: append_sub_box(bx, step_y0, bz + 0.5f, bx + 0.5f, step_y1, bz + 1.0f, blk.tint_rgb); break;
                                case 3u: append_sub_box(bx + 0.5f, step_y0, bz + 0.5f, bx + 1.0f, step_y1, bz + 1.0f, blk.tint_rgb); break;
                                default: break;
                            }
                        }
                    }
                }
            }
            bvh_quad_count = static_cast<uint32_t>(entry.packed_meta_cpu.size());
            entry.quad_count = bvh_quad_count;
            entry.triangle_count = bvh_quad_count * 2u;
        }

        // 驗證鄰格是否開放可安全前推 +0.42m（絕不推入 sub=1..13 實心半磚/門板或 tag=15 階梯內部）
        auto is_cell_open_for_push = [&](int cx_i, int cy_i, int cz_i) -> bool {
            if (cx_i < 0 || cx_i > 15 || cy_i < 0 || cy_i > 15 || cz_i < 0 || cz_i > 15) {
                return true;
            }
            const int16_t nslot = voxel_slot[(cy_i * 16 + cz_i) * 16 + cx_i];
            if (nslot < 0) {
                return true;
            }
            const auto& nblk = entry.voxel_blocks[static_cast<size_t>(nslot)];
            const uint8_t ntag = nblk.semantic_tag;
            if (ntag == 0u || (ntag >= 9u && ntag <= 11u) || ntag == 13u) {
                return true;
            }
            if (ntag == 14u && (nblk.emissive == 0u || nblk.emissive == 14u || nblk.emissive == 15u)) {
                return true;
            }
            return false;
        };

        // 展開實體發光面片至逐方塊 2x2x2 子桶，以唯一方塊位元遮罩計數並驗證安全前推
        for (const auto& pq : pending_solid_emits) {
            const float ux = static_cast<float>(kFaceUBasis[pq.fdir][0]);
            const float uy = static_cast<float>(kFaceUBasis[pq.fdir][1]);
            const float uz = static_cast<float>(kFaceUBasis[pq.fdir][2]);
            const float vx = static_cast<float>(kFaceVBasis[pq.fdir][0]);
            const float vy = static_cast<float>(kFaceVBasis[pq.fdir][1]);
            const float vz = static_cast<float>(kFaceVBasis[pq.fdir][2]);
            const int inx = kFaceNormals[pq.fdir][0];
            const int iny = kFaceNormals[pq.fdir][1];
            const int inz = kFaceNormals[pq.fdir][2];
            const float nx = static_cast<float>(inx);
            const float ny = static_cast<float>(iny);
            const float nz = static_cast<float>(inz);
            const float inset_x = -0.5f * nx;
            const float inset_y = -0.5f * ny;
            const float inset_z = -0.5f * nz;
            const uint32_t cat = (pq.effective_tag == 4u) ? 0u : classify_light_category(pq.effective_tag, pq.tr, pq.tg, pq.tb);
            if (pq.effective_tag != 4u && small_buckets.empty()) {
                small_buckets.resize(512u * kNumLightCats);
            }
            for (uint32_t dv = 0; dv < pq.h; ++dv) {
                for (uint32_t du = 0; du < pq.w; ++du) {
                    const float fcx = pq.qx + (static_cast<float>(du) + 0.5f) * ux + (static_cast<float>(dv) + 0.5f) * vx;
                    const float fcy = pq.qy + (static_cast<float>(du) + 0.5f) * uy + (static_cast<float>(dv) + 0.5f) * vy;
                    const float fcz = pq.qz + (static_cast<float>(du) + 0.5f) * uz + (static_cast<float>(dv) + 0.5f) * vz;
                    const int bx = std::clamp(static_cast<int>(std::floor(fcx + inset_x)), 0, 15);
                    const int by = std::clamp(static_cast<int>(std::floor(fcy + inset_y)), 0, 15);
                    const int bz = std::clamp(static_cast<int>(std::floor(fcz + inset_z)), 0, 15);
                    const bool open_push = is_cell_open_for_push(bx + inx, by + iny, bz + inz);
                    const float px_pos = open_push ? (fcx + nx * 0.42f) : (static_cast<float>(bx) + 0.5f);
                    const float py_pos = open_push ? (fcy + ny * 0.42f) : (static_cast<float>(by) + 0.5f);
                    const float pz_pos = open_push ? (fcz + nz * 0.42f) : (static_cast<float>(bz) + 0.5f);

                    if (pq.effective_tag == 4u) {
                        const uint32_t lb = (static_cast<uint32_t>(by >> 3) * 2u + static_cast<uint32_t>(bz >> 3)) * 2u
                                          + static_cast<uint32_t>(bx >> 3);
                        auto& b = lava_buckets[lb & 7u];
                        b.sx += px_pos;
                        b.sy += py_pos;
                        b.sz += pz_pos;
                        b.sr += pq.tr;
                        b.sg += pq.tg;
                        b.sb += pq.tb;
                        b.cnt += 1u;
                        b.max_emit = std::max<uint8_t>(b.max_emit, static_cast<uint8_t>(pq.actual_emit));
                    } else {
                        const uint32_t sb = cat * 512u
                                          + (static_cast<uint32_t>(by >> 1) * 8u + static_cast<uint32_t>(bz >> 1)) * 8u
                                          + static_cast<uint32_t>(bx >> 1);
                        auto& b = small_buckets[sb];
                        const uint8_t bit = static_cast<uint8_t>(1u << ((bx & 1) | ((by & 1) << 1) | ((bz & 1) << 2)));
                        b.block_mask |= bit;
                        if (open_push) {
                            if (!b.has_first) {
                                b.first_x = px_pos;
                                b.first_y = py_pos;
                                b.first_z = pz_pos;
                                b.has_first = 1u;
                            }
                            b.open_sx += px_pos;
                            b.open_sy += py_pos;
                            b.open_sz += pz_pos;
                            b.open_cnt += 1u;
                        }
                        b.sx += px_pos;
                        b.sy += py_pos;
                        b.sz += pz_pos;
                        b.sr += pq.tr;
                        b.sg += pq.tg;
                        b.sb += pq.tb;
                        b.cnt += 1u;
                        b.max_emit = std::max<uint8_t>(b.max_emit, static_cast<uint8_t>(pq.actual_emit));
                        b.all_sprite = 0u;
                    }
                }
            }
        }

        // 補齊非實心裝飾性光源（火把、靈魂火把、紅石火把、燈籠、營火、終界燭、傳送門）與隱形 minecraft:light (tag=14, sub=14)
        // 弱光微粒精靈（如 emit <= 8 的發光地衣 glow_lichen / 紅石火把）以 4x4x4 (>>2) 粗聚類，將繁茂洞穴重複點光源減少 ~8x
        for (const auto& blk : entry.voxel_blocks) {
            if (blk.semantic_tag == 14u && blk.emissive == 14u) {
                if (light_block_buckets.empty()) {
                    light_block_buckets.resize(512u);
                }
                const uint32_t lb_idx = (static_cast<uint32_t>(blk.ly >> 1) * 8u + static_cast<uint32_t>(blk.lz >> 1)) * 8u
                                      + static_cast<uint32_t>(blk.lx >> 1);
                auto& lb = light_block_buckets[lb_idx];
                const uint8_t bit = static_cast<uint8_t>(1u << ((blk.lx & 1) | ((blk.ly & 1) << 1) | ((blk.lz & 1) << 2)));
                lb.block_mask |= bit;
                lb.sx += static_cast<float>(blk.lx) + 0.5f;
                lb.sy += static_cast<float>(blk.ly) + 0.5f;
                lb.sz += static_cast<float>(blk.lz) + 0.5f;
                lb.cnt += 1u;
                continue;
            }
            if (blk.semantic_tag == 12u || blk.semantic_tag == 8u || blk.semantic_tag == 3u || blk.semantic_tag >= 14u) continue;
            if (blk.emissive == 0u || blk.semantic_tag < 9u) continue;
            const float br = static_cast<float>(blk.tint_rgb & 0xFFu) / 255.0f;
            const float bg = static_cast<float>((blk.tint_rgb >> 8) & 0xFFu) / 255.0f;
            const float bb = static_cast<float>((blk.tint_rgb >> 16) & 0xFFu) / 255.0f;
            const uint32_t cat = classify_light_category(blk.semantic_tag, br, bg, bb);
            EmissiveBucket* b_ptr = nullptr;
            bool in_small = false;
            if (blk.emissive <= 8u) {
                if (weak_sprite_buckets.empty()) {
                    weak_sprite_buckets.resize(64u * kNumLightCats);
                }
                const uint32_t wb = cat * 64u
                                  + (static_cast<uint32_t>(blk.ly >> 2) * 4u + static_cast<uint32_t>(blk.lz >> 2)) * 4u
                                  + static_cast<uint32_t>(blk.lx >> 2);
                b_ptr = &weak_sprite_buckets[wb];
            } else {
                if (small_buckets.empty()) {
                    small_buckets.resize(512u * kNumLightCats);
                }
                const uint32_t sb = cat * 512u
                                  + (static_cast<uint32_t>(blk.ly >> 1) * 8u + static_cast<uint32_t>(blk.lz >> 1)) * 8u
                                  + static_cast<uint32_t>(blk.lx >> 1);
                b_ptr = &small_buckets[sb];
                in_small = true;
            }
            auto& b = *b_ptr;
            const float cx_blk = static_cast<float>(blk.lx) + 0.5f;
            const float cy_blk = static_cast<float>(blk.ly) + 0.55f;
            const float cz_blk = static_cast<float>(blk.lz) + 0.5f;
            if (in_small) {
                b.block_mask |= static_cast<uint8_t>(1u << ((blk.lx & 1) | ((blk.ly & 1) << 1) | ((blk.lz & 1) << 2)));
            }
            if (!b.has_first) {
                b.first_x = cx_blk;
                b.first_y = cy_blk;
                b.first_z = cz_blk;
                b.has_first = 1u;
            }
            b.open_sx += cx_blk;
            b.open_sy += cy_blk;
            b.open_sz += cz_blk;
            b.open_cnt += 1u;
            b.sx += cx_blk;
            b.sy += cy_blk;
            b.sz += cz_blk;
            b.sr += br;
            b.sg += bg;
            b.sb += bb;
            b.cnt += 1u;
            b.max_emit = std::max(b.max_emit, blk.emissive);
            b.any_sprite = 1u;
        }
        for (const auto& b : lava_buckets) {
            if (b.cnt == 0u) continue;
            const float inv_c = 1.0f / static_cast<float>(b.cnt);
            const float unit_pow = (b.max_emit >= 10u) ? 24.0f : 7.0f;
            const float pwr = std::min(130.0f, unit_pow * std::pow(static_cast<float>(b.cnt), 0.48f));
            const float rad = (b.max_emit >= 10u) ? 2.40f : 1.15f;
            entry.emissive_clusters.push_back({
                b.sx * inv_c, b.sy * inv_c, b.sz * inv_c, pwr, 1.00f, 0.30f, 0.030f, rad
            });
        }
        for (const auto& lb : light_block_buckets) {
            if (lb.cnt == 0u) continue;
            const float inv_c = 1.0f / static_cast<float>(lb.cnt);
            const uint32_t u_blocks = std::max<uint32_t>(1u, static_cast<uint32_t>(__builtin_popcount(lb.block_mask)));
            const float pwr = std::min(28.0f, 14.0f * std::pow(static_cast<float>(u_blocks), 0.35f));
            entry.emissive_clusters.push_back({
                lb.sx * inv_c, lb.sy * inv_c, lb.sz * inv_c, pwr, 1.00f, 0.96f, 0.88f, 1.35f
            });
        }
        if (!small_buckets.empty() || !weak_sprite_buckets.empty()) {
            static constexpr float kCatFallbackRgb[7][3] = {
                {1.00f, 0.52f, 0.14f}, // 0 WarmFlame
                {0.14f, 0.88f, 1.00f}, // 1 SoulCyan
                {0.82f, 0.36f, 1.00f}, // 2 VioletPearl
                {0.32f, 1.00f, 0.46f}, // 3 EmeraldGreen
                {0.52f, 0.94f, 1.00f}, // 4 AquaWhite
                {1.00f, 0.14f, 0.08f}, // 5 RubyRed
                {1.00f, 0.72f, 0.24f}, // 6 GoldOchre
            };
            static constexpr float kCatUnitPower[7] = {
                16.0f, // 0 WarmFlame
                17.0f, // 1 SoulCyan
                15.0f, // 2 VioletPearl
                16.0f, // 3 EmeraldGreen
                19.0f, // 4 AquaWhite
                11.0f, // 5 RubyRed
                18.0f, // 6 GoldOchre
            };
            auto emit_bucket_table = [&](const std::vector<EmissiveBucket>& buckets, uint32_t per_cat, float cnt_exp, bool use_block_mask) {
                if (buckets.empty()) return;
                for (uint32_t cat = 0; cat < kNumLightCats; ++cat) {
                    for (uint32_t i = 0; i < per_cat; ++i) {
                        const auto& b = buckets[cat * per_cat + i];
                        if (b.cnt == 0u) continue;
                        const float max_rgb = std::max({b.sr, b.sg, b.sb});
                        float cr = kCatFallbackRgb[cat][0];
                        float cg = kCatFallbackRgb[cat][1];
                        float cb = kCatFallbackRgb[cat][2];
                        if (max_rgb > 1e-3f) {
                            const float norm = 1.0f / max_rgb;
                            cr = b.sr * norm;
                            cg = b.sg * norm;
                            cb = b.sb * norm;
                        }
                        const uint32_t unique_blocks = (use_block_mask && b.block_mask != 0u)
                            ? static_cast<uint32_t>(__builtin_popcount(b.block_mask))
                            : b.cnt;
                        const float ef = static_cast<float>(b.max_emit) / 15.0f;
                        const float pwr = std::min(54.0f, kCatUnitPower[cat] * std::pow(ef, 1.35f) * std::pow(static_cast<float>(unique_blocks), cnt_exp));
                        const float rad = b.all_sprite
                            ? 0.28f
                            : std::clamp(0.45f * std::sqrt(static_cast<float>(unique_blocks)), 0.38f, 1.35f);
                        float cx, cy, cz;
                        if (b.open_cnt > 0u) {
                            const float inv_o = 1.0f / static_cast<float>(b.open_cnt);
                            cx = b.open_sx * inv_o;
                            cy = b.open_sy * inv_o;
                            cz = b.open_sz * inv_o;
                        } else {
                            const float inv_c = 1.0f / static_cast<float>(b.cnt);
                            cx = b.sx * inv_c;
                            cy = b.sy * inv_c;
                            cz = b.sz * inv_c;
                        }
                        if (b.has_first) {
                            const int ibx = std::clamp(static_cast<int>(std::floor(cx)), 0, 15);
                            const int iby = std::clamp(static_cast<int>(std::floor(cy)), 0, 15);
                            const int ibz = std::clamp(static_cast<int>(std::floor(cz)), 0, 15);
                            const int16_t cslot = voxel_slot[(iby * 16 + ibz) * 16 + ibx];
                            if (cslot >= 0) {
                                const auto& cblk = entry.voxel_blocks[static_cast<size_t>(cslot)];
                                const uint8_t ctag = cblk.semantic_tag;
                                if (ctag == 1u || ctag == 2u || ctag == 12u || ctag == 15u ||
                                    (ctag == 14u && cblk.emissive >= 1u && cblk.emissive <= 13u)) {
                                    cx = b.first_x;
                                    cy = b.first_y;
                                    cz = b.first_z;
                                }
                            }
                        }
                        entry.emissive_clusters.push_back({
                            cx, cy, cz, pwr, cr, cg, cb, rad
                        });
                    }
                }
            };
            emit_bucket_table(small_buckets, 512u, 0.72f, true);
            emit_bucket_table(weak_sprite_buckets, 64u, 0.45f, false);
        }

        if (bvh_quad_count > 0u) {
            const size_t vb_bytes = verts.size() * sizeof(MTLPackedFloat3);
            entry.vertex_buffer = [scene->device newBufferWithBytes:verts.data()
                                                             length:vb_bytes
                                                            options:MTLResourceStorageModeShared];
            const size_t mb_bytes = entry.packed_meta_cpu.size() * sizeof(uint32_t);
            entry.meta_buffer = [scene->device newBufferWithBytes:entry.packed_meta_cpu.data()
                                                           length:std::max<size_t>(4u, mb_bytes)
                                                          options:MTLResourceStorageModeShared];
            if (!entry.vertex_buffer || !entry.meta_buffer) {
                return false;
            }
        }

        if (scene->hardware_bvh && bvh_quad_count > 0u) {
            MTLAccelerationStructureTriangleGeometryDescriptor* geo =
                [MTLAccelerationStructureTriangleGeometryDescriptor descriptor];
            geo.vertexBuffer = entry.vertex_buffer;
            geo.vertexFormat = MTLAttributeFormatFloat3;
            geo.vertexStride = sizeof(MTLPackedFloat3);
            geo.triangleCount = entry.triangle_count;
            geo.opaque = YES;

            MTLPrimitiveAccelerationStructureDescriptor* blas_desc =
                [MTLPrimitiveAccelerationStructureDescriptor descriptor];
            blas_desc.geometryDescriptors = @[ geo ];
#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 260000
            if (@available(macOS 26.0, *)) {
                blas_desc.usage = MTLAccelerationStructureUsagePreferFastIntersection;
            }
#endif

            MTLAccelerationStructureSizes sizes =
                [scene->device accelerationStructureSizesWithDescriptor:blas_desc];
            entry.blas = [scene->device newAccelerationStructureWithSize:sizes.accelerationStructureSize];
            if (!entry.blas) {
                return false;
            }
            // 延後至渲染執行緒重建 TLAS 前以單一 batches encoder 平行建置 BLAS，徹底免除 worker waitUntilCompleted 鎖步卡頓
            entry.blas_desc = blas_desc;
            entry.blas_scratch_size = std::max<NSUInteger>(256u, (sizes.buildScratchBufferSize + 255u) & ~255u);
            entry.blas_needs_build = true;
        }

        {
            std::lock_guard<std::mutex> lock(scene->sections_mutex);
            auto prev_it = scene->sections.find(key);
            const bool had_quads = (prev_it != scene->sections.end() && prev_it->second.quad_count > 0u);
            const bool has_quads = (entry.quad_count > 0u);
            if (entry.quad_count == 0u && entry.voxel_blocks.empty()) {
                if (prev_it != scene->sections.end()) {
                    scene->sections.erase(prev_it);
                }
            } else {
                scene->sections[key] = std::move(entry);
            }
            if (!had_quads && has_quads) {
                scene->active_quad_section_count.fetch_add(1u, std::memory_order_relaxed);
            } else if (had_quads && !has_quads) {
                scene->active_quad_section_count.fetch_sub(1u, std::memory_order_relaxed);
            }
            scene->sections_generation += 1;
            const int32_t sx_c = sec_x * 16 + 8;
            const int32_t sy_c = sec_y * 16 + 8;
            const int32_t sz_c = sec_z * 16 + 8;
            const int32_t v_cy = scene->voxel_origin_init
                ? (scene->voxel_origin[1] + static_cast<int32_t>(kVoxelGridY / 2u))
                : scene->voxel_cam_iy;
            if (!scene->voxel_origin_init ||
                (std::abs(sx_c - scene->voxel_cam_ix) <= 96 &&
                 (std::abs(sy_c - scene->voxel_cam_iy) <= 64 || std::abs(sy_c - v_cy) <= 64) &&
                 std::abs(sz_c - scene->voxel_cam_iz) <= 96)) {
                scene->near_section_dirty = true;
            }
        }
        return true;
    }
}

bool metality_rt_scene_update_section(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    int32_t sec_x,
    int32_t sec_y,
    int32_t sec_z,
    const MetalityPackedQuad* solid_quads,
    uint32_t solid_count,
    const MetalityPackedQuad* cutout_quads,
    uint32_t cutout_count
) {
    return metality_rt_scene_update_section_v2(
        ctx, scene, sec_x, sec_y, sec_z, solid_quads, solid_count, cutout_quads, cutout_count, nullptr, 0u
    );
}

void metality_rt_scene_remove_section(
    MetalityRtSceneHandle scene,
    int32_t sec_x,
    int32_t sec_y,
    int32_t sec_z
) {
    if (!scene) return;
    std::lock_guard<std::mutex> lock(scene->sections_mutex);
    auto it = scene->sections.find(SectionKey{sec_x, sec_y, sec_z});
    if (it != scene->sections.end()) {
        if (it->second.quad_count > 0u) {
            scene->active_quad_section_count.fetch_sub(1u, std::memory_order_relaxed);
        }
        scene->sections.erase(it);
    }
    scene->sections_generation += 1;
    const int32_t sx_c = sec_x * 16 + 8;
    const int32_t sy_c = sec_y * 16 + 8;
    const int32_t sz_c = sec_z * 16 + 8;
    const int32_t v_cy = scene->voxel_origin_init
        ? (scene->voxel_origin[1] + static_cast<int32_t>(kVoxelGridY / 2u))
        : scene->voxel_cam_iy;
    if (!scene->voxel_origin_init ||
        (std::abs(sx_c - scene->voxel_cam_ix) <= 96 &&
         (std::abs(sy_c - scene->voxel_cam_iy) <= 64 || std::abs(sy_c - v_cy) <= 64) &&
         std::abs(sz_c - scene->voxel_cam_iz) <= 96)) {
        scene->near_section_dirty = true;
    }
}

void metality_rt_scene_clear_all(MetalityRtSceneHandle scene) {
    if (!scene) return;
    std::lock_guard<std::mutex> lock(scene->sections_mutex);
    scene->sections.clear();
    scene->active_quad_section_count.store(0u, std::memory_order_relaxed);
    scene->pending_compact_batches.clear();
    scene->cached_emissive_clusters.clear();
    scene->emissive_cache_gen = 0u;
    scene->max_near_wy_gen = 0u;
    scene->cached_max_near_wy = INT32_MIN;
    scene->dynamic_lights.clear();
    scene->water_ripples.clear();
    scene->entity_shadows.clear();
    scene->cached_tlas_entries.clear();
    scene->active_blas_list.clear();
    scene->active_blas_resources.clear();
    scene->cached_blas_array = nil;
    scene->meta_flat_buf = nil;
    scene->instance_meta_offset_buf = nil;
    scene->tlas = nil;
    scene->active_instance_count = 0u;
    scene->active_emissive_light_count = 0u;
    scene->voxel_origin_init = false;
    scene->voxel_cam_ix = 0x3FFFFFFF;
    scene->voxel_cam_iy = 0x3FFFFFFF;
    scene->voxel_cam_iz = 0x3FFFFFFF;
    scene->near_section_dirty = true;
    scene->voxel_flags = 0u;
    scene->last_voxel_gen = 0u;
    scene->last_voxel_rebuild_frame = 0u;
    scene->last_tlas_topo_frame = 0u;
    scene->c1_cache_valid = false;
    scene->c2_cache_valid = false;
    scene->has_last_tlas_cam = false;
    scene->sections_generation += 1u;
    for (uint32_t s = 0; s < kRtRingSlots; ++s) {
        if (scene->voxel_grid_ring[s] != nil && scene->voxel_grid_ring[s].contents != nullptr) {
            std::memset(scene->voxel_grid_ring[s].contents, 0, kVoxelGridBufferBytes);
        }
        scene->tlas_ring_topo_gen[s] = 0u;
    }
}

void metality_rt_scene_set_dynamic_lights(
    MetalityRtSceneHandle scene,
    const float* lights_data_8f,
    uint32_t light_count
) {
    if (!scene) return;
    std::lock_guard<std::mutex> lock(scene->sections_mutex);
    scene->dynamic_lights.clear();
    if (!lights_data_8f || light_count == 0u) {
        return;
    }
    const uint32_t clamped = std::min<uint32_t>(light_count, 128u);
    scene->dynamic_lights.reserve(clamped);
    for (uint32_t i = 0; i < clamped; ++i) {
        const float* p = lights_data_8f + i * 8u;
        scene->dynamic_lights.push_back({
            p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]
        });
    }
}

void metality_rt_scene_set_water_ripples(
    MetalityRtSceneHandle scene,
    const float* ripples_data_8f,
    uint32_t ripple_count
) {
    if (!scene) return;
    std::lock_guard<std::mutex> lock(scene->sections_mutex);
    scene->water_ripples.clear();
    if (!ripples_data_8f || ripple_count == 0u) {
        return;
    }
    const uint32_t clamped = std::min<uint32_t>(ripple_count, kMaxWaterRipples);
    scene->water_ripples.reserve(clamped);
    for (uint32_t i = 0; i < clamped; ++i) {
        const float* p = ripples_data_8f + i * 8u;
        scene->water_ripples.push_back({
            p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]
        });
    }
}

void metality_rt_scene_set_entity_shadows(
    MetalityRtSceneHandle scene,
    const float* casters_data_8f,
    uint32_t caster_count
) {
    if (!scene) return;
    std::lock_guard<std::mutex> lock(scene->sections_mutex);
    scene->entity_shadows.clear();
    if (!casters_data_8f || caster_count == 0u) {
        return;
    }
    const uint32_t clamped = std::min<uint32_t>(caster_count, kMaxEntityShadowCasters);
    scene->entity_shadows.reserve(clamped);
    for (uint32_t i = 0; i < clamped; ++i) {
        const float* p = casters_data_8f + i * 8u;
        scene->entity_shadows.push_back({
            p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]
        });
    }
}

} // extern "C"

namespace {

void sync_world_data_locked(
    MetalityContext* ctx,
    MetalityRayTracingSceneImpl* scene,
    float cam_x,
    float cam_y,
    float cam_z,
    std::unique_lock<std::mutex>& lock
) {
        const uint64_t cur_frame = ctx->current_frame_id;
        uint64_t completed_frame = ctx->frame_event ? ctx->frame_event.signaledValue : cur_frame;

        // 1. 安全多緩衝更新 512x128x512 語意體素標籤緩衝（Smart Y-Anchoring + 全高度 512x512 地形/水面高程圖 + 1024x1024 海水遮罩）
        const int32_t cam_ix = static_cast<int32_t>(std::floor(cam_x));
        const int32_t cam_iy = static_cast<int32_t>(std::floor(cam_y));
        const int32_t cam_iz = static_cast<int32_t>(std::floor(cam_z));

        // Smart Y-anchoring: cache max_near_wy unless sections changed or camera moved >= 8m horizontally
        if (scene->max_near_wy_gen != scene->sections_generation ||
            std::abs(cam_ix - scene->max_near_wy_cam_ix) >= 8 ||
            std::abs(cam_iz - scene->max_near_wy_cam_iz) >= 8) {
            int32_t near_wy = INT32_MIN;
            for (const auto& kv : scene->sections) {
                const auto& sec = kv.second;
                if (sec.voxel_blocks.empty() && sec.quad_count == 0u) {
                    continue;
                }
                const int32_t sx_c = sec.sec_x * 16 + 8;
                const int32_t sz_c = sec.sec_z * 16 + 8;
                if (std::abs(sx_c - cam_ix) <= 192 && std::abs(sz_c - cam_iz) <= 192) {
                    near_wy = std::max(near_wy, sec.sec_y * 16 + 16);
                }
            }
            scene->cached_max_near_wy = near_wy;
            scene->max_near_wy_cam_ix = cam_ix;
            scene->max_near_wy_cam_iz = cam_iz;
            scene->max_near_wy_gen = scene->sections_generation;
        }
        const int32_t max_near_wy = scene->cached_max_near_wy;
        int32_t desired_origin_y = cam_iy - static_cast<int32_t>(kVoxelGridY / 2u);
        if (max_near_wy != INT32_MIN) {
            const int32_t ceiling_clamped = (max_near_wy + 8) - static_cast<int32_t>(kVoxelGridY);
            if (desired_origin_y > ceiling_clamped) {
                desired_origin_y = std::max(-64, ceiling_clamped);
            }
        }
        if (cam_iy > 96 && desired_origin_y > 48) {
            // Keep sea level (Y=62) and shallow seabed inside [vOrg.y, vOrg.y+128) when flying over open terrain,
            // while never clipping tall indoor ceilings up to 40m above the camera.
            if (max_near_wy == INT32_MIN || max_near_wy <= cam_iy + 40) {
                const int32_t top_needed = (max_near_wy != INT32_MIN) ? std::min(cam_iy + 40, max_near_wy + 8) : (cam_iy + 16);
                desired_origin_y = std::min(desired_origin_y, std::max(16, top_needed - static_cast<int32_t>(kVoxelGridY)));
            }
        }

        const bool origin_shifted = !scene->voxel_origin_init ||
                                    std::abs(cam_ix - scene->voxel_cam_ix) >= 32 ||
                                    std::abs(desired_origin_y - scene->voxel_origin[1]) >= 16 ||
                                    std::abs(cam_iz - scene->voxel_cam_iz) >= 32;
        const bool voxel_dirty = scene->near_section_dirty || (scene->sections_generation != scene->last_voxel_gen);
        const bool voxel_cadence_ready =
            (scene->last_voxel_gen == 0u) ||
            (scene->sections.size() <= 32u) ||
            (cur_frame == 0u) ||
            (cur_frame >= scene->last_voxel_rebuild_frame + 8u) ||
            (cur_frame < scene->last_voxel_rebuild_frame);
        if (scene->voxel_grid_buf && (origin_shifted || (voxel_dirty && voxel_cadence_ready))) {
            uint32_t chosen = UINT32_MAX;
            for (uint32_t s = 0; s < kRtRingSlots; ++s) {
                if (scene->voxel_grid_ring[s] != nil && scene->voxel_slot_last_frame[s] <= completed_frame) {
                    chosen = s;
                    break;
                }
            }
            if (chosen == UINT32_MAX) {
                for (uint32_t s = 0; s < kRtRingSlots; ++s) {
                    if (scene->voxel_grid_ring[s] == nil) {
                        id<MTLBuffer> nb = [ctx->device newBufferWithLength:kVoxelGridBufferBytes
                                                                   options:MTLResourceStorageModeShared];
                        if (nb) {
                            scene->voxel_grid_ring[s] = nb;
                            chosen = s;
                        }
                        break;
                    }
                }
            }
            bool skip_voxel_rebuild = false;
            if (chosen == UINT32_MAX) {
                if (!origin_shifted && scene->voxel_origin_init && scene->last_voxel_gen > 0u) {
                    // All ring slots still in-flight on GPU: skip full 34.8 MB rebuild this frame instead of blocking
                    skip_voxel_rebuild = true;
                } else {
                    uint32_t min_s = 0;
                    for (uint32_t s = 1; s < kRtRingSlots; ++s) {
                        if (scene->voxel_slot_last_frame[s] < scene->voxel_slot_last_frame[min_s]) {
                            min_s = s;
                        }
                    }
                    chosen = min_s;
                    const uint64_t wait_f = scene->voxel_slot_last_frame[chosen];
                    id<MTLSharedEvent> ev = ctx->frame_event;
                    if (ev && wait_f > completed_frame && wait_f < cur_frame) {
                        // NEVER hold sections_mutex while blocking on GPU completion
                        lock.unlock();
                        [ev waitUntilSignaledValue:wait_f timeoutMS:1000];
                        lock.lock();
                        completed_frame = ev.signaledValue;
                    }
                }
            }
            if (!skip_voxel_rebuild && chosen < kRtRingSlots && scene->voxel_grid_ring[chosen] != nil) {
            scene->active_voxel_slot = chosen;
            scene->voxel_grid_buf = scene->voxel_grid_ring[chosen];
            if (origin_shifted) {
                scene->voxel_cam_ix = cam_ix;
                scene->voxel_cam_iy = cam_iy;
                scene->voxel_cam_iz = cam_iz;
                scene->voxel_origin[0] = cam_ix - static_cast<int32_t>(kVoxelGridX / 2u);
                scene->voxel_origin[1] = desired_origin_y;
                scene->voxel_origin[2] = cam_iz - static_cast<int32_t>(kVoxelGridZ / 2u);
                scene->voxel_origin_init = true;
            }
            uint8_t* grid = static_cast<uint8_t*>(scene->voxel_grid_buf.contents);
            // Parallel 4-slice zeroing of the 34.86 MB UMA voxel buffer on Apple Silicon P-cores (~2.5x faster)
            constexpr size_t kZeroChunks = 4u;
            const size_t chunk_bytes = kVoxelGridBufferBytes / kZeroChunks;
            dispatch_apply(kZeroChunks, dispatch_get_global_queue(QOS_CLASS_USER_INTERACTIVE, 0), ^(size_t c) {
                const size_t off = c * chunk_bytes;
                const size_t len = (c + 1u == kZeroChunks) ? (kVoxelGridBufferBytes - off) : chunk_bytes;
                std::memset(grid + off, 0, len);
            });
            uint32_t* water_macro = reinterpret_cast<uint32_t*>(grid + kVoxelGridVolume);
            uint32_t* glass_macro = reinterpret_cast<uint32_t*>(grid + kVoxelGridVolume + kWaterMacroBytes);
            uint16_t* top_hmap = reinterpret_cast<uint16_t*>(grid + kVoxelGridVolume + kWaterMacroBytes + kGlassMacroBytes);
            uint32_t* sea_water_mask = reinterpret_cast<uint32_t*>(
                grid + kVoxelGridVolume + kWaterMacroBytes + kGlassMacroBytes + kTopHeightmapBytes
            );
            uint16_t* top_water_hmap = reinterpret_cast<uint16_t*>(
                grid + kVoxelGridVolume + kWaterMacroBytes + kGlassMacroBytes + kTopHeightmapBytes + kSeaWaterMaskBytes
            );
            constexpr size_t kTopHmapCols = static_cast<size_t>(kVoxelGridX) * kVoxelGridZ;
            if (scene->leaf_top_scratch.size() != kTopHmapCols) {
                scene->leaf_top_scratch.resize(kTopHmapCols);
            }
            uint16_t* leaf_top = scene->leaf_top_scratch.data();
            std::memset(leaf_top, 0, kTopHmapCols * sizeof(uint16_t));
            auto mark_macro = [](uint32_t* mask_buf, int32_t gx, int32_t gy, int32_t gz) {
                const uint32_t mb = (static_cast<uint32_t>(gy >> 3) * 64u + static_cast<uint32_t>(gz >> 3)) * 64u
                                  + static_cast<uint32_t>(gx >> 3);
                mask_buf[mb >> 5u] |= (1u << (mb & 31u));
            };
            bool any_water = false;
            bool any_wide_water = false;
            bool any_stained_glass = false;
            int32_t water_y_min = 999999;
            int32_t water_y_max = -999999;
            auto& top_water_cells = scene->top_water_cells_scratch;
            auto& bottom_water_cells = scene->bottom_water_cells_scratch;
            top_water_cells.clear();
            bottom_water_cells.clear();

            const int32_t v_ox = scene->voxel_origin[0];
            const int32_t v_oy = scene->voxel_origin[1];
            const int32_t v_oz = scene->voxel_origin[2];
            const int32_t wide_ox = v_ox - 256;
            const int32_t wide_oz = v_oz - 256;

            for (const auto& kv : scene->sections) {
                const auto& sec = kv.second;
                const int32_t base_wx = sec.sec_x * 16;
                const int32_t base_wy = sec.sec_y * 16;
                const int32_t base_wz = sec.sec_z * 16;
                if (base_wx + 15 < wide_ox || base_wx >= wide_ox + 1024 ||
                    base_wz + 15 < wide_oz || base_wz >= wide_oz + 1024) {
                    continue;
                }
                const bool sec_in_512_xz =
                    (base_wx + 15 >= v_ox) && (base_wx < v_ox + static_cast<int32_t>(kVoxelGridX)) &&
                    (base_wz + 15 >= v_oz) && (base_wz < v_oz + static_cast<int32_t>(kVoxelGridZ));

                for (const auto& blk : sec.voxel_blocks) {
                    const int32_t wx = base_wx + blk.lx;
                    const int32_t wy = base_wy + blk.ly;
                    const int32_t wz = base_wz + blk.lz;

                    // 1024x1024 wide 1-bit sea_water_mask (restricted to water near sea level Y=62 so high fountains or underground pools never spawn a phantom sea plane)
                    if (blk.semantic_tag == 3u && std::abs(wy - 62) <= 4) {
                        const int32_t sx = wx - wide_ox;
                        const int32_t sz = wz - wide_oz;
                        if (sx >= 0 && sx < 1024 && sz >= 0 && sz < 1024) {
                            const uint32_t bit_idx = static_cast<uint32_t>(sz) * 1024u + static_cast<uint32_t>(sx);
                            sea_water_mask[bit_idx >> 5u] |= (1u << (bit_idx & 31u));
                            any_wide_water = true;
                        }
                    }

                    if (!sec_in_512_xz) {
                        continue;
                    }
                    const int32_t gx = wx - v_ox;
                    const int32_t gz = wz - v_oz;
                    if (gx < 0 || gx >= static_cast<int32_t>(kVoxelGridX) ||
                        gz < 0 || gz >= static_cast<int32_t>(kVoxelGridZ)) {
                        continue;
                    }
                    const uint32_t col = static_cast<uint32_t>(gz) * kVoxelGridX + static_cast<uint32_t>(gx);

                    // 512x512 full-height uint16_t top_hmap & top_water_hmap (populated BEFORE vOrg.y Y-bounds check!)
                    // top_hmap bits[0..11] = highest solid non-leaf roof Y + 1024 in [1..4095];
                    // bottom-only sub-blocks (sub in {3,7,10,11} and bottom stairs) encode wy+1024 so outdoor slabs/stairs never self-trigger pixelUnderRoof.
                    // bits[12..15] packed post-loop = clamp((leaf_top - solid_top + 1) >> 1, 1, 15) when foliage overhangs solid ground.
                    if (blk.semantic_tag == 2u) {
                        const uint16_t enc_leaf = static_cast<uint16_t>(std::clamp<int32_t>((wy + 1) + 1024, 1, 4095));
                        if (enc_leaf > leaf_top[col]) {
                            leaf_top[col] = enc_leaf;
                        }
                    } else if (is_bvh_opaque_tag(blk.semantic_tag) ||
                               (blk.semantic_tag == 15u && (blk.emissive & 4u) != 0u) ||
                               (blk.semantic_tag == 14u &&
                                (blk.emissive == 4u || blk.emissive == 8u || blk.emissive == 9u || blk.emissive == 13u))) {
                        const uint16_t enc_top = static_cast<uint16_t>(std::clamp<int32_t>((wy + 1) + 1024, 1, 4095));
                        if (enc_top > top_hmap[col]) {
                            top_hmap[col] = enc_top;
                        }
                    } else if ((blk.semantic_tag == 15u && (blk.emissive & 4u) == 0u) ||
                               (blk.semantic_tag == 14u &&
                                (blk.emissive == 3u || blk.emissive == 7u || blk.emissive == 10u || blk.emissive == 11u))) {
                        const uint16_t enc_top = static_cast<uint16_t>(std::clamp<int32_t>(wy + 1024, 1, 4095));
                        if (enc_top > top_hmap[col]) {
                            top_hmap[col] = enc_top;
                        }
                    }
                    if (blk.semantic_tag == 3u) {
                        const uint16_t enc_wtop = static_cast<uint16_t>(std::clamp<int32_t>(wy + 1024, 1, 65535));
                        if (enc_wtop > top_water_hmap[col]) {
                            top_water_hmap[col] = enc_wtop;
                        }
                        any_wide_water = true;
                    }

                    const int32_t gy = wy - v_oy;
                    if (blk.semantic_tag == 3u &&
                        gy >= 0 && gy < static_cast<int32_t>(kVoxelGridY) + 48 &&
                        (blk.face_mask & 0x1u) != 0u && (blk.face_mask & 0x2u) == 0u &&
                        (blk.emissive == 8u || blk.emissive == 0u)) {
                        const uint32_t packed_top = (static_cast<uint32_t>(gy) << 18)
                                                  | (static_cast<uint32_t>(gz) << 9)
                                                  | static_cast<uint32_t>(gx);
                        top_water_cells.push_back(packed_top);
                    }

                    if (gy >= 0 && gy < static_cast<int32_t>(kVoxelGridY)) {
                        const uint32_t idx = (static_cast<uint32_t>(gy) * kVoxelGridZ + static_cast<uint32_t>(gz)) * kVoxelGridX
                                           + static_cast<uint32_t>(gx);
                        // Invisible minecraft:light (tag=14, sub=14) must stay 0 in voxelGrid (never occluding or surface-emissive)
                        if (!(blk.semantic_tag == 14u && blk.emissive == 14u)) {
                            grid[idx] = static_cast<uint8_t>(((blk.semantic_tag & 0xFu) << 4) | (blk.emissive & 0xFu));
                        }
                        if (blk.semantic_tag == 3u) {
                            any_water = true;
                            water_y_min = std::min(water_y_min, wy);
                            water_y_max = std::max(water_y_max, wy);
                            mark_macro(water_macro, gx, gy, gz);
                            if ((blk.face_mask & 0x2u) != 0u) {
                                const uint32_t packed_bot = (static_cast<uint32_t>(gy) << 18)
                                                          | (static_cast<uint32_t>(gz) << 9)
                                                          | static_cast<uint32_t>(gx);
                                bottom_water_cells.push_back(packed_bot);
                            }
                        } else if (blk.semantic_tag == 8u && blk.emissive >= 2u) {
                            any_stained_glass = true;
                            mark_macro(glass_macro, gx, gy, gz);
                        }
                    }
                }
            }

            // Pack foliage canopy delta into top_hmap bits[12..15] while preserving bits[0..11] solid-only roof height
            for (size_t col = 0; col < kTopHmapCols; ++col) {
                const uint16_t solid_top = top_hmap[col];
                const uint16_t l_top = leaf_top[col];
                if (solid_top > 0u && l_top > solid_top) {
                    const uint32_t delta = std::clamp<uint32_t>(
                        (static_cast<uint32_t>(l_top - solid_top) + 1u) >> 1u, 1u, 15u);
                    top_hmap[col] = static_cast<uint16_t>(solid_top | (delta << 12u));
                }
            }

            std::sort(bottom_water_cells.begin(), bottom_water_cells.end());

            // Fill interior submerged water columns downward (<=48m) beneath still top water blocks
            // clamped safely into [0, 127] so deep pools/oceans retain interior water voxels (0x3A).
            for (const uint32_t packed : top_water_cells) {
                const int32_t gx = static_cast<int32_t>(packed & 0x1FFu);
                const int32_t gz = static_cast<int32_t>((packed >> 9) & 0x1FFu);
                const int32_t gy = static_cast<int32_t>((packed >> 18) & 0x3FFFu);
                if (gy <= 0) continue;
                if (gy >= 0 && gy + 1 < static_cast<int32_t>(kVoxelGridY)) {
                    const uint32_t above_idx = (static_cast<uint32_t>(gy + 1) * kVoxelGridZ + static_cast<uint32_t>(gz)) * kVoxelGridX
                                             + static_cast<uint32_t>(gx);
                    if (((grid[above_idx] >> 4u) & 0xFu) == 3u) continue;
                }
                const int32_t max_d = std::min<int32_t>(48, gy);
                int32_t floor_d = 0;
                for (int32_t d = 1; d <= max_d; ++d) {
                    const int32_t cur_gy = gy - d;
                    if (cur_gy >= static_cast<int32_t>(kVoxelGridY)) {
                        continue;
                    }
                    if (cur_gy < 0) {
                        floor_d = d;
                        break;
                    }
                    const uint32_t idx = (static_cast<uint32_t>(cur_gy) * kVoxelGridZ + static_cast<uint32_t>(gz)) * kVoxelGridX
                                       + static_cast<uint32_t>(gx);
                    const uint8_t v = grid[idx];
                    if (v != 0u) {
                        if (((v >> 4u) & 0xFu) == 3u) {
                            const uint32_t probe_packed = (static_cast<uint32_t>(cur_gy) << 18)
                                                        | (static_cast<uint32_t>(gz) << 9)
                                                        | static_cast<uint32_t>(gx);
                            if (std::binary_search(bottom_water_cells.begin(), bottom_water_cells.end(), probe_packed)) {
                                floor_d = d;
                                break;
                            }
                            continue;
                        }
                        floor_d = d;
                        break;
                    }
                }
                for (int32_t d = 1; d < floor_d; ++d) {
                    const int32_t cur_gy = gy - d;
                    if (cur_gy < 0 || cur_gy >= static_cast<int32_t>(kVoxelGridY)) {
                        continue;
                    }
                    const uint32_t idx = (static_cast<uint32_t>(cur_gy) * kVoxelGridZ + static_cast<uint32_t>(gz)) * kVoxelGridX
                                       + static_cast<uint32_t>(gx);
                    if (grid[idx] == 0u) {
                        grid[idx] = 0x3Au;
                        any_water = true;
                        const int32_t fill_wy = v_oy + cur_gy;
                        water_y_min = std::min(water_y_min, fill_wy);
                        water_y_max = std::max(water_y_max, fill_wy);
                        mark_macro(water_macro, gx, cur_gy, gz);
                    }
                }
            }
            uint32_t vflags = 1u | (any_water ? 2u : 0u) | (any_stained_glass ? 4u : 0u) | (any_wide_water ? 8u : 0u);
            if (any_water && water_y_min <= water_y_max) {
                const uint32_t enc_ymin = static_cast<uint32_t>(std::clamp<int32_t>(water_y_min, -512, 1535) + 512) & 0x7FFu;
                const uint32_t enc_ymax = static_cast<uint32_t>(std::clamp<int32_t>(water_y_max, -512, 1535) + 512) & 0x7FFu;
                vflags |= 0x80u | (enc_ymin << 8u) | (enc_ymax << 19u);
            }
            scene->voxel_flags = vflags;
            scene->last_voxel_gen = scene->sections_generation;
            scene->near_section_dirty = false;
            scene->last_voxel_rebuild_frame = cur_frame;
            }
        }
        if (cur_frame > scene->voxel_slot_last_frame[scene->active_voxel_slot]) {
            scene->voxel_slot_last_frame[scene->active_voxel_slot] = cur_frame;
        }

        // 選用安全未佔用之動態光源／水波 Ring 槽位（絕不於持有 sections_mutex 時阻塞等待 GPU）
        uint32_t dslot = UINT32_MAX;
        for (uint32_t i = 0; i < kRtRingSlots; ++i) {
            const uint32_t cand = static_cast<uint32_t>((cur_frame + i) % kRtRingSlots);
            if (scene->dynamic_slot_last_frame[cand] <= completed_frame) {
                dslot = cand;
                break;
            }
        }
        if (dslot == UINT32_MAX) {
            dslot = 0u;
            for (uint32_t s = 1; s < kRtRingSlots; ++s) {
                if (scene->dynamic_slot_last_frame[s] < scene->dynamic_slot_last_frame[dslot]) {
                    dslot = s;
                }
            }
            const uint64_t wait_f = scene->dynamic_slot_last_frame[dslot];
            id<MTLSharedEvent> ev = ctx->frame_event;
            if (ev && wait_f > completed_frame && wait_f < cur_frame) {
                lock.unlock();
                [ev waitUntilSignaledValue:wait_f timeoutMS:1000];
                lock.lock();
                completed_frame = ev.signaledValue;
            }
        }
        scene->active_dynamic_slot = dslot;
        scene->emissive_light_buf = scene->emissive_light_ring[dslot];
        scene->water_ripple_buf = scene->water_ripple_ring[dslot];
        scene->entity_shadow_buf = scene->entity_shadow_ring[dslot];
        if (cur_frame > scene->dynamic_slot_last_frame[dslot]) {
            scene->dynamic_slot_last_frame[dslot] = cur_frame;
        }

        // 2. 收集距離攝影機 192m 內最具影響力的自發光聚類寫入 UMA emissive_light_buf（攝影機相對座標，160~192m 平滑淡出）
        if (scene->emissive_light_buf) {
            constexpr float kMaxLightCamDist = 192.0f;
            constexpr float kMaxLightCamDist2 = kMaxLightCamDist * kMaxLightCamDist;
            constexpr float kFadeStartDist = 160.0f;
            constexpr float kInvFadeSpan = 1.0f / (kMaxLightCamDist - kFadeStartDist);
            struct ScoredLight {
                float dx, dy, dz, power;
                float r, g, b, radius;
                float score;
            };
            // Rebuild flat world-space emissive cluster cache ONLY when section topology changes (eliminates 4096-entry map scan per frame!)
            if (scene->emissive_cache_gen != scene->sections_generation) {
                scene->cached_emissive_clusters.clear();
                for (const auto& kv : scene->sections) {
                    const auto& sec = kv.second;
                    if (sec.emissive_clusters.empty()) continue;
                    const float bx = static_cast<float>(sec.sec_x) * 16.0f;
                    const float by = static_cast<float>(sec.sec_y) * 16.0f;
                    const float bz = static_cast<float>(sec.sec_z) * 16.0f;
                    for (const auto& c : sec.emissive_clusters) {
                        scene->cached_emissive_clusters.push_back({
                            bx + c.lx, by + c.ly, bz + c.lz,
                            c.power, c.r, c.g, c.b, c.radius
                        });
                    }
                }
                scene->emissive_cache_gen = scene->sections_generation;
            }
            std::vector<ScoredLight> candidates;
            candidates.reserve(std::min<size_t>(scene->cached_emissive_clusters.size() + scene->dynamic_lights.size(), 1024u));
            for (const auto& c : scene->cached_emissive_clusters) {
                const float dx = c.wx - cam_x;
                const float dy = c.wy - cam_y;
                const float dz = c.wz - cam_z;
                const float dist2 = dx * dx + dy * dy + dz * dz;
                if (dist2 <= kMaxLightCamDist2) {
                    float cam_fade = 1.0f;
                    if (dist2 > kFadeStartDist * kFadeStartDist) {
                        const float cam_dist = std::sqrt(dist2);
                        const float t = std::clamp((cam_dist - kFadeStartDist) * kInvFadeSpan, 0.0f, 1.0f);
                        cam_fade = 1.0f - t * t * (3.0f - 2.0f * t);
                    }
                    if (cam_fade > 0.002f) {
                        const float near_boost = (dist2 < 32.0f * 32.0f) ? 2.5f : 1.0f;
                        const float p_weight = std::pow(std::min(std::max(c.power, 1.0f), 130.0f), 1.15f);
                        const float score = (p_weight * near_boost * cam_fade) / (dist2 + 36.0f);
                        candidates.push_back({dx, dy, dz, c.power, c.r * cam_fade, c.g * cam_fade, c.b * cam_fade, c.radius, score});
                    }
                }
            }
            for (const auto& dl : scene->dynamic_lights) {
                float dx = dl.wx - cam_x;
                float dy = dl.wy - cam_y;
                float dz = dl.wz - cam_z;
                const float raw_dist2 = dx * dx + dy * dy + dz * dz;
                // 僅針對貼身手持光源（距離攝影機 < 1.2m）於陷入實心方塊時往攝影機方向微幅拉回 <= 0.35m，
                // 絕不對遠處實體光源執行 dx*=0.5f 穿牆傳送！
                if (raw_dist2 < 1.2f * 1.2f && raw_dist2 > 1e-4f && scene->voxel_grid_buf && !scene->sections.empty()) {
                    const uint8_t* grid = static_cast<const uint8_t*>(scene->voxel_grid_buf.contents);
                    const int32_t gx = static_cast<int32_t>(std::floor(dl.wx)) - scene->voxel_origin[0];
                    const int32_t gy = static_cast<int32_t>(std::floor(dl.wy)) - scene->voxel_origin[1];
                    const int32_t gz = static_cast<int32_t>(std::floor(dl.wz)) - scene->voxel_origin[2];
                    if (gx >= 0 && gx < static_cast<int32_t>(kVoxelGridX) &&
                        gy >= 0 && gy < static_cast<int32_t>(kVoxelGridY) &&
                        gz >= 0 && gz < static_cast<int32_t>(kVoxelGridZ)) {
                        const uint8_t v = grid[(static_cast<uint32_t>(gy) * kVoxelGridZ + static_cast<uint32_t>(gz)) * kVoxelGridX + static_cast<uint32_t>(gx)];
                        const uint32_t tag = static_cast<uint32_t>(v >> 4u);
                        const uint32_t sub = static_cast<uint32_t>(v & 0xFu);
                        const bool is_solid = (v != 0u) && (
                            (tag >= 1u && tag <= 7u && tag != 3u) ||
                            tag == 12u ||
                            tag == 15u ||
                            (tag == 14u && sub >= 1u && sub <= 13u)
                        );
                        if (is_solid) {
                            const float dlen = std::sqrt(raw_dist2);
                            const float pull = std::min(0.35f, dlen * 0.5f);
                            const float scale = (dlen - pull) / dlen;
                            dx *= scale;
                            dy *= scale;
                            dz *= scale;
                        }
                    }
                }
                const float dist2 = dx * dx + dy * dy + dz * dz;
                if (dist2 <= kMaxLightCamDist2) {
                    float cam_fade = 1.0f;
                    if (dist2 > kFadeStartDist * kFadeStartDist) {
                        const float cam_dist = std::sqrt(dist2);
                        const float t = std::clamp((cam_dist - kFadeStartDist) * kInvFadeSpan, 0.0f, 1.0f);
                        cam_fade = 1.0f - t * t * (3.0f - 2.0f * t);
                    }
                    if (cam_fade > 0.002f) {
                        const float score = (dl.power * 4.0f * cam_fade) / (dist2 + 4.0f);
                        candidates.push_back({dx, dy, dz, dl.power, dl.r * cam_fade, dl.g * cam_fade, dl.b * cam_fade, dl.radius, score});
                    }
                }
            }

            const uint32_t emit_n = std::min<uint32_t>(kMaxEmissiveLights, static_cast<uint32_t>(candidates.size()));
            if (emit_n > 0u) {
                std::partial_sort(
                    candidates.begin(),
                    candidates.begin() + emit_n,
                    candidates.end(),
                    [](const ScoredLight& a, const ScoredLight& b) { return a.score > b.score; }
                );
            }
            RtVec4* lbuf = static_cast<RtVec4*>(scene->emissive_light_buf.contents);
            lbuf[0] = {static_cast<float>(emit_n), 0.0f, 0.0f, 0.0f};
            for (uint32_t li = 0; li < emit_n; ++li) {
                const auto& sl = candidates[li];
                lbuf[1u + 2u * li] = {sl.dx, sl.dy, sl.dz, sl.power};
                lbuf[2u + 2u * li] = {sl.r, sl.g, sl.b, sl.radius};
            }
            scene->active_emissive_light_count = emit_n;
        }

        // 3. 更新互動式水面波紋緩衝（划船尾流與跳水漣漪，攝影機相對座標）
        if (scene->water_ripple_buf) {
            const uint32_t rn = std::min<uint32_t>(kMaxWaterRipples, static_cast<uint32_t>(scene->water_ripples.size()));
            RtVec4* rbuf = static_cast<RtVec4*>(scene->water_ripple_buf.contents);
            rbuf[0] = {static_cast<float>(rn), 0.0f, 0.0f, 0.0f};
            for (uint32_t ri = 0; ri < rn; ++ri) {
                const auto& wp = scene->water_ripples[ri];
                rbuf[1u + 2u * ri] = {wp.wx - cam_x, wp.wy - cam_y, wp.wz - cam_z, wp.age};
                rbuf[2u + 2u * ri] = {wp.dir_x, wp.dir_z, wp.amplitude, wp.kind};
            }
        }

        // 4. 更新生物與玩家即時定向包圍盒陰影緩衝（攝影機相對座標）
        if (scene->entity_shadow_buf) {
            const uint32_t en = std::min<uint32_t>(kMaxEntityShadowCasters, static_cast<uint32_t>(scene->entity_shadows.size()));
            RtVec4* ebuf = static_cast<RtVec4*>(scene->entity_shadow_buf.contents);
            ebuf[0] = {static_cast<float>(en), 0.0f, 0.0f, 0.0f};
            for (uint32_t ei = 0; ei < en; ++ei) {
                const auto& es = scene->entity_shadows[ei];
                ebuf[1u + 2u * ei] = {es.wx - cam_x, es.wy - cam_y, es.wz - cam_z, es.hx};
                ebuf[2u + 2u * ei] = {es.hy, es.hz, es.yaw_rad, es.pitch_rad};
            }
        }
}

} // namespace

extern "C" {

bool metality_rt_scene_sync_world_data(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    float cam_x,
    float cam_y,
    float cam_z
) {
    if (!ctx || !scene) {
        return false;
    }
    @autoreleasepool {
        std::unique_lock<std::mutex> lock(scene->sections_mutex);
        sync_world_data_locked(ctx, scene, cam_x, cam_y, cam_z, lock);
        scene->active_instance_count = static_cast<uint32_t>(scene->sections.size());
        return true;
    }
}

bool metality_rt_scene_rebuild_tlas(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    float cam_x,
    float cam_y,
    float cam_z
) {
    if (!ctx || !scene) {
        return false;
    }
    @autoreleasepool {
        std::unique_lock<std::mutex> lock(scene->sections_mutex);
        sync_world_data_locked(ctx, scene, cam_x, cam_y, cam_z, lock);

        if (!scene->hardware_bvh) {
            scene->active_instance_count = static_cast<uint32_t>(scene->sections.size());
            return true;
        }

        // 先於單一 Encoder 平行建置所有待更新區段的 BLAS，再建置 TLAS（未成功建置之 BLAS 絕不加入 TLAS）
        const uint64_t cur_frame = ctx->current_frame_id;
        uint64_t completed_frame = ctx->frame_event ? ctx->frame_event.signaledValue : cur_frame;
        const bool on_render_thread = (std::this_thread::get_id() == ctx->render_thread_id);
        const bool use_active_cb = on_render_thread && (ctx->active_command_buffer != nil);
        id<MTLCommandBuffer> cb = use_active_cb ? ctx->active_command_buffer : nil;
        auto ensure_cb = [&]() -> id<MTLCommandBuffer> {
            if (use_active_cb) {
                if (ctx->active_render_encoder) {
                    [ctx->active_render_encoder endEncoding];
                    ctx->active_render_encoder = nil;
                    ctx->enc_state.reset();
                    ctx->active_pass_width = 0;
                    ctx->active_pass_height = 0;
                }
                return cb;
            }
            if (!cb) {
                cb = [scene->queue commandBuffer];
            }
            return cb;
        };

        const bool force_initial_topo =
            !scene->cached_blas_array ||
            (scene->tlas == nil);
        const bool topo_cadence_ready =
            (scene->sections.size() <= 32u) ||
            (cur_frame == 0u) ||
            (cur_frame >= scene->last_tlas_topo_frame + 6u) ||
            (cur_frame < scene->last_tlas_topo_frame);
        const bool allow_topo_rebuild = force_initial_topo || topo_cadence_ready;

        NSUInteger total_blas_scratch = 0;
        struct DirtyBlasItem {
            SectionRtEntry* sec;
            NSUInteger offset;
        };
        std::vector<DirtyBlasItem> dirty_blas;
        if (allow_topo_rebuild) {
            for (auto& kv : scene->sections) {
                auto& sec = kv.second;
                if (sec.blas_needs_build && sec.blas && sec.blas_desc) {
                    dirty_blas.push_back({&sec, total_blas_scratch});
                    total_blas_scratch += sec.blas_scratch_size;
                }
            }
        }
        // Non-blocking async BLAS compaction: drain ready batches whose build command buffer has completed on GPU (~50% VRAM savings!)
        bool compacted_any_blas = false;
        while (!scene->pending_compact_batches.empty()) {
            const auto& front = scene->pending_compact_batches.front();
            if (front.ready_frame_id > completed_frame && cur_frame >= front.ready_frame_id) {
                break;
            }
            PendingCompactBatch batch = std::move(scene->pending_compact_batches.front());
            scene->pending_compact_batches.pop_front();
            if (!batch.sizes_buf || batch.items.empty()) {
                continue;
            }
            const auto* sizes_ptr = static_cast<const uint32_t*>(batch.sizes_buf.contents);
            std::vector<std::pair<id<MTLAccelerationStructure>, id<MTLAccelerationStructure>>> compact_pairs;
            compact_pairs.reserve(batch.items.size());
            for (size_t i = 0; i < batch.items.size(); ++i) {
                const uint32_t csz = sizes_ptr[i];
                if (csz == 0u) continue;
                auto it = scene->sections.find(batch.items[i].key);
                if (it == scene->sections.end()) continue;
                auto& sec = it->second;
                if (sec.blas != batch.items[i].src_blas || sec.blas_needs_build) continue;
                if (static_cast<NSUInteger>(csz) + 256u >= sec.blas.size) continue;
                id<MTLAccelerationStructure> cblas = [scene->device newAccelerationStructureWithSize:csz];
                if (cblas) {
                    compact_pairs.emplace_back(sec.blas, cblas);
                    sec.blas = cblas;
                    compacted_any_blas = true;
                }
            }
            if (!compact_pairs.empty()) {
                id<MTLCommandBuffer> cmd = ensure_cb();
                if (cmd) {
                    id<MTLAccelerationStructureCommandEncoder> cenc = [cmd accelerationStructureCommandEncoder];
                    for (const auto& pair : compact_pairs) {
                        [cenc copyAndCompactAccelerationStructure:pair.first toAccelerationStructure:pair.second];
                    }
                    [cenc endEncoding];
                }
            }
        }
        if (compacted_any_blas) {
            scene->sections_generation += 1u;
        }

        if (!dirty_blas.empty()) {
            const NSUInteger needed_blas_scr = std::max<NSUInteger>(256u, total_blas_scratch);
            if (!scene->blas_scratch_buf || needed_blas_scr > scene->blas_scratch_cap) {
                const NSUInteger alloc_scr = std::max<NSUInteger>(needed_blas_scr, needed_blas_scr + (256u * 1024u));
                scene->blas_scratch_buf = [scene->device newBufferWithLength:alloc_scr
                                                                     options:MTLResourceStorageModePrivate];
                scene->blas_scratch_cap = scene->blas_scratch_buf ? alloc_scr : 0u;
            }
            if (scene->blas_scratch_buf) {
                id<MTLCommandBuffer> cmd = ensure_cb();
                if (cmd) {
                    id<MTLBuffer> compact_sz_buf = [scene->device newBufferWithLength:std::max<NSUInteger>(4u, dirty_blas.size() * sizeof(uint32_t))
                                                                              options:MTLResourceStorageModeShared];
                    PendingCompactBatch batch{};
                    batch.ready_frame_id = cur_frame;
                    batch.sizes_buf = compact_sz_buf;
                    if (compact_sz_buf) {
                        batch.items.reserve(dirty_blas.size());
                    }
                    id<MTLAccelerationStructureCommandEncoder> blas_enc = [cmd accelerationStructureCommandEncoder];
                    for (size_t bi = 0; bi < dirty_blas.size(); ++bi) {
                        const auto& item = dirty_blas[bi];
                        [blas_enc buildAccelerationStructure:item.sec->blas
                                                  descriptor:item.sec->blas_desc
                                               scratchBuffer:scene->blas_scratch_buf
                                         scratchBufferOffset:item.offset];
                        if (compact_sz_buf) {
                            [blas_enc writeCompactedAccelerationStructureSize:item.sec->blas
                                                                     toBuffer:compact_sz_buf
                                                                       offset:bi * sizeof(uint32_t)
                                                                 sizeDataType:MTLDataTypeUInt];
                            batch.items.push_back({SectionKey{item.sec->sec_x, item.sec->sec_y, item.sec->sec_z}, item.sec->blas});
                        }
                        item.sec->blas_needs_build = false;
                        item.sec->blas_desc = nil;
                    }
                    [blas_enc endEncoding];
                    if (compact_sz_buf && !batch.items.empty() && use_active_cb) {
                        scene->pending_compact_batches.push_back(std::move(batch));
                    }
                }
            }
        }

        const bool has_topo_delta =
            (scene->cached_tlas_sections_gen != scene->sections_generation) ||
            !dirty_blas.empty();
        const bool topo_changed = force_initial_topo || (has_topo_delta && topo_cadence_ready);

        if (topo_changed) {
            scene->cached_tlas_entries.clear();
            scene->active_blas_list.clear();
            scene->active_blas_resources.clear();
            scene->cached_blas_array = [NSMutableArray array];
            std::vector<uint32_t> instance_offsets;
            std::vector<uint32_t> flat_meta;
            scene->cached_tlas_entries.reserve(scene->sections.size());
            scene->active_blas_list.reserve(scene->sections.size());
            scene->active_blas_resources.reserve(scene->sections.size());
            instance_offsets.reserve(scene->sections.size());

            for (const auto& kv : scene->sections) {
                const auto& sec = kv.second;
                if (!sec.blas || sec.quad_count == 0 || sec.blas_needs_build) continue;
                [scene->cached_blas_array addObject:sec.blas];
                scene->cached_tlas_entries.push_back({
                    static_cast<float>(sec.sec_x) * 16.0f,
                    static_cast<float>(sec.sec_y) * 16.0f,
                    static_cast<float>(sec.sec_z) * 16.0f,
                    sec.blas
                });
                scene->active_blas_list.push_back(sec.blas);
                scene->active_blas_resources.push_back(sec.blas);
                instance_offsets.push_back(static_cast<uint32_t>(flat_meta.size()));
                flat_meta.insert(flat_meta.end(), sec.packed_meta_cpu.begin(), sec.packed_meta_cpu.end());
            }

            scene->active_instance_count = static_cast<uint32_t>(scene->cached_tlas_entries.size());
            scene->cached_tlas_sections_gen = scene->sections_generation;
            scene->last_tlas_topo_frame = cur_frame;
            scene->has_last_tlas_cam = false;

            if (scene->active_instance_count == 0u) {
                scene->meta_flat_buf = nil;
                scene->instance_meta_offset_buf = nil;
                scene->tlas = nil;
                if (!use_active_cb && cb) {
                    lock.unlock();
                    [cb commit];
                    [cb waitUntilCompleted];
                }
                return true;
            }

            scene->meta_flat_buf = [scene->device newBufferWithBytes:flat_meta.data()
                                                              length:std::max<size_t>(4, flat_meta.size() * sizeof(uint32_t))
                                                             options:MTLResourceStorageModeShared];
            scene->instance_meta_offset_buf = [scene->device newBufferWithBytes:instance_offsets.data()
                                                                         length:std::max<size_t>(4, instance_offsets.size() * sizeof(uint32_t))
                                                                        options:MTLResourceStorageModeShared];
        } else if (scene->active_instance_count == 0u) {
            scene->tlas = nil;
            return true;
        } else {
            if (scene->active_blas_resources.size() != scene->cached_tlas_entries.size()) {
                scene->active_blas_list.clear();
                scene->active_blas_resources.clear();
                scene->active_blas_list.reserve(scene->cached_tlas_entries.size());
                scene->active_blas_resources.reserve(scene->cached_tlas_entries.size());
                for (const auto& e : scene->cached_tlas_entries) {
                    scene->active_blas_list.push_back(e.blas);
                    scene->active_blas_resources.push_back(e.blas);
                }
            }
            const float dcx = cam_x - scene->last_tlas_sync_cam[0];
            const float dcy = cam_y - scene->last_tlas_sync_cam[1];
            const float dcz = cam_z - scene->last_tlas_sync_cam[2];
            if (scene->has_last_tlas_cam && (dcx * dcx + dcy * dcy + dcz * dcz) < 1e-8f && scene->tlas != nil) {
                return true;
            }
        }

        const uint32_t prev_slot = scene->active_tlas_slot;
        uint32_t tslot = UINT32_MAX;
        for (uint32_t i = 0; i < kRtRingSlots; ++i) {
            const uint32_t cand = (prev_slot < kRtRingSlots) ? ((prev_slot + 1u + i) % kRtRingSlots) : i;
            if (cand != prev_slot && scene->tlas_slot_last_frame[cand] <= completed_frame) {
                tslot = cand;
                break;
            }
        }
        if (tslot == UINT32_MAX) {
            tslot = (prev_slot < kRtRingSlots) ? ((prev_slot + 1u) % kRtRingSlots) : 0u;
            for (uint32_t s = 0; s < kRtRingSlots; ++s) {
                if (s != prev_slot && scene->tlas_slot_last_frame[s] < scene->tlas_slot_last_frame[tslot]) {
                    tslot = s;
                }
            }
            const uint64_t wait_f = scene->tlas_slot_last_frame[tslot];
            id<MTLSharedEvent> ev = ctx->frame_event;
            if (ev && wait_f > completed_frame && wait_f < cur_frame) {
                // NEVER hold sections_mutex while blocking on GPU completion
                lock.unlock();
                [ev waitUntilSignaledValue:wait_f timeoutMS:1000];
                lock.lock();
                completed_frame = ev.signaledValue;
                if (scene->active_instance_count == 0u || !scene->cached_blas_array || scene->cached_tlas_entries.empty()) {
                    return true;
                }
            }
        }
        const uint32_t inst_idx = scene->active_instance_count;

        const NSUInteger needed_desc_bytes = std::max<NSUInteger>(
            sizeof(MTLAccelerationStructureInstanceDescriptor),
            static_cast<NSUInteger>(inst_idx) * sizeof(MTLAccelerationStructureInstanceDescriptor)
        );
        if (needed_desc_bytes > scene->instance_desc_ring_cap || !scene->instance_desc_ring[tslot]) {
            const NSUInteger alloc_bytes = std::max<NSUInteger>(needed_desc_bytes, needed_desc_bytes + 64u * sizeof(MTLAccelerationStructureInstanceDescriptor));
            for (uint32_t rs = 0; rs < kRtRingSlots; ++rs) {
                scene->instance_desc_ring[rs] = [scene->device newBufferWithLength:alloc_bytes
                                                                           options:MTLResourceStorageModeShared];
            }
            scene->instance_desc_ring_cap = alloc_bytes;
        }
        id<MTLBuffer> inst_buf = scene->instance_desc_ring[tslot];
        if (!inst_buf) {
            return false;
        }
        auto* dst_inst = static_cast<MTLAccelerationStructureInstanceDescriptor*>(inst_buf.contents);
        for (uint32_t ii = 0; ii < inst_idx; ++ii) {
            const auto& e = scene->cached_tlas_entries[ii];
            MTLAccelerationStructureInstanceDescriptor& d = dst_inst[ii];
            d.transformationMatrix.columns[0] = MTLPackedFloat3Make(1.0f, 0.0f, 0.0f);
            d.transformationMatrix.columns[1] = MTLPackedFloat3Make(0.0f, 1.0f, 0.0f);
            d.transformationMatrix.columns[2] = MTLPackedFloat3Make(0.0f, 0.0f, 1.0f);
            d.transformationMatrix.columns[3] = MTLPackedFloat3Make(e.wx - cam_x, e.wy - cam_y, e.wz - cam_z);
            d.options = MTLAccelerationStructureInstanceOptionOpaque;
            d.mask = 0xFFu;
            d.intersectionFunctionTableOffset = 0u;
            d.accelerationStructureIndex = ii;
        }
        scene->instance_desc_buf = inst_buf;

        MTLInstanceAccelerationStructureDescriptor* tlas_desc =
            [MTLInstanceAccelerationStructureDescriptor descriptor];
        tlas_desc.instancedAccelerationStructures = scene->cached_blas_array;
        tlas_desc.instanceCount = inst_idx;
        tlas_desc.instanceDescriptorBuffer = inst_buf;
        tlas_desc.instanceDescriptorType = MTLAccelerationStructureInstanceDescriptorTypeDefault;
        tlas_desc.usage = MTLAccelerationStructureUsageRefit;

        MTLAccelerationStructureSizes sizes = [scene->device accelerationStructureSizesWithDescriptor:tlas_desc];
        bool tlas_ring_reallocated = false;
        if (sizes.accelerationStructureSize > scene->tlas_ring_size || !scene->tlas_ring[tslot]) {
            const NSUInteger alloc_as = std::max<NSUInteger>(sizes.accelerationStructureSize, sizes.accelerationStructureSize + 128u * 1024u);
            for (uint32_t rs = 0; rs < kRtRingSlots; ++rs) {
                scene->tlas_ring[rs] = [scene->device newAccelerationStructureWithSize:alloc_as];
                scene->tlas_ring_topo_gen[rs] = 0;
            }
            scene->tlas_ring_size = alloc_as;
            tlas_ring_reallocated = true;
        }
#if __MAC_OS_X_VERSION_MAX_ALLOWED >= 150000
        if (@available(macOS 15.0, *)) {
            if ((topo_changed || tlas_ring_reallocated || !scene->blas_residency_set) && scene->queue) {
                if (!scene->blas_residency_set) {
                    MTLResidencySetDescriptor* rsd = [[MTLResidencySetDescriptor alloc] init];
                    rsd.initialCapacity = std::max<NSUInteger>(64u, scene->active_blas_list.size() + kRtRingSlots);
                    NSError* rerr = nil;
                    scene->blas_residency_set = [scene->device newResidencySetWithDescriptor:rsd error:&rerr];
                }
                if (scene->blas_residency_set) {
                    [scene->blas_residency_set removeAllAllocations];
                    for (uint32_t rs = 0; rs < kRtRingSlots; ++rs) {
                        if (scene->tlas_ring[rs]) {
                            [scene->blas_residency_set addAllocation:scene->tlas_ring[rs]];
                        }
                    }
                    for (id<MTLAccelerationStructure> blas : scene->active_blas_list) {
                        if (blas) {
                            [scene->blas_residency_set addAllocation:blas];
                        }
                    }
                    [scene->blas_residency_set commit];
                    [scene->blas_residency_set requestResidency];
                    if (!scene->residency_set_attached) {
                        [scene->queue addResidencySet:scene->blas_residency_set];
                        scene->residency_set_attached = true;
                    }
                }
            }
        }
#endif
        const NSUInteger req_scratch = std::max<NSUInteger>(256u, std::max(sizes.buildScratchBufferSize, sizes.refitScratchBufferSize));
        if (req_scratch > scene->tlas_scratch_cap || !scene->tlas_scratch_buf) {
            const NSUInteger alloc_scr = std::max<NSUInteger>(req_scratch, req_scratch + 64u * 1024u);
            scene->tlas_scratch_buf = [scene->device newBufferWithLength:alloc_scr
                                                                 options:MTLResourceStorageModePrivate];
            scene->tlas_scratch_cap = alloc_scr;
        }
        id<MTLAccelerationStructure> dst_tlas = scene->tlas_ring[tslot];
        if (!dst_tlas || !scene->tlas_scratch_buf) {
            return false;
        }

        const float b_dx = cam_x - scene->last_tlas_build_cam[0];
        const float b_dy = cam_y - scene->last_tlas_build_cam[1];
        const float b_dz = cam_z - scene->last_tlas_build_cam[2];
        const bool can_refit = !topo_changed &&
                               scene->has_last_tlas_cam &&
                               (b_dx * b_dx + b_dy * b_dy + b_dz * b_dz) < (24.0f * 24.0f) &&
                               (prev_slot < kRtRingSlots) &&
                               (prev_slot != tslot) &&
                               (scene->tlas_ring[prev_slot] != nil) &&
                               (scene->tlas_ring_topo_gen[prev_slot] == scene->cached_tlas_sections_gen);

        id<MTLCommandBuffer> tlas_cmd = ensure_cb();
        if (!tlas_cmd) {
            return false;
        }
        id<MTLAccelerationStructureCommandEncoder> as_enc = [tlas_cmd accelerationStructureCommandEncoder];
        if (can_refit) {
            [as_enc refitAccelerationStructure:scene->tlas_ring[prev_slot]
                                      descriptor:tlas_desc
                                     destination:dst_tlas
                                   scratchBuffer:scene->tlas_scratch_buf
                             scratchBufferOffset:0];
        } else {
            [as_enc buildAccelerationStructure:dst_tlas
                                    descriptor:tlas_desc
                                 scratchBuffer:scene->tlas_scratch_buf
                           scratchBufferOffset:0];
            scene->last_tlas_build_cam[0] = cam_x;
            scene->last_tlas_build_cam[1] = cam_y;
            scene->last_tlas_build_cam[2] = cam_z;
        }
        [as_enc endEncoding];

        scene->tlas = dst_tlas;
        scene->active_tlas_slot = tslot;
        scene->tlas_ring_topo_gen[tslot] = scene->cached_tlas_sections_gen;
        scene->tlas_slot_last_frame[tslot] = cur_frame;
        scene->last_tlas_sync_cam[0] = cam_x;
        scene->last_tlas_sync_cam[1] = cam_y;
        scene->last_tlas_sync_cam[2] = cam_z;
        scene->has_last_tlas_cam = true;

        if (!use_active_cb) {
            lock.unlock();
            [tlas_cmd commit];
            [tlas_cmd waitUntilCompleted];
        }
        return true;
    }
}

bool metality_rt_dispatch_gi(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    const float inv_view_proj[16],
    const float cam_pos[3],
    const float sun_dir[3],
    uint32_t width,
    uint32_t height,
    MetalityTextureHandle out_noisy_radiance,
    MetalityTextureHandle out_depth,
    MetalityTextureHandle out_motion,
    MetalityTextureHandle out_diffuse_albedo,
    MetalityTextureHandle out_specular_albedo,
    MetalityTextureHandle out_normal,
    MetalityTextureHandle out_roughness
) {
    if (!ctx || !ctx->active_command_buffer || !scene || !inv_view_proj || !cam_pos || !sun_dir ||
        width == 0 || height == 0) {
        return false;
    }
    @autoreleasepool {
        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
            ctx->active_pass_width = 0;
            ctx->active_pass_height = 0;
        }

        id<MTLTexture> t_rad = lookup_tex(ctx, out_noisy_radiance);
        id<MTLTexture> t_dep = lookup_tex(ctx, out_depth);
        id<MTLTexture> t_mot = lookup_tex(ctx, out_motion);
        id<MTLTexture> t_dif = lookup_tex(ctx, out_diffuse_albedo);
        id<MTLTexture> t_spc = lookup_tex(ctx, out_specular_albedo);
        id<MTLTexture> t_nrm = lookup_tex(ctx, out_normal);
        id<MTLTexture> t_rgh = lookup_tex(ctx, out_roughness);
        if (!t_rad || !t_dep || !t_mot || !t_dif || !t_spc || !t_nrm || !t_rgh) {
            return false;
        }

        std::lock_guard<std::mutex> lock(scene->sections_mutex);

        RtDispatchUniforms u{};
        std::memcpy(u.inv_view_proj, inv_view_proj, sizeof(float) * 16);
        u.cam_pos[0] = cam_pos[0];
        u.cam_pos[1] = cam_pos[1];
        u.cam_pos[2] = cam_pos[2];
        u.cam_pos[3] = static_cast<float>(ctx->current_frame_id & 0xFFFFu);
        u.sun_dir[0] = sun_dir[0];
        u.sun_dir[1] = sun_dir[1];
        u.sun_dir[2] = sun_dir[2];
        u.sun_dir[3] = 0.0f;
        u.voxel_origin[0] = scene->voxel_origin[0];
        u.voxel_origin[1] = scene->voxel_origin[1];
        u.voxel_origin[2] = scene->voxel_origin[2];
        u.voxel_origin[3] = scene->hardware_bvh ? 0 : 1;
        u.width = width;
        u.height = height;
        u.instance_count = scene->active_instance_count;

        id<MTLComputeCommandEncoder> ce = [ctx->active_command_buffer computeCommandEncoder];
        if (!ce) {
            return false;
        }

        if (scene->hardware_bvh) {
            [ce setComputePipelineState:scene->hw_gi_pso];
            [ce setBytes:&u length:sizeof(u) atIndex:0];
            [ce setAccelerationStructure:scene->tlas atBufferIndex:1];
            [ce setBuffer:scene->meta_flat_buf offset:0 atIndex:2];
            [ce setBuffer:scene->instance_meta_offset_buf offset:0 atIndex:3];
            if (scene->tlas) {
                [ce useResource:scene->tlas usage:MTLResourceUsageRead];
            }
            if (!scene->active_blas_resources.empty()) {
                [ce useResources:scene->active_blas_resources.data()
                           count:scene->active_blas_resources.size()
                           usage:MTLResourceUsageRead];
            }
        } else {
            [ce setComputePipelineState:scene->dda_gi_pso];
            [ce setBytes:&u length:sizeof(u) atIndex:0];
            [ce setBuffer:scene->voxel_grid_buf offset:0 atIndex:1];
        }

        [ce setTexture:t_rad atIndex:0];
        [ce setTexture:t_dep atIndex:1];
        [ce setTexture:t_mot atIndex:2];
        [ce setTexture:t_dif atIndex:3];
        [ce setTexture:t_spc atIndex:4];
        [ce setTexture:t_nrm atIndex:5];
        [ce setTexture:t_rgh atIndex:6];

        MTLSize grid = MTLSizeMake(width, height, 1);
        MTLSize tg = MTLSizeMake(8, 8, 1);
        [ce dispatchThreads:grid threadsPerThreadgroup:tg];
        [ce endEncoding];
        return true;
    }
}

} // extern "C"

namespace metality {

static id<MTLBuffer> get_shared_dummy_buffer(id<MTLDevice> dev) {
    static id<MTLBuffer> shared_dummy_buf = nil;
    static std::mutex dummy_mutex;
    std::lock_guard<std::mutex> dl(dummy_mutex);
    if (!shared_dummy_buf && dev) {
        shared_dummy_buf = [dev newBufferWithLength:256 options:MTLResourceStorageModeShared];
        std::memset(shared_dummy_buf.contents, 0, 256);
    }
    return shared_dummy_buf;
}

RtSceneCustomBindResult query_rt_scene_state(MetalityRtSceneHandle scene) {
    RtSceneCustomBindResult res{};
    if (!scene) {
        return res;
    }
    std::lock_guard<std::mutex> lock(scene->sections_mutex);
    res.hardware_active = scene->hardware_bvh && (scene->tlas != nil) && (scene->active_instance_count > 0u);
    res.instance_count = scene->active_instance_count;
    res.voxel_origin[0] = scene->voxel_origin[0];
    res.voxel_origin[1] = scene->voxel_origin[1];
    res.voxel_origin[2] = scene->voxel_origin[2];
    res.voxel_valid = (scene->voxel_grid_buf != nil) && !scene->sections.empty();
    res.voxel_flags = res.voxel_valid ? (scene->voxel_flags != 0u ? scene->voxel_flags : 1u) : 0u;
    res.emissive_light_count = scene->active_emissive_light_count;
    return res;
}

id<MTLBuffer> get_rt_emissive_light_buffer(id<MTLDevice> dev, MetalityRtSceneHandle scene, uint32_t* out_light_count) {
    if (out_light_count) *out_light_count = 0u;
    id<MTLBuffer> dummy = get_shared_dummy_buffer(dev);
    if (!scene) {
        return dummy;
    }
    std::lock_guard<std::mutex> lock(scene->sections_mutex);
    if (out_light_count) *out_light_count = scene->active_emissive_light_count;
    return scene->emissive_light_buf ? scene->emissive_light_buf : dummy;
}

RtSceneCustomBindResult bind_rt_scene_for_custom_compute(
    id<MTLDevice> dev,
    MetalityRtSceneHandle scene,
    id<MTLComputeCommandEncoder> ce,
    uint32_t tlas_buf_slot,
    uint32_t meta_buf_slot,
    uint32_t offsets_buf_slot,
    uint32_t voxel_buf_slot,
    uint32_t emissive_buf_slot,
    uint32_t ripple_buf_slot,
    uint32_t entity_shadow_buf_slot
) {
    RtSceneCustomBindResult res{};
    if (!dev || !ce) {
        return res;
    }
    id<MTLBuffer> dummy = get_shared_dummy_buffer(dev);

    if (!scene) {
        [ce setAccelerationStructure:nil atBufferIndex:tlas_buf_slot];
        [ce setBuffer:dummy offset:0 atIndex:meta_buf_slot];
        [ce setBuffer:dummy offset:0 atIndex:offsets_buf_slot];
        [ce setBuffer:dummy offset:0 atIndex:voxel_buf_slot];
        [ce setBuffer:dummy offset:0 atIndex:emissive_buf_slot];
        [ce setBuffer:dummy offset:0 atIndex:ripple_buf_slot];
        [ce setBuffer:dummy offset:0 atIndex:entity_shadow_buf_slot];
        return res;
    }

    std::lock_guard<std::mutex> lock(scene->sections_mutex);
    if (!scene->fallback_zero_buf) {
        scene->fallback_zero_buf = dummy;
    }

    res.hardware_active = scene->hardware_bvh && (scene->tlas != nil) && (scene->active_instance_count > 0u);
    res.instance_count = scene->active_instance_count;
    res.voxel_origin[0] = scene->voxel_origin[0];
    res.voxel_origin[1] = scene->voxel_origin[1];
    res.voxel_origin[2] = scene->voxel_origin[2];
    res.voxel_valid = (scene->voxel_grid_buf != nil) && !scene->sections.empty();
    res.voxel_flags = res.voxel_valid ? (scene->voxel_flags != 0u ? scene->voxel_flags : 1u) : 0u;
    res.emissive_light_count = scene->active_emissive_light_count;

    if (res.hardware_active) {
        [ce setAccelerationStructure:scene->tlas atBufferIndex:tlas_buf_slot];
        [ce useResource:scene->tlas usage:MTLResourceUsageRead];
        // When macOS 15+ MTLResidencySet is attached to command_queue, all active BLASes are already resident (6.5x-7.7x faster CPU encoding!)
        if (!scene->residency_set_attached && !scene->active_blas_resources.empty()) {
            [ce useResources:scene->active_blas_resources.data()
                       count:scene->active_blas_resources.size()
                       usage:MTLResourceUsageRead];
        }
    } else {
        [ce setAccelerationStructure:nil atBufferIndex:tlas_buf_slot];
    }
    [ce setBuffer:(scene->meta_flat_buf ? scene->meta_flat_buf : scene->fallback_zero_buf) offset:0 atIndex:meta_buf_slot];
    [ce setBuffer:(scene->instance_meta_offset_buf ? scene->instance_meta_offset_buf : scene->fallback_zero_buf) offset:0 atIndex:offsets_buf_slot];
    [ce setBuffer:(scene->voxel_grid_buf ? scene->voxel_grid_buf : scene->fallback_zero_buf) offset:0 atIndex:voxel_buf_slot];
    [ce setBuffer:(scene->emissive_light_buf ? scene->emissive_light_buf : scene->fallback_zero_buf) offset:0 atIndex:emissive_buf_slot];
    [ce setBuffer:(scene->water_ripple_buf ? scene->water_ripple_buf : scene->fallback_zero_buf) offset:0 atIndex:ripple_buf_slot];
    [ce setBuffer:(scene->entity_shadow_buf ? scene->entity_shadow_buf : scene->fallback_zero_buf) offset:0 atIndex:entity_shadow_buf_slot];
    return res;
}

void bind_rt_scene_for_custom_fragment(
    id<MTLDevice> dev,
    MetalityRtSceneHandle scene,
    id<MTLRenderCommandEncoder> enc,
    uint32_t voxel_buf_slot,
    uint32_t emissive_buf_slot,
    uint32_t ripple_buf_slot,
    uint32_t shadow_buf_slot,
    uint32_t entity_shadow_buf_slot
) {
    if (!dev || !enc) {
        return;
    }
    id<MTLBuffer> dummy = get_shared_dummy_buffer(dev);
    if (!scene) {
        [enc setFragmentBuffer:dummy offset:0 atIndex:voxel_buf_slot];
        [enc setFragmentBuffer:dummy offset:0 atIndex:emissive_buf_slot];
        [enc setFragmentBuffer:dummy offset:0 atIndex:ripple_buf_slot];
        [enc setFragmentBuffer:dummy offset:0 atIndex:shadow_buf_slot];
        [enc setFragmentBuffer:dummy offset:0 atIndex:entity_shadow_buf_slot];
        return;
    }
    std::lock_guard<std::mutex> lock(scene->sections_mutex);
    if (!scene->fallback_zero_buf) {
        scene->fallback_zero_buf = dummy;
    }
    [enc setFragmentBuffer:(scene->voxel_grid_buf ? scene->voxel_grid_buf : scene->fallback_zero_buf) offset:0 atIndex:voxel_buf_slot];
    [enc setFragmentBuffer:(scene->emissive_light_buf ? scene->emissive_light_buf : scene->fallback_zero_buf) offset:0 atIndex:emissive_buf_slot];
    [enc setFragmentBuffer:(scene->water_ripple_buf ? scene->water_ripple_buf : scene->fallback_zero_buf) offset:0 atIndex:ripple_buf_slot];
    [enc setFragmentBuffer:(scene->shadow_uniform_buf ? scene->shadow_uniform_buf : scene->fallback_zero_buf) offset:0 atIndex:shadow_buf_slot];
    [enc setFragmentBuffer:(scene->entity_shadow_buf ? scene->entity_shadow_buf : scene->fallback_zero_buf) offset:0 atIndex:entity_shadow_buf_slot];
}

RasterShadowRenderResult render_raster_shadow_map(
    MetalityContext* ctx,
    MetalityRtSceneHandle scene,
    const MetalityWorldFrameUniforms* frame_uniforms,
    uint32_t cascade_resolution,
    uint32_t cascade_count
) {
    RasterShadowRenderResult res{};
    if (!ctx || !ctx->device || !ctx->active_command_buffer || !scene || !frame_uniforms) {
        return res;
    }
    @autoreleasepool {
        std::lock_guard<std::mutex> lock(scene->sections_mutex);
        if (!scene->fallback_zero_buf) {
            scene->fallback_zero_buf = get_shared_dummy_buffer(ctx->device);
        }

        const uint32_t res_px = std::clamp(cascade_resolution, 512u, 2048u);
        const uint32_t ccount = std::clamp(cascade_count, 1u, 3u);
        const uint32_t dslot = static_cast<uint32_t>(ctx->current_frame_id % kRtRingSlots);
        scene->shadow_uniform_buf = scene->shadow_uniform_ring[dslot] ? scene->shadow_uniform_ring[dslot] : scene->fallback_zero_buf;
        res.shadow_uniform_buf = scene->shadow_uniform_buf;

        const bool has_shadow_dim = (frame_uniforms->dimension_id != -1);
        const bool has_geometry = !scene->sections.empty() && (scene->dusk_shadow_chunk_pso != nil);
        if (!has_shadow_dim || !has_geometry) {
            if (scene->shadow_uniform_buf) {
                std::memset(scene->shadow_uniform_buf.contents, 0, sizeof(DuskShadowUniforms));
            }
            if (scene->dusk_shadow_fallback_tex) {
                MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
                rpd.depthAttachment.texture = scene->dusk_shadow_fallback_tex;
                rpd.depthAttachment.loadAction = MTLLoadActionClear;
                rpd.depthAttachment.storeAction = MTLStoreActionStore;
                rpd.depthAttachment.clearDepth = 1.0;
                id<MTLRenderCommandEncoder> enc = [ctx->active_command_buffer renderCommandEncoderWithDescriptor:rpd];
                [enc endEncoding];
            }
            res.shadow_atlas_tex = scene->dusk_shadow_fallback_tex;
            res.valid = false;
            return res;
        }

        const bool need_atlas_alloc = !scene->dusk_shadow_atlas_tex ||
                                      scene->dusk_shadow_res != res_px ||
                                      scene->dusk_shadow_atlas_tex.width != static_cast<NSUInteger>(res_px * ccount);
        if (need_atlas_alloc) {
            MTLTextureDescriptor* td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth16Unorm
                                                                                          width:res_px * ccount
                                                                                         height:res_px
                                                                                      mipmapped:NO];
            td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
            td.storageMode = MTLStorageModePrivate;
            scene->dusk_shadow_atlas_tex = [ctx->device newTextureWithDescriptor:td];
            scene->dusk_shadow_atlas_tex.label = (ccount > 2u)
                ? @"MetalityDuskShadowAtlas3x"
                : ((ccount > 1u) ? @"MetalityDuskShadowAtlas2x" : @"MetalityDuskShadowAtlas1x");
            scene->dusk_shadow_res = res_px;
            scene->c1_cache_valid = false;
            scene->c2_cache_valid = false;
        }
        res.shadow_atlas_tex = scene->dusk_shadow_atlas_tex;
        if (!res.shadow_atlas_tex) {
            res.shadow_atlas_tex = scene->dusk_shadow_fallback_tex;
            return res;
        }

        float lx = 0.35f, ly = 0.72f, lz = -0.60f;
        if (frame_uniforms->dimension_id == 1) {
            lx = -0.56f; ly = 0.34f; lz = -0.75f;
        } else {
            const bool use_moon = (frame_uniforms->sun_world_dir[1] < -0.05f);
            const float* src_dir = use_moon ? frame_uniforms->moon_world_dir : frame_uniforms->sun_world_dir;
            lx = src_dir[0];
            ly = std::max(0.10f, src_dir[1]);
            lz = src_dir[2];
        }
        const float llen = std::sqrt(lx * lx + ly * ly + lz * lz);
        if (llen > 1e-5f) {
            lx /= llen; ly /= llen; lz /= llen;
        } else {
            lx = 0.0f; ly = 1.0f; lz = 0.0f;
        }

        // Right-handed light orthonormal basis (right, up, to_light)
        const float ref_y = (std::fabs(ly) < 0.96f) ? 1.0f : 0.0f;
        const float ref_z = (std::fabs(ly) < 0.96f) ? 0.0f : 1.0f;
        float rx = ref_y * lz - ref_z * ly;
        float ry = ref_z * lx;
        float rz = -ref_y * lx;
        const float rlen = std::sqrt(rx * rx + ry * ry + rz * rz);
        rx /= std::max(rlen, 1e-5f); ry /= std::max(rlen, 1e-5f); rz /= std::max(rlen, 1e-5f);
        const float ux = ly * rz - lz * ry;
        const float uy = lz * rx - lx * rz;
        const float uz = lx * ry - ly * rx;

        const float cam_x = frame_uniforms->cam_world_pos[0];
        const float cam_y = frame_uniforms->cam_world_pos[1];
        const float cam_z = frame_uniforms->cam_world_pos[2];
        const float cam_u = cam_x * rx + cam_y * ry + cam_z * rz;
        const float cam_v = cam_x * ux + cam_y * uy + cam_z * uz;

        // Tier-matched cascade half-extents:
        // tier 0: 768px,  2 cascades (36m / 152m)
        // tier 1: 1024px, 2 cascades (38m / 176m)
        // tier 2: 1280px, 3 cascades (38m / 116m / 248m)
        // tier 3: 1792px, 3 cascades (42m / 128m / 280m)
        float c0_half_m = 38.0f;
        float c1_half_m = 116.0f;
        float c2_half_m = 248.0f;
        if (ccount <= 2u) {
            if (res_px <= 800u) {
                c0_half_m = 36.0f;
                c1_half_m = 152.0f;
            } else {
                c0_half_m = 38.0f;
                c1_half_m = 176.0f;
            }
            c2_half_m = 0.0f;
        } else {
            if (res_px <= 1400u) {
                c0_half_m = 38.0f;
                c1_half_m = 116.0f;
                c2_half_m = 248.0f;
            } else {
                c0_half_m = 42.0f;
                c1_half_m = 128.0f;
                c2_half_m = 280.0f;
            }
        }
        constexpr float kC0ZHalfM = 160.0f;
        constexpr float kC1ZHalfM = 240.0f;
        constexpr float kC2ZHalfM = 320.0f;

        auto build_cascade_rows = [&](float half_m, float z_half_m, RtVec4 out_rows[4], float* out_snap_u, float* out_snap_v) {
            const float texel_w = (2.0f * half_m) / static_cast<float>(res_px);
            const float snap_u = std::floor(cam_u / texel_w) * texel_w;
            const float snap_v = std::floor(cam_v / texel_w) * texel_w;
            const float inv_h = 1.0f / half_m;
            const float inv_zh = 1.0f / (2.0f * z_half_m);
            out_rows[0] = {rx * inv_h, ry * inv_h, rz * inv_h, (cam_u - snap_u) * inv_h};
            out_rows[1] = {ux * inv_h, uy * inv_h, uz * inv_h, (cam_v - snap_v) * inv_h};
            out_rows[2] = {-lx * inv_zh, -ly * inv_zh, -lz * inv_zh, 0.5f};
            out_rows[3] = {0.0f, 0.0f, 0.0f, 1.0f};
            if (out_snap_u) *out_snap_u = snap_u;
            if (out_snap_v) *out_snap_v = snap_v;
        };

        const float sun_dot_c1 = lx * scene->c1_cache_light_dir[0] + ly * scene->c1_cache_light_dir[1] + lz * scene->c1_cache_light_dir[2];
        const float cdx = cam_x - scene->c1_cache_cam[0];
        const float cdy = cam_y - scene->c1_cache_cam[1];
        const float cdz = cam_z - scene->c1_cache_cam[2];
        const float cam_move_sq = cdx * cdx + cdy * cdy + cdz * cdz;
        const bool small_test_scene = (scene->sections.size() <= 32u);
        const bool redraw_c1 = (ccount > 1u) && (
                               !scene->c1_cache_valid ||
                               (ctx->current_frame_id < scene->c1_cache_frame) ||
                               ((ctx->current_frame_id % 6u) == 0u) ||
                               ((ctx->current_frame_id - scene->c1_cache_frame) >= 6u) ||
                               (sun_dot_c1 < 0.9999f) ||
                               (cam_move_sq > 16.0f) ||
                               (small_test_scene && scene->sections_generation != scene->c1_cache_sections_gen));

        const float sun_dot_c2 = lx * scene->c2_cache_light_dir[0] + ly * scene->c2_cache_light_dir[1] + lz * scene->c2_cache_light_dir[2];
        const float c2dx = cam_x - scene->c2_cache_cam[0];
        const float c2dy = cam_y - scene->c2_cache_cam[1];
        const float c2dz = cam_z - scene->c2_cache_cam[2];
        const float cam_move_sq_c2 = c2dx * c2dx + c2dy * c2dy + c2dz * c2dz;
        const bool redraw_c2 = (ccount > 2u) && (
                               !scene->c2_cache_valid ||
                               (ctx->current_frame_id < scene->c2_cache_frame) ||
                               ((ctx->current_frame_id % 12u) == 4u) ||
                               ((ctx->current_frame_id - scene->c2_cache_frame) >= 12u) ||
                               (sun_dot_c2 < 0.9998f) ||
                               (cam_move_sq_c2 > 64.0f) ||
                               (small_test_scene && scene->sections_generation != scene->c2_cache_sections_gen));

        DuskShadowUniforms su{};
        build_cascade_rows(c0_half_m, kC0ZHalfM, su.c0_rows, nullptr, nullptr);
        if (ccount > 1u) {
            if (redraw_c1) {
                float c1_su = 0.0f, c1_sv = 0.0f;
                build_cascade_rows(c1_half_m, kC1ZHalfM, su.c1_rows, &c1_su, &c1_sv);
                scene->c1_cache_light_dir[0] = lx; scene->c1_cache_light_dir[1] = ly; scene->c1_cache_light_dir[2] = lz;
                scene->c1_cache_r[0] = rx; scene->c1_cache_r[1] = ry; scene->c1_cache_r[2] = rz;
                scene->c1_cache_u[0] = ux; scene->c1_cache_u[1] = uy; scene->c1_cache_u[2] = uz;
                scene->c1_cache_cam[0] = cam_x; scene->c1_cache_cam[1] = cam_y; scene->c1_cache_cam[2] = cam_z;
                scene->c1_cache_snap_u = c1_su;
                scene->c1_cache_snap_v = c1_sv;
                scene->c1_cache_frame = ctx->current_frame_id;
                scene->c1_cache_sections_gen = scene->sections_generation;
                scene->c1_cache_valid = true;
            } else {
                const float inv_h1 = 1.0f / c1_half_m;
                const float inv_zh1 = 1.0f / (2.0f * kC1ZHalfM);
                const float crx = scene->c1_cache_r[0], cry = scene->c1_cache_r[1], crz = scene->c1_cache_r[2];
                const float cux = scene->c1_cache_u[0], cuy = scene->c1_cache_u[1], cuz = scene->c1_cache_u[2];
                const float clx = scene->c1_cache_light_dir[0], cly = scene->c1_cache_light_dir[1], clz = scene->c1_cache_light_dir[2];
                const float cur_u_c1 = cam_x * crx + cam_y * cry + cam_z * crz;
                const float cur_v_c1 = cam_x * cux + cam_y * cuy + cam_z * cuz;
                const float delta_l = cdx * clx + cdy * cly + cdz * clz;
                su.c1_rows[0] = {crx * inv_h1, cry * inv_h1, crz * inv_h1, (cur_u_c1 - scene->c1_cache_snap_u) * inv_h1};
                su.c1_rows[1] = {cux * inv_h1, cuy * inv_h1, cuz * inv_h1, (cur_v_c1 - scene->c1_cache_snap_v) * inv_h1};
                su.c1_rows[2] = {-clx * inv_zh1, -cly * inv_zh1, -clz * inv_zh1, 0.5f - delta_l * inv_zh1};
                su.c1_rows[3] = {0.0f, 0.0f, 0.0f, 1.0f};
            }
        }
        if (ccount > 2u) {
            if (redraw_c2) {
                float c2_su = 0.0f, c2_sv = 0.0f;
                build_cascade_rows(c2_half_m, kC2ZHalfM, su.c2_rows, &c2_su, &c2_sv);
                scene->c2_cache_light_dir[0] = lx; scene->c2_cache_light_dir[1] = ly; scene->c2_cache_light_dir[2] = lz;
                scene->c2_cache_r[0] = rx; scene->c2_cache_r[1] = ry; scene->c2_cache_r[2] = rz;
                scene->c2_cache_u[0] = ux; scene->c2_cache_u[1] = uy; scene->c2_cache_u[2] = uz;
                scene->c2_cache_cam[0] = cam_x; scene->c2_cache_cam[1] = cam_y; scene->c2_cache_cam[2] = cam_z;
                scene->c2_cache_snap_u = c2_su;
                scene->c2_cache_snap_v = c2_sv;
                scene->c2_cache_frame = ctx->current_frame_id;
                scene->c2_cache_sections_gen = scene->sections_generation;
                scene->c2_cache_valid = true;
            } else {
                const float inv_h2 = 1.0f / c2_half_m;
                const float inv_zh2 = 1.0f / (2.0f * kC2ZHalfM);
                const float crx = scene->c2_cache_r[0], cry = scene->c2_cache_r[1], crz = scene->c2_cache_r[2];
                const float cux = scene->c2_cache_u[0], cuy = scene->c2_cache_u[1], cuz = scene->c2_cache_u[2];
                const float clx = scene->c2_cache_light_dir[0], cly = scene->c2_cache_light_dir[1], clz = scene->c2_cache_light_dir[2];
                const float cur_u_c2 = cam_x * crx + cam_y * cry + cam_z * crz;
                const float cur_v_c2 = cam_x * cux + cam_y * cuy + cam_z * cuz;
                const float delta_l = c2dx * clx + c2dy * cly + c2dz * clz;
                su.c2_rows[0] = {crx * inv_h2, cry * inv_h2, crz * inv_h2, (cur_u_c2 - scene->c2_cache_snap_u) * inv_h2};
                su.c2_rows[1] = {cux * inv_h2, cuy * inv_h2, cuz * inv_h2, (cur_v_c2 - scene->c2_cache_snap_v) * inv_h2};
                su.c2_rows[2] = {-clx * inv_zh2, -cly * inv_zh2, -clz * inv_zh2, 0.5f - delta_l * inv_zh2};
                su.c2_rows[3] = {0.0f, 0.0f, 0.0f, 1.0f};
            }
        }
        su.cascade_params = {c0_half_m, (ccount > 1u) ? c1_half_m : 0.0f, 1.0f / static_cast<float>(res_px), static_cast<float>(ccount)};
        su.cascade_params2 = {(ccount > 2u) ? c2_half_m : 0.0f, kC0ZHalfM, kC1ZHalfM, kC2ZHalfM};
        std::memcpy(scene->shadow_uniform_buf.contents, &su, sizeof(su));

        // Extract camera forward vector from inv_view_rot (-column 2) for safe backward shadow culling
        const float fdx = -frame_uniforms->inv_view_rot[8];
        const float fdy = -frame_uniforms->inv_view_rot[9];
        const float fdz = -frame_uniforms->inv_view_rot[10];
        const float f_len = std::sqrt(fdx * fdx + fdy * fdy + fdz * fdz);
        const bool have_cam_fwd = (f_len > 0.5f && f_len < 1.5f);
        const float fwd_x = have_cam_fwd ? (fdx / f_len) : 0.0f;
        const float fwd_y = have_cam_fwd ? (fdy / f_len) : 0.0f;
        const float fwd_z = have_cam_fwd ? (fdz / f_len) : 0.0f;
        const float l_dot_fwd = lx * fwd_x + ly * fwd_y + lz * fwd_z;

        // In-place staggered atlas reuse: only clear entire atlas when all active cascades are being redrawn (or freshly allocated);
        // otherwise Load existing C1/C2 tiles in-place and clear ONLY dirty tile(s) via 1-triangle scissor clear (zero C1/C2 blit + -12.8 MB VRAM!)
        const bool full_clear = need_atlas_alloc || !scene->dusk_shadow_clear_pso || !scene->dusk_shadow_clear_dss ||
                                ((ccount <= 1u || redraw_c1) && (ccount <= 2u || redraw_c2));

        MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
        rpd.depthAttachment.texture = scene->dusk_shadow_atlas_tex;
        rpd.depthAttachment.loadAction = full_clear ? MTLLoadActionClear : MTLLoadActionLoad;
        rpd.depthAttachment.storeAction = MTLStoreActionStore;
        rpd.depthAttachment.clearDepth = 1.0;

        id<MTLRenderCommandEncoder> enc = [ctx->active_command_buffer renderCommandEncoderWithDescriptor:rpd];
        if (!enc) {
            return res;
        }
        enc.label = @"Metality_Dusk_ShadowMap";
        [enc setFrontFacingWinding:MTLWindingCounterClockwise];
        [enc setDepthClipMode:MTLDepthClipModeClamp];

        const uint32_t entity_count = std::min<uint32_t>(kMaxEntityShadowCasters, static_cast<uint32_t>(scene->entity_shadows.size()));

        auto encode_cascade = [&](uint32_t cascade_idx, const RtVec4 rows[4], float half_m, float z_half_m, bool draw_entities) {
            const double vp_x = static_cast<double>(cascade_idx * res_px);
            [enc setViewport:MTLViewport{vp_x, 0.0, static_cast<double>(res_px), static_cast<double>(res_px), 0.0, 1.0}];
            [enc setScissorRect:MTLScissorRect{cascade_idx * res_px, 0u, res_px, res_px}];

            if (!full_clear) {
                [enc setCullMode:MTLCullModeNone];
                [enc setDepthStencilState:scene->dusk_shadow_clear_dss];
                [enc setRenderPipelineState:scene->dusk_shadow_clear_pso];
                [enc setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
                [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3u];
            }

            [enc setDepthStencilState:scene->dusk_shadow_dss];
            [enc setCullMode:MTLCullModeNone];
            [enc setRenderPipelineState:scene->dusk_shadow_chunk_pso];
            [enc setDepthBias:1.8f slopeScale:2.4f clamp:0.0f];

            // Upload cascade 4x4 matrix ONCE per cascade (instead of per-section 64B copy) & skip unused meta_buffer bind!
            DuskCascadeMatPush cm{};
            std::memcpy(cm.rows, rows, sizeof(cm.rows));
            [enc setVertexBytes:&cm length:sizeof(cm) atIndex:1];

            const float cull_rad = half_m + 15.0f;
            const float z_cull_rad = z_half_m + 16.0f;
            const float back_cull_d = (cascade_idx == 0u) ? -24.0f : ((cascade_idx == 1u) ? -48.0f : -1e6f);
            for (const auto& kv : scene->sections) {
                const auto& sec = kv.second;
                if (sec.quad_count == 0u || !sec.vertex_buffer) {
                    continue;
                }
                const float rx_off = static_cast<float>(sec.sec_x) * 16.0f - cam_x;
                const float ry_off = static_cast<float>(sec.sec_y) * 16.0f - cam_y;
                const float rz_off = static_cast<float>(sec.sec_z) * 16.0f - cam_z;
                const float cx_r = rx_off + 8.0f;
                const float cy_r = ry_off + 8.0f;
                const float cz_r = rz_off + 8.0f;
                if (cascade_idx == 0u && have_cam_fwd && l_dot_fwd >= 0.05f) {
                    const float c_fwd = cx_r * fwd_x + cy_r * fwd_y + cz_r * fwd_z;
                    if (c_fwd < back_cull_d) {
                        continue;
                    }
                }
                const float su_c = cx_r * rx + cy_r * ry + cz_r * rz;
                const float sv_c = cx_r * ux + cy_r * uy + cz_r * uz;
                if (std::fabs(su_c) > cull_rad || std::fabs(sv_c) > cull_rad) {
                    continue;
                }
                const float sz_c = -(cx_r * lx + cy_r * ly + cz_r * lz);
                // Cull sections behind the cascade far plane (+sz_c) or extreme far-sunward distance (>384m);
                // near-sun overhead casters (up to 384m, full world height) clamp to z=0.0 via MTLDepthClipModeClamp.
                if (sz_c > z_cull_rad || sz_c < -384.0f) {
                    continue;
                }
                const RtVec4 sec_org{rx_off, ry_off, rz_off, 0.0f};
                [enc setVertexBuffer:sec.vertex_buffer offset:0 atIndex:0];
                [enc setVertexBytes:&sec_org length:sizeof(sec_org) atIndex:2];
                [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:sec.quad_count * 6u];
            }

            if (draw_entities && entity_count > 0u && scene->dusk_shadow_entity_pso && scene->entity_shadow_buf) {
                [enc setRenderPipelineState:scene->dusk_shadow_entity_pso];
                [enc setDepthBias:4.5f slopeScale:2.5f clamp:0.0f];
                DuskEntityShadowPush ep{};
                std::memcpy(ep.rows, rows, sizeof(ep.rows));
                ep.params = {static_cast<float>(entity_count), 0.0f, 0.0f, 0.0f};
                [enc setVertexBuffer:scene->entity_shadow_buf offset:0 atIndex:0];
                [enc setVertexBytes:&ep length:sizeof(ep) atIndex:1];
                [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:36u instanceCount:entity_count];
            }
        };

        encode_cascade(0u, su.c0_rows, c0_half_m, kC0ZHalfM, true);
        if (ccount > 1u && redraw_c1) {
            encode_cascade(1u, su.c1_rows, c1_half_m, kC1ZHalfM, true);
        }
        if (ccount > 2u && redraw_c2) {
            encode_cascade(2u, su.c2_rows, c2_half_m, kC2ZHalfM, false);
        }
        [enc endEncoding];

        res.valid = true;
        return res;
    }
}

} // namespace metality

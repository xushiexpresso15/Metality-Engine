#import "metality/metal_context.hpp"
#import <JavaScriptCore/JavaScriptCore.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

std::string read_text_file(const std::filesystem::path& path) {
    std::ifstream ifs(path, std::ios::binary);
    if (!ifs) {
        return {};
    }
    std::ostringstream oss;
    oss << ifs.rdbuf();
    return oss.str();
}

std::string expand_slang_includes(
    const std::filesystem::path& file_path,
    const std::filesystem::path& pack_root,
    int depth,
    std::vector<std::filesystem::path>& active_stack
) {
    if (depth > 24) {
        return {};
    }
    std::error_code ec;
    std::filesystem::path canon = std::filesystem::weakly_canonical(file_path, ec);
    for (const auto& prev : active_stack) {
        if (prev == canon) {
            return {};
        }
    }
    std::string raw = read_text_file(file_path);
    if (raw.empty()) {
        return {};
    }
    active_stack.push_back(canon);

    std::istringstream iss(raw);
    std::ostringstream out;
    std::string line;
    while (std::getline(iss, line)) {
        std::string trimmed = line;
        size_t first = trimmed.find_first_not_of(" \t\r");
        if (first != std::string::npos) {
            trimmed = trimmed.substr(first);
        }
        if (depth > 0 && trimmed.rfind("module ", 0) == 0) {
            continue;
        }
        if (trimmed.rfind("#include", 0) == 0) {
            size_t q1 = trimmed.find('"');
            size_t q2 = (q1 != std::string::npos) ? trimmed.find('"', q1 + 1) : std::string::npos;
            if (q1 != std::string::npos && q2 != std::string::npos && q2 > q1 + 1) {
                std::string rel = trimmed.substr(q1 + 1, q2 - q1 - 1);
                if (!rel.empty() && rel[0] == '/') {
                    rel = rel.substr(1);
                }
                std::filesystem::path cand = file_path.parent_path() / rel;
                if (!std::filesystem::exists(cand, ec)) {
                    cand = pack_root / "shaders" / rel;
                }
                if (!std::filesystem::exists(cand, ec)) {
                    cand = pack_root / rel;
                }
                std::string inc = expand_slang_includes(cand, pack_root, depth + 1, active_stack);
                if (!inc.empty()) {
                    out << "\n// begin include " << rel << "\n" << inc << "\n// end include " << rel << "\n";
                    continue;
                }
            }
        }
        out << line << "\n";
    }
    active_stack.pop_back();
    return out.str();
}

std::string load_slang_with_includes(const std::filesystem::path& file_path, const std::filesystem::path& pack_root) {
    std::vector<std::filesystem::path> stack;
    return expand_slang_includes(file_path, pack_root, 0, stack);
}

void set_err(char* err_buf, uint32_t err_cap, const std::string& msg) {
    if (!err_buf || err_cap == 0) {
        return;
    }
    std::strncpy(err_buf, msg.c_str(), err_cap - 1);
    err_buf[err_cap - 1] = '\0';
}

bool is_id_char(char c) {
    return std::isalnum(static_cast<unsigned char>(c)) || c == '_' || c == '$';
}

// 去除 TS 註解（完整保留字串與範本字串內文），避免後續語法掃描誤判註解中的冒號
std::string strip_js_comments_preserving_strings(const std::string& src) {
    std::string out;
    out.reserve(src.size());
    size_t i = 0;
    const size_t n = src.size();
    while (i < n) {
        char c = src[i];
        if (c == '"' || c == '\'' || c == '`') {
            char quote = c;
            out.push_back(c);
            ++i;
            while (i < n) {
                char sc = src[i];
                out.push_back(sc);
                ++i;
                if (sc == '\\' && i < n) {
                    out.push_back(src[i++]);
                    continue;
                }
                if (sc == quote) {
                    break;
                }
            }
            continue;
        }
        if (c == '/' && i + 1 < n && src[i + 1] == '/') {
            while (i < n && src[i] != '\n') ++i;
            continue;
        }
        if (c == '/' && i + 1 < n && src[i + 1] == '*') {
            i += 2;
            while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) ++i;
            if (i + 1 < n) i += 2;
            continue;
        }
        out.push_back(c);
        ++i;
    }
    return out;
}

bool match_kw(const std::string& s, size_t pos, const char* kw) {
    const size_t len = std::strlen(kw);
    if (pos + len > s.size() || s.compare(pos, len, kw) != 0) {
        return false;
    }
    const bool left_ok = (pos == 0) || !is_id_char(s[pos - 1]);
    const bool right_ok = (pos + len >= s.size()) || !is_id_char(s[pos + len]);
    return left_ok && right_ok;
}

// 跳過型別表達式（支援泛型 <A, B>、Tuple [number, number]、聯集 A | B、函式型別 (x) => Y）
size_t skip_type_expr(const std::string& s, size_t pos, bool stop_at_equals, bool stop_at_brace) {
    int paren = 0;
    int bracket = 0;
    int angle = 0;
    int brace = 0;
    const size_t n = s.size();
    while (pos < n) {
        char c = s[pos];
        if (c == '"' || c == '\'' || c == '`') {
            char q = c;
            ++pos;
            while (pos < n) {
                if (s[pos] == '\\' && pos + 1 < n) { pos += 2; continue; }
                if (s[pos++] == q) break;
            }
            continue;
        }
        if (paren == 0 && bracket == 0 && angle == 0 && brace == 0) {
            if (stop_at_equals && c == '=') return pos;
            if (stop_at_brace && c == '{') return pos;
            if (c == ',' || c == ')' || c == ';') return pos;
        }
        if (c == '(') ++paren;
        else if (c == ')') { if (paren > 0) --paren; else return pos; }
        else if (c == '[') ++bracket;
        else if (c == ']') { if (bracket > 0) --bracket; }
        else if (c == '<') ++angle;
        else if (c == '>') {
            // 避免箭頭函式 '=>' 被誤當作泛型右角括號
            if (pos > 0 && s[pos - 1] == '=') {
                ++pos;
                continue;
            }
            if (angle > 0) --angle;
        } else if (c == '{') ++brace;
        else if (c == '}') { if (brace > 0) --brace; else return pos; }
        ++pos;
    }
    return pos;
}

// 將 Aperture pack.ts 轉譯為純 ES2022 JavaScript 以供系統內建 JavaScriptCore 高速執行
std::string strip_typescript_syntax(const std::string& raw_ts) {
    const std::string s = strip_js_comments_preserving_strings(raw_ts);
    std::string out;
    out.reserve(s.size());

    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        char c = s[i];
        // 原封不動保留字串內容
        if (c == '"' || c == '\'' || c == '`') {
            char q = c;
            out.push_back(c);
            ++i;
            while (i < n) {
                char sc = s[i++];
                out.push_back(sc);
                if (sc == '\\' && i < n) {
                    out.push_back(s[i++]);
                    continue;
                }
                if (sc == q) break;
            }
            continue;
        }

        // 移除 import ... from "..."; 與單純 import "...";
        if (match_kw(s, i, "import")) {
            size_t j = i + 6;
            bool in_str = false;
            char q = 0;
            while (j < n) {
                char jc = s[j++];
                if (in_str) {
                    if (jc == '\\' && j < n) { ++j; continue; }
                    if (jc == q) in_str = false;
                } else {
                    if (jc == '"' || jc == '\'') { in_str = true; q = jc; }
                    else if (jc == ';') break;
                }
            }
            i = j;
            continue;
        }

        // 移除 interface Name { ... } 與 type Name = ...;
        if (match_kw(s, i, "interface")) {
            size_t lb = s.find('{', i);
            if (lb != std::string::npos) {
                int depth = 1;
                size_t k = lb + 1;
                while (k < n && depth > 0) {
                    if (s[k] == '{') ++depth;
                    else if (s[k] == '}') --depth;
                    ++k;
                }
                i = k;
                continue;
            }
        }
        if (match_kw(s, i, "type")) {
            size_t eq = s.find('=', i);
            size_t semi = s.find(';', i);
            if (eq != std::string::npos && semi != std::string::npos && eq < semi) {
                i = semi + 1;
                continue;
            }
        }

        // 處理 export 關鍵字
        if (match_kw(s, i, "export")) {
            size_t j = i + 6;
            while (j < n && std::isspace(static_cast<unsigned char>(s[j]))) ++j;
            if (match_kw(s, j, "default")) {
                out += "globalThis.__metalityDefaultExport = ";
                i = j + 7;
                continue;
            }
            i = j;
            continue;
        }

        // 處理 function name(p1: T1, p2: T2): RetType { ... }
        if (match_kw(s, i, "function")) {
            size_t lp = s.find('(', i + 8);
            if (lp != std::string::npos) {
                out += s.substr(i, lp - i + 1);
                size_t p = lp + 1;
                int pdepth = 1;
                while (p < n && pdepth > 0) {
                    char pc = s[p];
                    if (pc == '(') {
                        ++pdepth;
                        out.push_back(pc);
                        ++p;
                    } else if (pc == ')') {
                        --pdepth;
                        out.push_back(pc);
                        ++p;
                    } else if (pc == ':' && pdepth == 1) {
                        p = skip_type_expr(s, p + 1, true, false);
                    } else {
                        out.push_back(pc);
                        ++p;
                    }
                }
                // 檢查是否有 ': ReturnType' 位於 '{' 之前
                size_t q = p;
                while (q < n && std::isspace(static_cast<unsigned char>(s[q]))) ++q;
                if (q < n && s[q] == ':') {
                    p = skip_type_expr(s, q + 1, false, true);
                    out.push_back(' ');
                }
                i = p;
                continue;
            }
        }

        // 處理 const / let / var 變數型別標註 (例如 const x: CommandList = ...)
        if (match_kw(s, i, "const") || match_kw(s, i, "let") || match_kw(s, i, "var")) {
            size_t kw_len = match_kw(s, i, "const") ? 5 : 3;
            out += s.substr(i, kw_len);
            size_t p = i + kw_len;
            while (p < n && std::isspace(static_cast<unsigned char>(s[p]))) {
                out.push_back(s[p++]);
            }
            if (p < n && is_id_char(s[p])) {
                while (p < n && is_id_char(s[p])) out.push_back(s[p++]);
                size_t ws = p;
                while (ws < n && std::isspace(static_cast<unsigned char>(s[ws]))) ++ws;
                if (ws < n && s[ws] == ':') {
                    p = skip_type_expr(s, ws + 1, true, false);
                    out.push_back(' ');
                }
            }
            i = p;
            continue;
        }

        out.push_back(c);
        ++i;
    }
    return out;
}

MetalityPixelFormat parse_format_string(const std::string& fmt) {
    if (fmt == "RGBA8_UNORM" || fmt == "RGBA8") return METALITY_FORMAT_RGBA8_UNORM;
    if (fmt == "RGBA8_SRGB") return METALITY_FORMAT_RGBA8_SRGB;
    if (fmt == "BGRA8_SRGB") return METALITY_FORMAT_BGRA8_SRGB;
    if (fmt == "RGB10A2_UNORM") return METALITY_FORMAT_RGB10A2_UNORM;
    if (fmt == "RG11B10_FLOAT" || fmt == "RG11B10F" || fmt == "R11G11B10F") return METALITY_FORMAT_RG11B10_FLOAT;
    if (fmt == "RGBA16_FLOAT" || fmt == "RGBA16F") return METALITY_FORMAT_RGBA16_FLOAT;
    if (fmt == "R32_FLOAT" || fmt == "R32F") return METALITY_FORMAT_R32_FLOAT;
    if (fmt == "DEPTH32_FLOAT" || fmt == "DEPTH32F") return METALITY_FORMAT_DEPTH32_FLOAT;
    if (fmt == "RG16_FLOAT" || fmt == "RG16F") return METALITY_FORMAT_RG16_FLOAT;
    if (fmt == "R16_FLOAT" || fmt == "R16F") return METALITY_FORMAT_R16_FLOAT;
    if (fmt == "R8_UNORM" || fmt == "R8") return METALITY_FORMAT_R8_UNORM;
    return METALITY_FORMAT_RGBA8_UNORM;
}

MTLPixelFormat to_mtl_fmt(MetalityPixelFormat fmt) {
    switch (fmt) {
        case METALITY_FORMAT_RGBA8_UNORM: return MTLPixelFormatRGBA8Unorm;
        case METALITY_FORMAT_RGBA8_SRGB: return MTLPixelFormatRGBA8Unorm_sRGB;
        case METALITY_FORMAT_BGRA8_SRGB: return MTLPixelFormatBGRA8Unorm_sRGB;
        case METALITY_FORMAT_RGB10A2_UNORM: return MTLPixelFormatRGB10A2Unorm;
        case METALITY_FORMAT_RG11B10_FLOAT: return MTLPixelFormatRG11B10Float;
        case METALITY_FORMAT_RGBA16_FLOAT: return MTLPixelFormatRGBA16Float;
        case METALITY_FORMAT_R32_FLOAT: return MTLPixelFormatR32Float;
        case METALITY_FORMAT_DEPTH32_FLOAT: return MTLPixelFormatDepth32Float;
        case METALITY_FORMAT_RG16_FLOAT: return MTLPixelFormatRG16Float;
        case METALITY_FORMAT_R16_FLOAT: return MTLPixelFormatR16Float;
        case METALITY_FORMAT_R8_UNORM: return MTLPixelFormatR8Unorm;
        case METALITY_FORMAT_R8_SINT: return MTLPixelFormatR8Sint;
        case METALITY_FORMAT_R32_SINT: return MTLPixelFormatR32Sint;
        case METALITY_FORMAT_RGBA32_FLOAT: return MTLPixelFormatRGBA32Float;
        case METALITY_FORMAT_RGBA16_SINT: return MTLPixelFormatRGBA16Sint;
    }
    return MTLPixelFormatRGBA16Float;
}

MetalityUpscaleMode parse_upscale_mode(const std::string& m) {
    if (m == "METALFX_TEMPORAL_DENOISED") return METALITY_UPSCALE_TEMPORAL_DENOISED;
    if (m == "METALFX_TEMPORAL") return METALITY_UPSCALE_TEMPORAL;
    if (m == "METALFX_SPATIAL") return METALITY_UPSCALE_SPATIAL;
    return METALITY_UPSCALE_OFF;
}

constexpr const char* kFullscreenQuadVertexMsl = R"(
struct _MetalityFsVertOut {
    float4 position [[position]];
};
vertex _MetalityFsVertOut vs_metality_fullscreen(uint vid [[vertex_id]]) {
    constexpr float2 pos[3] = { float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0) };
    _MetalityFsVertOut out;
    out.position = float4(pos[vid % 3], 0.0, 1.0);
    return out;
}
)";

constexpr const char* kTileLightCullMsl = R"(
#include <metal_stdlib>
using namespace metal;

struct MetalityFrameCull {
    float4x4 invProj;
    float4x4 proj;
    float4x4 viewRot;
    float4x4 invViewRot;
    float4x4 invViewProj;
    float4x4 prevViewProj;
    float4 camWorldPos;
    float4 sunWorldDir;
    float4 moonWorldDir;
    float4 skyColor;
    float4 fogColor;
    float4 sunriseColor;
    int4 voxelOrigin;
    uint width;
    uint height;
    float timeSeconds;
    float rainStrength;
    float thunderStrength;
    float seaLevel;
    float edrHeadroom;
    int dimensionId;
    int submergedMode;
    uint rtInstanceCount;
    uint rtHardwareActive;
    uint tonemapMode;
    float4 userParams;
    float4 tonemapParams;
    float4 lightingParams;
    float4 surfaceParams;
};

constant sampler cull_nearest_clamp(coord::normalized, address::clamp_to_edge, filter::nearest);

inline float3 cull_reconstruct_rel(constant MetalityFrameCull& f, float2 uv, float depth) {
    float4 clip = float4(uv.x * 2.0f - 1.0f, uv.y * 2.0f - 1.0f, max(depth, 1e-5f), 1.0f);
    float4 viewH = f.invProj * clip;
    float w = abs(viewH.w) > 1e-6f ? viewH.w : (viewH.w >= 0.0f ? 1e-6f : -1e-6f);
    float3 viewPos = viewH.xyz / w;
    return (f.invViewRot * float4(viewPos, 0.0f)).xyz;
}

inline uint eval_tile_light_key(
    float4 lp,
    float3 nearMin,
    float3 nearMax,
    float3 farMin,
    float3 farMax,
    bool hasSplit,
    float3 rawMin,
    float3 rawMax,
    uint faceMask,
    uint li
) {
    float maxR = clamp(sqrt(max(lp.w, 0.5f)) * 3.6f + 2.5f, 6.0f, 34.0f);
    float3 c0 = clamp(lp.xyz, nearMin, nearMax);
    float3 dv0 = lp.xyz - c0;
    float d2 = dot(dv0, dv0);
    if (hasSplit) {
        float3 c1 = clamp(lp.xyz, farMin, farMax);
        float3 dv1 = lp.xyz - c1;
        d2 = min(d2, dot(dv1, dv1));
    }
    float maxR2 = maxR * maxR;
    if (d2 >= maxR2) {
        return 0u;
    }
    float r2 = d2 / max(maxR2, 1e-4f);
    float win = 1.0f - r2;
    win *= win;
    // Uniform-hemisphere soft-demotion (0.02x, never hard-discard):
    // Prevents recessed/back-facing ceiling or wall lamps from evicting visible lights on opposite-facing overhangs/beams.
    float hemiScale = 1.0f;
    if (faceMask != 0u) {
        bool anyFacing =
            ((faceMask & 0x01u) != 0u && lp.x >= rawMin.x - 0.25f) ||
            ((faceMask & 0x02u) != 0u && lp.x <= rawMax.x + 0.25f) ||
            ((faceMask & 0x04u) != 0u && lp.y >= rawMin.y - 0.25f) ||
            ((faceMask & 0x08u) != 0u && lp.y <= rawMax.y + 0.25f) ||
            ((faceMask & 0x10u) != 0u && lp.z >= rawMin.z - 0.25f) ||
            ((faceMask & 0x20u) != 0u && lp.z <= rawMax.z + 0.25f);
        if (!anyFacing) {
            hemiScale = 0.02f;
        }
    }
    float prio = clamp((max(lp.w, 0.5f) * win * hemiScale) / (d2 + 0.5f), 1e-12f, 1e6f);
    uint q = max(as_type<uint>(prio) >> 9u, 1u);
    return (q << 10u) | (li & 0x3FFu);
}

inline uint simd_bitonic_sort32_desc(uint v, uint lane) {
    for (uint k = 2u; k <= 32u; k <<= 1u) {
        bool dir = ((lane & k) == 0u);
        for (uint j = k >> 1u; j > 0u; j >>= 1u) {
            uint other = simd_shuffle_xor(v, j);
            bool is_hi_lane = ((lane & j) != 0u);
            bool want_max = (dir != is_hi_lane);
            v = want_max ? max(v, other) : min(v, other);
        }
    }
    return v;
}

inline uint simd_bitonic_merge32_desc(uint v, uint lane) {
    for (uint j = 16u; j > 0u; j >>= 1u) {
        uint other = simd_shuffle_xor(v, j);
        bool is_hi_lane = ((lane & j) != 0u);
        v = is_hi_lane ? min(v, other) : max(v, other);
    }
    return v;
}

inline uint simd_top32_merge_desc(uint a_desc, uint b_desc, uint lane) {
    uint b_asc = simd_shuffle(b_desc, 31u - lane);
    return simd_bitonic_merge32_desc(max(a_desc, b_asc), lane);
}

inline uint2 simd_top64_merge32_desc(uint aHi, uint aLo, uint b_desc, uint lane) {
    uint b_asc = simd_shuffle(b_desc, 31u - lane);
    uint newHi = simd_bitonic_merge32_desc(max(aHi, b_asc), lane);
    uint rem32 = simd_bitonic_merge32_desc(min(aHi, b_asc), lane);
    uint newLo = simd_any(aLo != 0u) ? simd_top32_merge_desc(aLo, rem32, lane) : rem32;
    return uint2(newHi, newLo);
}

inline uint2 simd_top64_merge_desc(uint aHi, uint aLo, uint bHi, uint bLo, uint lane) {
    uint2 m = simd_top64_merge32_desc(aHi, aLo, bHi, lane);
    if (simd_any(bLo != 0u)) {
        m.y = simd_top32_merge_desc(m.y, bLo, lane);
    }
    return m;
}

kernel void cs_metality_tile_light_cull(
    uint2 tgid [[threadgroup_position_in_grid]],
    uint2 lid [[thread_position_in_threadgroup]],
    uint tidx [[thread_index_in_threadgroup]],
    uint simd_lane [[thread_index_in_simdgroup]],
    uint simd_id [[simdgroup_index_in_threadgroup]],
    constant MetalityFrameCull& u [[buffer(0)]],
    device const float4* emissiveLights [[buffer(1)]],
    device uint* outTileLights [[buffer(2)]],
    texture2d<float> sceneDepthTex [[texture(0)]]
) {
    const uint tilesX = (u.width + 31u) / 32u;
    const uint tilesY = (u.height + 31u) / 32u;
    if (tgid.x >= tilesX || tgid.y >= tilesY) {
        return;
    }

    const uint lightCount = min(uint(emissiveLights[0].x + 0.5f), 1024u);
    const uint tileIdx = tgid.y * tilesX + tgid.x;
    const uint tileBase = 4u + tileIdx * 64u;

    if (tileIdx == 0u && tidx == 0u) {
        outTileLights[0] = tilesX;
        outTileLights[1] = tilesY;
        outTileLights[2] = (u.width & 0xFFFFu) | ((u.height & 0xFFFFu) << 16u);
        outTileLights[3] = lightCount;
    }

    if (lightCount == 0u) {
        if (tidx == 0u) {
            outTileLights[tileBase] = 0u;
        }
        return;
    }

    uint2 px = min(tgid * 32u + lid * 2u + 1u, uint2(max(u.width, 1u) - 1u, max(u.height, 1u) - 1u));
    float2 uv = clamp((float2(px) + 0.5f) / float2(max(u.width, 1u), max(u.height, 1u)),
                      float2(0.0005f), float2(0.9995f));
    float d = sceneDepthTex.sample(cull_nearest_clamp, uv, level(0.0f)).r;
    bool isSky = (d <= 1e-6f);
    float3 relPos = isSky ? float3(0.0f) : cull_reconstruct_rel(u, uv, d);
    float viewZ = isSky ? 0.0f : max(-(u.viewRot * float4(relPos, 0.0f)).z, 0.01f);

    float3 pMin = isSky ? float3( 1e9f) : relPos;
    float3 pMax = isSky ? float3(-1e9f) : relPos;
    float zMin = isSky ?  1e9f : viewZ;
    float zMax = isSky ? -1e9f : viewZ;

    pMin.x = simd_min(pMin.x); pMin.y = simd_min(pMin.y); pMin.z = simd_min(pMin.z);
    pMax.x = simd_max(pMax.x); pMax.y = simd_max(pMax.y); pMax.z = simd_max(pMax.z);
    zMin = simd_min(zMin);
    zMax = simd_max(zMax);

    threadgroup float4 s_posZ[16][16];
    threadgroup float3 s_min[8];
    threadgroup float3 s_max[8];
    threadgroup float3 s_min2[8];
    threadgroup float3 s_max2[8];
    threadgroup float s_zMin[8];
    threadgroup float s_zMax[8];
    threadgroup uint s_mask[8];
    threadgroup float3 s_rawMin;
    threadgroup float3 s_rawMax;
    threadgroup float3 s_nearMin;
    threadgroup float3 s_nearMax;
    threadgroup float3 s_farMin;
    threadgroup float3 s_farMax;
    threadgroup float s_zMid;
    threadgroup uint s_faceMask;
    threadgroup bool s_hasGeom;
    threadgroup bool s_splitDepth;
    threadgroup bool s_useSplit;
    threadgroup uint s_topHi[8][32];
    threadgroup uint s_topLo[8][32];

    s_posZ[lid.y][lid.x] = float4(relPos, isSky ? -1.0f : viewZ);
    if (simd_lane == 0u && simd_id < 8u) {
        s_min[simd_id] = pMin;
        s_max[simd_id] = pMax;
        s_zMin[simd_id] = zMin;
        s_zMax[simd_id] = zMax;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    // Zero-extra-read 6-bit cardinal normal mask from the 16x16 reconstructed tile positions
    uint localMask = 0u;
    if (!isSky) {
        float4 pL = (lid.x > 0u)  ? s_posZ[lid.y][lid.x - 1u] : float4(0.0f, 0.0f, 0.0f, -1.0f);
        float4 pR = (lid.x < 15u) ? s_posZ[lid.y][lid.x + 1u] : float4(0.0f, 0.0f, 0.0f, -1.0f);
        float4 pD = (lid.y > 0u)  ? s_posZ[lid.y - 1u][lid.x] : float4(0.0f, 0.0f, 0.0f, -1.0f);
        float4 pU = (lid.y < 15u) ? s_posZ[lid.y + 1u][lid.x] : float4(0.0f, 0.0f, 0.0f, -1.0f);
        bool hasL = (pL.w > 0.0f);
        bool hasR = (pR.w > 0.0f);
        bool hasD = (pD.w > 0.0f);
        bool hasU = (pU.w > 0.0f);
        if ((hasL || hasR) && (hasD || hasU)) {
            bool useL = hasL && (!hasR || abs(pL.w - viewZ) < abs(pR.w - viewZ));
            bool useD = hasD && (!hasU || abs(pD.w - viewZ) < abs(pU.w - viewZ));
            float4 nX = useL ? pL : pR;
            float4 nY = useD ? pD : pU;
            float zJump = max(0.35f, viewZ * 0.12f);
            if (abs(nX.w - viewZ) < zJump && abs(nY.w - viewZ) < zJump) {
                float3 dxW = useL ? (relPos - nX.xyz) : (nX.xyz - relPos);
                float3 dyW = useD ? (relPos - nY.xyz) : (nY.xyz - relPos);
                float3 nrm = cross(dxW, dyW);
                float nLen2 = dot(nrm, nrm);
                if (nLen2 > 1e-10f) {
                    nrm *= rsqrt(nLen2);
                    if (dot(nrm, -relPos) < 0.0f) {
                        nrm = -nrm;
                    }
                    if (nrm.x >  0.25f) localMask |= 0x01u;
                    if (nrm.x < -0.25f) localMask |= 0x02u;
                    if (nrm.y >  0.25f) localMask |= 0x04u;
                    if (nrm.y < -0.25f) localMask |= 0x08u;
                    if (nrm.z >  0.25f) localMask |= 0x10u;
                    if (nrm.z < -0.25f) localMask |= 0x20u;
                }
            }
        }
    }
    uint simdMask = simd_or(localMask);
    if (simd_lane == 0u && simd_id < 8u) {
        s_mask[simd_id] = simdMask;
    }

    if (tidx == 0u) {
        float3 bmin = float3( 1e9f);
        float3 bmax = float3(-1e9f);
        float tzMin =  1e9f;
        float tzMax = -1e9f;
        for (uint i = 0u; i < 8u; ++i) {
            bmin = min(bmin, s_min[i]);
            bmax = max(bmax, s_max[i]);
            tzMin = min(tzMin, s_zMin[i]);
            tzMax = max(tzMax, s_zMax[i]);
        }
        s_hasGeom = (bmin.x <= bmax.x);
        s_rawMin = bmin;
        s_rawMax = bmax;
        s_nearMin = bmin - float3(0.45f);
        s_nearMax = bmax + float3(0.45f);
        s_farMin = s_nearMin;
        s_farMax = s_nearMax;
        s_zMid = 0.5f * (tzMin + tzMax);
        s_splitDepth = s_hasGeom && ((tzMax - tzMin) > 3.0f);
        s_useSplit = false;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    if (!s_hasGeom) {
        if (tidx == 0u) {
            outTileLights[tileBase] = 0u;
        }
        return;
    }

    const bool doSplit = s_splitDepth;
    if (tidx == 0u) {
        uint fm = 0u;
        for (uint i = 0u; i < 8u; ++i) {
            fm |= s_mask[i];
        }
        s_faceMask = fm;
    }
    if (doSplit) {
        bool inNear = (!isSky && viewZ <= s_zMid);
        bool inFar  = (!isSky && viewZ >  s_zMid);
        float3 nMin = inNear ? relPos : float3( 1e9f);
        float3 nMax = inNear ? relPos : float3(-1e9f);
        float3 fMin = inFar  ? relPos : float3( 1e9f);
        float3 fMax = inFar  ? relPos : float3(-1e9f);
        nMin.x = simd_min(nMin.x); nMin.y = simd_min(nMin.y); nMin.z = simd_min(nMin.z);
        nMax.x = simd_max(nMax.x); nMax.y = simd_max(nMax.y); nMax.z = simd_max(nMax.z);
        fMin.x = simd_min(fMin.x); fMin.y = simd_min(fMin.y); fMin.z = simd_min(fMin.z);
        fMax.x = simd_max(fMax.x); fMax.y = simd_max(fMax.y); fMax.z = simd_max(fMax.z);
        if (simd_lane == 0u && simd_id < 8u) {
            s_min[simd_id] = nMin;
            s_max[simd_id] = nMax;
            s_min2[simd_id] = fMin;
            s_max2[simd_id] = fMax;
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    if (doSplit) {
        if (tidx == 0u) {
            float3 nMin = float3( 1e9f);
            float3 nMax = float3(-1e9f);
            float3 fMin = float3( 1e9f);
            float3 fMax = float3(-1e9f);
            for (uint i = 0u; i < 8u; ++i) {
                nMin = min(nMin, s_min[i]);
                nMax = max(nMax, s_max[i]);
                fMin = min(fMin, s_min2[i]);
                fMax = max(fMax, s_max2[i]);
            }
            if (nMin.x <= nMax.x && fMin.x <= fMax.x) {
                s_nearMin = nMin - float3(0.45f);
                s_nearMax = nMax + float3(0.45f);
                s_farMin = fMin - float3(0.45f);
                s_farMax = fMax + float3(0.45f);
                s_useSplit = true;
            }
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
    }

    const float3 nearMin = s_nearMin;
    const float3 nearMax = s_nearMax;
    const float3 farMin = s_farMin;
    const float3 farMax = s_farMax;
    const bool useSplit = s_useSplit;
    const float3 rawMin = s_rawMin;
    const float3 rawMax = s_rawMax;
    const uint faceMask = s_faceMask;

    uint myHi = 0u;
    uint myLo = 0u;
    bool hasTop = false;
    for (uint base = 0u; base < lightCount; base += 256u) {
        uint li = base + tidx;
        uint cand = 0u;
        if (li < lightCount) {
            float4 lp = emissiveLights[1u + 2u * li];
            cand = eval_tile_light_key(lp, nearMin, nearMax, farMin, farMax, useSplit, rawMin, rawMax, faceMask, li);
        }
        if (simd_any(cand != 0u)) {
            uint sortedCand = simd_bitonic_sort32_desc(cand, simd_lane);
            if (!hasTop) {
                myHi = sortedCand;
                myLo = 0u;
                hasTop = true;
            } else {
                uint2 m = simd_top64_merge32_desc(myHi, myLo, sortedCand, simd_lane);
                myHi = m.x;
                myLo = m.y;
            }
        }
    }

    // 3-stage zero-atomic binary-tree Top-64 merge (8 -> 4 -> 2 -> 1) filling all 63 allocated slots
    if (simd_id >= 4u && simd_id < 8u) {
        s_topHi[simd_id][simd_lane] = myHi;
        s_topLo[simd_id][simd_lane] = myLo;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    if (simd_id < 4u) {
        uint otherHi = s_topHi[simd_id + 4u][simd_lane];
        uint otherLo = s_topLo[simd_id + 4u][simd_lane];
        if (simd_any(otherHi != 0u)) {
            if (hasTop) {
                uint2 m = simd_top64_merge_desc(myHi, myLo, otherHi, otherLo, simd_lane);
                myHi = m.x;
                myLo = m.y;
            } else {
                myHi = otherHi;
                myLo = otherLo;
                hasTop = true;
            }
        }
        if (simd_id >= 2u) {
            s_topHi[simd_id][simd_lane] = myHi;
            s_topLo[simd_id][simd_lane] = myLo;
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    if (simd_id < 2u) {
        uint otherHi = s_topHi[simd_id + 2u][simd_lane];
        uint otherLo = s_topLo[simd_id + 2u][simd_lane];
        if (simd_any(otherHi != 0u)) {
            if (hasTop) {
                uint2 m = simd_top64_merge_desc(myHi, myLo, otherHi, otherLo, simd_lane);
                myHi = m.x;
                myLo = m.y;
            } else {
                myHi = otherHi;
                myLo = otherLo;
                hasTop = true;
            }
        }
        if (simd_id == 1u) {
            s_topHi[1][simd_lane] = myHi;
            s_topLo[1][simd_lane] = myLo;
        }
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);

    if (simd_id == 0u) {
        uint otherHi = s_topHi[1][simd_lane];
        uint otherLo = s_topLo[1][simd_lane];
        if (simd_any(otherHi != 0u)) {
            if (hasTop) {
                uint2 m = simd_top64_merge_desc(myHi, myLo, otherHi, otherLo, simd_lane);
                myHi = m.x;
                myLo = m.y;
            } else {
                myHi = otherHi;
                myLo = otherLo;
            }
        }
        uint hiCnt = simd_sum((myHi != 0u) ? 1u : 0u);
        uint loCnt = simd_sum((myLo != 0u) ? 1u : 0u);
        uint finalCount = min(hiCnt + loCnt, 63u);
        if (simd_lane == 0u) {
            outTileLights[tileBase] = finalCount;
        }
        if (simd_lane < finalCount) {
            outTileLights[tileBase + 1u + simd_lane] = myHi & 0x3FFu;
        }
        if (simd_lane < 31u && (32u + simd_lane) < finalCount) {
            outTileLights[tileBase + 1u + 32u + simd_lane] = myLo & 0x3FFu;
        }
    }
}
)";

// 注入給 JavaScriptCore 執行 pack.ts 的 @metality/sdk Runtime Bridge
constexpr const char* kJsPackBootstrap = R"JS(
const Format = Object.freeze({
  RGBA8: "RGBA8_UNORM",
  RGBA8_SRGB: "RGBA8_SRGB",
  RGB10A2: "RGB10A2_UNORM",
  RG11B10F: "RG11B10_FLOAT",
  RGBA16F: "RGBA16_FLOAT",
  R32F: "R32_FLOAT",
  DEPTH32F: "DEPTH32_FLOAT"
});

const GeometryStage = Object.freeze({
  TerrainSolid: "TERRAIN_SOLID",
  TerrainCutout: "TERRAIN_CUTOUT",
  TerrainTranslucent: "TERRAIN_TRANSLUCENT",
  Entities: "ENTITIES",
  BlockEntities: "BLOCK_ENTITIES",
  Particles: "PARTICLES",
  Sky: "SKY"
});

const StorageHint = Object.freeze({
  AutoTileOptimal: "AUTO_TILE_OPTIMAL",
  ForceMemoryless: "FORCE_MEMORYLESS",
  Persistent: "PERSISTENT"
});

const MetalFXMode = Object.freeze({
  Off: "OFF",
  Spatial: "METALFX_SPATIAL",
  Temporal: "METALFX_TEMPORAL",
  TemporalDenoised: "METALFX_TEMPORAL_DENOISED"
});

globalThis.__metalityDefinedOptions = [];
globalThis.__metalityDefinedProfiles = [];
function defineOption(name, defaultValue, allowedValues, slider, group) {
  globalThis.__metalityDefinedOptions.push({ name, defaultValue: String(defaultValue), allowedValues: (allowedValues || [defaultValue]).map(String), slider: !!slider, group: group || "MAIN" });
}
function defineProfile(name, overrides) {
  globalThis.__metalityDefinedProfiles.push({ name, overrides: overrides || {} });
}

function __metalityRunPack(envInfo) {
  const rendererConfig = {
    disableShade: false,
    ambientOcclusionLevel: 1.0,
    shadow: { enabled: true, resolution: 1024, cascades: 2, gpuDrivenCulling: false },
    render: { sun: true, moon: true, stars: true, vanillaClouds: true, vignette: true },
    appleSilicon: { enableEDR: false, enableHardwareRayTracing: false, thermalAdaptiveScaling: false }
  };

  if (typeof configureRenderer === "function") {
    configureRenderer(rendererConfig);
  }

  const textures = [];
  const stages = [];
  const passes = [];
  let metalfxSpec = null;
  let presentedName = null;
  let cachedBuiltinSceneColor = null;
  let cachedBuiltinShadowMap = null;

  function registerTex(name, format, width, height, storage, isBuiltinColor, useScale, scaleFactor) {
    const t = {
      __id: textures.length,
      name,
      format,
      width,
      height,
      storage,
      isBuiltinColor: !!isBuiltinColor,
      isBuiltinDepth: name === "__builtin_main_depth" || name === "__builtin_solid_depth",
      isBuiltinShadow: name === "__builtin_shadow_map",
      useScale: !!useScale,
      scaleFactor: scaleFactor || 1.0
    };
    textures.push(t);
    return t;
  }

  const solidDepthTex = registerTex("__builtin_solid_depth", Format.DEPTH32F, envInfo.internalWidth, envInfo.internalHeight, StorageHint.Persistent, false, true, 1.0);
  const mainDepthTex = registerTex("__builtin_main_depth", Format.DEPTH32F, envInfo.internalWidth, envInfo.internalHeight, StorageHint.Persistent, false, true, 1.0);
  const builtinMotionVectors = registerTex("__builtin_motion_vectors", "RG16_FLOAT", envInfo.internalWidth, envInfo.internalHeight, StorageHint.Persistent, false, true, 1.0);

  const pipeline = {
    env: envInfo,
    mainDepthTex,
    solidDepthTex,
    builtinMotionVectors,
    get builtinSceneColor() {
      if (!cachedBuiltinSceneColor) {
        cachedBuiltinSceneColor = registerTex("__builtin_scene_color", Format.RGBA8, envInfo.screenWidth, envInfo.screenHeight, StorageHint.Persistent, true, true, 1.0);
      }
      return cachedBuiltinSceneColor;
    },
    get builtinShadowMap() {
      if (!cachedBuiltinShadowMap) {
        const sres = Math.max(512, Number(rendererConfig.shadow.resolution) || 2048);
        cachedBuiltinShadowMap = registerTex("__builtin_shadow_map", Format.DEPTH32F, sres * 2, sres, StorageHint.Persistent, false, false, 1.0);
      }
      return cachedBuiltinShadowMap;
    },
    createTexture(name) {
      let fmt = Format.RGBA8_UNORM;
      let w = envInfo.screenWidth;
      let h = envInfo.screenHeight;
      let storage = StorageHint.AutoTileOptimal;
      let useScale = false;
      let scaleFactor = 1.0;
      const builder = {
        format(f) { fmt = f; return builder; },
        size(width, height) { w = width; h = height; useScale = false; return builder; },
        scale(factor) {
          useScale = true;
          scaleFactor = Number(factor) > 0 ? Number(factor) : 1.0;
          w = Math.max(1, Math.round(envInfo.screenWidth * scaleFactor));
          h = Math.max(1, Math.round(envInfo.screenHeight * scaleFactor));
          return builder;
        },
        storage(s) { storage = s; return builder; },
        clear() { return builder; },
        build() { return registerTex(name, fmt, w, h, storage, false, useScale, scaleFactor); }
      };
      return builder;
    },
    forStage(stageName) {
      const rec = {
        stage: stageName,
        vsShader: "",
        vsEntry: "main",
        fsShader: "",
        fsEntry: "main",
        targets: [],
        blend: false
      };
      const sb = {
        vertex(path, entry) { rec.vsShader = path; rec.vsEntry = entry || "vsMain"; return sb; },
        fragment(path, entry) { rec.fsShader = path; rec.fsEntry = entry || "fsMain"; return sb; },
        target(idx, tex) { rec.targets[idx] = tex; return sb; },
        blend(enabled) { rec.blend = !!enabled; return sb; },
        register() { stages.push(rec); }
      };
      return sb;
    },
    createCommandList(phase) {
      const cl = {
        fullscreenPass(spec) {
          passes.push({
            kind: "FULLSCREEN",
            phase,
            name: spec.name,
            shader: spec.shader,
            entry: spec.entry || "fsMain",
            reads: (spec.reads || []).map(t => t.__id),
            writes: (spec.writes || []).map(t => t.__id),
            samplesNeighbors: !!spec.samplesNeighbors
          });
          return cl;
        },
        computeDispatch(spec) {
          passes.push({
            kind: "COMPUTE",
            phase,
            name: spec.name,
            shader: spec.shader,
            entry: spec.entry || "csMain",
            wgX: (spec.workgroupSize && spec.workgroupSize[0]) || 8,
            wgY: (spec.workgroupSize && spec.workgroupSize[1]) || 8,
            wgZ: (spec.workgroupSize && spec.workgroupSize[2]) || 1,
            bindRayTracingScene: !!spec.bindRayTracingScene,
            reads: (spec.reads || []).map(t => t.__id),
            writes: (spec.writes || []).map(t => t.__id),
            samplesNeighbors: true
          });
          return cl;
        },
        applyMetalFX(spec) {
          metalfxSpec = {
            mode: spec.mode,
            inputColor: spec.inputColor ? spec.inputColor.__id : -1,
            depth: spec.depth ? spec.depth.__id : -1,
            motionVectors: spec.motionVectors ? spec.motionVectors.__id : -1,
            outputColor: spec.outputColor ? spec.outputColor.__id : -1,
            enableRayTracingDenoiser: !!spec.enableRayTracingDenoiser,
            enableFrameInterpolation: !!spec.enableFrameInterpolation
          };
          passes.push({
            kind: "METALFX",
            phase,
            name: "metalfx_upscale",
            shader: "",
            entry: "",
            reads: [metalfxSpec.inputColor, metalfxSpec.depth, metalfxSpec.motionVectors].filter(x => x >= 0),
            writes: metalfxSpec.outputColor >= 0 ? [metalfxSpec.outputColor] : [],
            samplesNeighbors: true
          });
          return cl;
        },
        present(tex) {
          presentedName = tex ? tex.name : null;
        }
      };
      return cl;
    }
  };

  if (typeof configurePipeline === "function") {
    configurePipeline(pipeline);
  }

  return { rendererConfig, textures, stages, passes, metalfxSpec, presentedName };
}
)JS";

} // namespace

struct TexExecSpec {
    std::string name;
    MetalityPixelFormat format = METALITY_FORMAT_RGBA8_UNORM;
    bool is_builtin_color = false;
    bool is_builtin_depth = false;
    bool is_builtin_shadow = false;
    bool use_scale = true;
    float scale_factor = 1.0f;
    uint32_t fixed_w = 1;
    uint32_t fixed_h = 1;
    bool is_referenced = false;
    bool written_by_render = false;
    bool written_by_compute = false;
    id<MTLTexture> runtime_tex = nil;
    uint32_t alloc_w = 0;
    uint32_t alloc_h = 0;
};

struct PassExecRecord {
    enum Kind { FULLSCREEN, COMPUTE } kind = FULLSCREEN;
    std::string name;
    id<MTLRenderPipelineState> render_pso = nil;
    id<MTLRenderPipelineState> render_pso_hdr = nil;
    id<MTLComputePipelineState> compute_pso = nil;
    uint32_t wg_x = 8;
    uint32_t wg_y = 8;
    uint32_t wg_z = 1;
    bool bind_rt_scene = false;
    bool needs_rt_fragment_buffers = false;
    bool needs_tile_light_buffer = false;
    std::vector<uint32_t> read_tex_indices;
    std::vector<uint32_t> write_tex_indices;
};

struct MetalityShaderPackImpl {
    std::filesystem::path root_dir;
    MetalityPackManifestSummary summary{};
    MetalityUpscalerHandle upscaler = nullptr;
    id<MTLFXSpatialScaler> runtime_spatial_scaler = nil;
    uint32_t runtime_scaler_in_w = 0;
    uint32_t runtime_scaler_in_h = 0;
    uint32_t runtime_scaler_out_w = 0;
    uint32_t runtime_scaler_out_h = 0;
    MTLPixelFormat runtime_scaler_fmt = MTLPixelFormatInvalid;
    id<MTLTexture> upscale_input_tex = nil;
    id<MTLTexture> upscale_output_tex = nil;
    id<MTLComputePipelineState> tile_cull_pso = nil;
    id<MTLBuffer> tile_light_buf = nil;
    id<MTLBuffer> tile_light_fallback_buf = nil;
    NSUInteger tile_light_buf_bytes = 0;
    std::vector<id<MTLRenderPipelineState>> compiled_psos;
    std::vector<id<MTLComputePipelineState>> compiled_compute_psos;
    std::vector<TexExecSpec> exec_textures;
    std::vector<PassExecRecord> exec_passes;
    id<MTLTexture> scene_color_snapshot = nil;
    id<MTLTexture> fallback_shadow_tex = nil;
    id<MTLTexture> fallback_black_tex = nil;
    bool fallback_shadow_cleared = false;
    bool needs_scene_color_snapshot = false;
    uint32_t dusk_lighting_raw_idx = UINT32_MAX;
    uint32_t dusk_lighting_filtered_idx = UINT32_MAX;
    uint32_t dusk_sun_raw_idx = UINT32_MAX;
    uint32_t dusk_sun_filtered_idx = UINT32_MAX;
    uint32_t rt_gi_raw_idx = UINT32_MAX;
    uint32_t rt_gi_step2_idx = UINT32_MAX;
    uint32_t rt_sun_raw_idx = UINT32_MAX;
    uint32_t rt_sun_step2_idx = UINT32_MAX;
    // Zenith zero-copy ping-pong history pairs (replaces rt_history_copy / rt_reflect_history_copy / rt_gbuf_copy!)
    uint32_t rt_gi_temporal_idx = UINT32_MAX;
    uint32_t rt_gi_history_idx = UINT32_MAX;
    uint32_t rt_sun_temporal_idx = UINT32_MAX;
    uint32_t rt_sun_history_idx = UINT32_MAX;
    uint32_t rt_reflect_temporal_idx = UINT32_MAX;
    uint32_t rt_reflect_history_idx = UINT32_MAX;
    uint32_t rt_gbuf_curr_idx = UINT32_MAX;
    uint32_t rt_gbuf_history_idx = UINT32_MAX;
    bool has_rt_history_copy = false;
    bool has_rt_reflect_history_copy = false;
    bool has_rt_gbuf_copy = false;
    uint32_t snapshot_w = 0;
    uint32_t snapshot_h = 0;
    MTLPixelFormat snapshot_fmt = MTLPixelFormatInvalid;
    bool enable_hw_raytracing = false;
    bool uses_builtin_shadow_map = false;
    uint32_t shadow_resolution = 2048;
};

static id<MTLTexture> find_ctx_texture(MetalityContext* ctx, MetalityTextureHandle handle) {
    if (!ctx || handle == 0) {
        return nil;
    }
    std::lock_guard<std::mutex> lock(ctx->resource_mutex);
    auto it = ctx->textures.find(handle);
    return (it != ctx->textures.end()) ? it->second.texture : nil;
}

extern "C" {

MetalityPackHandle metality_pack_load_directory(
    MetalityContext* ctx,
    const char* pack_root_dir,
    uint32_t screen_width,
    uint32_t screen_height,
    float internal_scale,
    MetalityPackManifestSummary* out_summary,
    char* err_buf,
    uint32_t err_cap
) {
    if (!pack_root_dir || screen_width == 0 || screen_height == 0) {
        set_err(err_buf, err_cap, "Invalid pack_root_dir or screen dimensions");
        return nullptr;
    }

    const std::filesystem::path root(pack_root_dir);
    const std::filesystem::path pack_ts_path = root / "pack.ts";
    const std::string raw_ts = read_text_file(pack_ts_path);
    if (raw_ts.empty()) {
        set_err(err_buf, err_cap, "Failed to read pack.ts at " + pack_ts_path.string());
        return nullptr;
    }

    const float clamped_scale = std::clamp(internal_scale, 0.33f, 1.0f);
    const uint32_t internal_w = std::max<uint32_t>(64u, static_cast<uint32_t>(std::lround(screen_width * clamped_scale)) & ~1u);
    const uint32_t internal_h = std::max<uint32_t>(64u, static_cast<uint32_t>(std::lround(screen_height * clamped_scale)) & ~1u);

    MetalityDeviceCaps caps{};
    if (ctx) {
        metality_query_capabilities(ctx, &caps);
    } else {
        caps.apple_gpu_family = 9;
        caps.supports_hardware_raytracing = 1;
        caps.supports_metalfx_temporal = 1;
        caps.supports_metal4_command_queue = 0;
    }

    @autoreleasepool {
        const std::string stripped_js = strip_typescript_syntax(raw_ts);

        JSContext* js = [[JSContext alloc] init];
        __block std::string js_error;
        js.exceptionHandler = ^(JSContext* c, JSValue* exception) {
            (void)c;
            js_error = exception.toString.UTF8String ? exception.toString.UTF8String : "JS exception";
        };

        [js evaluateScript:[NSString stringWithUTF8String:kJsPackBootstrap]];
        if (!js_error.empty()) {
            set_err(err_buf, err_cap, "SDK JS bootstrap error: " + js_error);
            return nullptr;
        }

        [js evaluateScript:[NSString stringWithUTF8String:stripped_js.c_str()]];
        if (!js_error.empty()) {
            set_err(err_buf, err_cap, "pack.ts evaluation error: " + js_error);
            return nullptr;
        }

        NSDictionary* envDict = @{
            @"screenWidth": @(screen_width),
            @"screenHeight": @(screen_height),
            @"internalWidth": @(internal_w),
            @"internalHeight": @(internal_h),
            @"appleGpuFamily": @(caps.apple_gpu_family),
            @"supportsHardwareRayTracing": @(caps.supports_hardware_raytracing != 0),
            @"supportsMetalFXTemporal": @(caps.supports_metalfx_temporal != 0),
            @"supportsMetal4": @(caps.supports_metal4_command_queue != 0),
        };

        JSValue* runFn = js[@"__metalityRunPack"];
        JSValue* manifestVal = [runFn callWithArguments:@[ envDict ]];
        if (!js_error.empty() || !manifestVal || manifestVal.isUndefined) {
            set_err(err_buf, err_cap, "pack.ts configurePipeline execution failed: " + js_error);
            return nullptr;
        }

        NSDictionary* manifest = [manifestVal toDictionary];
        NSDictionary* rc = manifest[@"rendererConfig"];
        NSDictionary* rcShadow = rc[@"shadow"];
        NSDictionary* rcApple = rc[@"appleSilicon"];
        NSArray* jsTextures = manifest[@"textures"];
        NSArray* jsStages = manifest[@"stages"];
        NSArray* jsPasses = manifest[@"passes"];
        NSDictionary* jsMfx = manifest[@"metalfxSpec"];

        auto* pack = new MetalityShaderPackImpl();
        pack->root_dir = root;

        auto& sum = pack->summary;
        sum.shadow_enabled = [rcShadow[@"enabled"] boolValue] ? 1 : 0;
        sum.shadow_resolution = static_cast<uint32_t>([rcShadow[@"resolution"] unsignedIntValue]);
        sum.shadow_cascades = static_cast<uint32_t>([rcShadow[@"cascades"] unsignedIntValue]);
        sum.gpu_driven_shadow_cull = [rcShadow[@"gpuDrivenCulling"] boolValue] ? 1 : 0;
        sum.enable_edr = [rcApple[@"enableEDR"] boolValue] ? 1 : 0;
        sum.enable_hw_raytracing = [rcApple[@"enableHardwareRayTracing"] boolValue] ? 1 : 0;
        sum.thermal_adaptive = [rcApple[@"thermalAdaptiveScaling"] boolValue] ? 1 : 0;
        pack->enable_hw_raytracing = (sum.enable_hw_raytracing != 0);
        pack->shadow_resolution = sum.shadow_resolution > 0 ? sum.shadow_resolution : 2048u;

        // 1. 將 pack.ts 宣告的貼圖與 Passes 送入 TBDR Render Graph 編譯器進行自動 Pass 融合與 Memoryless 分析
        MetalityGraphHandle graph = metality_graph_create();
        std::vector<uint32_t> graph_tex_ids;
        std::vector<MetalityPixelFormat> tex_formats;
        graph_tex_ids.reserve(jsTextures.count);
        tex_formats.reserve(jsTextures.count);

        uint32_t solid_depth_js_idx = UINT32_MAX;
        for (NSUInteger ti = 0; ti < jsTextures.count; ++ti) {
            NSDictionary* td = jsTextures[ti];
            std::string tname = td[@"name"] ? [td[@"name"] UTF8String] : "tex";
            std::string fmt_str = td[@"format"] ? [td[@"format"] UTF8String] : "RGBA8_UNORM";
            std::string st_str = td[@"storage"] ? [td[@"storage"] UTF8String] : "AUTO_TILE_OPTIMAL";
            uint32_t tw = [td[@"width"] unsignedIntValue];
            uint32_t th = [td[@"height"] unsignedIntValue];
            bool force_persistent = (st_str == "PERSISTENT");

            MetalityPixelFormat pfmt = parse_format_string(fmt_str);
            tex_formats.push_back(pfmt);
            uint32_t gid = metality_graph_declare_texture(graph, tname.c_str(), pfmt, tw, th, force_persistent);
            graph_tex_ids.push_back(gid);

            TexExecSpec tspec;
            tspec.name = tname;
            tspec.format = pfmt;
            tspec.is_builtin_color = [td[@"isBuiltinColor"] boolValue];
            tspec.is_builtin_depth = [td[@"isBuiltinDepth"] boolValue];
            tspec.is_builtin_shadow = [td[@"isBuiltinShadow"] boolValue];
            if (tspec.is_builtin_shadow) {
                pack->uses_builtin_shadow_map = true;
            }
            tspec.use_scale = [td[@"useScale"] boolValue];
            tspec.scale_factor = td[@"scaleFactor"] ? [td[@"scaleFactor"] floatValue] : 1.0f;
            tspec.fixed_w = std::max(1u, tw);
            tspec.fixed_h = std::max(1u, th);
            pack->exec_textures.push_back(std::move(tspec));
            if (tname == "dusk_lighting_raw") {
                pack->dusk_lighting_raw_idx = static_cast<uint32_t>(ti);
            } else if (tname == "dusk_lighting_filtered") {
                pack->dusk_lighting_filtered_idx = static_cast<uint32_t>(ti);
            } else if (tname == "dusk_sun_raw") {
                pack->dusk_sun_raw_idx = static_cast<uint32_t>(ti);
            } else if (tname == "dusk_sun_filtered") {
                pack->dusk_sun_filtered_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_gi_raw") {
                pack->rt_gi_raw_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_gi_step2") {
                pack->rt_gi_step2_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_sun_raw") {
                pack->rt_sun_raw_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_sun_step2") {
                pack->rt_sun_step2_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_gi_temporal") {
                pack->rt_gi_temporal_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_gi_history") {
                pack->rt_gi_history_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_sun_temporal") {
                pack->rt_sun_temporal_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_sun_history") {
                pack->rt_sun_history_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_reflect_temporal") {
                pack->rt_reflect_temporal_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_reflect_history") {
                pack->rt_reflect_history_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_gbuf_curr") {
                pack->rt_gbuf_curr_idx = static_cast<uint32_t>(ti);
            } else if (tname == "meteor_rt_gbuf_history") {
                pack->rt_gbuf_history_idx = static_cast<uint32_t>(ti);
            }
            if (tname == "__builtin_solid_depth") {
                solid_depth_js_idx = static_cast<uint32_t>(ti);
            }
        }

        // 若 pack.ts 註冊了任何地形/幾何 Stage，將其統整為 Render Graph 的 G-Buffer 繪製 Pass 0
        if (jsStages.count > 0) {
            std::vector<uint32_t> stage_writes;
            NSDictionary* firstStage = jsStages[0];
            NSArray* targets = firstStage[@"targets"];
            for (id item in targets) {
                if ([item isKindOfClass:[NSDictionary class]]) {
                    uint32_t js_idx = [item[@"__id"] unsignedIntValue];
                    if (js_idx < graph_tex_ids.size() &&
                        std::find(stage_writes.begin(), stage_writes.end(), graph_tex_ids[js_idx]) == stage_writes.end()) {
                        stage_writes.push_back(graph_tex_ids[js_idx]);
                    }
                }
            }
            if (solid_depth_js_idx != UINT32_MAX) {
                stage_writes.push_back(graph_tex_ids[solid_depth_js_idx]);
            }
            metality_graph_add_pass(graph, "geometry_gbuffers", nullptr, 0, false,
                                    stage_writes.data(), static_cast<uint32_t>(stage_writes.size()));
        }

        for (NSDictionary* pd in jsPasses) {
            std::string pname = pd[@"name"] ? [pd[@"name"] UTF8String] : "pass";
            bool samples_neighbors = [pd[@"samplesNeighbors"] boolValue];

            std::vector<uint32_t> reads;
            for (NSNumber* ridx in pd[@"reads"]) {
                uint32_t idx = [ridx unsignedIntValue];
                if (idx < graph_tex_ids.size()) reads.push_back(graph_tex_ids[idx]);
            }
            std::vector<uint32_t> writes;
            for (NSNumber* widx in pd[@"writes"]) {
                uint32_t idx = [widx unsignedIntValue];
                if (idx < graph_tex_ids.size()) writes.push_back(graph_tex_ids[idx]);
            }
            metality_graph_add_pass(graph, pname.c_str(),
                                    reads.data(), static_cast<uint32_t>(reads.size()),
                                    samples_neighbors,
                                    writes.data(), static_cast<uint32_t>(writes.size()));
        }

        MetalityGraphPlanStats stats{};
        metality_graph_compile_tbdr(graph, &stats);
        metality_graph_destroy(graph);

        sum.declared_texture_count = stats.total_textures;
        sum.memoryless_texture_count = stats.memoryless_textures;
        sum.total_pass_count = stats.total_passes;
        sum.fused_group_count = stats.fused_encoder_groups;
        sum.saved_dram_bytes_per_frame = stats.saved_dram_bytes_per_frame;

        if (jsMfx && [jsMfx isKindOfClass:[NSDictionary class]] && jsMfx[@"mode"]) {
            std::string mstr = [jsMfx[@"mode"] UTF8String];
            sum.upscaler_mode = static_cast<uint32_t>(parse_upscale_mode(mstr));
        } else {
            sum.upscaler_mode = METALITY_UPSCALE_OFF;
        }

        // 2. 將 pack.ts 參考的 .slang 著色器轉譯為 MSL，並在有活躍 Metal Device 時編譯真實 Apple GPU PSO 與 MetalFX
        std::vector<char> msl_buf(512 * 1024);
        char compile_err[1024] = {0};

        auto compile_slang_source = [&](const std::string& msl_code, const char* label) -> id<MTLLibrary> {
            if (!ctx || !ctx->device) {
                return nil;
            }
            MTLCompileOptions* opts = [[MTLCompileOptions alloc] init];
            opts.languageVersion = MTLLanguageVersion3_1;
            opts.mathMode = MTLMathModeFast;
            NSError* err = nil;
            id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:msl_code.c_str()]
                                                           options:opts
                                                             error:&err];
            if (!lib) {
                std::string msg = (err && err.localizedDescription) ? err.localizedDescription.UTF8String : "MSL compile failed";
                set_err(err_buf, err_cap, std::string(label) + ": " + msg);
            }
            return lib;
        };

        for (NSDictionary* st in jsStages) {
            std::string vs_rel = st[@"vsShader"] ? [st[@"vsShader"] UTF8String] : "";
            std::string vs_entry = st[@"vsEntry"] ? [st[@"vsEntry"] UTF8String] : "";
            std::string fs_rel = st[@"fsShader"] ? [st[@"fsShader"] UTF8String] : "";
            std::string fs_entry = st[@"fsEntry"] ? [st[@"fsEntry"] UTF8String] : "";
            if (vs_rel.empty() || fs_rel.empty()) continue;

            std::string vs_src = load_slang_with_includes(root / vs_rel, root);
            std::string fs_src = (fs_rel == vs_rel) ? vs_src : load_slang_with_includes(root / fs_rel, root);
            if (vs_src.empty() || fs_src.empty()) {
                set_err(err_buf, err_cap, "Missing shader file referenced in pack.ts: " + vs_rel);
                delete pack;
                return nullptr;
            }

            if (!metality_transpile_slang_to_msl(vs_src.c_str(), vs_entry.c_str(), msl_buf.data(),
                                                static_cast<uint32_t>(msl_buf.size()), compile_err, sizeof(compile_err))) {
                set_err(err_buf, err_cap, std::string("Slang VS transpile failed: ") + compile_err);
                delete pack;
                return nullptr;
            }
            std::string vs_msl(msl_buf.data());

            if (!metality_transpile_slang_to_msl(fs_src.c_str(), fs_entry.c_str(), msl_buf.data(),
                                                static_cast<uint32_t>(msl_buf.size()), compile_err, sizeof(compile_err))) {
                set_err(err_buf, err_cap, std::string("Slang FS transpile failed: ") + compile_err);
                delete pack;
                return nullptr;
            }
            std::string fs_msl(msl_buf.data());

            if (ctx && ctx->device) {
                id<MTLLibrary> vlib = compile_slang_source(vs_msl, vs_entry.c_str());
                id<MTLLibrary> flib = compile_slang_source(fs_msl, fs_entry.c_str());
                if (!vlib || !flib) {
                    delete pack;
                    return nullptr;
                }
                id<MTLFunction> vfn = [vlib newFunctionWithName:[NSString stringWithUTF8String:vs_entry.c_str()]];
                id<MTLFunction> ffn = [flib newFunctionWithName:[NSString stringWithUTF8String:fs_entry.c_str()]];
                if (!vfn || !ffn) {
                    set_err(err_buf, err_cap, "Missing Metal entry point for stage " + fs_entry);
                    delete pack;
                    return nullptr;
                }

                MTLRenderPipelineDescriptor* rpd = [[MTLRenderPipelineDescriptor alloc] init];
                rpd.vertexFunction = vfn;
                rpd.fragmentFunction = ffn;
                rpd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;

                NSArray* targets = st[@"targets"];
                for (NSUInteger ti = 0; ti < targets.count; ++ti) {
                    id item = targets[ti];
                    if ([item isKindOfClass:[NSDictionary class]]) {
                        uint32_t tidx = [item[@"__id"] unsignedIntValue];
                        if (tidx < tex_formats.size()) {
                            rpd.colorAttachments[ti].pixelFormat = to_mtl_fmt(tex_formats[tidx]);
                        }
                    }
                }
                if (ctx->binary_archive) {
                    rpd.binaryArchives = @[ ctx->binary_archive ];
                }

                NSError* pso_err = nil;
                id<MTLRenderPipelineState> pso = [ctx->device newRenderPipelineStateWithDescriptor:rpd error:&pso_err];
                if (!pso) {
                    std::string msg = (pso_err && pso_err.localizedDescription) ? pso_err.localizedDescription.UTF8String : "Stage PSO failed";
                    set_err(err_buf, err_cap, msg);
                    delete pack;
                    return nullptr;
                }
                pack->compiled_psos.push_back(pso);
                sum.compiled_pso_count += 1;
            }
        }

        for (NSDictionary* pd in jsPasses) {
            std::string kind = pd[@"kind"] ? [pd[@"kind"] UTF8String] : "";
            std::string pname = pd[@"name"] ? [pd[@"name"] UTF8String] : "pass";

            std::vector<uint32_t> read_ids;
            for (NSNumber* ridx in pd[@"reads"]) {
                uint32_t tidx = [ridx unsignedIntValue];
                if (tidx < pack->exec_textures.size()) read_ids.push_back(tidx);
            }
            std::vector<uint32_t> write_ids;
            for (NSNumber* widx in pd[@"writes"]) {
                uint32_t tidx = [widx unsignedIntValue];
                if (tidx < pack->exec_textures.size()) write_ids.push_back(tidx);
            }

            if (kind == "COMPUTE") {
                std::string cs_rel = pd[@"shader"] ? [pd[@"shader"] UTF8String] : "";
                std::string cs_entry = pd[@"entry"] ? [pd[@"entry"] UTF8String] : "";
                std::string cs_src = load_slang_with_includes(root / cs_rel, root);
                if (cs_src.empty()) {
                    set_err(err_buf, err_cap, "Missing compute shader: " + cs_rel);
                    delete pack;
                    return nullptr;
                }
                if (!metality_transpile_slang_to_msl(cs_src.c_str(), cs_entry.c_str(), msl_buf.data(),
                                                    static_cast<uint32_t>(msl_buf.size()), compile_err, sizeof(compile_err))) {
                    set_err(err_buf, err_cap, std::string("Slang Compute transpile failed: ") + compile_err);
                    delete pack;
                    return nullptr;
                }
                if (ctx && ctx->device) {
                    id<MTLLibrary> lib = compile_slang_source(std::string(msl_buf.data()), cs_entry.c_str());
                    if (!lib) {
                        delete pack;
                        return nullptr;
                    }
                    id<MTLFunction> cfn = [lib newFunctionWithName:[NSString stringWithUTF8String:cs_entry.c_str()]];
                    if (!cfn) {
                        set_err(err_buf, err_cap, "Missing compute entry point " + cs_entry);
                        delete pack;
                        return nullptr;
                    }
                    const uint32_t wg_x = std::max<uint32_t>(1u, pd[@"wgX"] ? [pd[@"wgX"] unsignedIntValue] : 8u);
                    const uint32_t wg_y = std::max<uint32_t>(1u, pd[@"wgY"] ? [pd[@"wgY"] unsignedIntValue] : 8u);
                    const uint32_t wg_z = std::max<uint32_t>(1u, pd[@"wgZ"] ? [pd[@"wgZ"] unsignedIntValue] : 1u);
                    MTLComputePipelineDescriptor* cpd = [[MTLComputePipelineDescriptor alloc] init];
                    cpd.computeFunction = cfn;
                    cpd.threadGroupSizeIsMultipleOfThreadExecutionWidth = (((wg_x * wg_y * wg_z) % 32u) == 0u) ? YES : NO;
                    if (ctx->binary_archive) {
                        cpd.binaryArchives = @[ ctx->binary_archive ];
                    }
                    NSError* cpso_err = nil;
                    id<MTLComputePipelineState> cpso = [ctx->device newComputePipelineStateWithDescriptor:cpd
                                                                                                  options:0
                                                                                               reflection:nil
                                                                                                    error:&cpso_err];
                    if (!cpso) {
                        std::string msg = (cpso_err && cpso_err.localizedDescription) ? cpso_err.localizedDescription.UTF8String : "Compute PSO failed";
                        set_err(err_buf, err_cap, msg);
                        delete pack;
                        return nullptr;
                    }
                    if (ctx->binary_archive) {
                        NSError* arch_err = nil;
                        if ([ctx->binary_archive addComputePipelineFunctionsWithDescriptor:cpd error:&arch_err]) {
                            ctx->archive_dirty = true;
                        }
                    }
                    pack->compiled_compute_psos.push_back(cpso);
                    sum.compiled_pso_count += 1;

                    PassExecRecord prec;
                    prec.kind = PassExecRecord::COMPUTE;
                    prec.name = pname;
                    prec.compute_pso = cpso;
                    prec.wg_x = wg_x;
                    prec.wg_y = wg_y;
                    prec.wg_z = wg_z;
                    prec.bind_rt_scene = [pd[@"bindRayTracingScene"] boolValue];
                    prec.read_tex_indices = std::move(read_ids);
                    prec.write_tex_indices = std::move(write_ids);
                    pack->exec_passes.push_back(std::move(prec));
                }
                continue;
            }
            if (kind != "FULLSCREEN") continue;

            if (pname == "meteor_rt_history_copy") pack->has_rt_history_copy = true;
            else if (pname == "meteor_rt_reflect_history_copy") pack->has_rt_reflect_history_copy = true;
            else if (pname == "meteor_rt_gbuf_copy") pack->has_rt_gbuf_copy = true;

            std::string fs_rel = pd[@"shader"] ? [pd[@"shader"] UTF8String] : "";
            std::string fs_entry = pd[@"entry"] ? [pd[@"entry"] UTF8String] : "";
            std::string fs_src = load_slang_with_includes(root / fs_rel, root);
            if (fs_src.empty()) {
                set_err(err_buf, err_cap, "Missing pass shader: " + fs_rel);
                delete pack;
                return nullptr;
            }
            if (!metality_transpile_slang_to_msl(fs_src.c_str(), fs_entry.c_str(), msl_buf.data(),
                                                static_cast<uint32_t>(msl_buf.size()), compile_err, sizeof(compile_err))) {
                set_err(err_buf, err_cap, std::string("Slang pass transpile failed: ") + compile_err);
                delete pack;
                return nullptr;
            }

            if (ctx && ctx->device) {
                const std::string pass_msl(msl_buf.data());
                const bool needs_rt_bufs =
                    (pass_msl.find("[[buffer(1)]]") != std::string::npos) ||
                    (pass_msl.find("[[buffer(2)]]") != std::string::npos) ||
                    (pass_msl.find("[[buffer(3)]]") != std::string::npos) ||
                    (pass_msl.find("[[buffer(4)]]") != std::string::npos) ||
                    (pass_msl.find("[[buffer(6)]]") != std::string::npos);
                const bool needs_tile_buf = (pass_msl.find("[[buffer(5)]]") != std::string::npos);

                std::string combined_msl = pass_msl + "\n" + kFullscreenQuadVertexMsl;
                id<MTLLibrary> lib = compile_slang_source(combined_msl, fs_entry.c_str());
                if (!lib) {
                    delete pack;
                    return nullptr;
                }
                id<MTLFunction> vfn = [lib newFunctionWithName:@"vs_metality_fullscreen"];
                id<MTLFunction> ffn = [lib newFunctionWithName:[NSString stringWithUTF8String:fs_entry.c_str()]];
                if (!vfn || !ffn) {
                    set_err(err_buf, err_cap, "Missing pass entry point " + fs_entry);
                    delete pack;
                    return nullptr;
                }

                MTLRenderPipelineDescriptor* rpd = [[MTLRenderPipelineDescriptor alloc] init];
                rpd.vertexFunction = vfn;
                rpd.fragmentFunction = ffn;

                bool targets_builtin_color = false;
                for (NSUInteger wi = 0; wi < write_ids.size(); ++wi) {
                    uint32_t tidx = write_ids[wi];
                    if (tidx < tex_formats.size()) {
                        rpd.colorAttachments[wi].pixelFormat = to_mtl_fmt(tex_formats[tidx]);
                        if (wi == 0 && pack->exec_textures[tidx].is_builtin_color) {
                            targets_builtin_color = true;
                        }
                    }
                }
                if (ctx->binary_archive) {
                    rpd.binaryArchives = @[ ctx->binary_archive ];
                }

                NSError* pso_err = nil;
                id<MTLRenderPipelineState> pso = [ctx->device newRenderPipelineStateWithDescriptor:rpd error:&pso_err];
                if (!pso) {
                    std::string msg = (pso_err && pso_err.localizedDescription) ? pso_err.localizedDescription.UTF8String : "Pass PSO failed";
                    set_err(err_buf, err_cap, msg);
                    delete pack;
                    return nullptr;
                }
                if (ctx->binary_archive) {
                    NSError* arch_err = nil;
                    if ([ctx->binary_archive addRenderPipelineFunctionsWithDescriptor:rpd error:&arch_err]) {
                        ctx->archive_dirty = true;
                    }
                }
                pack->compiled_psos.push_back(pso);
                sum.compiled_pso_count += 1;

                id<MTLRenderPipelineState> pso_hdr = nil;
                if (targets_builtin_color && write_ids.size() == 1u) {
                    rpd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
                    NSError* hdr_err = nil;
                    pso_hdr = [ctx->device newRenderPipelineStateWithDescriptor:rpd error:&hdr_err];
                    if (pso_hdr && ctx->binary_archive) {
                        NSError* arch_err = nil;
                        if ([ctx->binary_archive addRenderPipelineFunctionsWithDescriptor:rpd error:&arch_err]) {
                            ctx->archive_dirty = true;
                        }
                    }
                }

                PassExecRecord prec;
                prec.kind = PassExecRecord::FULLSCREEN;
                prec.name = pname;
                prec.render_pso = pso;
                prec.render_pso_hdr = pso_hdr;
                prec.needs_rt_fragment_buffers = needs_rt_bufs;
                prec.needs_tile_light_buffer = needs_tile_buf;
                prec.read_tex_indices = std::move(read_ids);
                prec.write_tex_indices = std::move(write_ids);
                pack->exec_passes.push_back(std::move(prec));
            }
        }

        // Compute minimal Apple Silicon MTLTextureUsage per texture:
        // - Strip MTLTextureUsageShaderWrite from fragment-only render targets (RE-ENABLES hardware lossless compression!)
        // - Strip MTLTextureUsageRenderTarget from compute-only targets
        // - Skip allocating unreferenced textures (deletes dead 21.25 MB __builtin_motion_vectors when unused!)
        for (const auto& prec : pack->exec_passes) {
            for (uint32_t rid : prec.read_tex_indices) {
                if (rid < pack->exec_textures.size()) {
                    pack->exec_textures[rid].is_referenced = true;
                }
            }
            for (uint32_t wid : prec.write_tex_indices) {
                if (wid < pack->exec_textures.size()) {
                    pack->exec_textures[wid].is_referenced = true;
                    if (prec.kind == PassExecRecord::COMPUTE) {
                        pack->exec_textures[wid].written_by_compute = true;
                    } else {
                        pack->exec_textures[wid].written_by_render = true;
                    }
                }
            }
        }
        if (jsMfx && [jsMfx isKindOfClass:[NSDictionary class]]) {
            for (NSString* k in @[@"inputColor", @"depth", @"motionVectors", @"outputColor"]) {
                NSNumber* idxNum = jsMfx[k];
                if (idxNum && [idxNum intValue] >= 0) {
                    uint32_t tidx = static_cast<uint32_t>([idxNum intValue]);
                    if (tidx < pack->exec_textures.size()) {
                        pack->exec_textures[tidx].is_referenced = true;
                        if ([k isEqualToString:@"outputColor"]) {
                            pack->exec_textures[tidx].written_by_compute = true;
                        }
                    }
                }
            }
        }
        auto sync_pingpong_pair_usage = [&](uint32_t a, uint32_t b) {
            if (a < pack->exec_textures.size() && b < pack->exec_textures.size()) {
                auto& ta = pack->exec_textures[a];
                auto& tb = pack->exec_textures[b];
                const bool ref = ta.is_referenced || tb.is_referenced;
                const bool wr = ta.written_by_render || tb.written_by_render;
                const bool wc = ta.written_by_compute || tb.written_by_compute;
                ta.is_referenced = tb.is_referenced = ref;
                ta.written_by_render = tb.written_by_render = wr;
                ta.written_by_compute = tb.written_by_compute = wc;
            }
        };
        if (!pack->has_rt_history_copy) {
            sync_pingpong_pair_usage(pack->rt_gi_temporal_idx, pack->rt_gi_history_idx);
            sync_pingpong_pair_usage(pack->rt_sun_temporal_idx, pack->rt_sun_history_idx);
        }
        if (!pack->has_rt_reflect_history_copy) {
            sync_pingpong_pair_usage(pack->rt_reflect_temporal_idx, pack->rt_reflect_history_idx);
        }
        if (!pack->has_rt_gbuf_copy) {
            sync_pingpong_pair_usage(pack->rt_gbuf_curr_idx, pack->rt_gbuf_history_idx);
        }

        if (ctx && ctx->device) {
            bool builtin_color_written = false;
            pack->needs_scene_color_snapshot = false;
            for (const auto& prec : pack->exec_passes) {
                bool reads_c = false;
                bool writes_c = false;
                for (uint32_t rid : prec.read_tex_indices) {
                    if (rid < pack->exec_textures.size() && pack->exec_textures[rid].is_builtin_color) {
                        reads_c = true;
                    }
                }
                for (uint32_t wid : prec.write_tex_indices) {
                    if (wid < pack->exec_textures.size() && pack->exec_textures[wid].is_builtin_color) {
                        writes_c = true;
                    }
                }
                if (reads_c && (writes_c || builtin_color_written)) {
                    pack->needs_scene_color_snapshot = true;
                    break;
                }
                if (writes_c) {
                    builtin_color_written = true;
                }
            }

            MTLCompileOptions* cull_opts = [[MTLCompileOptions alloc] init];
            cull_opts.languageVersion = MTLLanguageVersion3_1;
            cull_opts.mathMode = MTLMathModeFast;
            NSError* cull_err = nil;
            id<MTLLibrary> cull_lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:kTileLightCullMsl]
                                                                options:cull_opts
                                                                  error:&cull_err];
            if (cull_lib) {
                id<MTLFunction> cull_fn = [cull_lib newFunctionWithName:@"cs_metality_tile_light_cull"];
                if (cull_fn) {
                    MTLComputePipelineDescriptor* tcpd = [[MTLComputePipelineDescriptor alloc] init];
                    tcpd.computeFunction = cull_fn;
                    tcpd.threadGroupSizeIsMultipleOfThreadExecutionWidth = YES;
                    if (ctx->binary_archive) {
                        tcpd.binaryArchives = @[ ctx->binary_archive ];
                    }
                    pack->tile_cull_pso = [ctx->device newComputePipelineStateWithDescriptor:tcpd
                                                                                     options:0
                                                                                  reflection:nil
                                                                                       error:&cull_err];
                    if (pack->tile_cull_pso && ctx->binary_archive) {
                        NSError* arch_err = nil;
                        if ([ctx->binary_archive addComputePipelineFunctionsWithDescriptor:tcpd error:&arch_err]) {
                            ctx->archive_dirty = true;
                        }
                    }
                }
            }
            const uint32_t fb_hdr[4] = {1u, 1u, (1u | (1u << 16u)), 0u};
            pack->tile_light_fallback_buf = [ctx->device newBufferWithBytes:fb_hdr
                                                                     length:256u
                                                                    options:MTLResourceStorageModeShared];
        }

        if (ctx && ctx->device && sum.upscaler_mode != METALITY_UPSCALE_OFF) {
            MetalityUpscalerDesc udesc{};
            udesc.mode = static_cast<MetalityUpscaleMode>(sum.upscaler_mode);
            udesc.input_width = internal_w;
            udesc.input_height = internal_h;
            udesc.output_width = screen_width;
            udesc.output_height = screen_height;
            udesc.color_format = METALITY_FORMAT_RGBA16_FLOAT;
            udesc.depth_format = METALITY_FORMAT_DEPTH32_FLOAT;
            udesc.motion_format = METALITY_FORMAT_RG16_FLOAT;
            udesc.output_format = METALITY_FORMAT_RGBA16_FLOAT;
            udesc.auto_exposure = 1;
            udesc.depth_reversed = 0;
            pack->upscaler = metality_upscaler_create(ctx, &udesc);
        }

        if (out_summary) {
            *out_summary = sum;
        }
        return pack;
    }
}

void metality_pack_destroy(MetalityPackHandle pack) {
    if (!pack) {
        return;
    }
    if (pack->upscaler) {
        metality_upscaler_destroy(pack->upscaler);
        pack->upscaler = nullptr;
    }
    delete pack;
}

bool metality_pack_execute(
    MetalityContext* ctx,
    MetalityPackHandle pack,
    MetalityRtSceneHandle opt_rt_scene,
    MetalityTextureHandle inout_scene_color,
    MetalityTextureHandle scene_depth,
    const MetalityWorldFrameUniforms* frame_uniforms
) {
    if (!ctx || !ctx->device || !ctx->active_command_buffer || !pack || !frame_uniforms) {
        return false;
    }
    @autoreleasepool {
        id<MTLTexture> t_color = find_ctx_texture(ctx, inout_scene_color);
        id<MTLTexture> t_depth = find_ctx_texture(ctx, scene_depth);
        if (!t_color) {
            return false;
        }
        if (pack->exec_passes.empty()) {
            return true;
        }

        if (ctx->active_render_encoder) {
            [ctx->active_render_encoder endEncoding];
            ctx->active_render_encoder = nil;
            ctx->enc_state.reset();
            ctx->active_pass_width = 0;
            ctx->active_pass_height = 0;
        }

        const uint32_t screen_w = static_cast<uint32_t>(t_color.width);
        const uint32_t screen_h = static_cast<uint32_t>(t_color.height);

        // 解碼統一功能旗標與陰影階級（若外部未設定 bit 8，依預設全開與 user_params[3] 推導）
        uint32_t eff_flags = frame_uniforms->tonemap_mode;
        if ((eff_flags & 0x100u) == 0u) {
            const float sq = (frame_uniforms->user_params[3] > 0.0f) ? frame_uniforms->user_params[3] : 1.0f;
            uint32_t tier = 2u;
            if (sq < 0.72f) tier = 0u;
            else if (sq < 0.90f) tier = 1u;
            else if (sq >= 1.15f) tier = 3u;
            eff_flags = (eff_flags & 1u) | 0x1FF00u | (tier << 17);
        }
        const bool f_shadows = (eff_flags & 0x200u) != 0u;
        const bool f_refl    = (eff_flags & 0x400u) != 0u;
        const bool f_clouds  = (eff_flags & 0x1000u) != 0u;
        const bool f_godrays = (eff_flags & 0x2000u) != 0u;
        const bool f_bloom   = (eff_flags & 0x4000u) != 0u;
        const bool f_gi_ao   = (eff_flags & 0x8000u) != 0u;
        const uint32_t shadow_tier = (eff_flags >> 17) & 3u;

        // 1. 僅在有讀寫 Hazard 或外部輸入無 ShaderRead 權限時才執行 SceneColor Blit 快照（Zero-Copy Fast Path）
        const bool do_snapshot = pack->needs_scene_color_snapshot || ((t_color.usage & MTLTextureUsageShaderRead) == 0);
        if (do_snapshot) {
            if (!pack->scene_color_snapshot ||
                pack->snapshot_w != screen_w ||
                pack->snapshot_h != screen_h ||
                pack->snapshot_fmt != t_color.pixelFormat) {
                MTLTextureDescriptor* sd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:t_color.pixelFormat
                                                                                              width:screen_w
                                                                                             height:screen_h
                                                                                          mipmapped:NO];
                sd.usage = MTLTextureUsageShaderRead;
                sd.storageMode = MTLStorageModePrivate;
                pack->scene_color_snapshot = [ctx->device newTextureWithDescriptor:sd];
                pack->scene_color_snapshot.label = @"MetalitySceneColorSnapshot";
                pack->snapshot_w = screen_w;
                pack->snapshot_h = screen_h;
                pack->snapshot_fmt = t_color.pixelFormat;
            }
            id<MTLBlitCommandEncoder> blit = [ctx->active_command_buffer blitCommandEncoder];
            [blit copyFromTexture:t_color
                      sourceSlice:0
                      sourceLevel:0
                     sourceOrigin:MTLOriginMake(0, 0, 0)
                       sourceSize:MTLSizeMake(screen_w, screen_h, 1)
                        toTexture:pack->scene_color_snapshot
                 destinationSlice:0
                 destinationLevel:0
                destinationOrigin:MTLOriginMake(0, 0, 0)];
            [blit endEncoding];
        }

        if (!pack->fallback_black_tex) {
            MTLTextureDescriptor* bd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float
                                                                                          width:1 height:1 mipmapped:NO];
            bd.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
            bd.storageMode = MTLStorageModePrivate;
            pack->fallback_black_tex = [ctx->device newTextureWithDescriptor:bd];
            pack->fallback_black_tex.label = @"MetalityFallbackBlack";
            if (pack->fallback_black_tex) {
                MTLRenderPassDescriptor* brpd = [MTLRenderPassDescriptor renderPassDescriptor];
                brpd.colorAttachments[0].texture = pack->fallback_black_tex;
                brpd.colorAttachments[0].loadAction = MTLLoadActionClear;
                brpd.colorAttachments[0].storeAction = MTLStoreActionStore;
                brpd.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 0.0);
                [[ctx->active_command_buffer renderCommandEncoderWithDescriptor:brpd] endEncoding];
            }
        }

        // 2. 依當前解析度與引擎縮放比動態配置／調整所有中間紋理（1.0x / 0.5x / 0.25x）與 MetalFX Spatial 目標
        const float rscale = std::clamp(ctx->shader_render_scale, 0.5f, 1.0f);
        const bool use_spatial_upscale =
            (rscale < 0.99f) &&
            (screen_w >= 128u && screen_h >= 128u) &&
            [MTLFXSpatialScalerDescriptor supportsDevice:ctx->device];
        const uint32_t color_in_w = use_spatial_upscale
            ? std::max<uint32_t>(64u, static_cast<uint32_t>(std::lround(screen_w * rscale)) & ~1u)
            : screen_w;
        const uint32_t color_in_h = use_spatial_upscale
            ? std::max<uint32_t>(64u, static_cast<uint32_t>(std::lround(screen_h * rscale)) & ~1u)
            : screen_h;
        id<MTLTexture> final_color_target = t_color;
        if (use_spatial_upscale) {
            if (!pack->runtime_spatial_scaler ||
                pack->runtime_scaler_in_w != color_in_w ||
                pack->runtime_scaler_in_h != color_in_h ||
                pack->runtime_scaler_out_w != screen_w ||
                pack->runtime_scaler_out_h != screen_h ||
                pack->runtime_scaler_fmt != t_color.pixelFormat) {
                MTLFXSpatialScalerDescriptor* sd = [[MTLFXSpatialScalerDescriptor alloc] init];
                sd.colorTextureFormat = t_color.pixelFormat;
                sd.outputTextureFormat = t_color.pixelFormat;
                sd.inputWidth = color_in_w;
                sd.inputHeight = color_in_h;
                sd.outputWidth = screen_w;
                sd.outputHeight = screen_h;
                sd.colorProcessingMode = (t_color.pixelFormat == MTLPixelFormatRGBA16Float)
                    ? MTLFXSpatialScalerColorProcessingModeHDR
                    : MTLFXSpatialScalerColorProcessingModePerceptual;
                pack->runtime_spatial_scaler = [sd newSpatialScalerWithDevice:ctx->device];
                pack->runtime_scaler_in_w = color_in_w;
                pack->runtime_scaler_in_h = color_in_h;
                pack->runtime_scaler_out_w = screen_w;
                pack->runtime_scaler_out_h = screen_h;
                pack->runtime_scaler_fmt = t_color.pixelFormat;
            }
            if (pack->runtime_spatial_scaler) {
                const MTLTextureUsage req_in = pack->runtime_spatial_scaler.colorTextureUsage;
                if (!pack->upscale_input_tex ||
                    static_cast<uint32_t>(pack->upscale_input_tex.width) != color_in_w ||
                    static_cast<uint32_t>(pack->upscale_input_tex.height) != color_in_h ||
                    pack->upscale_input_tex.pixelFormat != t_color.pixelFormat ||
                    (pack->upscale_input_tex.usage & req_in) != req_in) {
                    MTLTextureDescriptor* ud = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:t_color.pixelFormat
                                                                                                  width:color_in_w
                                                                                                 height:color_in_h
                                                                                              mipmapped:NO];
                    ud.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead | req_in;
                    ud.storageMode = MTLStorageModePrivate;
                    pack->upscale_input_tex = [ctx->device newTextureWithDescriptor:ud];
                    pack->upscale_input_tex.label = @"MetalitySpatialUpscaleInput";
                }
                if (pack->upscale_input_tex) {
                    final_color_target = pack->upscale_input_tex;
                }
            }
        }

        for (auto& tspec : pack->exec_textures) {
            if (tspec.is_builtin_color || tspec.is_builtin_depth || tspec.is_builtin_shadow || !tspec.is_referenced) {
                continue;
            }
            const uint32_t tw = tspec.use_scale
                    ? std::max<uint32_t>(1u, static_cast<uint32_t>(std::lround(screen_w * tspec.scale_factor * rscale)))
                    : std::max<uint32_t>(1u, tspec.fixed_w);
            const uint32_t th = tspec.use_scale
                    ? std::max<uint32_t>(1u, static_cast<uint32_t>(std::lround(screen_h * tspec.scale_factor * rscale)))
                    : std::max<uint32_t>(1u, tspec.fixed_h);
            if (!tspec.runtime_tex || tspec.alloc_w != tw || tspec.alloc_h != th) {
                MTLTextureDescriptor* td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:to_mtl_fmt(tspec.format)
                                                                                              width:tw
                                                                                             height:th
                                                                                          mipmapped:NO];
                // Minimal Apple Silicon texture usage: omit ShaderWrite on fragment-only targets to enable hardware lossless compression!
                MTLTextureUsage usage = MTLTextureUsageShaderRead;
                if (tspec.written_by_render) usage |= MTLTextureUsageRenderTarget;
                if (tspec.written_by_compute) usage |= MTLTextureUsageShaderWrite;
                if (usage == MTLTextureUsageShaderRead) usage |= MTLTextureUsageRenderTarget;
                td.usage = usage;
                td.storageMode = MTLStorageModePrivate;
                tspec.runtime_tex = [ctx->device newTextureWithDescriptor:td];
                tspec.runtime_tex.label = [NSString stringWithUTF8String:tspec.name.c_str()];
                tspec.alloc_w = tw;
                tspec.alloc_h = th;
            }
        }

        metality::RasterShadowRenderResult shadow_res{};
        if (pack->uses_builtin_shadow_map) {
            if (f_shadows && opt_rt_scene) {
                uint32_t dyn_res = 1280u;
                uint32_t dyn_cascades = 3u;
                if (shadow_tier == 0u) { dyn_res = 768u; dyn_cascades = 2u; }
                else if (shadow_tier == 1u) { dyn_res = 1024u; dyn_cascades = 2u; }
                else if (shadow_tier == 2u) { dyn_res = 1280u; dyn_cascades = 3u; }
                else { dyn_res = 1792u; dyn_cascades = 3u; }
                shadow_res = metality::render_raster_shadow_map(ctx, opt_rt_scene, frame_uniforms, dyn_res, dyn_cascades);
            }
            if (!shadow_res.shadow_atlas_tex) {
                if (!pack->fallback_shadow_tex) {
                    MTLTextureDescriptor* sd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth16Unorm
                                                                                                  width:1 height:1 mipmapped:NO];
                    sd.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
                    sd.storageMode = MTLStorageModePrivate;
                    pack->fallback_shadow_tex = [ctx->device newTextureWithDescriptor:sd];
                    pack->fallback_shadow_cleared = false;
                }
                if (pack->fallback_shadow_tex && !pack->fallback_shadow_cleared) {
                    MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
                    rpd.depthAttachment.texture = pack->fallback_shadow_tex;
                    rpd.depthAttachment.loadAction = MTLLoadActionClear;
                    rpd.depthAttachment.storeAction = MTLStoreActionStore;
                    rpd.depthAttachment.clearDepth = 1.0;
                    [[ctx->active_command_buffer renderCommandEncoderWithDescriptor:rpd] endEncoding];
                    pack->fallback_shadow_cleared = true;
                }
                shadow_res.shadow_atlas_tex = pack->fallback_shadow_tex;
            }
        }

        const bool skip_dusk_blur = (!f_shadows && !f_gi_ao);
        const bool skip_rt_denoise = (!f_shadows && !f_gi_ao);
        std::vector<uint8_t> tex_skipped(pack->exec_textures.size(), 0u);

        auto resolve_read_tex = [&](uint32_t tidx) -> id<MTLTexture> {
            if (tidx >= pack->exec_textures.size()) return nil;
            if (tidx == pack->dusk_lighting_filtered_idx && skip_dusk_blur &&
                pack->dusk_lighting_raw_idx < pack->exec_textures.size()) {
                return pack->exec_textures[pack->dusk_lighting_raw_idx].runtime_tex;
            }
            if (tidx == pack->dusk_sun_filtered_idx && skip_dusk_blur &&
                pack->dusk_sun_raw_idx < pack->exec_textures.size()) {
                return pack->exec_textures[pack->dusk_sun_raw_idx].runtime_tex;
            }
            if (tidx == pack->rt_gi_step2_idx && skip_rt_denoise &&
                pack->rt_gi_raw_idx < pack->exec_textures.size()) {
                return pack->exec_textures[pack->rt_gi_raw_idx].runtime_tex;
            }
            if (tidx == pack->rt_sun_step2_idx && skip_rt_denoise &&
                pack->rt_sun_raw_idx < pack->exec_textures.size()) {
                return pack->exec_textures[pack->rt_sun_raw_idx].runtime_tex;
            }
            if (tex_skipped[tidx]) {
                return pack->fallback_black_tex;
            }
            const auto& ts = pack->exec_textures[tidx];
            if (ts.is_builtin_color) return do_snapshot ? pack->scene_color_snapshot : t_color;
            if (ts.is_builtin_depth) return t_depth ? t_depth : (do_snapshot ? pack->scene_color_snapshot : t_color);
            if (ts.is_builtin_shadow) return shadow_res.shadow_atlas_tex;
            return ts.runtime_tex;
        };

        auto resolve_write_tex = [&](uint32_t tidx) -> id<MTLTexture> {
            if (tidx >= pack->exec_textures.size()) return nil;
            const auto& ts = pack->exec_textures[tidx];
            if (ts.is_builtin_color) return final_color_target;
            if (ts.is_builtin_depth) return t_depth;
            return ts.runtime_tex;
        };

        // 2b. GPU Compute 32x32 Screen-Tile Light Culling（一次執行，供全幀點光源與水面/PBR鏡面共用）
        const metality::RtSceneCustomBindResult rt_state = metality::query_rt_scene_state(opt_rt_scene);
        id<MTLBuffer> active_tile_light_buf = pack->tile_light_fallback_buf;
        if (pack->tile_cull_pso && opt_rt_scene && t_depth && rt_state.emissive_light_count > 0u) {
            const uint32_t tiles_x = (screen_w + 31u) / 32u;
            const uint32_t tiles_y = (screen_h + 31u) / 32u;
            const NSUInteger need_bytes = static_cast<NSUInteger>(4u + tiles_x * tiles_y * 64u) * sizeof(uint32_t);
            if (!pack->tile_light_buf || pack->tile_light_buf_bytes < need_bytes) {
                const NSUInteger alloc_bytes = std::max<NSUInteger>(need_bytes, need_bytes + 32u * 1024u);
                pack->tile_light_buf = [ctx->device newBufferWithLength:alloc_bytes
                                                               options:MTLResourceStorageModePrivate];
                pack->tile_light_buf.label = @"MetalityTileLightGrid";
                pack->tile_light_buf_bytes = alloc_bytes;
            }
            if (pack->tile_light_buf) {
                uint32_t emit_cnt = 0u;
                id<MTLBuffer> emit_buf = metality::get_rt_emissive_light_buffer(ctx->device, opt_rt_scene, &emit_cnt);
                MetalityWorldFrameUniforms cull_u = *frame_uniforms;
                cull_u.width = screen_w;
                cull_u.height = screen_h;
                id<MTLComputeCommandEncoder> ce = [ctx->active_command_buffer computeCommandEncoder];
                if (ce) {
                    ce.label = @"MetalityTileLightCull";
                    [ce setComputePipelineState:pack->tile_cull_pso];
                    [ce setBytes:&cull_u length:sizeof(cull_u) atIndex:0];
                    [ce setBuffer:emit_buf offset:0 atIndex:1];
                    [ce setBuffer:pack->tile_light_buf offset:0 atIndex:2];
                    [ce setTexture:t_depth atIndex:0];
                    [ce dispatchThreadgroups:MTLSizeMake(tiles_x, tiles_y, 1)
                       threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
                    [ce endEncoding];
                    active_tile_light_buf = pack->tile_light_buf;
                }
            }
        }

        // 3. 依排程順序執行 COMPUTE 與 FULLSCREEN Pass（停用的特效直接略過 GPU Pass）
        auto should_skip_pass = [&](const std::string& pname) -> bool {
            if (pname == "meteor_dusk_lighting_blur" ||
                pname == "meteor_dusk_lighting_blur_2") return skip_dusk_blur;
            if (pname == "meteor_rt_temporal" ||
                pname == "meteor_rt_history_copy" ||
                pname == "meteor_rt_atrous_1" ||
                pname == "meteor_rt_atrous_2") return skip_rt_denoise;
            if (pname == "meteor_rt_gbuf_copy") return (skip_rt_denoise && !f_refl);
            if (pname == "meteor_dusk_ssr" ||
                pname == "meteor_rt_reflect_temporal" ||
                pname == "meteor_rt_reflect_history_copy") return !f_refl;
            if (pname == "meteor_clouds_qres") return !f_clouds;
            if (pname == "meteor_volumetric_godrays") return !f_godrays;
            if (pname == "meteor_bloom_down_half" ||
                pname == "meteor_bloom_down_eighth" ||
                pname == "meteor_bloom_down_32" ||
                pname == "meteor_bloom_upsample") return !f_bloom;
            return false;
        };
        for (const auto& prec : pack->exec_passes) {
            if (should_skip_pass(prec.name)) {
                for (uint32_t wid : prec.write_tex_indices) {
                    if (wid < tex_skipped.size()) tex_skipped[wid] = 1u;
                }
                continue;
            }
            id<MTLTexture> primary_write = prec.write_tex_indices.empty()
                    ? final_color_target
                    : resolve_write_tex(prec.write_tex_indices[0]);
            if (!primary_write) {
                continue;
            }
            const uint32_t target_w = static_cast<uint32_t>(primary_write.width);
            const uint32_t target_h = static_cast<uint32_t>(primary_write.height);

            MetalityWorldFrameUniforms local_u = *frame_uniforms;
            local_u.tonemap_mode = eff_flags;
            if (local_u.tonemap_params[0] <= 0.0f) {
                local_u.tonemap_params[0] = 0.90f;
                local_u.tonemap_params[1] = 1.16f;
                local_u.tonemap_params[2] = 1.16f;
                local_u.tonemap_params[3] = 1.25f;
            }
            if (local_u.lighting_params[0] <= 0.0f) {
                local_u.lighting_params[0] = 1.22f;
                local_u.lighting_params[1] = 1.18f;
                local_u.lighting_params[2] = 1.12f;
                local_u.lighting_params[3] = 0.65f;
            }
            if (local_u.surface_params[0] <= 0.0f) {
                local_u.surface_params[0] = 1.15f;
                local_u.surface_params[1] = 1.15f;
                local_u.surface_params[2] = 1.15f;
                local_u.surface_params[3] = 1.10f;
            }
            local_u.width = target_w;
            local_u.height = target_h;
            local_u.rt_hardware_active = (pack->enable_hw_raytracing && rt_state.hardware_active) ? 1u : 0u;
            local_u.rt_instance_count = rt_state.instance_count;
            if (rt_state.voxel_valid) {
                local_u.voxel_origin[0] = rt_state.voxel_origin[0];
                local_u.voxel_origin[1] = rt_state.voxel_origin[1];
                local_u.voxel_origin[2] = rt_state.voxel_origin[2];
                local_u.voxel_origin[3] = static_cast<int32_t>(rt_state.voxel_flags != 0u ? rt_state.voxel_flags : 1u);
            }

            if (prec.kind == PassExecRecord::COMPUTE) {
                if (!prec.compute_pso) continue;
                id<MTLComputeCommandEncoder> ce = [ctx->active_command_buffer computeCommandEncoder];
                if (!ce) return false;
                ce.label = [NSString stringWithUTF8String:prec.name.c_str()];
                [ce setComputePipelineState:prec.compute_pso];

                if (prec.bind_rt_scene) {
                    metality::RtSceneCustomBindResult rtb =
                            metality::bind_rt_scene_for_custom_compute(ctx->device, opt_rt_scene, ce, 1u, 2u, 3u, 4u, 5u, 6u, 7u);
                    if (active_tile_light_buf) {
                        [ce setBuffer:active_tile_light_buf offset:0 atIndex:8u];
                    }
                    local_u.rt_hardware_active = rtb.hardware_active ? 1u : 0u;
                    local_u.rt_instance_count = rtb.instance_count;
                    if (rtb.voxel_valid) {
                        local_u.voxel_origin[0] = rtb.voxel_origin[0];
                        local_u.voxel_origin[1] = rtb.voxel_origin[1];
                        local_u.voxel_origin[2] = rtb.voxel_origin[2];
                        local_u.voxel_origin[3] = static_cast<int32_t>(rtb.voxel_flags != 0u ? rtb.voxel_flags : 1u);
                    }
                } else if (active_tile_light_buf) {
                    [ce setBuffer:active_tile_light_buf offset:0 atIndex:8u];
                }
                [ce setBytes:&local_u length:sizeof(local_u) atIndex:0];

                NSUInteger tslot = 0;
                for (uint32_t rid : prec.read_tex_indices) {
                    [ce setTexture:resolve_read_tex(rid) atIndex:tslot++];
                }
                for (uint32_t wid : prec.write_tex_indices) {
                    [ce setTexture:resolve_write_tex(wid) atIndex:tslot++];
                }
                [ce dispatchThreads:MTLSizeMake(target_w, target_h, 1)
                      threadsPerThreadgroup:MTLSizeMake(prec.wg_x, prec.wg_y, prec.wg_z)];
                [ce endEncoding];
            } else {
                id<MTLRenderPipelineState> chosen_pso =
                        (primary_write == final_color_target && t_color.pixelFormat == MTLPixelFormatRGBA16Float && prec.render_pso_hdr)
                        ? prec.render_pso_hdr
                        : prec.render_pso;
                if (!chosen_pso) continue;

                MTLRenderPassDescriptor* rpd = [MTLRenderPassDescriptor renderPassDescriptor];
                for (NSUInteger wi = 0; wi < prec.write_tex_indices.size(); ++wi) {
                    id<MTLTexture> wt = resolve_write_tex(prec.write_tex_indices[wi]);
                    if (wt) {
                        rpd.colorAttachments[wi].texture = wt;
                        rpd.colorAttachments[wi].loadAction = MTLLoadActionDontCare;
                        rpd.colorAttachments[wi].storeAction = MTLStoreActionStore;
                    }
                }
                id<MTLRenderCommandEncoder> enc = [ctx->active_command_buffer renderCommandEncoderWithDescriptor:rpd];
                if (!enc) return false;
                enc.label = [NSString stringWithUTF8String:prec.name.c_str()];
                [enc setRenderPipelineState:chosen_pso];
                [enc setViewport:MTLViewport{0.0, 0.0, static_cast<double>(target_w), static_cast<double>(target_h), 0.0, 1.0}];
                [enc setFragmentBytes:&local_u length:sizeof(local_u) atIndex:0];
                // Gate RT/voxel/shadow/tile buffer binding strictly to passes that declare them (-8..12 sections_mutex locks/frame!)
                if (prec.needs_rt_fragment_buffers) {
                    metality::bind_rt_scene_for_custom_fragment(ctx->device, opt_rt_scene, enc, 1u, 2u, 3u, 4u, 6u);
                }
                if (prec.needs_tile_light_buffer && active_tile_light_buf) {
                    [enc setFragmentBuffer:active_tile_light_buf offset:0 atIndex:5u];
                }
                for (NSUInteger ri = 0; ri < prec.read_tex_indices.size(); ++ri) {
                    [enc setFragmentTexture:resolve_read_tex(prec.read_tex_indices[ri]) atIndex:ri];
                }
                [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
                [enc endEncoding];
            }
        }

        // Zero-copy Zenith temporal/G-Buffer ping-pong swap (eliminates rt_history_copy, rt_reflect_history_copy, rt_gbuf_copy!)
        auto swap_pingpong_tex = [&](uint32_t a, uint32_t b) {
            if (a < pack->exec_textures.size() && b < pack->exec_textures.size()) {
                std::swap(pack->exec_textures[a].runtime_tex, pack->exec_textures[b].runtime_tex);
            }
        };
        if (!pack->has_rt_history_copy && !skip_rt_denoise) {
            swap_pingpong_tex(pack->rt_gi_temporal_idx, pack->rt_gi_history_idx);
            swap_pingpong_tex(pack->rt_sun_temporal_idx, pack->rt_sun_history_idx);
        }
        if (!pack->has_rt_reflect_history_copy && f_refl) {
            swap_pingpong_tex(pack->rt_reflect_temporal_idx, pack->rt_reflect_history_idx);
        }
        if (!pack->has_rt_gbuf_copy) {
            swap_pingpong_tex(pack->rt_gbuf_curr_idx, pack->rt_gbuf_history_idx);
        }

        if (final_color_target != t_color && pack->runtime_spatial_scaler) {
            const MTLTextureUsage req_out = pack->runtime_spatial_scaler.outputTextureUsage;
            id<MTLTexture> scale_dst = t_color;
            if ((t_color.usage & req_out) != req_out) {
                if (!pack->upscale_output_tex ||
                    static_cast<uint32_t>(pack->upscale_output_tex.width) != screen_w ||
                    static_cast<uint32_t>(pack->upscale_output_tex.height) != screen_h ||
                    pack->upscale_output_tex.pixelFormat != t_color.pixelFormat ||
                    (pack->upscale_output_tex.usage & req_out) != req_out) {
                    MTLTextureDescriptor* od = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:t_color.pixelFormat
                                                                                                  width:screen_w
                                                                                                 height:screen_h
                                                                                              mipmapped:NO];
                    od.usage = req_out | MTLTextureUsageShaderRead;
                    od.storageMode = MTLStorageModePrivate;
                    pack->upscale_output_tex = [ctx->device newTextureWithDescriptor:od];
                    pack->upscale_output_tex.label = @"MetalitySpatialUpscaleOutput";
                }
                scale_dst = pack->upscale_output_tex;
            }
            if (scale_dst) {
                pack->runtime_spatial_scaler.colorTexture = final_color_target;
                pack->runtime_spatial_scaler.outputTexture = scale_dst;
                pack->runtime_spatial_scaler.inputContentWidth = color_in_w;
                pack->runtime_spatial_scaler.inputContentHeight = color_in_h;
                [pack->runtime_spatial_scaler encodeToCommandBuffer:ctx->active_command_buffer];
                if (scale_dst != t_color) {
                    id<MTLBlitCommandEncoder> copy_blit = [ctx->active_command_buffer blitCommandEncoder];
                    [copy_blit copyFromTexture:scale_dst
                                   sourceSlice:0
                                   sourceLevel:0
                                  sourceOrigin:MTLOriginMake(0, 0, 0)
                                    sourceSize:MTLSizeMake(screen_w, screen_h, 1)
                                     toTexture:t_color
                              destinationSlice:0
                              destinationLevel:0
                             destinationOrigin:MTLOriginMake(0, 0, 0)];
                    [copy_blit endEncoding];
                }
            }
        }
        return true;
    }
}

} // extern "C"

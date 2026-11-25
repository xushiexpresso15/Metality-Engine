#import "metality/metal_context.hpp"

#include <cctype>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

namespace {

std::string trim(const std::string& s) {
    size_t a = 0;
    while (a < s.size() && std::isspace(static_cast<unsigned char>(s[a]))) ++a;
    size_t b = s.size();
    while (b > a && std::isspace(static_cast<unsigned char>(s[b - 1]))) --b;
    return s.substr(a, b - a);
}

bool is_ident_char(char c) {
    return std::isalnum(static_cast<unsigned char>(c)) || c == '_';
}

// 替換識別字（確保不會誤改長識別字的子字串，例如 float16_t4 不會被 float16_t 截斷）
std::string replace_identifier(const std::string& src, const std::string& from, const std::string& to) {
    std::string out;
    out.reserve(src.size());
    size_t i = 0;
    while (i < src.size()) {
        if (src.compare(i, from.size(), from) == 0) {
            const bool left_ok = (i == 0) || !is_ident_char(src[i - 1]);
            const bool right_ok = (i + from.size() >= src.size()) || !is_ident_char(src[i + from.size()]);
            if (left_ok && right_ok) {
                out += to;
                i += from.size();
                continue;
            }
        }
        out.push_back(src[i]);
        ++i;
    }
    return out;
}

std::string normalize_texture_scalar_type(const std::string& elem) {
    if (elem.rfind("half", 0) == 0 || elem.rfind("float16_t", 0) == 0) return "half";
    if (elem.rfind("float", 0) == 0) return "float";
    if (elem.rfind("uint", 0) == 0) return "uint";
    if (elem.rfind("int", 0) == 0) return "int";
    return elem;
}

bool contains_identifier(const std::string& text, const std::string& ident) {
    if (ident.empty()) return false;
    size_t pos = 0;
    while ((pos = text.find(ident, pos)) != std::string::npos) {
        const bool left_ok = (pos == 0) || !is_ident_char(text[pos - 1]);
        const bool right_ok = (pos + ident.size() >= text.size()) || !is_ident_char(text[pos + ident.size()]);
        if (left_ok && right_ok) {
            return true;
        }
        pos += ident.size();
    }
    return false;
}

std::string replace_template_resource_type(
    std::string src,
    const std::string& template_name,
    const std::string& replacement_prefix,
    const std::string& replacement_suffix
) {
    size_t pos = 0;
    while ((pos = src.find(template_name, pos)) != std::string::npos) {
        const bool left_ok = (pos == 0) || !is_ident_char(src[pos - 1]);
        size_t p = pos + template_name.size();
        while (p < src.size() && std::isspace(static_cast<unsigned char>(src[p]))) ++p;
        if (!left_ok || p >= src.size() || src[p] != '<') {
            pos += template_name.size();
            continue;
        }
        int depth = 1;
        size_t gt = p + 1;
        while (gt < src.size() && depth > 0) {
            if (src[gt] == '<') ++depth;
            else if (src[gt] == '>') --depth;
            ++gt;
        }
        if (depth != 0) {
            pos += template_name.size();
            continue;
        }
        std::string elem = trim(src.substr(p + 1, (gt - 1) - (p + 1)));
        if (template_name == "DepthTexture2D") {
            elem = "float";
        } else if (template_name == "Texture2D") {
            elem = normalize_texture_scalar_type(elem);
        }
        std::string repl = replacement_prefix + elem + replacement_suffix;
        src.replace(pos, gt - pos, repl);
        pos += repl.size();
    }
    return src;
}

std::string translate_slang_types(std::string s) {
    s = replace_identifier(s, "float16_t4", "half4");
    s = replace_identifier(s, "float16_t3", "half3");
    s = replace_identifier(s, "float16_t2", "half2");
    s = replace_identifier(s, "float16_t", "half");
    s = replace_identifier(s, "uint16_t", "ushort");
    s = replace_identifier(s, "int16_t", "short");
    s = replace_template_resource_type(s, "Texture2D", "texture2d<", ">");
    s = replace_template_resource_type(s, "DepthTexture2D", "depth2d<", ">");
    s = replace_template_resource_type(s, "StructuredBuffer", "device const ", "*");
    s = replace_identifier(s, "discard", "discard_fragment()");
    // 若使用者原本已寫 discard_fragment()，修正可能誤產生之重複括號
    size_t pos = 0;
    while ((pos = s.find("discard_fragment()()", pos)) != std::string::npos) {
        s.replace(pos, 20, "discard_fragment()");
    }
    return s;
}

// 括號配對展開 mul(A, B) -> ((A) * (B))
std::string rewrite_mul_calls(const std::string& src) {
    std::string out;
    out.reserve(src.size());
    size_t i = 0;
    while (i < src.size()) {
        if (src.compare(i, 3, "mul") == 0 &&
            (i == 0 || !is_ident_char(src[i - 1])) &&
            i + 3 < src.size() && !is_ident_char(src[i + 3])) {
            size_t j = i + 3;
            while (j < src.size() && std::isspace(static_cast<unsigned char>(src[j]))) ++j;
            if (j < src.size() && src[j] == '(') {
                int depth = 1;
                size_t k = j + 1;
                size_t comma_pos = std::string::npos;
                while (k < src.size() && depth > 0) {
                    if (src[k] == '(') ++depth;
                    else if (src[k] == ')') --depth;
                    else if (src[k] == ',' && depth == 1 && comma_pos == std::string::npos) {
                        comma_pos = k;
                    }
                    ++k;
                }
                if (depth == 0 && comma_pos != std::string::npos) {
                    std::string lhs = rewrite_mul_calls(trim(src.substr(j + 1, comma_pos - (j + 1))));
                    std::string rhs = rewrite_mul_calls(trim(src.substr(comma_pos + 1, (k - 1) - (comma_pos + 1))));
                    out += "((" + lhs + ") * (" + rhs + "))";
                    i = k;
                    continue;
                }
            }
        }
        out.push_back(src[i]);
        ++i;
    }
    return out;
}

// 轉譯 .Sample(...) -> .sample(...), .SampleLevel(s, uv, lod) -> .sample(s, uv, level(lod)),
// 並將 .Load(coord) 重寫為 metality_tex_load(tex, coord)
std::string rewrite_texture_ops(const std::string& src) {
    std::string tmp = src;
    size_t pos = 0;
    while ((pos = tmp.find(".Sample(", pos)) != std::string::npos) {
        tmp.replace(pos, 8, ".sample(");
        pos += 8;
    }

    // .SampleLevel(sampler, uv, lod) -> .sample(sampler, uv, level(lod))
    pos = 0;
    while ((pos = tmp.find(".SampleLevel(", pos)) != std::string::npos) {
        size_t lp = pos + 12;
        int depth = 1;
        size_t k = lp + 1;
        std::vector<size_t> commas;
        while (k < tmp.size() && depth > 0) {
            char c = tmp[k];
            if (c == '(') ++depth;
            else if (c == ')') --depth;
            else if (c == ',' && depth == 1) commas.push_back(k);
            ++k;
        }
        if (depth == 0 && commas.size() == 2) {
            std::string args0 = tmp.substr(lp + 1, commas[1] - (lp + 1));
            std::string lodArg = trim(tmp.substr(commas[1] + 1, (k - 1) - (commas[1] + 1)));
            std::string repl = ".sample(" + args0 + ", level(" + lodArg + "))";
            tmp.replace(pos, k - pos, repl);
            pos += repl.size();
        } else {
            pos += 13;
        }
    }

    // 2. 以括號與識別字邊界匹配 .Load(expr)
    std::string out;
    out.reserve(tmp.size() + 64);
    size_t i = 0;
    while (i < tmp.size()) {
        if (tmp.compare(i, 6, ".Load(") == 0) {
            // 回溯找出 tex 識別字起點
            size_t tex_end = out.size();
            size_t tex_start = tex_end;
            while (tex_start > 0 && is_ident_char(out[tex_start - 1])) {
                --tex_start;
            }
            std::string tex_name = out.substr(tex_start);
            if (!tex_name.empty()) {
                int depth = 1;
                size_t k = i + 6;
                while (k < tmp.size() && depth > 0) {
                    if (tmp[k] == '(') ++depth;
                    else if (tmp[k] == ')') --depth;
                    ++k;
                }
                if (depth == 0) {
                    std::string arg = tmp.substr(i + 6, (k - 1) - (i + 6));
                    out.resize(tex_start);
                    out += "metality_tex_load(" + tex_name + ", " + arg + ")";
                    i = k;
                    continue;
                }
            }
        }
        out.push_back(tmp[i]);
        ++i;
    }
    return out;
}

std::string strip_comments(const std::string& src) {
    std::string out;
    out.reserve(src.size());
    size_t i = 0;
    while (i < src.size()) {
        if (i + 1 < src.size() && src[i] == '/' && src[i + 1] == '/') {
            while (i < src.size() && src[i] != '\n') ++i;
            continue;
        }
        if (i + 1 < src.size() && src[i] == '/' && src[i + 1] == '*') {
            i += 2;
            while (i + 1 < src.size() && !(src[i] == '*' && src[i + 1] == '/')) ++i;
            if (i + 1 < src.size()) i += 2;
            continue;
        }
        out.push_back(src[i]);
        ++i;
    }
    return out;
}

struct GlobalResource {
    enum Kind { ConstantBuf, StructuredBuf, Tex2D, DepthTex2D, RwTex2D, AccelStruct, Sampler } kind;
    std::string elem_type;
    std::string name;
};

struct ParsedStageFunc {
    std::string stage; // "vertex", "fragment", "kernel"
    std::string ret_type;
    std::string name;
    std::string params;
    std::string body;
};

std::string convert_struct_semantics(const std::string& struct_block) {
    std::istringstream iss(struct_block);
    std::ostringstream oss;
    std::string line;
    while (std::getline(iss, line)) {
        std::string t = trim(line);
        if (t.empty()) {
            oss << "\n";
            continue;
        }
        size_t semi = line.find(';');
        size_t colon = line.find(':');
        if (colon != std::string::npos && semi != std::string::npos && colon < semi) {
            std::string decl = trim(line.substr(0, colon));
            std::string sem = trim(line.substr(colon + 1, semi - (colon + 1)));
            std::string attr;
            if (sem == "SV_Position") {
                attr = " [[position]]";
            } else if (sem.rfind("SV_Target", 0) == 0) {
                std::string idx = sem.substr(9);
                if (idx.empty()) idx = "0";
                attr = " [[color(" + idx + ")]]";
            }
            oss << "    " << translate_slang_types(decl) << attr << ";\n";
        } else {
            oss << translate_slang_types(line) << "\n";
        }
    }
    return oss.str();
}

std::string convert_func_body(const std::string& raw_body) {
    std::string s = rewrite_mul_calls(raw_body);
    s = rewrite_texture_ops(s);
    s = translate_slang_types(s);
    return s;
}

} // namespace

extern "C" {

bool metality_transpile_slang_to_msl(
    const char* slang_source,
    const char* entry_name,
    char* out_msl_buf,
    uint32_t out_msl_cap,
    char* err_buf,
    uint32_t err_cap
) {
    if (!slang_source || !out_msl_buf || out_msl_cap == 0) {
        return false;
    }

    const std::string cleaned = strip_comments(slang_source);
    const std::string target_entry = entry_name ? trim(entry_name) : "";

    std::vector<std::string> structs;
    std::vector<GlobalResource> resources;
    std::vector<ParsedStageFunc> stage_funcs;
    std::vector<std::string> helper_funcs;
    bool uses_metality_std = false;

    size_t pos = 0;
    const size_t n = cleaned.size();
    while (pos < n) {
        while (pos < n && std::isspace(static_cast<unsigned char>(cleaned[pos]))) ++pos;
        if (pos >= n) break;

        // 1. module / import 宣告
        if (cleaned.compare(pos, 6, "module") == 0 && !is_ident_char(cleaned[pos + 6])) {
            size_t semi = cleaned.find(';', pos);
            if (semi == std::string::npos) break;
            pos = semi + 1;
            continue;
        }
        if (cleaned.compare(pos, 6, "import") == 0 && !is_ident_char(cleaned[pos + 6])) {
            size_t semi = cleaned.find(';', pos);
            if (semi == std::string::npos) break;
            std::string imp = cleaned.substr(pos, semi - pos);
            if (imp.find("metality") != std::string::npos) {
                uses_metality_std = true;
            }
            pos = semi + 1;
            continue;
        }

        // 2. struct 宣告
        if (cleaned.compare(pos, 6, "struct") == 0 && !is_ident_char(cleaned[pos + 6])) {
            size_t lbrace = cleaned.find('{', pos);
            if (lbrace == std::string::npos) break;
            int depth = 1;
            size_t k = lbrace + 1;
            while (k < n && depth > 0) {
                if (cleaned[k] == '{') ++depth;
                else if (cleaned[k] == '}') --depth;
                ++k;
            }
            size_t semi = cleaned.find(';', k);
            if (semi == std::string::npos) semi = k - 1;
            structs.push_back(convert_struct_semantics(cleaned.substr(pos, semi - pos + 1)));
            pos = semi + 1;
            continue;
        }

        // 3. [shader("vertex" | "fragment" | "compute")] 函式宣告
        if (cleaned[pos] == '[') {
            std::string stage_kind;
            size_t attr_scan = pos;
            while (attr_scan < n && cleaned[attr_scan] == '[') {
                size_t rbrack = cleaned.find(']', attr_scan);
                if (rbrack == std::string::npos) break;
                std::string attr = cleaned.substr(attr_scan, rbrack - attr_scan + 1);
                if (attr.find("\"vertex\"") != std::string::npos) stage_kind = "vertex";
                else if (attr.find("\"fragment\"") != std::string::npos) stage_kind = "fragment";
                else if (attr.find("\"compute\"") != std::string::npos) stage_kind = "kernel";
                attr_scan = rbrack + 1;
                while (attr_scan < n && std::isspace(static_cast<unsigned char>(cleaned[attr_scan]))) ++attr_scan;
            }

            size_t lparen = cleaned.find('(', attr_scan);
            size_t lbrace = cleaned.find('{', attr_scan);
            if (lparen != std::string::npos && lbrace != std::string::npos && lparen < lbrace) {
                std::string sig_head = trim(cleaned.substr(attr_scan, lparen - attr_scan));
                size_t last_sp = sig_head.find_last_of(" \t\r\n");
                std::string ret_type = (last_sp != std::string::npos) ? trim(sig_head.substr(0, last_sp)) : "void";
                std::string fn_name = (last_sp != std::string::npos) ? trim(sig_head.substr(last_sp + 1)) : sig_head;

                int pdepth = 1;
                size_t rparen = lparen + 1;
                while (rparen < n && pdepth > 0) {
                    if (cleaned[rparen] == '(') ++pdepth;
                    else if (cleaned[rparen] == ')') --pdepth;
                    ++rparen;
                }
                std::string params = trim(cleaned.substr(lparen + 1, (rparen - 1) - (lparen + 1)));
                // 重新定位函數主體左大括號（跳過 ': SV_Target0'）
                size_t body_lbrace = cleaned.find('{', rparen);
                if (body_lbrace != std::string::npos) {
                    int bdepth = 1;
                    size_t k = body_lbrace + 1;
                    while (k < n && bdepth > 0) {
                        if (cleaned[k] == '{') ++bdepth;
                        else if (cleaned[k] == '}') --bdepth;
                        ++k;
                    }
                    std::string body = cleaned.substr(body_lbrace, k - body_lbrace);
                    if (!stage_kind.empty()) {
                        stage_funcs.push_back({stage_kind, translate_slang_types(ret_type), fn_name, params, body});
                    } else {
                        helper_funcs.push_back(translate_slang_types(ret_type) + " " + fn_name + "(" +
                                               translate_slang_types(params) + ") " + convert_func_body(body));
                    }
                    pos = k;
                    continue;
                }
            }
        }

        // 4. 全域資源宣告 (ConstantBuffer<T>, StructuredBuffer<T>, Texture2D<T>, SamplerState)
        size_t semi = cleaned.find(';', pos);
        size_t lbrace = cleaned.find('{', pos);
        if (semi != std::string::npos && (lbrace == std::string::npos || semi < lbrace)) {
            std::string stmt = trim(cleaned.substr(pos, semi - pos));
            auto parse_template_decl = [&](const std::string& prefix, GlobalResource::Kind kind) -> bool {
                if (stmt.rfind(prefix, 0) != 0) return false;
                size_t lt = stmt.find('<');
                size_t gt = stmt.rfind('>');
                if (lt == std::string::npos || gt == std::string::npos || gt <= lt) return false;
                std::string elem = translate_slang_types(trim(stmt.substr(lt + 1, gt - lt - 1)));
                std::string var_name = trim(stmt.substr(gt + 1));
                resources.push_back({kind, elem, var_name});
                return true;
            };

            if (!parse_template_decl("ConstantBuffer", GlobalResource::ConstantBuf) &&
                !parse_template_decl("StructuredBuffer", GlobalResource::StructuredBuf) &&
                !parse_template_decl("RWTexture2D", GlobalResource::RwTex2D) &&
                !parse_template_decl("DepthTexture2D", GlobalResource::DepthTex2D) &&
                !parse_template_decl("Texture2D", GlobalResource::Tex2D)) {
                if (stmt.rfind("SamplerState", 0) == 0) {
                    std::string var_name = trim(stmt.substr(12));
                    resources.push_back({GlobalResource::Sampler, "", var_name});
                } else if (stmt.rfind("RaytracingAccelerationStructure", 0) == 0) {
                    std::string var_name = trim(stmt.substr(31));
                    resources.push_back({GlobalResource::AccelStruct, "", var_name});
                } else if (stmt.rfind("static const ", 0) == 0 ||
                           stmt.rfind("const ", 0) == 0 ||
                           stmt.rfind("constexpr ", 0) == 0) {
                    std::string cdecl = translate_slang_types(stmt);
                    if (cdecl.rfind("static const ", 0) == 0) {
                        cdecl.replace(0, 12, "constant ");
                    } else if (cdecl.rfind("const ", 0) == 0) {
                        cdecl.replace(0, 5, "constant ");
                    }
                    helper_funcs.push_back(cdecl + ";");
                }
            }
            pos = semi + 1;
            continue;
        }

        // 5. 一般輔助函式宣告
        if (lbrace != std::string::npos) {
            size_t lparen = cleaned.find('(', pos);
            if (lparen != std::string::npos && lparen < lbrace) {
                int bdepth = 1;
                size_t k = lbrace + 1;
                while (k < n && bdepth > 0) {
                    if (cleaned[k] == '{') ++bdepth;
                    else if (cleaned[k] == '}') --bdepth;
                    ++k;
                }
                std::string fn_head = cleaned.substr(pos, lbrace - pos);
                std::string fn_body = cleaned.substr(lbrace, k - lbrace);
                helper_funcs.push_back(translate_slang_types(fn_head) + convert_func_body(fn_body));
                pos = k;
                continue;
            }
        }

        ++pos;
    }

    if (stage_funcs.empty()) {
        if (err_buf && err_cap > 0) {
            std::strncpy(err_buf, "No [shader(\"...\")] entry points found in Slang source", err_cap - 1);
            err_buf[err_cap - 1] = '\0';
        }
        return false;
    }

    bool uses_raytracing = false;
    for (const auto& res : resources) {
        if (res.kind == GlobalResource::AccelStruct) {
            uses_raytracing = true;
            break;
        }
    }

    std::ostringstream msl;
    msl << "#include <metal_stdlib>\n";
    if (uses_raytracing) {
        msl << "#include <metal_raytracing>\nusing namespace metal::raytracing;\n";
    }
    msl << "using namespace metal;\n\n";
    msl << "template<typename T>\n"
        << "inline vec<T, 4> metality_tex_load(texture2d<T> t, int3 c) {\n"
        << "    return t.read(uint2(c.xy), uint(c.z));\n"
        << "}\n"
        << "inline float4 metality_tex_load(depth2d<float> t, int3 c) {\n"
        << "    return float4(t.read(uint2(c.xy), uint(c.z)), 0.0f, 0.0f, 1.0f);\n"
        << "}\n"
        << "template<typename T>\n"
        << "inline int2 metality_tex_size(texture2d<T> t) {\n"
        << "    return int2(t.get_width(), t.get_height());\n"
        << "}\n"
        << "inline int2 metality_tex_size(depth2d<float> t) {\n"
        << "    return int2(t.get_width(), t.get_height());\n"
        << "}\n\n";

    if (uses_metality_std) {
        msl << "// metality/std built-in cross-dimension frame uniforms, samplers, HLSL/Slang helpers & EDR\n"
            << "constant sampler metality_linear_clamp(coord::normalized, address::clamp_to_edge, filter::linear);\n"
            << "constant sampler metality_nearest_clamp(coord::normalized, address::clamp_to_edge, filter::nearest);\n"
            << "constant sampler metality_linear_repeat(coord::normalized, address::repeat, filter::linear);\n"
            << "constant sampler metality_shadow_cmp(coord::normalized, address::clamp_to_edge, filter::linear, compare_func::less_equal);\n"
            << "inline float metality_shadow_cmp_sample(depth2d<float> t, float2 uv, float cmp_depth) {\n"
            << "    return t.sample_compare(metality_shadow_cmp, uv, cmp_depth);\n"
            << "}\n"
            << "struct MetalityFrame {\n"
            << "    float4x4 invProj;\n"
            << "    float4x4 proj;\n"
            << "    float4x4 viewRot;\n"
            << "    float4x4 invViewRot;\n"
            << "    float4x4 invViewProj;\n"
            << "    float4x4 prevViewProj;\n"
            << "    float4 camWorldPos;\n"
            << "    float4 sunWorldDir;\n"
            << "    float4 moonWorldDir;\n"
            << "    float4 skyColor;\n"
            << "    float4 fogColor;\n"
            << "    float4 sunriseColor;\n"
            << "    int4 voxelOrigin;\n"
            << "    uint width;\n"
            << "    uint height;\n"
            << "    float timeSeconds;\n"
            << "    float rainStrength;\n"
            << "    float thunderStrength;\n"
            << "    float seaLevel;\n"
            << "    float edrHeadroom;\n"
            << "    int dimensionId;\n"
            << "    int submergedMode;\n"
            << "    uint rtInstanceCount;\n"
            << "    uint rtHardwareActive;\n"
            << "    uint tonemapMode;\n"
            << "    float4 userParams;\n"
            << "    float4 tonemapParams;\n"
            << "    float4 lightingParams;\n"
            << "    float4 surfaceParams;\n"
            << "};\n"
            << "template<typename T, typename W>\n"
            << "inline T lerp(T a, T b, W t) { return mix(a, b, t); }\n"
            << "template<typename T>\n"
            << "inline T frac(T x) { return fract(x); }\n"
            << "template<typename T>\n"
            << "inline T saturate(T x) { return clamp(x, T(0), T(1)); }\n"
            << "template<typename T>\n"
            << "inline T ddx(T x) { return dfdx(x); }\n"
            << "template<typename T>\n"
            << "inline T ddy(T x) { return dfdy(x); }\n"
            << "inline half metality_decode_roughness(half4 labpbr) {\n"
            << "    return pow(1.0h - labpbr.r, 2.0h);\n"
            << "}\n"
            << "inline half3 metality_tonemap_edr(half3 hdr, half headroom) {\n"
            << "    half3 x = hdr * (1.0h / max(headroom, 1.0h));\n"
            << "    half3 mapped = (x * (2.51h * x + 0.03h)) / (x * (2.43h * x + 0.59h) + 0.14h);\n"
            << "    return clamp(mapped, 0.0h, 1.0h) * headroom;\n"
            << "}\n\n";
    }

    for (const auto& st : structs) {
        msl << st << "\n\n";
    }
    for (const auto& hf : helper_funcs) {
        msl << hf << "\n\n";
    }

    bool emitted_any = false;
    for (const auto& fn : stage_funcs) {
        if (!target_entry.empty() && fn.name != target_entry) {
            continue;
        }
        emitted_any = true;

        std::string converted_body = convert_func_body(fn.body);
        std::vector<std::string> msl_params;
        uint32_t next_buf_slot = 0;
        uint32_t next_tex_slot = 0;
        uint32_t next_samp_slot = 0;

        // 處理原本的函式參數
        if (!fn.params.empty()) {
            std::istringstream pss(fn.params);
            std::string pitem;
            while (std::getline(pss, pitem, ',')) {
                std::string pt = trim(pitem);
                if (pt.empty()) continue;

                size_t colon = pt.find(':');
                if (colon != std::string::npos) {
                    std::string decl = translate_slang_types(trim(pt.substr(0, colon)));
                    std::string sem = trim(pt.substr(colon + 1));
                    if (sem == "SV_Position") {
                        msl_params.push_back(decl + " [[position]]");
                    } else if (sem == "SV_DispatchThreadID") {
                        msl_params.push_back(decl + " [[thread_position_in_grid]]");
                    } else {
                        msl_params.push_back(decl + " [[stage_in]]");
                    }
                } else if (fn.stage == "vertex") {
                    // 將 Vertex Struct 轉為 Pull-Model 讀取（與 Metality UMA Zero-Copy 幾何對齊）
                    size_t sp = pt.find_last_of(" \t");
                    if (sp != std::string::npos) {
                        std::string vtype = translate_slang_types(trim(pt.substr(0, sp)));
                        std::string vname = trim(pt.substr(sp + 1));
                        msl_params.push_back("uint _metality_vid [[vertex_id]]");
                        msl_params.push_back("device const " + vtype + "* _metality_vbuf [[buffer(0)]]");
                        next_buf_slot = 1;
                        size_t bpos = converted_body.find('{');
                        if (bpos != std::string::npos) {
                            converted_body.insert(bpos + 1, "\n    " + vtype + " " + vname + " = _metality_vbuf[_metality_vid];");
                        }
                    } else {
                        msl_params.push_back(translate_slang_types(pt));
                    }
                } else if (fn.stage == "fragment") {
                    msl_params.push_back(translate_slang_types(pt) + " [[stage_in]]");
                } else {
                    msl_params.push_back(translate_slang_types(pt));
                }
            }
        }

        // 若單檔僅有一個入口函式，保留宣告順序的確定性槽位；若有多階段共用單檔，僅綁定本函式有用到的資源
        const bool deterministic_single_entry = (stage_funcs.size() == 1u);
        for (const auto& res : resources) {
            if (!deterministic_single_entry && !contains_identifier(converted_body, res.name)) {
                continue;
            }
            if (res.kind == GlobalResource::ConstantBuf) {
                msl_params.push_back("constant " + res.elem_type + "& " + res.name +
                                     " [[buffer(" + std::to_string(next_buf_slot++) + ")]]");
            } else if (res.kind == GlobalResource::StructuredBuf) {
                msl_params.push_back("device const " + res.elem_type + "* " + res.name +
                                     " [[buffer(" + std::to_string(next_buf_slot++) + ")]]");
            } else if (res.kind == GlobalResource::Tex2D) {
                msl_params.push_back("texture2d<" + normalize_texture_scalar_type(res.elem_type) + "> " + res.name +
                                     " [[texture(" + std::to_string(next_tex_slot++) + ")]]");
            } else if (res.kind == GlobalResource::DepthTex2D) {
                msl_params.push_back("depth2d<float> " + res.name +
                                     " [[texture(" + std::to_string(next_tex_slot++) + ")]]");
            } else if (res.kind == GlobalResource::RwTex2D) {
                msl_params.push_back("texture2d<" + normalize_texture_scalar_type(res.elem_type) + ", access::write> " + res.name +
                                     " [[texture(" + std::to_string(next_tex_slot++) + ")]]");
            } else if (res.kind == GlobalResource::AccelStruct) {
                msl_params.push_back("instance_acceleration_structure " + res.name +
                                     " [[buffer(" + std::to_string(next_buf_slot++) + ")]]");
            } else if (res.kind == GlobalResource::Sampler) {
                msl_params.push_back("sampler " + res.name +
                                     " [[sampler(" + std::to_string(next_samp_slot++) + ")]]");
            }
        }

        msl << fn.stage << " " << fn.ret_type << " " << fn.name << "(\n";
        for (size_t pi = 0; pi < msl_params.size(); ++pi) {
            msl << "    " << msl_params[pi] << (pi + 1 < msl_params.size() ? "," : "") << "\n";
        }
        msl << ") " << converted_body << "\n\n";
    }

    if (!emitted_any) {
        if (err_buf && err_cap > 0) {
            std::string msg = "Entry point not found: " + target_entry;
            std::strncpy(err_buf, msg.c_str(), err_cap - 1);
            err_buf[err_cap - 1] = '\0';
        }
        return false;
    }

    const std::string result = msl.str();
    if (result.size() + 1 > out_msl_cap) {
        if (err_buf && err_cap > 0) {
            std::strncpy(err_buf, "Output MSL buffer too small", err_cap - 1);
            err_buf[err_cap - 1] = '\0';
        }
        return false;
    }

    std::memcpy(out_msl_buf, result.c_str(), result.size() + 1);
    return true;
}

} // extern "C"

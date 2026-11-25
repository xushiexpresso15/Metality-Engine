#import "metality/metal_context.hpp"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace {

constexpr uint32_t kSpvMagic = 0x07230203u;
constexpr uint16_t kSpvOpExtInst = 12u;
constexpr uint16_t kSpvOpEntryPoint = 15u;
constexpr uint16_t kSpvOpExecutionMode = 16u;
constexpr uint16_t kSpvOpExecutionModeId = 331u;
constexpr uint16_t kSpvOpCapability = 17u;
constexpr uint16_t kSpvOpExtension = 10u;
constexpr uint16_t kSpvOpExtInstImport = 11u;
constexpr uint16_t kSpvOpMemoryModel = 14u;
constexpr uint16_t kSpvOpSourceContinued = 2u;
constexpr uint16_t kSpvOpSource = 3u;
constexpr uint16_t kSpvOpSourceExtension = 4u;
constexpr uint16_t kSpvOpName = 5u;
constexpr uint16_t kSpvOpMemberName = 6u;
constexpr uint16_t kSpvOpString = 7u;
constexpr uint16_t kSpvOpLine = 8u;
constexpr uint16_t kSpvOpNoLine = 317u;
constexpr uint16_t kSpvOpModuleProcessed = 330u;
constexpr uint16_t kSpvOpDecorate = 71u;
constexpr uint16_t kSpvOpMemberDecorate = 72u;
constexpr uint16_t kSpvOpDecorationGroup = 73u;
constexpr uint16_t kSpvOpGroupDecorate = 74u;
constexpr uint16_t kSpvOpGroupMemberDecorate = 75u;
constexpr uint16_t kSpvOpDecorateId = 332u;
constexpr uint16_t kSpvOpDecorateString = 5632u;
constexpr uint16_t kSpvOpMemberDecorateString = 5633u;

constexpr uint16_t kSpvOpTypeFloat = 22u;
constexpr uint16_t kSpvOpTypeVector = 23u;
constexpr uint16_t kSpvOpFNegate = 127u;
constexpr uint16_t kSpvOpFAdd = 129u;
constexpr uint16_t kSpvOpFSub = 131u;
constexpr uint16_t kSpvOpFMul = 133u;
constexpr uint16_t kSpvOpFDiv = 136u;
constexpr uint16_t kSpvOpFRem = 138u;
constexpr uint16_t kSpvOpFMod = 141u;
constexpr uint16_t kSpvOpVectorTimesScalar = 142u;
constexpr uint16_t kSpvOpDot = 148u;
constexpr uint16_t kSpvOpKill = 252u;
constexpr uint16_t kSpvOpDemoteToHelperInvocation = 5380u;

constexpr uint32_t kSpvDecorationRelaxedPrecision = 0u;
constexpr uint32_t kSpvDecorationBuiltIn = 11u;
constexpr uint32_t kSpvExecModelFragment = 4u;

bool is_pre_type_section_opcode(uint16_t op) {
    switch (op) {
        case kSpvOpCapability:
        case kSpvOpExtension:
        case kSpvOpExtInstImport:
        case kSpvOpMemoryModel:
        case kSpvOpEntryPoint:
        case kSpvOpExecutionMode:
        case kSpvOpExecutionModeId:
        case kSpvOpSourceContinued:
        case kSpvOpSource:
        case kSpvOpSourceExtension:
        case kSpvOpName:
        case kSpvOpMemberName:
        case kSpvOpString:
        case kSpvOpLine:
        case kSpvOpNoLine:
        case kSpvOpModuleProcessed:
        case kSpvOpDecorate:
        case kSpvOpMemberDecorate:
        case kSpvOpDecorationGroup:
        case kSpvOpGroupDecorate:
        case kSpvOpGroupMemberDecorate:
        case kSpvOpDecorateId:
        case kSpvOpDecorateString:
        case kSpvOpMemberDecorateString:
            return true;
        default:
            return false;
    }
}

bool is_fp16_candidate_alu(uint16_t op) {
    switch (op) {
        case kSpvOpExtInst:
        case kSpvOpFNegate:
        case kSpvOpFAdd:
        case kSpvOpFSub:
        case kSpvOpFMul:
        case kSpvOpFDiv:
        case kSpvOpFRem:
        case kSpvOpFMod:
        case kSpvOpVectorTimesScalar:
        case kSpvOpDot:
            return true;
        default:
            return false;
    }
}

std::string trim_copy(const std::string& s) {
    size_t a = 0;
    while (a < s.size() && std::isspace(static_cast<unsigned char>(s[a]))) ++a;
    size_t b = s.size();
    while (b > a && std::isspace(static_cast<unsigned char>(s[b - 1]))) --b;
    return s.substr(a, b - a);
}

bool is_ident_char(char c) {
    const unsigned char uc = static_cast<unsigned char>(c);
    return std::isalnum(uc) != 0 || c == '_';
}

bool parse_sampler_param(const std::string& param, std::string& out_sampler_name, uint32_t& out_slot) {
    const size_t samp_attr = param.find("[[sampler(");
    if (samp_attr == std::string::npos) {
        return false;
    }
    const size_t num_start = samp_attr + 10;
    size_t num_end = num_start;
    while (num_end < param.size() && std::isdigit(static_cast<unsigned char>(param[num_end]))) {
        ++num_end;
    }
    if (num_end == num_start) {
        return false;
    }
    const uint32_t slot = static_cast<uint32_t>(std::strtoul(param.substr(num_start, num_end - num_start).c_str(), nullptr, 10));

    // 擷取參數型別為 sampler <name> 的識別字（排除 texture2d 等參數）
    std::string prefix = trim_copy(param.substr(0, samp_attr));
    size_t sp = prefix.rfind(' ');
    if (sp == std::string::npos) {
        return false;
    }
    std::string type_part = trim_copy(prefix.substr(0, sp));
    std::string name_part = trim_copy(prefix.substr(sp + 1));
    if (type_part.rfind("sampler") == std::string::npos || name_part.empty()) {
        return false;
    }
    out_sampler_name = name_part;
    out_slot = slot;
    return true;
}

size_t find_matching_brace(const std::string& code, size_t open_brace) {
    int depth = 0;
    for (size_t i = open_brace; i < code.size(); ++i) {
        if (code[i] == '{') {
            ++depth;
        } else if (code[i] == '}') {
            --depth;
            if (depth == 0) {
                return i;
            }
        }
    }
    return std::string::npos;
}

std::string remap_identifiers(
    const std::string& src,
    const std::unordered_set<std::string>& targets,
    const std::string& replacement_name
) {
    if (targets.empty()) {
        return src;
    }
    std::string out;
    out.reserve(src.size() + targets.size() * 16u);
    size_t i = 0;
    while (i < src.size()) {
        if (is_ident_char(src[i]) && !std::isdigit(static_cast<unsigned char>(src[i]))) {
            size_t j = i + 1;
            while (j < src.size() && is_ident_char(src[j])) {
                ++j;
            }
            std::string tok = src.substr(i, j - i);
            if (targets.count(tok)) {
                out += replacement_name;
            } else {
                out += tok;
            }
            i = j;
        } else {
            out.push_back(src[i]);
            ++i;
        }
    }
    return out;
}

std::vector<std::string> split_top_level_params(const std::string& params_str) {
    std::vector<std::string> out;
    int depth = 0;
    size_t start = 0;
    for (size_t i = 0; i < params_str.size(); ++i) {
        char c = params_str[i];
        if (c == '(' || c == '<' || c == '[') ++depth;
        else if (c == ')' || c == '>' || c == ']') { if (depth > 0) --depth; }
        else if (c == ',' && depth == 0) {
            out.push_back(params_str.substr(start, i - start));
            start = i + 1;
        }
    }
    if (start < params_str.size()) {
        out.push_back(params_str.substr(start));
    }
    return out;
}

} // namespace

extern "C" {

bool metality_spirv_optimize_for_apple_gpu(
    const uint32_t* in_words,
    uint32_t in_word_count,
    bool inject_relaxed_fp16,
    uint32_t* out_words,
    uint32_t out_word_cap,
    MetalitySpirvOptStats* out_stats
) {
    if (!in_words || in_word_count < 5u || in_words[0] != kSpvMagic) {
        return false;
    }

    bool is_fragment = false;
    uint32_t insert_word_offset = 5u;
    std::unordered_set<uint32_t> existing_relaxed;
    std::unordered_set<uint32_t> builtin_ids;
    std::unordered_set<uint32_t> f32_scalar_types;
    std::unordered_set<uint32_t> f32_or_vec_types;
    std::vector<uint32_t> inject_ids;
    std::unordered_set<uint32_t> inject_seen;
    uint32_t discard_ops = 0u;

    bool seen_type_section = false;
    uint32_t cursor = 5u;
    while (cursor < in_word_count) {
        const uint32_t w0 = in_words[cursor];
        const uint16_t wc = static_cast<uint16_t>(w0 >> 16);
        const uint16_t op = static_cast<uint16_t>(w0 & 0xFFFFu);
        if (wc == 0u || cursor + wc > in_word_count) {
            return false;
        }

        if (!seen_type_section) {
            if (is_pre_type_section_opcode(op)) {
                insert_word_offset = cursor + wc;
            } else {
                seen_type_section = true;
                insert_word_offset = cursor;
            }
        }

        if (op == kSpvOpEntryPoint && wc >= 2u) {
            if (in_words[cursor + 1u] == kSpvExecModelFragment) {
                is_fragment = true;
            }
        } else if (op == kSpvOpDecorate && wc >= 3u) {
            const uint32_t target_id = in_words[cursor + 1u];
            const uint32_t deco = in_words[cursor + 2u];
            if (deco == kSpvDecorationRelaxedPrecision) {
                existing_relaxed.insert(target_id);
            } else if (deco == kSpvDecorationBuiltIn) {
                builtin_ids.insert(target_id);
            }
        } else if (op == kSpvOpTypeFloat && wc >= 3u) {
            if (in_words[cursor + 2u] == 32u) {
                const uint32_t tid = in_words[cursor + 1u];
                f32_scalar_types.insert(tid);
                f32_or_vec_types.insert(tid);
            }
        } else if (op == kSpvOpTypeVector && wc >= 4u) {
            const uint32_t elem_type = in_words[cursor + 2u];
            if (f32_scalar_types.count(elem_type)) {
                f32_or_vec_types.insert(in_words[cursor + 1u]);
            }
        } else if (op == kSpvOpKill || op == kSpvOpDemoteToHelperInvocation) {
            ++discard_ops;
        } else if (inject_relaxed_fp16 && is_fragment && is_fp16_candidate_alu(op) && wc >= 3u) {
            const uint32_t res_type = in_words[cursor + 1u];
            const uint32_t res_id = in_words[cursor + 2u];
            if (f32_or_vec_types.count(res_type) &&
                !existing_relaxed.count(res_id) &&
                !builtin_ids.count(res_id) &&
                !inject_seen.count(res_id)) {
                inject_seen.insert(res_id);
                inject_ids.push_back(res_id);
            }
        }

        cursor += wc;
    }

    const uint32_t injected_words = static_cast<uint32_t>(inject_ids.size()) * 3u;
    const uint32_t out_total = in_word_count + injected_words;
    if (out_stats) {
        out_stats->input_word_count = in_word_count;
        out_stats->output_word_count = out_total;
        out_stats->discard_op_count = discard_ops;
        out_stats->relaxed_fp16_decorations = static_cast<uint32_t>(inject_ids.size());
    }

    if (!out_words || out_word_cap < out_total) {
        return false;
    }

    std::memcpy(out_words, in_words, insert_word_offset * sizeof(uint32_t));
    uint32_t out_pos = insert_word_offset;
    for (uint32_t target_id : inject_ids) {
        out_words[out_pos++] = (3u << 16) | kSpvOpDecorate;
        out_words[out_pos++] = target_id;
        out_words[out_pos++] = kSpvDecorationRelaxedPrecision;
    }
    const uint32_t tail_words = in_word_count - insert_word_offset;
    if (tail_words > 0u) {
        std::memcpy(out_words + out_pos, in_words + insert_word_offset, tail_words * sizeof(uint32_t));
    }
    return true;
}

bool metality_rewrite_msl_samplers_and_fp16(
    const char* in_msl,
    bool strip_discard_safety,
    char* out_msl,
    uint32_t out_msl_cap,
    uint32_t* out_inlined_samplers
) {
    if (!in_msl || !out_msl || out_msl_cap == 0u) {
        return false;
    }
    std::string code(in_msl);
    uint32_t inlined_count = 0u;

    // 1. 確保 SOLID HSR 管線零 discard_fragment()
    if (strip_discard_safety) {
        size_t pos = 0;
        while ((pos = code.find("discard_fragment();", pos)) != std::string::npos) {
            code.replace(pos, 19, "{}");
            pos += 2;
        }
    }

    // 2. 將超過 Metal 16 sampler slots 限制的 [[sampler(N >= 16)]] 轉為體內 constexpr sampler，
    //    徹底避免 Complementary / Bliss / Solas 等多貼圖光影編譯錯誤。
    const char* stage_markers[] = {"fragment ", "vertex ", "kernel "};
    for (const char* marker : stage_markers) {
        size_t search_pos = 0;
        while ((search_pos = code.find(marker, search_pos)) != std::string::npos) {
            const bool boundary_ok = (search_pos == 0) || !std::isalnum(static_cast<unsigned char>(code[search_pos - 1]));
            if (!boundary_ok) {
                search_pos += std::strlen(marker);
                continue;
            }
            size_t lp = code.find('(', search_pos);
            if (lp == std::string::npos) {
                break;
            }
            // 僅匹配 entry function 宣告（前面不應先遇到 ';' 或 '{'）
            size_t semi = code.find(';', search_pos);
            size_t lb = code.find('{', search_pos);
            if ((semi != std::string::npos && semi < lp) || (lb != std::string::npos && lb < lp)) {
                search_pos += std::strlen(marker);
                continue;
            }

            int pdepth = 1;
            size_t rp = lp + 1;
            while (rp < code.size() && pdepth > 0) {
                if (code[rp] == '(') ++pdepth;
                else if (code[rp] == ')') --pdepth;
                ++rp;
            }
            if (pdepth != 0) {
                break;
            }
            size_t body_brace = code.find('{', rp);
            if (body_brace == std::string::npos) {
                break;
            }
            // 避免把純前向宣告誤當作本體
            size_t pre_semi = code.find(';', rp);
            if (pre_semi != std::string::npos && pre_semi < body_brace) {
                search_pos = pre_semi + 1;
                continue;
            }

            std::string raw_params = code.substr(lp + 1, (rp - 1) - (lp + 1));
            std::vector<std::string> params = split_top_level_params(raw_params);
            struct ParsedParam {
                std::string text;
                bool is_sampler;
                std::string sampler_name;
                uint32_t slot;
            };
            std::vector<ParsedParam> parsed;
            parsed.reserve(params.size());
            size_t total_samplers = 0;
            bool has_slot_ge_16 = false;
            for (const auto& p : params) {
                std::string trimmed = trim_copy(p);
                if (trimmed.empty()) continue;
                std::string sname;
                uint32_t slot = 0;
                const bool is_s = parse_sampler_param(trimmed, sname, slot);
                if (is_s) {
                    ++total_samplers;
                    if (slot >= 16u) has_slot_ge_16 = true;
                }
                parsed.push_back({trimmed, is_s, sname, slot});
            }

            std::vector<std::string> kept_params;
            std::unordered_set<std::string> moved_samplers;
            const bool needs_folding = (total_samplers > 16u) || has_slot_ge_16;
            size_t kept_samplers = 0;
            for (const auto& pp : parsed) {
                if (pp.is_sampler && needs_folding && (pp.slot >= 16u || kept_samplers >= 15u)) {
                    moved_samplers.insert(pp.sampler_name);
                    ++inlined_count;
                } else {
                    if (pp.is_sampler) {
                        ++kept_samplers;
                    }
                    kept_params.push_back(pp.text);
                }
            }

            const size_t end_brace = find_matching_brace(code, body_brace);
            if (!moved_samplers.empty() && end_brace != std::string::npos) {
                std::ostringstream new_params;
                for (size_t k = 0; k < kept_params.size(); ++k) {
                    if (k > 0) new_params << ", ";
                    new_params << kept_params[k];
                }
                static constexpr const char kOverflowSamplerName[] = "_metality_overflow_sampler";
                std::string body_inner = code.substr(body_brace + 1, end_brace - (body_brace + 1));
                std::string remapped_body = remap_identifiers(body_inner, moved_samplers, kOverflowSamplerName);
                std::ostringstream injected;
                injected << "\n    constexpr sampler " << kOverflowSamplerName
                         << "(coord::normalized, address::clamp_to_edge, filter::linear, mip_filter::linear);\n"
                         << "    (void)" << kOverflowSamplerName << ";\n";
                std::string tail_after_rp = code.substr(rp, (body_brace + 1) - rp);
                std::string replacement = "(" + new_params.str() + ")" + tail_after_rp + injected.str() + remapped_body + "}";
                code.replace(lp, (end_brace + 1) - lp, replacement);
                search_pos = lp + replacement.size();
            } else {
                search_pos = body_brace + 1;
            }
        }
    }

    if (out_inlined_samplers) {
        *out_inlined_samplers = inlined_count;
    }
    if (code.size() + 1u > out_msl_cap) {
        return false;
    }
    std::memcpy(out_msl, code.c_str(), code.size() + 1u);
    return true;
}

} // extern "C"

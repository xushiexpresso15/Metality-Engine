#include "metality/render_graph.hpp"

#include <algorithm>
#include <cstring>

namespace {

uint64_t bytes_per_pixel(MetalityPixelFormat fmt) {
    switch (fmt) {
        case METALITY_FORMAT_RGBA8_UNORM:
        case METALITY_FORMAT_RGBA8_SRGB:
        case METALITY_FORMAT_BGRA8_SRGB:
        case METALITY_FORMAT_RGB10A2_UNORM:
        case METALITY_FORMAT_RG11B10_FLOAT:
        case METALITY_FORMAT_R32_FLOAT:
        case METALITY_FORMAT_DEPTH32_FLOAT:
        case METALITY_FORMAT_RG16_FLOAT:
            return 4;
        case METALITY_FORMAT_R16_FLOAT:
            return 2;
        case METALITY_FORMAT_R8_UNORM:
        case METALITY_FORMAT_R8_SINT:
            return 1;
        case METALITY_FORMAT_RGBA16_FLOAT:
        case METALITY_FORMAT_RGBA16_SINT:
            return 8;
        case METALITY_FORMAT_R32_SINT:
            return 4;
        case METALITY_FORMAT_RGBA32_FLOAT:
            return 16;
    }
    return 4;
}

bool pass_resolutions_match(
    const MetalityRenderGraphImpl& graph,
    const MetalityRenderGraphImpl::RenderPassNode& a,
    const MetalityRenderGraphImpl::RenderPassNode& b
) {
    if (a.writes.empty() || b.writes.empty()) {
        return false;
    }
    const auto& ta = graph.textures[a.writes.front()];
    const auto& tb = graph.textures[b.writes.front()];
    return ta.width == tb.width && ta.height == tb.height;
}

} // namespace

extern "C" {

MetalityGraphHandle metality_graph_create(void) {
    return new MetalityRenderGraphImpl();
}

void metality_graph_destroy(MetalityGraphHandle graph) {
    delete graph;
}

uint32_t metality_graph_declare_texture(
    MetalityGraphHandle graph,
    const char* name,
    MetalityPixelFormat format,
    uint32_t width,
    uint32_t height,
    bool force_persistent
) {
    if (!graph) {
        return UINT32_MAX;
    }
    const uint32_t id = static_cast<uint32_t>(graph->textures.size());
    MetalityRenderGraphImpl::VirtualTexture tex;
    tex.id = id;
    tex.name = name ? name : "unnamed_tex";
    tex.format = format;
    tex.width = width;
    tex.height = height;
    tex.force_persistent = force_persistent;
    graph->textures.push_back(std::move(tex));
    return id;
}

uint32_t metality_graph_add_pass(
    MetalityGraphHandle graph,
    const char* name,
    const uint32_t* read_tex_ids,
    uint32_t read_count,
    bool samples_neighbors,
    const uint32_t* write_tex_ids,
    uint32_t write_count
) {
    if (!graph) {
        return UINT32_MAX;
    }
    const uint32_t id = static_cast<uint32_t>(graph->passes.size());
    MetalityRenderGraphImpl::RenderPassNode node;
    node.id = id;
    node.name = name ? name : "unnamed_pass";
    node.samples_neighbors = samples_neighbors;
    if (read_tex_ids && read_count > 0) {
        node.reads.assign(read_tex_ids, read_tex_ids + read_count);
    }
    if (write_tex_ids && write_count > 0) {
        node.writes.assign(write_tex_ids, write_tex_ids + write_count);
    }
    graph->passes.push_back(std::move(node));
    return id;
}

void metality_graph_compile_tbdr(
    MetalityGraphHandle graph,
    MetalityGraphPlanStats* out_stats
) {
    if (!graph) {
        return;
    }

    // 1. 計算每張虛擬貼圖的生命週期 (First Write ~ Last Read)
    for (auto& tex : graph->textures) {
        tex.first_write_pass = -1;
        tex.last_read_pass = -1;
        tex.promoted_to_memoryless = false;
    }

    for (size_t p = 0; p < graph->passes.size(); ++p) {
        const auto& pass = graph->passes[p];
        for (uint32_t tid : pass.writes) {
            if (tid < graph->textures.size() && graph->textures[tid].first_write_pass < 0) {
                graph->textures[tid].first_write_pass = static_cast<int32_t>(p);
            }
        }
        for (uint32_t tid : pass.reads) {
            if (tid < graph->textures.size()) {
                graph->textures[tid].last_read_pass = static_cast<int32_t>(p);
            }
        }
    }

    // 2. TBDR 自動 Pass 融合 (Automatic Tile Memory Fusion)：
    //    若下一個 Pass 不做鄰近像素採樣 (samples_neighbors == false，例如延遲光照、大氣合成、色調映射)
    //    且解析度與前一個 Pass 一致，即可融合進同一個 MTLRenderCommandEncoder，透過晶片內 Imageblock / Tile SRAM 直傳！
    uint32_t current_group = 0;
    for (size_t p = 0; p < graph->passes.size(); ++p) {
        if (p == 0) {
            graph->passes[p].fused_group_id = 0;
            continue;
        }
        const auto& prev = graph->passes[p - 1];
        auto& curr = graph->passes[p];

        const bool can_fuse_into_tile =
            !curr.samples_neighbors && pass_resolutions_match(*graph, prev, curr);

        if (!can_fuse_into_tile) {
            current_group += 1;
        }
        curr.fused_group_id = current_group;
    }

    // 3. 自動晉升為 MTLStorageModeMemoryless：
    //    若某張貼圖非歷史保留貼圖 (!force_persistent)，且其寫入與最後讀取皆位於同一個融合 Tile Group 內，
    //    代表完全不需要分配系統記憶體 (0 MB VRAM)，也無須執行 StoreAction / LoadAction 讀寫 DRAM！
    uint64_t dram_without_tbdr = 0;
    uint64_t dram_with_tbdr = 0;
    uint32_t memoryless_count = 0;

    for (auto& tex : graph->textures) {
        const uint64_t footprint =
            static_cast<uint64_t>(tex.width) * static_cast<uint64_t>(tex.height) * bytes_per_pixel(tex.format);
        dram_without_tbdr += footprint;

        if (!tex.force_persistent &&
            tex.first_write_pass >= 0 &&
            tex.last_read_pass >= tex.first_write_pass) {
            const uint32_t write_grp = graph->passes[tex.first_write_pass].fused_group_id;
            const uint32_t read_grp = graph->passes[tex.last_read_pass].fused_group_id;
            if (write_grp == read_grp) {
                tex.promoted_to_memoryless = true;
                memoryless_count += 1;
                continue;
            }
        }
        dram_with_tbdr += footprint;
    }

    if (out_stats) {
        std::memset(out_stats, 0, sizeof(MetalityGraphPlanStats));
        out_stats->total_passes = static_cast<uint32_t>(graph->passes.size());
        out_stats->fused_encoder_groups =
            graph->passes.empty() ? 0 : (current_group + 1);
        out_stats->total_textures = static_cast<uint32_t>(graph->textures.size());
        out_stats->memoryless_textures = memoryless_count;
        out_stats->dram_bytes_without_tbdr = dram_without_tbdr;
        out_stats->dram_bytes_with_tbdr = dram_with_tbdr;
        out_stats->saved_dram_bytes_per_frame = dram_without_tbdr - dram_with_tbdr;
    }
}

} // extern "C"

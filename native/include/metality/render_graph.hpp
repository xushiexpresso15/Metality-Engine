#pragma once

#include "metality/metality_c_api.h"

#include <cstdint>
#include <string>
#include <vector>

struct MetalityRenderGraphImpl {
    struct VirtualTexture {
        uint32_t id = 0;
        std::string name;
        MetalityPixelFormat format = METALITY_FORMAT_RGBA8_UNORM;
        uint32_t width = 0;
        uint32_t height = 0;
        bool force_persistent = false;

        int32_t first_write_pass = -1;
        int32_t last_read_pass = -1;
        bool promoted_to_memoryless = false;
    };

    struct RenderPassNode {
        uint32_t id = 0;
        std::string name;
        std::vector<uint32_t> reads;
        std::vector<uint32_t> writes;
        bool samples_neighbors = false;
        uint32_t fused_group_id = 0;
    };

    std::vector<VirtualTexture> textures;
    std::vector<RenderPassNode> passes;
};

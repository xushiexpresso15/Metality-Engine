#pragma once

#include "metality/metality_c_api.h"

#include <cstdint>
#include <map>
#include <mutex>

struct MetalityChunkArenaImpl {
    uint64_t total_capacity = 0;
    uint64_t alignment = 256;
    uint64_t used_bytes = 0;

    mutable std::mutex mutex;
    // 以 offset 排序的空閒區間表 (offset -> length)，支援釋放時 O(log N) 前後相鄰區間無縫合併（零外部碎片累積）
    std::map<uint64_t, uint64_t> free_by_offset;
};

#include "metality/chunk_arena.hpp"

extern "C" {

MetalityArenaHandle metality_arena_create(uint64_t total_capacity_bytes, uint32_t alignment_bytes) {
    if (total_capacity_bytes == 0) {
        return nullptr;
    }
    auto* arena = new MetalityChunkArenaImpl();
    const uint64_t align = alignment_bytes >= 16 ? alignment_bytes : 256;
    arena->alignment = align;
    arena->total_capacity = (total_capacity_bytes + align - 1ULL) & ~(align - 1ULL);
    arena->used_bytes = 0;
    arena->free_by_offset.emplace(0ULL, arena->total_capacity);
    return arena;
}

void metality_arena_destroy(MetalityArenaHandle arena) {
    delete arena;
}

bool metality_arena_alloc(
    MetalityArenaHandle arena,
    uint64_t size_bytes,
    uint64_t* out_offset,
    uint64_t* out_aligned_size
) {
    if (!arena || size_bytes == 0) {
        return false;
    }
    const uint64_t align = arena->alignment;
    const uint64_t req = (size_bytes + align - 1ULL) & ~(align - 1ULL);

    std::lock_guard<std::mutex> lock(arena->mutex);
    for (auto it = arena->free_by_offset.begin(); it != arena->free_by_offset.end(); ++it) {
        const uint64_t span_off = it->first;
        const uint64_t span_len = it->second;
        if (span_len >= req) {
            const uint64_t rem = span_len - req;
            arena->free_by_offset.erase(it);
            if (rem > 0) {
                arena->free_by_offset.emplace(span_off + req, rem);
            }
            arena->used_bytes += req;
            if (out_offset) {
                *out_offset = span_off;
            }
            if (out_aligned_size) {
                *out_aligned_size = req;
            }
            return true;
        }
    }
    return false;
}

void metality_arena_free(MetalityArenaHandle arena, uint64_t offset, uint64_t aligned_size) {
    if (!arena || aligned_size == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(arena->mutex);
    if (arena->used_bytes >= aligned_size) {
        arena->used_bytes -= aligned_size;
    } else {
        arena->used_bytes = 0;
    }

    uint64_t merged_off = offset;
    uint64_t merged_len = aligned_size;

    auto next_it = arena->free_by_offset.lower_bound(offset);

    // 檢查並合併前一個相鄰空閒區間
    if (next_it != arena->free_by_offset.begin()) {
        auto prev_it = std::prev(next_it);
        if (prev_it->first + prev_it->second == merged_off) {
            merged_off = prev_it->first;
            merged_len += prev_it->second;
            arena->free_by_offset.erase(prev_it);
        }
    }

    // 檢查並合併後一個相鄰空閒區間
    if (next_it != arena->free_by_offset.end() && merged_off + merged_len == next_it->first) {
        merged_len += next_it->second;
        arena->free_by_offset.erase(next_it);
    }

    arena->free_by_offset.emplace(merged_off, merged_len);
}

uint64_t metality_arena_used_bytes(MetalityArenaHandle arena) {
    if (!arena) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(arena->mutex);
    return arena->used_bytes;
}

uint32_t metality_arena_free_span_count(MetalityArenaHandle arena) {
    if (!arena) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(arena->mutex);
    return static_cast<uint32_t>(arena->free_by_offset.size());
}

} // extern "C"

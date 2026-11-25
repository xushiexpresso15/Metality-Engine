package dev.metality.impl;

import dev.metality.api.material.BlockMaterialDescriptor;
import dev.metality.api.material.BlockMaterialRegistry;
import dev.metality.api.material.RenderLayerKind;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 執行緒安全且熱路徑零分配之方塊材質表：
 * 將字串方塊 ID（如 "minecraft:stone", "create:brass_casing"）扁平化為連續緊湊之 18-byte 原生陣列。
 */
public final class DefaultBlockMaterialRegistry implements BlockMaterialRegistry {

    public static final int NATIVE_ENTRY_BYTES = 18;
    public static final String FALLBACK_SOLID_ID = "metality:fallback_solid";

    private final ConcurrentHashMap<String, Integer> idByName = new ConcurrentHashMap<>();
    private final List<BlockMaterialDescriptor> entries = new ArrayList<>();
    private final AtomicLong version = new AtomicLong(1L);

    public DefaultBlockMaterialRegistry() {
        // slot 0 固定保留給空氣
        entries.add(BlockMaterialDescriptor.builder("minecraft:air")
                .layer(RenderLayerKind.EMPTY)
                .opaqueFullCube(false)
                .alpha(0)
                .build());
        idByName.put("minecraft:air", AIR_MATERIAL_ID);

        // slot 1 作為未知方塊的安全不透明預設後備材質
        register(BlockMaterialDescriptor.builder(FALLBACK_SOLID_ID)
                .allFacesSprite(0)
                .layer(RenderLayerKind.SOLID)
                .opaqueFullCube(true)
                .build());
    }

    @Override
    public synchronized int register(BlockMaterialDescriptor descriptor) {
        Integer existing = idByName.get(descriptor.blockId());
        if (existing != null) {
            entries.set(existing, descriptor);
            version.incrementAndGet();
            return existing;
        }
        if (entries.size() >= MAX_MATERIALS) {
            throw new IllegalStateException("Exceeded maximum registered block materials (" + MAX_MATERIALS + ")");
        }
        int assignedId = entries.size();
        entries.add(descriptor);
        idByName.put(descriptor.blockId(), assignedId);
        version.incrementAndGet();
        return assignedId;
    }

    @Override
    public int resolveId(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return 1;
        }
        return idByName.getOrDefault(blockId, 1);
    }

    @Override
    public synchronized Optional<BlockMaterialDescriptor> getDescriptor(int materialId) {
        if (materialId < 0 || materialId >= entries.size()) {
            return Optional.empty();
        }
        return Optional.of(entries.get(materialId));
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public long paletteVersion() {
        return version.get();
    }

    /**
     * 將目前所有材質表一次性扁平寫入原生 C ABI `MetalityBlockMaterialEntry[]` 緩衝區（18 bytes / 筆）。
     */
    public synchronized MemorySegment bakeNativePalette(Arena arena) {
        int count = entries.size();
        MemorySegment buf = arena.allocate((long) count * NATIVE_ENTRY_BYTES, 2);
        for (int i = 0; i < count; ++i) {
            BlockMaterialDescriptor d = entries.get(i);
            long base = (long) i * NATIVE_ENTRY_BYTES;
            for (int f = 0; f < 6; ++f) {
                buf.set(ValueLayout.JAVA_SHORT, base + f * 2L, (short) d.spriteForFace(f));
            }
            buf.set(ValueLayout.JAVA_BYTE, base + 12, (byte) d.layer().nativeCode());
            buf.set(ValueLayout.JAVA_BYTE, base + 13, (byte) (d.opaqueFullCube() ? 1 : 0));
            buf.set(ValueLayout.JAVA_BYTE, base + 14, (byte) (d.waveTag() & 0xFF));
            buf.set(ValueLayout.JAVA_BYTE, base + 15, (byte) (d.emissiveLevel() & 0x0F));
            buf.set(ValueLayout.JAVA_BYTE, base + 16, (byte) (d.alpha() & 0xFF));
            buf.set(ValueLayout.JAVA_BYTE, base + 17, (byte) 0);
        }
        return buf;
    }
}

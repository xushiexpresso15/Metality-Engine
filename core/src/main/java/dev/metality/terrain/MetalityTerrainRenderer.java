package dev.metality.terrain;

import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import dev.metality.nativebridge.MetalityFFM;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 版本無關之 GPU-Driven 世界地形渲染器（UMA MegaBuffer + Compute 視錐剔除 + Indirect Command Buffer）。
 */
public final class MetalityTerrainRenderer implements AutoCloseable {

    public static final int DEFAULT_MAX_SECTIONS = 32_768;
    public static final long DEFAULT_QUAD_POOL_BYTES = 256L << 20;

    private record SectionAllocation(int slotId, long arenaByteOffset, long arenaAlignedBytes) {}

    private final MetalGpuDevice device;
    private final MemorySegment nativeArena;
    private final MemorySegment terrainPipeline;
    private final MemorySegment quadPoolSegment;

    private final AtomicInteger nextSectionSlot = new AtomicInteger(0);
    private final Map<Long, SectionAllocation> sectionMap = new LinkedHashMap<>(1024, 0.75f, true);
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private final Object sectionLock = new Object();

    public MetalityTerrainRenderer(MetalGpuDevice device) {
        this.device = device;
        MetalityFFM ffm = device.ffm();
        this.nativeArena = ffm.createArena(DEFAULT_QUAD_POOL_BYTES, 256);
        this.terrainPipeline = ffm.createTerrainPipeline(device.nativeContext(), DEFAULT_MAX_SECTIONS, DEFAULT_QUAD_POOL_BYTES);
        this.quadPoolSegment = ffm.getTerrainQuadPoolSegment(this.terrainPipeline, DEFAULT_QUAD_POOL_BYTES);
    }

    public static long packSectionKey(int secX, int secY, int secZ) {
        return ((long) (secX & 0x3FFFFF) << 42)
                | ((long) (secZ & 0x3FFFFF) << 20)
                | (long) (secY & 0xFFFFF);
    }

    /**
     * 將 ARM NEON 建構好的區段幾何零拷貝寫入 UMA 地形池並更新 GPU 區段記錄，回傳分配的 GPU slotId。
     */
    public int uploadMeshedSection(int secX, int secY, int secZ, ChunkMeshingScratch meshed) {
        int solidCount = Math.clamp(meshed.solidQuadCount(), 0, ChunkMeshingScratch.MAX_QUADS_PER_LAYER);
        int cutoutCount = Math.clamp(meshed.cutoutQuadCount(), 0, ChunkMeshingScratch.MAX_QUADS_PER_LAYER);
        int transCount = Math.clamp(meshed.translucentQuadCount(), 0, ChunkMeshingScratch.MAX_QUADS_PER_LAYER);
        int totalQuads = solidCount + cutoutCount + transCount;

        long key = packSectionKey(secX, secY, secZ);
        int slot;
        synchronized (sectionLock) {
            SectionAllocation prev = sectionMap.remove(key);
            if (prev != null && prev.arenaAlignedBytes() > 0) {
                device.ffm().freeToArena(nativeArena, prev.arenaByteOffset(), prev.arenaAlignedBytes());
            }

            if (prev != null) {
                slot = prev.slotId();
            } else if (!freeSlots.isEmpty()) {
                slot = freeSlots.pollFirst();
            } else {
                int candidate = nextSectionSlot.get();
                if (candidate < DEFAULT_MAX_SECTIONS) {
                    slot = nextSectionSlot.getAndIncrement();
                } else {
                    SectionAllocation evicted = evictOldestSectionLocked();
                    if (evicted == null) {
                        return -1;
                    }
                    slot = freeSlots.pollFirst();
                }
            }

            long solidQuadIdx = 0;
            long cutoutQuadIdx = 0;
            long transQuadIdx = 0;

            if (totalQuads > 0) {
                long totalBytes = (long) totalQuads * ChunkMeshingScratch.QUAD_BYTES;
                long[] alloc = device.ffm().allocFromArena(nativeArena, totalBytes);
                while (alloc == null && !sectionMap.isEmpty()) {
                    if (evictOldestSectionLocked() == null) {
                        break;
                    }
                    alloc = device.ffm().allocFromArena(nativeArena, totalBytes);
                }
                if (alloc == null) {
                    zeroGpuRecord(slot);
                    freeSlots.addLast(slot);
                    return -1;
                }
                long byteOffset = alloc[0];
                long alignedBytes = alloc[1];

                long cursor = byteOffset;
                solidQuadIdx = cursor >>> 4;
                if (solidCount > 0) {
                    long bytes = (long) solidCount * 16L;
                    MemorySegment.copy(meshed.solidStaging, 0, quadPoolSegment, cursor, bytes);
                    cursor += bytes;
                }

                cutoutQuadIdx = cursor >>> 4;
                if (cutoutCount > 0) {
                    long bytes = (long) cutoutCount * 16L;
                    MemorySegment.copy(meshed.cutoutStaging, 0, quadPoolSegment, cursor, bytes);
                    cursor += bytes;
                }

                transQuadIdx = cursor >>> 4;
                if (transCount > 0) {
                    long bytes = (long) transCount * 16L;
                    MemorySegment.copy(meshed.translucentStaging, 0, quadPoolSegment, cursor, bytes);
                }

                sectionMap.put(key, new SectionAllocation(slot, byteOffset, alignedBytes));
            } else {
                sectionMap.put(key, new SectionAllocation(slot, 0L, 0L));
            }

            try (Arena temp = Arena.ofConfined()) {
                MemorySegment rec = temp.allocate(MetalityFFM.SECTION_GPU_RECORD_LAYOUT);
                rec.set(ValueLayout.JAVA_INT, 0, secX);
                rec.set(ValueLayout.JAVA_INT, 4, secY);
                rec.set(ValueLayout.JAVA_INT, 8, secZ);
                rec.set(ValueLayout.JAVA_INT, 12, (int) solidQuadIdx);
                rec.set(ValueLayout.JAVA_INT, 16, solidCount);
                rec.set(ValueLayout.JAVA_INT, 20, (int) cutoutQuadIdx);
                rec.set(ValueLayout.JAVA_INT, 24, cutoutCount);
                rec.set(ValueLayout.JAVA_INT, 28, (int) transQuadIdx);
                rec.set(ValueLayout.JAVA_INT, 32, transCount);
                for (int a = 0; a < 3; ++a) {
                    rec.set(ValueLayout.JAVA_FLOAT, 36L + a * 4L, meshed.aabbMin(a));
                    rec.set(ValueLayout.JAVA_FLOAT, 48L + a * 4L, meshed.aabbMax(a));
                }
                device.ffm().updateTerrainSection(terrainPipeline, slot, rec);
            }
        }
        var rt = device.rayTracingSceneIfCreated();
        if (rt != null) {
            rt.updateSectionQuads(secX, secY, secZ, meshed);
        }
        return slot;
    }

    /**
     * 移除並回收指定座標之單一區段幾何與 GPU 槽位（例如伺服器卸載區塊或區段變為純空氣時）。
     */
    public void removeSection(int secX, int secY, int secZ) {
        long key = packSectionKey(secX, secY, secZ);
        synchronized (sectionLock) {
            SectionAllocation prev = sectionMap.remove(key);
            if (prev != null) {
                if (prev.arenaAlignedBytes() > 0) {
                    device.ffm().freeToArena(nativeArena, prev.arenaByteOffset(), prev.arenaAlignedBytes());
                }
                zeroGpuRecord(prev.slotId());
                freeSlots.addLast(prev.slotId());
            }
        }
        var rt = device.rayTracingSceneIfCreated();
        if (rt != null) {
            rt.removeSection(secX, secY, secZ);
        }
    }

    /**
     * 清空並回收所有已上傳的區塊幾何與 GPU 槽位（例如切換伺服器或維度時）。
     */
    public void clearAllSections() {
        synchronized (sectionLock) {
            for (SectionAllocation alloc : sectionMap.values()) {
                if (alloc.arenaAlignedBytes() > 0) {
                    device.ffm().freeToArena(nativeArena, alloc.arenaByteOffset(), alloc.arenaAlignedBytes());
                }
                zeroGpuRecord(alloc.slotId());
            }
            sectionMap.clear();
            freeSlots.clear();
            nextSectionSlot.set(0);
        }
    }

    private SectionAllocation evictOldestSectionLocked() {
        Iterator<Map.Entry<Long, SectionAllocation>> it = sectionMap.entrySet().iterator();
        if (!it.hasNext()) {
            return null;
        }
        SectionAllocation oldest = it.next().getValue();
        it.remove();
        if (oldest.arenaAlignedBytes() > 0) {
            device.ffm().freeToArena(nativeArena, oldest.arenaByteOffset(), oldest.arenaAlignedBytes());
        }
        zeroGpuRecord(oldest.slotId());
        freeSlots.addLast(oldest.slotId());
        return oldest;
    }

    private void zeroGpuRecord(int slot) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment rec = temp.allocate(MetalityFFM.SECTION_GPU_RECORD_LAYOUT);
            rec.fill((byte) 0);
            device.ffm().updateTerrainSection(terrainPipeline, slot, rec);
        }
    }

    public int activeSectionCount() {
        return Math.min(nextSectionSlot.get(), DEFAULT_MAX_SECTIONS);
    }

    public void dispatchGpuCull(float[] frustumPlanes24, float camX, float camY, float camZ) {
        int activeCount = activeSectionCount();
        if (activeCount <= 0) {
            return;
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment planesSeg = temp.allocateFrom(ValueLayout.JAVA_FLOAT, frustumPlanes24);
            MemorySegment camSeg = temp.allocateFrom(ValueLayout.JAVA_FLOAT, camX, camY, camZ);
            device.ffm().encodeTerrainGpuCull(device.nativeContext(), terrainPipeline, planesSeg, camSeg, activeCount);
        }
    }

    public void drawLayerIndirect(
            float[] viewProjMatrix16,
            float camX,
            float camY,
            float camZ,
            MetalGpuTexture blockAtlas,
            long atlasSamplerHandle,
            int layerType
    ) {
        int activeCount = activeSectionCount();
        if (activeCount <= 0) {
            return;
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment vpSeg = temp.allocateFrom(ValueLayout.JAVA_FLOAT, viewProjMatrix16);
            MemorySegment camSeg = temp.allocateFrom(ValueLayout.JAVA_FLOAT, camX, camY, camZ);
            device.ffm().encodeTerrainIcbDraw(
                    device.nativeContext(),
                    terrainPipeline,
                    vpSeg,
                    camSeg,
                    blockAtlas.handle(),
                    atlasSamplerHandle,
                    layerType,
                    activeCount
            );
        }
    }

    @Override
    public void close() {
        device.ffm().destroyTerrainPipeline(device.nativeContext(), terrainPipeline);
        device.ffm().destroyArena(nativeArena);
    }
}

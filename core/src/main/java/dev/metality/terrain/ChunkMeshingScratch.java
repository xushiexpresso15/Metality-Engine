package dev.metality.terrain;

import dev.metality.nativebridge.MetalityFFM;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 執行緒區域 (Thread-Local) 堆外網格建構工作區（版本無關，零 GC 停頓）。
 */
public final class ChunkMeshingScratch implements AutoCloseable {

    public static final int PADDED_VOLUME = 18 * 18 * 18;
    public static final int INNER_VOLUME  = 16 * 16 * 16;
    public static final int MAX_QUADS_PER_LAYER = 24576;
    public static final long QUAD_BYTES = 16L;

    private final Arena arena;
    public final MemorySegment blockMaterialIds;
    public final MemorySegment packedLight;
    public final MemorySegment biomeTintRgb;

    public final MemorySegment solidStaging;
    public final MemorySegment cutoutStaging;
    public final MemorySegment translucentStaging;

    public final MemorySegment inputStruct;
    public final MemorySegment outputStruct;

    public ChunkMeshingScratch() {
        this.arena = Arena.ofShared();
        this.blockMaterialIds = arena.allocate(PADDED_VOLUME * 2L, 16);
        this.packedLight = arena.allocate(PADDED_VOLUME, 16);
        this.biomeTintRgb = arena.allocate(INNER_VOLUME * 4L, 16);

        this.solidStaging = arena.allocate(MAX_QUADS_PER_LAYER * QUAD_BYTES, 16);
        this.cutoutStaging = arena.allocate(MAX_QUADS_PER_LAYER * QUAD_BYTES, 16);
        this.translucentStaging = arena.allocate(MAX_QUADS_PER_LAYER * QUAD_BYTES, 16);

        this.inputStruct = arena.allocate(MetalityFFM.CHUNK_SECTION_INPUT_LAYOUT);
        this.outputStruct = arena.allocate(MetalityFFM.MESHED_SECTION_OUTPUT_LAYOUT);

        inputStruct.set(ValueLayout.ADDRESS, 0, blockMaterialIds);
        inputStruct.set(ValueLayout.ADDRESS, 8, packedLight);
        inputStruct.set(ValueLayout.ADDRESS, 16, biomeTintRgb);

        outputStruct.set(ValueLayout.ADDRESS, 0, solidStaging);
        outputStruct.set(ValueLayout.JAVA_INT, 8, MAX_QUADS_PER_LAYER);
        outputStruct.set(ValueLayout.ADDRESS, 16, cutoutStaging);
        outputStruct.set(ValueLayout.JAVA_INT, 24, MAX_QUADS_PER_LAYER);
        outputStruct.set(ValueLayout.ADDRESS, 32, translucentStaging);
        outputStruct.set(ValueLayout.JAVA_INT, 40, MAX_QUADS_PER_LAYER);
    }

    public void bindPalette(MemorySegment paletteTable, int paletteSize, boolean greedyMerge) {
        inputStruct.set(ValueLayout.ADDRESS, 8, packedLight);
        inputStruct.set(ValueLayout.ADDRESS, 24, paletteTable);
        inputStruct.set(ValueLayout.JAVA_INT, 32, paletteSize);
        inputStruct.set(ValueLayout.JAVA_INT, 36, greedyMerge ? 1 : 0);
    }

    /**
     * RT/CSM 專用快速綁定：enable_greedy_merge=2（忽略 ao4/lightmap4/sprite_id）且 packed_light=NULL。
     */
    public void bindPaletteRtGeometryOnly(MemorySegment paletteTable, int paletteSize) {
        inputStruct.set(ValueLayout.ADDRESS, 8, MemorySegment.NULL);
        inputStruct.set(ValueLayout.ADDRESS, 24, paletteTable);
        inputStruct.set(ValueLayout.JAVA_INT, 32, paletteSize);
        inputStruct.set(ValueLayout.JAVA_INT, 36, 2);
    }

    public int solidQuadCount() {
        return outputStruct.get(ValueLayout.JAVA_INT, 12);
    }

    public int cutoutQuadCount() {
        return outputStruct.get(ValueLayout.JAVA_INT, 28);
    }

    public int translucentQuadCount() {
        return outputStruct.get(ValueLayout.JAVA_INT, 44);
    }

    public float aabbMin(int axis) {
        return outputStruct.get(ValueLayout.JAVA_FLOAT, 48L + axis * 4L);
    }

    public float aabbMax(int axis) {
        return outputStruct.get(ValueLayout.JAVA_FLOAT, 60L + axis * 4L);
    }

    @Override
    public void close() {
        arena.close();
    }
}

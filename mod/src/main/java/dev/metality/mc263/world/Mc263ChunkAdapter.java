package dev.metality.mc263.world;

import dev.metality.api.MetalityApi;
import dev.metality.api.material.BlockMaterialDescriptor;
import dev.metality.api.material.BlockMaterialRegistry;
import dev.metality.api.material.RenderLayerKind;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.impl.DefaultBlockMaterialRegistry;
import dev.metality.impl.DefaultMetalityEngine;
import dev.metality.terrain.ChunkMeshingScratch;
import dev.metality.terrain.MetalityTerrainRenderer;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Minecraft 26.3 世界區塊轉接器：
 * 將 RenderSectionRegion 18x18x18 鄰域零 GC 打包為原生 NEON 網格建構格式，
 * 並以 BlockState raw numeric ID 直接索引，避免熱迴圈任何 HashMap / String 配置。
 */
public final class Mc263ChunkAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Metality/ChunkAdapter");
    private static final int INITIAL_STATE_CACHE_CAP = 65_536;
    private static final int TINT_KIND_FIXED_BGR = 1;
    private static final int TINT_KIND_WATER_BIOME = 2;
    private static final int TINT_KIND_GRASS_BIOME = 3;
    private static final int TINT_KIND_FOLIAGE_BIOME = 4;

    private static final class BiomeTintScratch {
        final int[] waterBgr = new int[16];
        final int[] grassBgr = new int[16];
        final int[] foliageBgr = new int[16];

        void reset() {
            Arrays.fill(waterBgr, -1);
            Arrays.fill(grassBgr, -1);
            Arrays.fill(foliageBgr, -1);
        }
    }

    private static final ThreadLocal<ChunkMeshingScratch> THREAD_SCRATCH =
            ThreadLocal.withInitial(ChunkMeshingScratch::new);
    private static final ThreadLocal<BiomeTintScratch> BIOME_TINT_SCRATCH =
            ThreadLocal.withInitial(BiomeTintScratch::new);

    private static final AtomicBoolean VANILLA_PRESETS_REGISTERED = new AtomicBoolean(false);
    private static final AtomicReference<Object> ACTIVE_LEVEL_REF = new AtomicReference<>();
    private static final AtomicLong LEVEL_EPOCH = new AtomicLong(1L);
    private static final AtomicLong LAST_ERROR_LOG_MS = new AtomicLong(0L);
    private static final Object LEVEL_SYNC_LOCK = new Object();
    private static final MemorySegment PERSISTENT_PALETTE_BUFFER =
            Arena.ofAuto().allocate(65_536L * 18L, 8L);
    private static volatile int[] stateIdToMaterialCache = new int[INITIAL_STATE_CACHE_CAP];
    // Packed per-BlockState rawId tint cache: 0=unset, bits[24..27]=TINT_KIND_*, bits[0..23]=0xBBGGRR (when FIXED_BGR)
    private static volatile int[] stateTintPackedCache = new int[INITIAL_STATE_CACHE_CAP];
    private static volatile long cachedPaletteVersion = -1L;
    private static volatile long bakedPaletteVersion = -1L;
    private static volatile int bakedPaletteSize = 0;

    static {
        Arrays.fill(stateIdToMaterialCache, -1);
    }

    private Mc263ChunkAdapter() {}

    public static void syncClientLevel(Object levelIdentity) {
        if (ACTIVE_LEVEL_REF.get() == levelIdentity) {
            return;
        }
        synchronized (LEVEL_SYNC_LOCK) {
            if (ACTIVE_LEVEL_REF.getAndSet(levelIdentity) != levelIdentity) {
                LEVEL_EPOCH.incrementAndGet();
                clearAllSectionsLocked();
            }
        }
    }

    public static void invalidateAllSections() {
        synchronized (LEVEL_SYNC_LOCK) {
            LEVEL_EPOCH.incrementAndGet();
            clearAllSectionsLocked();
        }
    }

    public static void onSectionRemoved(int secX, int secY, int secZ) {
        MetalGpuDevice dev = MetalGpuDevice.getInstance();
        if (dev != null) {
            MetalityTerrainRenderer terrain = dev.terrainRendererIfCreated();
            if (terrain != null) {
                terrain.removeSection(secX, secY, secZ);
                return;
            }
            var rt = dev.rayTracingSceneIfCreated();
            if (rt != null) {
                rt.removeSection(secX, secY, secZ);
                return;
            }
        }
        if (MetalityApi.isAvailable() && MetalityApi.get() instanceof DefaultMetalityEngine def) {
            def.nativeRayTracingScene().removeSection(secX, secY, secZ);
        }
    }

    private static void clearAllSectionsLocked() {
        if (MetalityApi.isAvailable() && MetalityApi.get() instanceof DefaultMetalityEngine def) {
            def.nativeRayTracingScene().clearAllSections();
        }
        MetalGpuDevice dev = MetalGpuDevice.getInstance();
        if (dev != null) {
            MetalityTerrainRenderer terrain = dev.terrainRendererIfCreated();
            if (terrain != null) {
                terrain.clearAllSections();
            }
        }
    }

    public static void ensureVanillaPresetsRegistered(BlockMaterialRegistry registry) {
        if (!VANILLA_PRESETS_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        registry.register(BlockMaterialDescriptor.builder("minecraft:water")
                .allFacesSprite(1).layer(RenderLayerKind.TRANSLUCENT)
                .opaqueFullCube(false).waveTag(3).alpha(180)
                .roughness(0.04f).indexOfRefraction(1.333f).build());
        registry.register(BlockMaterialDescriptor.builder("minecraft:lava")
                .allFacesSprite(2).layer(RenderLayerKind.SOLID)
                .opaqueFullCube(true).waveTag(4).emissiveLevel(15)
                .roughness(0.45f).build());
        registry.register(BlockMaterialDescriptor.builder("minecraft:glass")
                .allFacesSprite(3).layer(RenderLayerKind.TRANSLUCENT)
                .opaqueFullCube(false).waveTag(8).alpha(96)
                .roughness(0.05f).indexOfRefraction(1.5f).build());
        registry.register(BlockMaterialDescriptor.builder("minecraft:oak_leaves")
                .allFacesSprite(4).layer(RenderLayerKind.CUTOUT)
                .opaqueFullCube(false).waveTag(2).roughness(0.75f).build());
        registry.register(BlockMaterialDescriptor.builder("minecraft:short_grass")
                .allFacesSprite(5).layer(RenderLayerKind.EMPTY)
                .opaqueFullCube(false).waveTag(1).roughness(0.8f).build());
        registry.register(BlockMaterialDescriptor.builder("minecraft:glowstone")
                .allFacesSprite(6).layer(RenderLayerKind.SOLID)
                .opaqueFullCube(true).waveTag(5).emissiveLevel(15).build());
        registry.register(BlockMaterialDescriptor.builder("minecraft:sea_lantern")
                .allFacesSprite(7).layer(RenderLayerKind.SOLID)
                .opaqueFullCube(true).waveTag(6).emissiveLevel(15).roughness(0.18f).build());
        registry.register(BlockMaterialDescriptor.builder("minecraft:torch")
                .allFacesSprite(8).layer(RenderLayerKind.CUTOUT)
                .opaqueFullCube(false).waveTag(9).emissiveLevel(14).build());
    }

    public static void onCompileSection(MetalGpuDevice device, SectionPos sectionPos, RenderSectionRegion region) {
        try {
            compileSectionInternal(device, sectionPos, region);
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            long prev = LAST_ERROR_LOG_MS.get();
            if (now - prev >= 5000L && LAST_ERROR_LOG_MS.compareAndSet(prev, now)) {
                LOGGER.warn("[Metality] Skipped malformed chunk section ({}, {}, {}): {}",
                        sectionPos.x(), sectionPos.y(), sectionPos.z(), t.toString());
            }
        }
    }

    private static void compileSectionInternal(MetalGpuDevice device, SectionPos sectionPos, RenderSectionRegion region) {
        Object currentLevel = Minecraft.getInstance().level;
        if (currentLevel == null) {
            return;
        }
        syncClientLevel(currentLevel);
        long startEpoch = LEVEL_EPOCH.get();
        if (Minecraft.getInstance().level != currentLevel) {
            return;
        }
        DefaultBlockMaterialRegistry registry = resolveMaterialRegistry();
        if (registry == null) {
            return;
        }
        ensureVanillaPresetsRegistered(registry);

        ChunkMeshingScratch scratch = THREAD_SCRATCH.get();
        BiomeTintScratch biomeScratch = BIOME_TINT_SCRATCH.get();
        biomeScratch.reset();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos biomeSamplePos = new BlockPos.MutableBlockPos();
        int baseX = sectionPos.minBlockX();
        int baseY = sectionPos.minBlockY();
        int baseZ = sectionPos.minBlockZ();

        boolean anyNonAir = false;
        MemorySegment matSeg = scratch.blockMaterialIds;
        MemorySegment tintSeg = scratch.biomeTintRgb;

        for (int py = 0; py < 18; ++py) {
            // Early-abort stale worker threads immediately during Save & Quit or dimension switch
            if ((py & 3) == 0 && (LEVEL_EPOCH.get() != startEpoch || Minecraft.getInstance().level != currentLevel)) {
                return;
            }
            int wy = baseY + py - 1;
            for (int pz = 0; pz < 18; ++pz) {
                int wz = baseZ + pz - 1;
                for (int px = 0; px < 18; ++px) {
                    int wx = baseX + px - 1;
                    mutablePos.set(wx, wy, wz);

                    BlockState state = region.getBlockState(mutablePos);
                    int pIdx = (py * 18 + pz) * 18 + px;

                    int matId = resolveBlockStateFast(registry, state, region, mutablePos);
                    if (matId != BlockMaterialRegistry.AIR_MATERIAL_ID) {
                        anyNonAir = true;
                    }
                    matSeg.set(ValueLayout.JAVA_SHORT, pIdx * 2L, (short) matId);

                    if (px >= 1 && px <= 16 && py >= 1 && py <= 16 && pz >= 1 && pz <= 16) {
                        int innerIdx = ((py - 1) * 16 + (pz - 1)) * 16 + (px - 1);
                        int bgrTint = 0xFFFFFF;
                        if (matId != BlockMaterialRegistry.AIR_MATERIAL_ID) {
                            int packedTint = resolveBlockTintPackedFast(state, region, mutablePos);
                            int tintKind = packedTint >>> 24;
                            if (tintKind == TINT_KIND_FIXED_BGR) {
                                bgrTint = packedTint & 0xFFFFFF;
                            } else {
                                // Coarse 4x4 XZ biome grid (at most 16 BiomeColors calls/section instead of 4,096)
                                int cellX = (px - 1) >> 2;
                                int cellZ = (pz - 1) >> 2;
                                int cellIdx = (cellZ << 2) | cellX;
                                int[] cacheArr = (tintKind == TINT_KIND_WATER_BIOME)
                                        ? biomeScratch.waterBgr
                                        : ((tintKind == TINT_KIND_GRASS_BIOME)
                                                ? biomeScratch.grassBgr
                                                : biomeScratch.foliageBgr);
                                int cachedBgr = cacheArr[cellIdx];
                                if (cachedBgr < 0) {
                                    biomeSamplePos.set(baseX + (cellX << 2) + 2, wy, baseZ + (cellZ << 2) + 2);
                                    int rgb;
                                    if (tintKind == TINT_KIND_WATER_BIOME) {
                                        rgb = BiomeColors.getAverageWaterColor(region, biomeSamplePos) & 0xFFFFFF;
                                    } else if (tintKind == TINT_KIND_GRASS_BIOME) {
                                        rgb = BiomeColors.getAverageGrassColor(region, biomeSamplePos) & 0xFFFFFF;
                                    } else {
                                        rgb = BiomeColors.getAverageFoliageColor(region, biomeSamplePos) & 0xFFFFFF;
                                    }
                                    cachedBgr = rgbToBgr24(rgb);
                                    cacheArr[cellIdx] = cachedBgr;
                                }
                                bgrTint = cachedBgr;
                            }
                        }
                        tintSeg.set(ValueLayout.JAVA_INT, innerIdx * 4L, bgrTint);
                    }
                }
            }
        }

        if (LEVEL_EPOCH.get() != startEpoch
                || ACTIVE_LEVEL_REF.get() != currentLevel
                || Minecraft.getInstance().level != currentLevel) {
            return;
        }

        if (!anyNonAir) {
            onSectionRemoved(sectionPos.x(), sectionPos.y(), sectionPos.z());
            return;
        }

        MemorySegment paletteSeg = ensureBakedPalette(registry);
        // RT/CSM geometry-only greedy merge (enable_greedy_merge=2, packed_light=NULL):
        // ~6.5x fewer BVH/CSM quads and eliminates 11,664 virtual light lookups per section!
        scratch.bindPaletteRtGeometryOnly(paletteSeg, bakedPaletteSize);
        boolean ok = device.ffm().meshChunkSectionNeon(scratch.inputStruct, scratch.outputStruct);
        if (ok) {
            if (LEVEL_EPOCH.get() != startEpoch
                    || ACTIVE_LEVEL_REF.get() != currentLevel
                    || Minecraft.getInstance().level != currentLevel) {
                return;
            }
            // Upload directly to HardwareRayTracingScene (bypasses unused 256MB MetalityTerrainRenderer pool & global sectionLock)
            var rt = device.rayTracingSceneIfCreated();
            if (rt == null && MetalityApi.isAvailable() && MetalityApi.get() instanceof DefaultMetalityEngine def) {
                rt = def.nativeRayTracingScene();
            }
            if (rt != null) {
                rt.updateSectionQuads(sectionPos.x(), sectionPos.y(), sectionPos.z(), scratch);
                if (LEVEL_EPOCH.get() != startEpoch || ACTIVE_LEVEL_REF.get() != currentLevel) {
                    rt.removeSection(sectionPos.x(), sectionPos.y(), sectionPos.z());
                }
            }
        }
    }

    private static int rgbToBgr24(int rgb) {
        return ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >>> 16) & 0xFF);
    }

    private static int resolveBlockTintPackedFast(BlockState state, RenderSectionRegion region, BlockPos pos) {
        int rawId = Block.getId(state);
        int[] cache = stateTintPackedCache;
        if (rawId >= 0 && rawId < cache.length) {
            int hit = cache[rawId];
            if (hit != 0) {
                return hit;
            }
        }
        int packed = classifyBlockTintPacked(state, region, pos);
        if (rawId >= 0) {
            updateTintCache(rawId, packed);
        }
        return packed;
    }

    private static int classifyBlockTintPacked(BlockState state, RenderSectionRegion region, BlockPos pos) {
        FluidState fs = state.getFluidState();
        int emitLvl = state.getLightEmission();
        if (!fs.isEmpty() && (emitLvl == 0 || fs.is(FluidTags.LAVA))) {
            String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if (path.contains("lava") || fs.is(FluidTags.LAVA)) {
                return (TINT_KIND_FIXED_BGR << 24) | rgbToBgr24(0xFF520A);
            }
            return (TINT_KIND_WATER_BIOME << 24);
        }
        if (emitLvl > 0) {
            String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            return (TINT_KIND_FIXED_BGR << 24) | rgbToBgr24(classifyEmissiveHexRgb(path));
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (!state.canOcclude()) {
            if (path.contains("grass") || path.contains("fern") || path.contains("sugar_cane")) {
                return (TINT_KIND_GRASS_BIOME << 24);
            }
            if (path.contains("leaves") || path.contains("vine")) {
                return (TINT_KIND_FOLIAGE_BIOME << 24);
            }
        } else if ("grass_block".equals(path)) {
            return (TINT_KIND_GRASS_BIOME << 24);
        }
        int mapCol = state.getMapColor(region, pos).col & 0xFFFFFF;
        int rgb = (mapCol != 0) ? mapCol : 0xFFFFFF;
        return (TINT_KIND_FIXED_BGR << 24) | rgbToBgr24(rgb);
    }

    private static synchronized void updateTintCache(int rawId, int packed) {
        if (rawId >= stateTintPackedCache.length) {
            int newLen = Math.max(stateTintPackedCache.length * 2, rawId + 1024);
            stateTintPackedCache = Arrays.copyOf(stateTintPackedCache, newLen);
        }
        stateTintPackedCache[rawId] = packed;
    }

    private static DefaultBlockMaterialRegistry resolveMaterialRegistry() {
        if (MetalityApi.isAvailable() && MetalityApi.get() instanceof DefaultMetalityEngine def) {
            return def.nativeMaterialRegistry();
        }
        return null;
    }

    private static int resolveBlockStateFast(
            DefaultBlockMaterialRegistry registry,
            BlockState state,
            RenderSectionRegion region,
            BlockPos pos
    ) {
        if (state.isAir() && state.getFluidState().isEmpty()) {
            return BlockMaterialRegistry.AIR_MATERIAL_ID;
        }
        if (state.getRenderShape() == RenderShape.INVISIBLE
                && state.getFluidState().isEmpty()
                && state.getLightEmission() == 0) {
            return BlockMaterialRegistry.AIR_MATERIAL_ID;
        }

        long palVer = registry.paletteVersion();
        int rawId = Block.getId(state);
        if (palVer == cachedPaletteVersion && rawId >= 0 && rawId < stateIdToMaterialCache.length) {
            int hit = stateIdToMaterialCache[rawId];
            if (hit >= 0) {
                return hit;
            }
        }

        FluidState fs = state.getFluidState();
        if (!fs.isEmpty() && state.getLightEmission() == 0) {
            if (fs.is(FluidTags.LAVA)) {
                int lavaId = registry.resolveId("minecraft:lava");
                updateStateCache(rawId, lavaId, registry.paletteVersion());
                return lavaId;
            }
            if (fs.is(FluidTags.WATER)) {
                int amount = Mth.clamp(fs.getAmount(), 1, 8);
                boolean falling = fs.hasProperty(FlowingFluid.FALLING) && fs.getValue(FlowingFluid.FALLING);
                int waterCode = falling ? 9 : amount;
                String waterKey = "minecraft:water#h" + waterCode;
                int resolvedWater = registry.resolveId(waterKey);
                if (resolvedWater == 1 && !DefaultBlockMaterialRegistry.FALLBACK_SOLID_ID.equals(waterKey)) {
                    resolvedWater = registry.register(BlockMaterialDescriptor.builder(waterKey)
                            .allFacesSprite(1)
                            .layer(RenderLayerKind.TRANSLUCENT)
                            .opaqueFullCube(false)
                            .waveTag(3)
                            .emissiveLevel(waterCode)
                            .alpha(180)
                            .roughness(0.04f)
                            .indexOfRefraction(1.333f)
                            .build());
                }
                updateStateCache(rawId, resolvedWater, registry.paletteVersion());
                return resolvedWater;
            }
        }

        BlockState effectiveState = (state.isAir() && !fs.isEmpty())
                ? fs.createLegacyBlock()
                : state;
        String baseKey = BuiltInRegistries.BLOCK.getKey(effectiveState.getBlock()).toString();
        String path = BuiltInRegistries.BLOCK.getKey(effectiveState.getBlock()).getPath();
        FluidState effFs = effectiveState.getFluidState();
        boolean isLavaFluid = effFs.is(FluidTags.LAVA);
        boolean isWaterlogged = !effFs.isEmpty() && !isLavaFluid;
        boolean isInvisibleShape = (effectiveState.getRenderShape() == RenderShape.INVISIBLE);
        int emit = effectiveState.getLightEmission();
        boolean opaqueCube = !isInvisibleShape && effectiveState.canOcclude() && effectiveState.isSolidRender();
        if (isLavaFluid) {
            emit = Math.max(emit, 15);
            opaqueCube = true;
        }

        boolean isInvisibleLight = isInvisibleShape && !isLavaFluid && emit > 0;
        boolean isTintedGlass = !isInvisibleShape && path.equals("tinted_glass");
        boolean isGlass = !isInvisibleShape && !isTintedGlass && !opaqueCube && (classifyGlassSubtype(path) >= 0);
        boolean isFullCubeFoliage = !isInvisibleShape && !opaqueCube && (path.contains("leaves") || path.equals("mangrove_roots"));
        boolean isThinVegetation = !isInvisibleShape && !opaqueCube && !isLavaFluid && emit == 0
                && !isGlass && !isTintedGlass && !isFullCubeFoliage && isPassThroughVegetation(path);

        VoxelShape collisionShape = (isInvisibleShape || opaqueCube || isThinVegetation || isTintedGlass)
                ? null
                : resolvePureCollisionShape(effectiveState, region, pos);
        boolean hasCollision = (collisionShape != null && !collisionShape.isEmpty());
        if (!opaqueCube && !hasCollision && !isLavaFluid && emit == 0 && !isThinVegetation && !isTintedGlass) {
            updateStateCache(rawId, BlockMaterialRegistry.AIR_MATERIAL_ID, registry.paletteVersion());
            return BlockMaterialRegistry.AIR_MATERIAL_ID;
        }

        String key = baseKey;
        int subBlockPacked = -1;
        if (isInvisibleLight) {
            subBlockPacked = (14 << 4) | 14;
            key = baseKey + "#p14" + (isWaterlogged ? "#wl" : "");
        } else if (isThinVegetation) {
            subBlockPacked = (14 << 4) | 15;
            key = baseKey + "#p15";
        } else if (isTintedGlass) {
            key = baseKey + "#opaque";
        } else if (emit > 0) {
            key = baseKey + "#e" + emit + (opaqueCube ? "#full" : "") + (isWaterlogged ? "#wl" : "");
        } else if (opaqueCube) {
            key = baseKey + "#full";
        } else if (!isLavaFluid && !isGlass && !isFullCubeFoliage) {
            VoxelShape visualShape = resolvePureVisualShape(effectiveState, region, pos);
            subBlockPacked = classifySubBlockPacked(effectiveState, path, visualShape);
            int subTag = (subBlockPacked >>> 4) & 0xF;
            int subVal = subBlockPacked & 0xF;
            key = baseKey + (subTag == 15 ? "#s" : "#p") + subVal;
        }

        int resolved = registry.resolveId(key);
        if (resolved == 1 && !DefaultBlockMaterialRegistry.FALLBACK_SOLID_ID.equals(key)) {
            RenderLayerKind layer;
            int waveTag;
            int packedMetaLevel;
            boolean descriptorOpaqueFullCube = opaqueCube;
            if (subBlockPacked >= 0) {
                layer = RenderLayerKind.CUTOUT;
                waveTag = (subBlockPacked >>> 4) & 0xF;
                packedMetaLevel = subBlockPacked & 0xF;
                descriptorOpaqueFullCube = false;
            } else if (isTintedGlass) {
                layer = RenderLayerKind.SOLID;
                waveTag = 1;
                packedMetaLevel = 0;
                descriptorOpaqueFullCube = false;
            } else {
                layer = (!opaqueCube && (isGlass || path.contains("portal")))
                        ? RenderLayerKind.TRANSLUCENT
                        : (opaqueCube ? RenderLayerKind.SOLID : RenderLayerKind.CUTOUT);
                waveTag = classifySemanticWaveTag(path, isLavaFluid, opaqueCube, emit);
                packedMetaLevel = emit;
                if (emit == 0 && waveTag == 8) {
                    packedMetaLevel = Math.max(0, classifyGlassSubtype(path));
                } else if (emit == 0 && waveTag == 12) {
                    packedMetaLevel = classifyPbrMaterialSubtype(path);
                }
            }
            resolved = registry.register(BlockMaterialDescriptor.builder(key)
                    .allFacesSprite(0)
                    .layer(layer)
                    .opaqueFullCube(descriptorOpaqueFullCube)
                    .waveTag(waveTag)
                    .emissiveLevel(packedMetaLevel)
                    .alpha(layer == RenderLayerKind.TRANSLUCENT ? 192 : 255)
                    .build());
        }

        updateStateCache(rawId, resolved, registry.paletteVersion());
        return resolved;
    }

    private static VoxelShape resolvePureCollisionShape(
            BlockState state,
            RenderSectionRegion region,
            BlockPos pos
    ) {
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (Throwable ignored) {
            return state.getCollisionShape(region, pos);
        }
    }

    private static VoxelShape resolvePureVisualShape(
            BlockState state,
            RenderSectionRegion region,
            BlockPos pos
    ) {
        try {
            return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (Throwable ignored) {
            return state.getShape(region, pos);
        }
    }

    private static boolean isGratedTrapdoor(String path) {
        return path.equals("iron_trapdoor")
                || path.equals("oak_trapdoor")
                || path.equals("acacia_trapdoor")
                || path.equals("jungle_trapdoor")
                || path.equals("bamboo_trapdoor")
                || path.endsWith("copper_trapdoor");
    }

    private static boolean isPassThroughVegetation(String path) {
        if (path.equals("grass_block") || path.equals("moss_block") || path.equals("pale_moss_block")
                || path.equals("flower_pot") || path.startsWith("potted_")
                || path.contains("leaves") || path.equals("mangrove_roots")
                || path.equals("dirt_path") || path.equals("farmland")) {
            return false;
        }
        return path.endsWith("_grass")
                || path.contains("fern")
                || path.contains("sapling")
                || path.equals("mangrove_propagule")
                || path.equals("sugar_cane")
                || path.contains("vine")
                || path.contains("azalea")
                || path.contains("dripleaf")
                || path.equals("lily_pad")
                || path.contains("bush")
                || path.contains("petals")
                || path.equals("wildflowers")
                || path.equals("leaf_litter")
                || path.endsWith("moss_carpet")
                || path.equals("pale_hanging_moss")
                || path.equals("hanging_roots")
                || path.equals("spore_blossom")
                || path.equals("crimson_roots")
                || path.equals("warped_roots")
                || path.equals("nether_sprouts")
                || path.equals("crimson_fungus")
                || path.equals("warped_fungus")
                || path.equals("red_mushroom")
                || path.equals("brown_mushroom")
                || path.equals("wheat")
                || path.equals("carrots")
                || path.equals("potatoes")
                || path.equals("beetroots")
                || path.equals("nether_wart")
                || path.equals("cocoa")
                || path.equals("pitcher_crop")
                || path.equals("pitcher_plant")
                || path.equals("torchflower")
                || path.equals("torchflower_crop")
                || path.endsWith("_stem")
                || path.equals("dandelion")
                || path.equals("poppy")
                || path.equals("blue_orchid")
                || path.equals("allium")
                || path.equals("azure_bluet")
                || path.endsWith("_tulip")
                || path.equals("oxeye_daisy")
                || path.equals("cornflower")
                || path.equals("lily_of_the_valley")
                || path.equals("wither_rose")
                || path.contains("eyeblossom")
                || path.equals("cactus_flower")
                || path.equals("sunflower")
                || path.equals("lilac")
                || path.equals("peony");
    }

    /**
     * Classifies non-opaque solid-collision blocks into exact conservative sub-block voxel encodings
     * satisfying ShadowGeom(S) <= VisualGeom(S) so no sub-1.0 or hollow block can ever self-shadow:
     * - Returns (15 << 4) | sub for stairs:
     *     0..3 = bottom STRAIGHT/INNER (0=-X/W, 1=+X/E, 2=-Z/N, 3=+Z/S), 4..7 = top STRAIGHT/INNER;
     *     8..11 = bottom OUTER_LEFT/OUTER_RIGHT (8=NW(-X,-Z), 9=NE(+X,-Z), 10=SW(-X,+Z), 11=SE(+X,+Z));
     *     12..15 = top OUTER_LEFT/OUTER_RIGHT.
     * - Returns (14 << 4) | sub for single-box sub-blocks and non-occluding cutouts:
     *     0 = non-occluding cutouts (fences, walls, bars, chains, ladders, carpets, grated trapdoors,
     *         cauldrons, composters, hoppers, lecterns, copper grates, cake, anvils, bells, snow<=2, multi-AABB hollows);
     *     1..6 = 3/16m flush panels (doors & solid trapdoors: 1=-X, 2=+X, 3=-Y, 4=+Y, 5=-Z, 6=+Z);
     *     7..8 = bottom (0..0.5) / top (0.5..1.0) half-slabs & single-AABB beds/stonecutters;
     *     9 = exact 15/16m (0..0.9375) dirt_path / farmland;
     *     10 = conservative 12/16m (0..0.75) enchanting_table / end_portal_frame / snow[layers=6..7];
     *     11 = conservative 6/16m (0..0.375) daylight_detector / unlit campfire / snow[layers=3..5];
     *     12 = centered 14x14x14 ([0.0625..0.9375, 0..0.875, 0.0625..0.9375]) chest / trapped_chest / decorated_pot;
     *     13 = centered 14x16x14 ([0.0625..0.9375, 0..1.0, 0.0625..0.9375]) cactus.
     */
    private static int classifySubBlockPacked(BlockState state, String path, VoxelShape visualShape) {
        if (StairBlock.isStairs(state)
                && state.hasProperty(StairBlock.FACING)
                && state.hasProperty(StairBlock.HALF)) {
            Direction facing = state.getValue(StairBlock.FACING);
            int topBit = (state.getValue(StairBlock.HALF) == Half.TOP) ? 4 : 0;
            StairsShape stairShape = state.hasProperty(StairBlock.SHAPE)
                    ? state.getValue(StairBlock.SHAPE)
                    : StairsShape.STRAIGHT;
            if (stairShape == StairsShape.OUTER_LEFT || stairShape == StairsShape.OUTER_RIGHT) {
                Direction qDir = (stairShape == StairsShape.OUTER_RIGHT) ? facing.getClockWise() : facing;
                int quadBits;
                if (qDir == Direction.NORTH) {
                    quadBits = 0; // NW (-X, -Z)
                } else if (qDir == Direction.EAST) {
                    quadBits = 1; // NE (+X, -Z)
                } else if (qDir == Direction.WEST) {
                    quadBits = 2; // SW (-X, +Z)
                } else {
                    quadBits = 3; // SE (+X, +Z)
                }
                return (15 << 4) | (8 | topBit | quadBits);
            }
            int dirBits;
            if (facing == Direction.WEST) {
                dirBits = 0;
            } else if (facing == Direction.EAST) {
                dirBits = 1;
            } else if (facing == Direction.NORTH) {
                dirBits = 2;
            } else {
                dirBits = 3;
            }
            return (15 << 4) | (topBit | dirBits);
        }

        if (isGratedTrapdoor(path)
                || path.contains("copper_grate")
                || path.contains("spawner")
                || path.equals("vault")
                || path.equals("scaffolding")) {
            return (14 << 4);
        }

        if (visualShape == null || visualShape.isEmpty()) {
            return (14 << 4);
        }

        List<AABB> aabbs = visualShape.toAabbs();
        if (aabbs.size() != 1) {
            // Multi-AABB hollow or composite blocks (cauldron, composter, hopper, lectern, anvil, bell, etc.)
            return (14 << 4);
        }

        AABB bb = aabbs.get(0);
        if (bb.maxY > 1.01 || bb.minY < -0.01) {
            return (14 << 4);
        }

        boolean fullX = (bb.minX <= 0.01 && bb.maxX >= 0.99);
        boolean fullY = (bb.minY <= 0.01 && bb.maxY >= 0.99);
        boolean fullZ = (bb.minZ <= 0.01 && bb.maxZ >= 0.99);

        // Exact 3/16m (0.1875m) flush panels for doors and solid trapdoors (open or closed)
        if (fullY && fullZ) {
            if (bb.minX <= 0.01 && bb.maxX >= 0.18 && bb.maxX <= 0.25) return (14 << 4) | 1;
            if (bb.minX >= 0.75 && bb.minX <= 0.82 && bb.maxX >= 0.99) return (14 << 4) | 2;
        }
        if (fullX && fullZ) {
            if (bb.minY <= 0.01 && bb.maxY >= 0.18 && bb.maxY <= 0.20) return (14 << 4) | 3;
            if (bb.minY >= 0.80 && bb.minY <= 0.82 && bb.maxY >= 0.99) return (14 << 4) | 4;
        }
        if (fullX && fullY) {
            if (bb.minZ <= 0.01 && bb.maxZ >= 0.18 && bb.maxZ <= 0.25) return (14 << 4) | 5;
            if (bb.minZ >= 0.75 && bb.minZ <= 0.82 && bb.maxZ >= 0.99) return (14 << 4) | 6;
        }

        // Full-XZ horizontal sub-blocks (strictly conservative: emitted box <= visual AABB)
        if (fullX && fullZ) {
            if (bb.minY >= 0.49 && bb.minY <= 0.505 && bb.maxY >= 0.99) {
                return (14 << 4) | 8; // Top half-slab [0.5..1.0]
            }
            if (bb.minY <= 0.01) {
                if (bb.maxY >= 0.93 && bb.maxY <= 0.98) {
                    return (14 << 4) | 9; // Exact 15/16m (dirt_path, farmland)
                }
                if (bb.maxY >= 0.745 && bb.maxY < 0.93) {
                    return (14 << 4) | 10; // Conservative 12/16m (enchanting_table, end_portal_frame, snow 6..7)
                }
                if (!path.equals("snow") && bb.maxY >= 0.495 && bb.maxY <= 0.57) {
                    return (14 << 4) | 7; // Bottom half-slab / single-AABB bed / stonecutter [0..0.5]
                }
                if (bb.maxY >= 0.37 && bb.maxY < 0.745) {
                    return (14 << 4) | 11; // Conservative 6/16m (daylight_detector, unlit campfire, snow 3..5)
                }
            }
        }

        // Centered 14/16 inset boxes (chest, trapped_chest, decorated_pot, cactus)
        boolean inset14XZ = (bb.minX <= 0.065 && bb.maxX >= 0.935 && bb.minZ <= 0.065 && bb.maxZ >= 0.935);
        if (inset14XZ && bb.minY <= 0.01) {
            if (path.equals("cactus") && bb.maxY >= 0.99) {
                return (14 << 4) | 13; // Centered 14x16x14
            }
            if ((path.contains("chest") || path.equals("decorated_pot")) && bb.maxY >= 0.87) {
                return (14 << 4) | 12; // Centered 14x14x14 (<= visual for single & double chest and pot body)
            }
        }

        // Thin/small non-occluding cutouts (carpet, snow<=2, cake, fence, wall, iron_bars, chain, ladder, etc.)
        return (14 << 4);
    }

    private static int classifyEmissiveHexRgb(String path) {
        if (path.contains("lava")) return 0xFF520A;
        if (path.contains("magma")) return 0xFF3D06;
        if (path.contains("redstone_torch") || path.contains("redstone_wall_torch")
                || path.contains("repeater") || path.contains("comparator") || path.contains("redstone_ore")) {
            return 0xFF2214;
        }
        if (path.contains("redstone_lamp")) return 0xFF9838;
        if (path.contains("verdant")) return 0x52FF78;
        if (path.contains("glow_lichen") || path.contains("sea_pickle")) return 0x8CE0AD;
        if (path.contains("sea_lantern") || path.contains("conduit") || path.contains("beacon")) return 0x85F2FF;
        if (path.contains("sculk")) return 0x18C8D8;
        if (path.contains("soul")) return 0x24DBFF;
        if (path.contains("end_rod")) return 0xF5DCFF;
        if (path.contains("pearlescent")) return 0xFF94E2;
        if (path.contains("amethyst")) return 0xC86EFF;
        if (path.contains("portal") || path.contains("crying_obsidian") || path.contains("respawn_anchor")
                || path.contains("dragon_egg") || path.contains("end_gateway") || path.contains("ender_chest")) {
            return 0xC23BFF;
        }
        if (path.contains("ochre")) return 0xFFCC62;
        if (path.contains("glowstone")) return 0xFFB848;
        if (path.contains("shroomlight")) return 0xFF802E;
        if (path.contains("copper_bulb")) return 0xFFA242;
        if (path.contains("cave_vines")) return 0xFFB438;
        return 0xFF8826;
    }

    private static int classifySemanticWaveTag(String path, boolean isLavaFluid, boolean opaqueCube, int emit) {
        if (isLavaFluid) {
            return 4;
        }
        if (emit > 0) {
            if (opaqueCube && (path.contains("lava") || path.contains("magma"))) {
                return 4;
            }
            if (path.contains("redstone_torch") || path.contains("repeater") || path.contains("comparator")) {
                return 13;
            }
            if (path.contains("soul") || path.contains("sea_lantern") || path.contains("verdant")
                    || path.contains("sculk") || path.contains("conduit") || path.contains("beacon")
                    || path.contains("glow_lichen") || path.contains("sea_pickle")) {
                return opaqueCube ? 6 : 10;
            }
            if (path.contains("portal") || path.contains("crying_obsidian") || path.contains("end_rod")
                    || path.contains("respawn_anchor") || path.contains("pearlescent") || path.contains("amethyst")
                    || path.contains("dragon_egg") || path.contains("end_gateway") || path.contains("ender_chest")) {
                return opaqueCube ? 7 : 11;
            }
            return opaqueCube ? 5 : 9;
        }
        if (!opaqueCube && classifyGlassSubtype(path) >= 0) {
            return 8;
        }
        if (opaqueCube && classifyPbrMaterialSubtype(path) > 0) {
            return 12;
        }
        if (!opaqueCube && (path.contains("leaves") || path.equals("mangrove_roots"))) {
            return 2;
        }
        return 1;
    }

    private static int classifyGlassSubtype(String path) {
        if (path.equals("tinted_glass")) return -1;
        if (path.equals("ice") || path.equals("frosted_ice")) return 1;
        if (path.contains("red_stained_glass")) return 2;
        if (path.contains("orange_stained_glass") || path.equals("honey_block")) return 3;
        if (path.contains("yellow_stained_glass")) return 4;
        if (path.contains("lime_stained_glass") || path.equals("slime_block")) return 5;
        if (path.contains("green_stained_glass")) return 6;
        if (path.contains("cyan_stained_glass")) return 7;
        if (path.contains("light_blue_stained_glass")) return 8;
        if (path.contains("blue_stained_glass")) return 9;
        if (path.contains("purple_stained_glass")) return 10;
        if (path.contains("magenta_stained_glass")) return 11;
        if (path.contains("pink_stained_glass")) return 12;
        if (path.contains("white_stained_glass") || path.contains("light_gray_stained_glass")) return 13;
        if (path.contains("gray_stained_glass") || path.contains("black_stained_glass")) return 14;
        if (path.contains("brown_stained_glass")) return 15;
        if (path.equals("glass") || path.equals("glass_pane")
                || path.endsWith("_glass") || path.endsWith("_glass_pane")) return 0;
        return -1;
    }

    private static int classifyPbrMaterialSubtype(String path) {
        if (path.contains("coal_ore")) {
            return 0;
        }
        if (path.contains("diamond_ore") || path.contains("emerald_ore") || path.contains("redstone_ore")
                || path.contains("lapis_ore") || path.contains("gold_ore") || path.contains("copper_ore")
                || path.contains("nether_quartz_ore") || path.contains("gilded_blackstone")) {
            return 11;
        }
        if (path.contains("raw_gold") || path.contains("raw_iron") || path.contains("raw_copper")
                || path.endsWith("_ore")) {
            return 10;
        }
        if (path.equals("gold_block")) {
            return 1;
        }
        if (path.equals("iron_block") || path.equals("lodestone")) {
            return 2;
        }
        if (path.contains("weathered_copper") || path.contains("weathered_cut_copper") || path.contains("weathered_chiseled_copper")
                || path.contains("oxidized_copper") || path.contains("oxidized_cut_copper") || path.contains("oxidized_chiseled_copper")
                || path.contains("weathered_copper_bulb") || path.contains("oxidized_copper_bulb")) {
            return 5;
        }
        if (path.contains("exposed_copper") || path.contains("exposed_cut_copper") || path.contains("exposed_chiseled_copper")
                || path.contains("exposed_copper_bulb")) {
            return 4;
        }
        if (path.contains("copper_block") || path.contains("cut_copper") || path.contains("chiseled_copper")
                || path.contains("copper_bulb")) {
            return 3;
        }
        if (path.equals("netherite_block") || path.equals("ancient_debris")) {
            return 6;
        }
        if (path.equals("diamond_block") || path.equals("emerald_block") || path.equals("lapis_block")
                || path.equals("redstone_block") || path.equals("amethyst_block") || path.equals("budding_amethyst")
                || path.equals("packed_ice") || path.equals("blue_ice")) {
            return 7;
        }
        if (path.contains("quartz_block") || path.contains("smooth_quartz") || path.contains("quartz_bricks")
                || path.contains("quartz_pillar") || path.contains("chiseled_quartz") || path.contains("quartz_slab")
                || path.endsWith("glazed_terracotta") || path.equals("calcite")) {
            return 8;
        }
        if (path.equals("obsidian") || path.contains("polished_blackstone") || path.contains("polished_deepslate")
                || path.equals("polished_basalt") || path.equals("smooth_basalt") || path.contains("polished_granite")
                || path.contains("polished_diorite") || path.contains("polished_andesite") || path.contains("polished_tuff")
                || path.equals("dark_prismarine") || path.equals("prismarine_bricks")
                || path.startsWith("purpur") || path.equals("end_stone_bricks")) {
            return 9;
        }
        return 0;
    }

    private static synchronized void updateStateCache(int rawId, int matId, long palVer) {
        if (cachedPaletteVersion != palVer) {
            Arrays.fill(stateIdToMaterialCache, -1);
            cachedPaletteVersion = palVer;
        }
        if (rawId < 0) {
            return;
        }
        if (rawId >= stateIdToMaterialCache.length) {
            int oldLen = stateIdToMaterialCache.length;
            int newLen = Math.max(oldLen * 2, rawId + 1024);
            stateIdToMaterialCache = Arrays.copyOf(stateIdToMaterialCache, newLen);
            Arrays.fill(stateIdToMaterialCache, oldLen, newLen, -1);
        }
        stateIdToMaterialCache[rawId] = matId;
    }

    private static synchronized MemorySegment ensureBakedPalette(DefaultBlockMaterialRegistry registry) {
        long ver = registry.paletteVersion();
        if (bakedPaletteVersion == ver && bakedPaletteSize == registry.size()) {
            return PERSISTENT_PALETTE_BUFFER;
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment fresh = registry.bakeNativePalette(temp);
            long copyLen = Math.min(fresh.byteSize(), PERSISTENT_PALETTE_BUFFER.byteSize());
            MemorySegment.copy(fresh, 0L, PERSISTENT_PALETTE_BUFFER, 0L, copyLen);
            bakedPaletteSize = (int) (copyLen / 18L);
            bakedPaletteVersion = ver;
        }
        return PERSISTENT_PALETTE_BUFFER;
    }
}

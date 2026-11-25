package dev.metality.mc263.glass;

import dev.metality.mc263.shader.Mc263ShaderRuntime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine-level Connected Glass &amp; Connected Glass Panes chunk-meshing processor.
 *
 * <p>Thread-safe and zero-allocation on the chunk-builder hot path:
 * <ul>
 *   <li>Maintains an immutable {@link AtlasSnapshot} swapped atomically on {@code TextureAtlas.upload}.</li>
 *   <li>Caches per-{@link BakedQuad} UV basis, pane sub-element classification, and lazy 47-tile remapped
 *       {@link BakedQuad} variants in lock-free {@link ConcurrentHashMap} + {@link AtomicReferenceArray}.</li>
 *   <li>Eliminates un-culled vertical glass pane rim caps ({@code *_glass_pane_top}) per arm/post and
 *       flush horizontal rim caps against matching full glass blocks.</li>
 *   <li>Computes exact 8-neighbor CTM masks (including diagonal inner corners and per-arm pane continuity)
 *       directly from each quad's extracted 3D {@code +U/+V} basis.</li>
 * </ul>
 */
public final class ConnectedGlassEngine {

    /** Sub-element classification for vanilla/custom glass pane quads derived from [0..1] block bounds. */
    public enum PaneSubPart {
        NONE(null),
        CENTER_POST(null),
        ARM_NORTH(Direction.NORTH),
        ARM_SOUTH(Direction.SOUTH),
        ARM_EAST(Direction.EAST),
        ARM_WEST(Direction.WEST);

        private final Direction armDir;

        PaneSubPart(Direction armDir) {
            this.armDir = armDir;
        }

        public Direction armDir() {
            return armDir;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Metality/ConnectedGlass");

    private static final class AtlasSnapshot {
        final Map<Identifier, TextureAtlasSprite[]> spritesByBase;
        final ConcurrentHashMap<BakedQuad, QuadCtmEntry> quadCache = new ConcurrentHashMap<>(1024);
        final AtomicBoolean firstAppliedLogged = new AtomicBoolean(false);

        AtlasSnapshot(Map<Identifier, TextureAtlasSprite[]> spritesByBase) {
            this.spritesByBase = spritesByBase;
        }
    }

    private static final class QuadCtmEntry {
        final boolean isPaneEdge;
        final PaneSubPart paneSubPart;
        final TextureAtlasSprite[] tileSprites; // null if not a CTM-synthesized glass sprite
        final float lu0, lv0, lu1, lv1, lu2, lv2, lu3, lv3;
        final Direction dirU, dirD, dirL, dirR;
        final boolean armOuterIsRight;
        final AtomicReferenceArray<BakedQuad> variants;

        QuadCtmEntry(
                boolean isPaneEdge,
                PaneSubPart paneSubPart,
                TextureAtlasSprite[] tileSprites,
                float lu0, float lv0, float lu1, float lv1, float lu2, float lv2, float lu3, float lv3,
                Direction dirU, Direction dirD, Direction dirL, Direction dirR,
                boolean armOuterIsRight,
                BakedQuad originalQuad
        ) {
            this.isPaneEdge = isPaneEdge;
            this.paneSubPart = paneSubPart;
            this.tileSprites = tileSprites;
            this.lu0 = lu0; this.lv0 = lv0;
            this.lu1 = lu1; this.lv1 = lv1;
            this.lu2 = lu2; this.lv2 = lv2;
            this.lu3 = lu3; this.lv3 = lv3;
            this.dirU = dirU;
            this.dirD = dirD;
            this.dirL = dirL;
            this.dirR = dirR;
            this.armOuterIsRight = armOuterIsRight;
            if (tileSprites != null) {
                this.variants = new AtomicReferenceArray<>(ConnectedGlassRegistry.TILE_COUNT);
                this.variants.set(0, originalQuad);
            } else {
                this.variants = null;
            }
        }

        BakedQuad getOrCreateVariant(BakedQuad originalQuad, int tileIdx) {
            if (tileIdx <= 0 || tileIdx >= ConnectedGlassRegistry.TILE_COUNT || tileSprites == null) {
                return originalQuad;
            }
            BakedQuad cached = variants.get(tileIdx);
            if (cached != null) {
                return cached;
            }
            TextureAtlasSprite targetSprite = tileSprites[tileIdx];
            if (targetSprite == null) {
                return originalQuad;
            }
            BakedQuad built = remapQuadToSprite(originalQuad, targetSprite,
                    lu0, lv0, lu1, lv1, lu2, lv2, lu3, lv3);
            if (variants.compareAndSet(tileIdx, null, built)) {
                return built;
            }
            BakedQuad winner = variants.get(tileIdx);
            return winner != null ? winner : built;
        }
    }

    private static volatile AtlasSnapshot activeSnapshot = new AtlasSnapshot(Map.of());
    private static volatile Boolean enabledOverrideForTesting = null;
    private static final ThreadLocal<BlockPos.MutableBlockPos> SCRATCH_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private ConnectedGlassEngine() {}

    /** Lock-free hot-path query for whether Engine Connected Glass is enabled. */
    public static boolean isEnabled() {
        Boolean override = enabledOverrideForTesting;
        return override != null ? override : Mc263ShaderRuntime.isConnectedGlassEnabled();
    }

    /** Test-only override to deterministically exercise enabled/disabled toggle behavior. */
    public static void setEnabledOverrideForTesting(Boolean override) {
        enabledOverrideForTesting = override;
    }

    /**
     * Returns true iff {@code level} is a live world/chunk region suitable for neighbor-aware CTM
     * (includes vanilla/Indigo {@code RenderSectionRegion} and third-party chunk slices while
     * excluding GUI/item {@link BlockAndTintGetter#EMPTY} and moving piston/falling-block entities).
     */
    public static boolean isEligibleWorldRegion(BlockAndTintGetter level) {
        return level != null
                && level != BlockAndTintGetter.EMPTY
                && !(level instanceof MovingBlockRenderState);
    }

    /** Fast O(1) pre-filter for chunk-meshing hot paths (~0.5ns on non-glass blocks). */
    public static boolean isCandidateState(BlockState state) {
        return state != null && ConnectedGlassRegistry.isConnectedGlassBlock(state.getBlock());
    }

    /**
     * Called from {@code TextureAtlasMixin} right after the blocks atlas finishes uploading
     * (initial load and every {@code F3+T} / resource pack reload).
     */
    public static void onBlocksAtlasUploaded(Map<Identifier, TextureAtlasSprite> regions) {
        if (regions == null || regions.isEmpty()) {
            activeSnapshot = new AtlasSnapshot(Map.of());
            return;
        }
        Map<Identifier, TextureAtlasSprite[]> built = new HashMap<>(32);
        for (Identifier baseId : ConnectedGlassRegistry.glassBaseSpriteIds()) {
            Identifier[] variantIds = ConnectedGlassRegistry.variantSpriteIds(baseId);
            if (variantIds == null) {
                continue;
            }
            TextureAtlasSprite baseSprite = regions.get(baseId);
            if (baseSprite == null) {
                continue;
            }
            TextureAtlasSprite[] sprites = new TextureAtlasSprite[ConnectedGlassRegistry.TILE_COUNT];
            sprites[0] = baseSprite;
            boolean complete = true;
            for (int tile = 1; tile < ConnectedGlassRegistry.TILE_COUNT; ++tile) {
                TextureAtlasSprite s = regions.get(variantIds[tile]);
                if (s == null) {
                    complete = false;
                    break;
                }
                sprites[tile] = s;
            }
            if (complete) {
                built.put(baseId, sprites);
            }
        }
        activeSnapshot = new AtlasSnapshot(Collections.unmodifiableMap(built));
        if (built.isEmpty()) {
            LOGGER.warn("[Metality] Connected Glass CTM snapshot found 0/{} complete glass families in blocks atlas ({} regions)",
                    ConnectedGlassRegistry.glassBaseSpriteIds().size(), regions.size());
        } else {
            LOGGER.info("[Metality] Connected Glass CTM snapshot ready: {}/{} glass families ({} sprites)",
                    built.size(),
                    ConnectedGlassRegistry.glassBaseSpriteIds().size(),
                    built.size() * ConnectedGlassRegistry.TILE_COUNT);
        }
    }

    /**
     * Returns true iff this quad is an internal glass-pane rim/cap face that should be culled
     * (stacked vertical {@code UP}/{@code DOWN} rim caps covered by an identical pane's post/arm,
     * or a horizontal rim end-cap pressed flush against a matching full glass block).
     */
    public static boolean shouldCullGlassPaneRimQuad(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            BakedQuad quad
    ) {
        if (!isEnabled()
                || !isEligibleWorldRegion(level)
                || pos == null
                || !ConnectedGlassRegistry.isGlassPaneBlock(state.getBlock())) {
            return false;
        }
        AtlasSnapshot snap = activeSnapshot;
        QuadCtmEntry entry = resolveEntry(snap, quad);
        if (!entry.isPaneEdge) {
            return false;
        }

        Direction faceDir = quad.direction();
        Block paneBlock = state.getBlock();
        BlockPos.MutableBlockPos mPos = SCRATCH_POS.get();
        mPos.setWithOffset(pos, faceDir);
        BlockState adjState = level.getBlockState(mPos);

        if (faceDir == Direction.UP || faceDir == Direction.DOWN) {
            return shouldCullVerticalPaneRim(
                    entry.paneSubPart,
                    adjState.is(paneBlock),
                    ConnectedGlassRegistry.paneHasArm(adjState, Direction.NORTH),
                    ConnectedGlassRegistry.paneHasArm(adjState, Direction.SOUTH),
                    ConnectedGlassRegistry.paneHasArm(adjState, Direction.EAST),
                    ConnectedGlassRegistry.paneHasArm(adjState, Direction.WEST)
            );
        }

        // Horizontal #edge end-cap: vanilla already culls against same pane & solid cubes.
        // Also cull when flush against a matching-color full glass block to eliminate z-fighting seams.
        return ConnectedGlassRegistry.isMatchingFullGlassForPane(paneBlock, adjState.getBlock());
    }

    /**
     * Pure-logic helper for vertical ({@code UP}/{@code DOWN}) glass pane rim culling per sub-element.
     * Ensures stepped, L-shaped, T-junction, and cross-junction panes keep exposed top/bottom rims
     * while removing 100% of internal horizontal seams between stacked panes.
     */
    public static boolean shouldCullVerticalPaneRim(
            PaneSubPart subPart,
            boolean adjIsSamePane,
            boolean adjNorth,
            boolean adjSouth,
            boolean adjEast,
            boolean adjWest
    ) {
        if (!adjIsSamePane) {
            return false;
        }
        return switch (subPart) {
            case CENTER_POST -> true;
            case ARM_NORTH -> adjNorth;
            case ARM_SOUTH -> adjSouth;
            case ARM_EAST -> adjEast;
            case ARM_WEST -> adjWest;
            case NONE -> false;
        };
    }

    /**
     * Remaps a glass block or glass pane {@link BakedQuad} to the appropriate stitched 47-tile CTM
     * sprite variant based on its 8-neighbor world topology. Returns {@code quad} unchanged for
     * non-glass blocks, isolated glass (tile 0), or when connected glass is disabled.
     */
    public static BakedQuad remapGlassQuad(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            BakedQuad quad
    ) {
        if (!isEnabled()
                || !isEligibleWorldRegion(level)
                || pos == null) {
            return quad;
        }
        Block block = state.getBlock();
        boolean isFullGlass = ConnectedGlassRegistry.isFullGlassBlock(block);
        boolean isPane = !isFullGlass && ConnectedGlassRegistry.isGlassPaneBlock(block);
        if (!isFullGlass && !isPane) {
            return quad;
        }

        AtlasSnapshot snap = activeSnapshot;
        if (snap.spritesByBase.isEmpty()) {
            return quad;
        }
        QuadCtmEntry entry = resolveEntry(snap, quad);
        if (entry.tileSprites == null || entry.dirU == null || entry.dirR == null) {
            return quad;
        }

        int mask = isFullGlass
                ? computeFullGlassMask(level, block, pos, entry.dirU, entry.dirD, entry.dirL, entry.dirR)
                : computeGlassPaneMask(level, block, pos, entry);
        int tileIdx = ConnectedGlassRegistry.tileIndexForMask(mask);
        if (tileIdx == 0) {
            return quad;
        }
        BakedQuad remapped = entry.getOrCreateVariant(quad, tileIdx);
        if (remapped != quad && !snap.firstAppliedLogged.get() && snap.firstAppliedLogged.compareAndSet(false, true)) {
            LOGGER.info("[Metality] Connected Glass active in chunk meshing (first remapped {} at {}, tile={})",
                    block, pos, tileIdx);
        }
        return remapped;
    }

    private static int computeFullGlassMask(
            BlockAndTintGetter level,
            Block glassBlock,
            BlockPos pos,
            Direction dirU,
            Direction dirD,
            Direction dirL,
            Direction dirR
    ) {
        BlockPos.MutableBlockPos mPos = SCRATCH_POS.get();
        int bx = pos.getX();
        int by = pos.getY();
        int bz = pos.getZ();

        int ux = dirU.getStepX(), uy = dirU.getStepY(), uz = dirU.getStepZ();
        int dx = dirD.getStepX(), dy = dirD.getStepY(), dz = dirD.getStepZ();
        int lx = dirL.getStepX(), ly = dirL.getStepY(), lz = dirL.getStepZ();
        int rx = dirR.getStepX(), ry = dirR.getStepY(), rz = dirR.getStepZ();

        boolean u = level.getBlockState(mPos.set(bx + ux, by + uy, bz + uz)).is(glassBlock);
        boolean r = level.getBlockState(mPos.set(bx + rx, by + ry, bz + rz)).is(glassBlock);
        boolean d = level.getBlockState(mPos.set(bx + dx, by + dy, bz + dz)).is(glassBlock);
        boolean l = level.getBlockState(mPos.set(bx + lx, by + ly, bz + lz)).is(glassBlock);

        int mask = 0;
        if (u) mask |= ConnectedGlassRegistry.BIT_UP;
        if (r) mask |= ConnectedGlassRegistry.BIT_RIGHT;
        if (d) mask |= ConnectedGlassRegistry.BIT_DOWN;
        if (l) mask |= ConnectedGlassRegistry.BIT_LEFT;

        // Only probe diagonals when both adjacent cardinal neighbors are connected
        if (u && l && level.getBlockState(mPos.set(bx + ux + lx, by + uy + ly, bz + uz + lz)).is(glassBlock)) {
            mask |= ConnectedGlassRegistry.BIT_UP_LEFT;
        }
        if (u && r && level.getBlockState(mPos.set(bx + ux + rx, by + uy + ry, bz + uz + rz)).is(glassBlock)) {
            mask |= ConnectedGlassRegistry.BIT_UP_RIGHT;
        }
        if (d && r && level.getBlockState(mPos.set(bx + dx + rx, by + dy + ry, bz + dz + rz)).is(glassBlock)) {
            mask |= ConnectedGlassRegistry.BIT_DOWN_RIGHT;
        }
        if (d && l && level.getBlockState(mPos.set(bx + dx + lx, by + dy + ly, bz + dz + lz)).is(glassBlock)) {
            mask |= ConnectedGlassRegistry.BIT_DOWN_LEFT;
        }
        return mask;
    }

    private static int computeGlassPaneMask(
            BlockAndTintGetter level,
            Block paneBlock,
            BlockPos pos,
            QuadCtmEntry entry
    ) {
        PaneSubPart subPart = entry.paneSubPart;
        if (subPart == PaneSubPart.NONE) {
            return computeFullGlassMask(level, paneBlock, pos, entry.dirU, entry.dirD, entry.dirL, entry.dirR);
        }

        BlockPos.MutableBlockPos mPos = SCRATCH_POS.get();
        int bx = pos.getX();
        int by = pos.getY();
        int bz = pos.getZ();
        int ux = entry.dirU.getStepX(), uy = entry.dirU.getStepY(), uz = entry.dirU.getStepZ();
        int dx = entry.dirD.getStepX(), dy = entry.dirD.getStepY(), dz = entry.dirD.getStepZ();

        if (subPart == PaneSubPart.CENTER_POST) {
            boolean u = level.getBlockState(mPos.set(bx + ux, by + uy, bz + uz)).is(paneBlock);
            boolean d = level.getBlockState(mPos.set(bx + dx, by + dy, bz + dz)).is(paneBlock);
            return buildPaneCenterPostMask(u, d);
        }

        // ARM_NORTH / ARM_SOUTH / ARM_EAST / ARM_WEST
        Direction armDir = subPart.armDir();
        Direction oppArmDir = armDir.getOpposite();
        int ax = armDir.getStepX(), ay = armDir.getStepY(), az = armDir.getStepZ();

        BlockState upState = level.getBlockState(mPos.set(bx + ux, by + uy, bz + uz));
        BlockState downState = level.getBlockState(mPos.set(bx + dx, by + dy, bz + dz));
        BlockState outerState = level.getBlockState(mPos.set(bx + ax, by + ay, bz + az));

        boolean upConnected = upState.is(paneBlock) && ConnectedGlassRegistry.paneHasArm(upState, armDir);
        boolean downConnected = downState.is(paneBlock) && ConnectedGlassRegistry.paneHasArm(downState, armDir);
        boolean outerConnected = connectsPaneOuterEdge(paneBlock, oppArmDir, outerState);

        boolean diagUpOuter = false;
        if (upConnected && outerConnected) {
            BlockState diagState = level.getBlockState(mPos.set(bx + ux + ax, by + uy + ay, bz + uz + az));
            diagUpOuter = connectsPaneOuterEdge(paneBlock, oppArmDir, diagState);
        }
        boolean diagDownOuter = false;
        if (downConnected && outerConnected) {
            BlockState diagState = level.getBlockState(mPos.set(bx + dx + ax, by + dy + ay, bz + dz + az));
            diagDownOuter = connectsPaneOuterEdge(paneBlock, oppArmDir, diagState);
        }

        return buildPaneArmMask(entry.armOuterIsRight, upConnected, downConnected, outerConnected, diagUpOuter, diagDownOuter);
    }

    private static boolean connectsPaneOuterEdge(Block paneBlock, Direction oppArmDir, BlockState neighbor) {
        if (neighbor.is(paneBlock)) {
            return ConnectedGlassRegistry.paneHasArm(neighbor, oppArmDir);
        }
        return ConnectedGlassRegistry.isMatchingFullGlassForPane(paneBlock, neighbor.getBlock());
    }

    /**
     * Pure-logic mask builder for a glass pane center-post face ({@code noside}/{@code noside_alt}).
     * Marks both horizontal edges and matching horizontal corners as connected so even thick-border
     * custom resource packs never inject stray border pixels into the center {@code [7/16..9/16]} strip.
     */
    public static int buildPaneCenterPostMask(boolean upConnected, boolean downConnected) {
        int mask = ConnectedGlassRegistry.BIT_LEFT | ConnectedGlassRegistry.BIT_RIGHT;
        if (upConnected) {
            mask |= ConnectedGlassRegistry.BIT_UP
                    | ConnectedGlassRegistry.BIT_UP_LEFT
                    | ConnectedGlassRegistry.BIT_UP_RIGHT;
        }
        if (downConnected) {
            mask |= ConnectedGlassRegistry.BIT_DOWN
                    | ConnectedGlassRegistry.BIT_DOWN_LEFT
                    | ConnectedGlassRegistry.BIT_DOWN_RIGHT;
        }
        return mask;
    }

    /**
     * Pure-logic mask builder for a glass pane wing ({@code side}/{@code side_alt}).
     * Always marks the post-facing (inner) horizontal edge and its active vertical corners as connected
     * so thick-border packs never produce corner ticks at {@code u=7/16} or {@code u=9/16}, while
     * preserving exact 8-neighbor outer borders and 90-degree inner corners against solid blocks.
     */
    public static int buildPaneArmMask(
            boolean outerIsRight,
            boolean upConnected,
            boolean downConnected,
            boolean outerConnected,
            boolean diagUpOuter,
            boolean diagDownOuter
    ) {
        int mask = 0;
        if (upConnected) mask |= ConnectedGlassRegistry.BIT_UP;
        if (downConnected) mask |= ConnectedGlassRegistry.BIT_DOWN;

        if (outerIsRight) {
            // Inner (post-facing) edge is LEFT; outer edge is RIGHT
            mask |= ConnectedGlassRegistry.BIT_LEFT;
            if (upConnected) mask |= ConnectedGlassRegistry.BIT_UP_LEFT;
            if (downConnected) mask |= ConnectedGlassRegistry.BIT_DOWN_LEFT;
            if (outerConnected) {
                mask |= ConnectedGlassRegistry.BIT_RIGHT;
                if (upConnected && diagUpOuter) mask |= ConnectedGlassRegistry.BIT_UP_RIGHT;
                if (downConnected && diagDownOuter) mask |= ConnectedGlassRegistry.BIT_DOWN_RIGHT;
            }
        } else {
            // Inner (post-facing) edge is RIGHT; outer edge is LEFT
            mask |= ConnectedGlassRegistry.BIT_RIGHT;
            if (upConnected) mask |= ConnectedGlassRegistry.BIT_UP_RIGHT;
            if (downConnected) mask |= ConnectedGlassRegistry.BIT_DOWN_RIGHT;
            if (outerConnected) {
                mask |= ConnectedGlassRegistry.BIT_LEFT;
                if (upConnected && diagUpOuter) mask |= ConnectedGlassRegistry.BIT_UP_LEFT;
                if (downConnected && diagDownOuter) mask |= ConnectedGlassRegistry.BIT_DOWN_LEFT;
            }
        }
        return mask;
    }

    private static QuadCtmEntry resolveEntry(AtlasSnapshot snap, BakedQuad quad) {
        QuadCtmEntry cached = snap.quadCache.get(quad);
        if (cached != null) {
            return cached;
        }
        QuadCtmEntry created = analyzeQuad(snap, quad);
        QuadCtmEntry prev = snap.quadCache.putIfAbsent(quad, created);
        return prev != null ? prev : created;
    }

    private static QuadCtmEntry analyzeQuad(AtlasSnapshot snap, BakedQuad quad) {
        BakedQuad.MaterialInfo mi = quad.materialInfo();
        TextureAtlasSprite sprite = mi.sprite();
        Identifier spriteId = sprite.contents().name();
        boolean isPaneEdge = ConnectedGlassRegistry.isPaneEdgeSprite(spriteId);

        Vector3fc p0 = quad.position0();
        Vector3fc p1 = quad.position1();
        Vector3fc p2 = quad.position2();
        Vector3fc p3 = quad.position3();

        float minX = Math.min(Math.min(p0.x(), p1.x()), Math.min(p2.x(), p3.x()));
        float maxX = Math.max(Math.max(p0.x(), p1.x()), Math.max(p2.x(), p3.x()));
        float minZ = Math.min(Math.min(p0.z(), p1.z()), Math.min(p2.z(), p3.z()));
        float maxZ = Math.max(Math.max(p0.z(), p1.z()), Math.max(p2.z(), p3.z()));
        PaneSubPart subPart = classifyPaneSubPart(minX, maxX, minZ, maxZ);

        TextureAtlasSprite[] tileSprites = isPaneEdge ? null : snap.spritesByBase.get(spriteId);
        if (tileSprites == null) {
            return new QuadCtmEntry(
                    isPaneEdge, subPart, null,
                    0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                    null, null, null, null, false, quad
            );
        }

        float u0 = sprite.getU0();
        float uSpan = sprite.getU1() - u0;
        float v0 = sprite.getV0();
        float vSpan = sprite.getV1() - v0;
        if (Math.abs(uSpan) < 1e-7f || Math.abs(vSpan) < 1e-7f) {
            return new QuadCtmEntry(
                    false, subPart, null,
                    0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                    null, null, null, null, false, quad
            );
        }

        float[] px = {p0.x(), p1.x(), p2.x(), p3.x()};
        float[] py = {p0.y(), p1.y(), p2.y(), p3.y()};
        float[] pz = {p0.z(), p1.z(), p2.z(), p3.z()};
        float[] lu = {
                Math.clamp((UVPair.unpackU(quad.packedUV0()) - u0) / uSpan, 0.0f, 1.0f),
                Math.clamp((UVPair.unpackU(quad.packedUV1()) - u0) / uSpan, 0.0f, 1.0f),
                Math.clamp((UVPair.unpackU(quad.packedUV2()) - u0) / uSpan, 0.0f, 1.0f),
                Math.clamp((UVPair.unpackU(quad.packedUV3()) - u0) / uSpan, 0.0f, 1.0f)
        };
        float[] lv = {
                Math.clamp((UVPair.unpackV(quad.packedUV0()) - v0) / vSpan, 0.0f, 1.0f),
                Math.clamp((UVPair.unpackV(quad.packedUV1()) - v0) / vSpan, 0.0f, 1.0f),
                Math.clamp((UVPair.unpackV(quad.packedUV2()) - v0) / vSpan, 0.0f, 1.0f),
                Math.clamp((UVPair.unpackV(quad.packedUV3()) - v0) / vSpan, 0.0f, 1.0f)
        };

        Direction[] basis = extractUvBasis(px, py, pz, lu, lv);
        Direction dirR = basis[0];
        Direction dirD = basis[1];
        if (dirR == null || dirD == null || dirR.getAxis() == dirD.getAxis()) {
            return new QuadCtmEntry(
                    false, subPart, null,
                    0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                    null, null, null, null, false, quad
            );
        }
        Direction dirL = dirR.getOpposite();
        Direction dirU = dirD.getOpposite();
        boolean armOuterIsRight = subPart.armDir() != null && subPart.armDir() == dirR;

        return new QuadCtmEntry(
                false, subPart, tileSprites,
                lu[0], lv[0], lu[1], lv[1], lu[2], lv[2], lu[3], lv[3],
                dirU, dirD, dirL, dirR, armOuterIsRight, quad
        );
    }

    /**
     * Classifies a glass pane quad into center post or one of the 4 horizontal arms from its
     * block-local XZ bounds in {@code [0, 1]}.
     */
    public static PaneSubPart classifyPaneSubPart(float minX, float maxX, float minZ, float maxZ) {
        if (minX >= 0.40f && maxX <= 0.60f && minZ >= 0.40f && maxZ <= 0.60f) {
            return PaneSubPart.CENTER_POST;
        }
        if (minZ < 0.40f && maxZ <= 0.50f && minX >= 0.40f && maxX <= 0.60f) {
            return PaneSubPart.ARM_NORTH;
        }
        if (minZ >= 0.50f && maxZ > 0.60f && minX >= 0.40f && maxX <= 0.60f) {
            return PaneSubPart.ARM_SOUTH;
        }
        if (minX < 0.40f && maxX <= 0.50f && minZ >= 0.40f && maxZ <= 0.60f) {
            return PaneSubPart.ARM_WEST;
        }
        if (minX >= 0.50f && maxX > 0.60f && minZ >= 0.40f && maxZ <= 0.60f) {
            return PaneSubPart.ARM_EAST;
        }
        return PaneSubPart.NONE;
    }

    /**
     * Extracts {@code [dirR (+U), dirD (+V)]} directly from quad vertex positions and normalized UVs.
     * Immune to rotated/flipped pane models (such as {@code template_glass_pane_side}'s reversed west UV).
     */
    public static Direction[] extractUvBasis(
            float[] px, float[] py, float[] pz,
            float[] lu, float[] lv
    ) {
        Direction dirR = null;
        Direction dirD = null;
        float bestDu = 1e-4f;
        float bestDv = 1e-4f;

        for (int i = 0; i < 4; ++i) {
            int j = (i + 1) & 3;
            float du = lu[j] - lu[i];
            float dv = lv[j] - lv[i];
            float dx = px[j] - px[i];
            float dy = py[j] - py[i];
            float dz = pz[j] - pz[i];
            float absDu = Math.abs(du);
            float absDv = Math.abs(dv);

            if (absDu > bestDu && absDu >= absDv) {
                float sign = du > 0f ? 1f : -1f;
                Direction d = snapCardinal(dx * sign, dy * sign, dz * sign);
                if (d != null) {
                    dirR = d;
                    bestDu = absDu;
                }
            }
            if (absDv > bestDv && absDv >= absDu) {
                float sign = dv > 0f ? 1f : -1f;
                Direction d = snapCardinal(dx * sign, dy * sign, dz * sign);
                if (d != null) {
                    dirD = d;
                    bestDv = absDv;
                }
            }
        }
        return new Direction[]{dirR, dirD};
    }

    private static Direction snapCardinal(float x, float y, float z) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (ax >= ay && ax >= az && ax > 1e-4f) {
            return x > 0f ? Direction.EAST : Direction.WEST;
        }
        if (ay >= ax && ay >= az && ay > 1e-4f) {
            return y > 0f ? Direction.UP : Direction.DOWN;
        }
        if (az > 1e-4f) {
            return z > 0f ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    private static BakedQuad remapQuadToSprite(
            BakedQuad orig,
            TextureAtlasSprite targetSprite,
            float lu0, float lv0, float lu1, float lv1, float lu2, float lv2, float lu3, float lv3
    ) {
        BakedQuad.MaterialInfo mi = orig.materialInfo();
        BakedQuad.MaterialInfo remappedInfo = new BakedQuad.MaterialInfo(
                targetSprite,
                mi.layer(),
                mi.itemRenderType(),
                mi.itemGlintRenderType(),
                mi.itemGlintSpecialRenderType(),
                mi.tintIndex(),
                mi.shadeDirectionOverride(),
                mi.lightEmission()
        );
        return new BakedQuad(
                orig.position0(),
                orig.position1(),
                orig.position2(),
                orig.position3(),
                UVPair.pack(targetSprite.getU(lu0), targetSprite.getV(lv0)),
                UVPair.pack(targetSprite.getU(lu1), targetSprite.getV(lv1)),
                UVPair.pack(targetSprite.getU(lu2), targetSprite.getV(lv2)),
                UVPair.pack(targetSprite.getU(lu3), targetSprite.getV(lv3)),
                orig.direction(),
                remappedInfo
        );
    }
}

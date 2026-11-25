package dev.metality.mc263;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.NativeImage;
import dev.metality.mc263.glass.ConnectedGlassAtlasGenerator;
import dev.metality.mc263.glass.ConnectedGlassBlockStateModel;
import dev.metality.mc263.glass.ConnectedGlassEngine;
import dev.metality.mc263.glass.ConnectedGlassEngine.PaneSubPart;
import dev.metality.mc263.glass.ConnectedGlassRegistry;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.fabric.impl.client.renderer.VanillaBlockModelPartEncoder;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

public final class ConnectedGlassTest {

    @Test
    void testCanonical47TileBijection() {
        assertEquals(47, ConnectedGlassRegistry.TILE_COUNT);
        assertEquals(0, ConnectedGlassRegistry.tileIndexForMask(0));
        assertEquals(0, ConnectedGlassRegistry.canonicalMaskForTile(0));

        Set<Integer> seenTiles = new HashSet<>();
        Set<Integer> seenCanonical = new HashSet<>();
        for (int raw = 0; raw < 256; ++raw) {
            int canonical = ConnectedGlassRegistry.canonicalizeMask(raw);
            assertEquals(canonical, ConnectedGlassRegistry.canonicalizeMask(canonical), "canonicalizeMask must be idempotent");
            int tile = ConnectedGlassRegistry.tileIndexForMask(raw);
            assertTrue(tile >= 0 && tile < 47, "tile out of range: " + tile);
            assertEquals(canonical, ConnectedGlassRegistry.canonicalMaskForTile(tile));
            assertEquals(tile, ConnectedGlassRegistry.tileIndexForMask(canonical));
            seenTiles.add(tile);
            seenCanonical.add(canonical);
        }
        assertEquals(47, seenTiles.size(), "All 47 CTM tile indices must be reachable");
        assertEquals(47, seenCanonical.size(), "Exactly 47 unique canonical 8-neighbor states");

        // Diagonal bits without both adjacent cardinal bits must collapse to tile 0
        int diagOnly = ConnectedGlassRegistry.BIT_UP_LEFT
                | ConnectedGlassRegistry.BIT_UP_RIGHT
                | ConnectedGlassRegistry.BIT_DOWN_RIGHT
                | ConnectedGlassRegistry.BIT_DOWN_LEFT;
        assertEquals(0, ConnectedGlassRegistry.tileIndexForMask(diagOnly));

        // U + L without UL must be distinct from U + L + UL (concave inner corner!)
        int ulInnerCorner = ConnectedGlassRegistry.BIT_UP | ConnectedGlassRegistry.BIT_LEFT;
        int ulFilled = ulInnerCorner | ConnectedGlassRegistry.BIT_UP_LEFT;
        assertNotEquals(
                ConnectedGlassRegistry.tileIndexForMask(ulInnerCorner),
                ConnectedGlassRegistry.tileIndexForMask(ulFilled),
                "Concave inner corner state must map to a distinct CTM tile"
        );
    }

    @Test
    void testSpriteRegistryCompleteness() {
        assertEquals(18, ConnectedGlassRegistry.glassBaseSpriteIds().size(),
                "Expected glass + tinted_glass + 16 stained_glass = 18 base sprites");
        assertTrue(ConnectedGlassRegistry.isGlassBaseSprite(Identifier.withDefaultNamespace("block/glass")));
        assertTrue(ConnectedGlassRegistry.isGlassBaseSprite(Identifier.withDefaultNamespace("block/tinted_glass")));
        assertTrue(ConnectedGlassRegistry.isPaneEdgeSprite(Identifier.withDefaultNamespace("block/glass_pane_top")));
        assertFalse(ConnectedGlassRegistry.isGlassBaseSprite(Identifier.withDefaultNamespace("block/iron_bars")));
        assertFalse(ConnectedGlassRegistry.isPaneEdgeSprite(Identifier.withDefaultNamespace("block/iron_bars")));

        for (DyeColor color : DyeColor.values()) {
            Identifier base = Identifier.withDefaultNamespace("block/" + color.getName() + "_stained_glass");
            Identifier edge = Identifier.withDefaultNamespace("block/" + color.getName() + "_stained_glass_pane_top");
            assertTrue(ConnectedGlassRegistry.isGlassBaseSprite(base), "Missing base sprite " + base);
            assertTrue(ConnectedGlassRegistry.isPaneEdgeSprite(edge), "Missing edge sprite " + edge);
            Identifier[] variants = ConnectedGlassRegistry.variantSpriteIds(base);
            assertNotNull(variants);
            assertEquals(47, variants.length);
            assertEquals(base, variants[0]);
            Set<Identifier> unique = new HashSet<>();
            for (Identifier id : variants) {
                assertTrue(unique.add(id), "Duplicate CTM variant id: " + id);
            }
        }
    }

    @Test
    void testVanillaMinecraft263GlassTexturesSynthesisZeroGhostSeams() throws Exception {
        // Load all 18 REAL MC 26.3 vanilla glass PNGs directly from the classpath
        for (Identifier baseId : ConnectedGlassRegistry.glassBaseSpriteIds()) {
            String resourcePath = "assets/" + baseId.getNamespace() + "/textures/" + baseId.getPath() + ".png";
            int[] src;
            int w;
            int h;
            try (InputStream in = ConnectedGlassTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
                assertNotNull(in, "Missing vanilla texture on test classpath: " + resourcePath);
                BufferedImage img = ImageIO.read(in);
                assertNotNull(img, "Failed reading PNG: " + resourcePath);
                w = img.getWidth();
                h = img.getHeight();
                src = img.getRGB(0, 0, w, h, null, 0, w);
            }

            int median = ConnectedGlassAtlasGenerator.computeInteriorMedianArgb(src, w, h);
            int border = ConnectedGlassAtlasGenerator.detectBorderThickness(src, w, h, median);
            assertEquals(1, border, "Vanilla " + baseId + " must detect exact 1px border");

            int medAlpha = (median >>> 24) & 0xFF;
            boolean isClearGlass = baseId.getPath().equals("block/glass");
            boolean isTinted = baseId.getPath().equals("block/tinted_glass");
            if (isClearGlass) {
                assertEquals(0, medAlpha, "Vanilla glass.png interior must be alpha=0");
            } else if (isTinted) {
                assertEquals(110, medAlpha, "Vanilla tinted_glass.png interior alpha must be 110");
            } else {
                assertEquals(102, medAlpha, "Vanilla stained_glass interior alpha must be 102");
            }

            // 1. Fully surrounded interior tile (0xFF): EVERY outer perimeter pixel must equal clean interior (zero ghost seams!)
            int[] fullTile = ConnectedGlassAtlasGenerator.synthesizeTileArgb(src, w, h, border, median, 0xFF);
            for (int x = 0; x < w; ++x) {
                assertEquals(medAlpha, (fullTile[x] >>> 24) & 0xFF,
                        "Top seam ghost pixel on " + baseId + " at x=" + x);
                assertEquals(medAlpha, (fullTile[(h - 1) * w + x] >>> 24) & 0xFF,
                        "Bottom seam ghost pixel on " + baseId + " at x=" + x);
            }
            for (int y = 0; y < h; ++y) {
                assertEquals(medAlpha, (fullTile[y * w] >>> 24) & 0xFF,
                        "Left seam ghost pixel on " + baseId + " at y=" + y);
                assertEquals(medAlpha, (fullTile[y * w + (w - 1)] >>> 24) & 0xFF,
                        "Right seam ghost pixel on " + baseId + " at y=" + y);
            }
            // Verify interior specular streaks on clear glass.png are preserved verbatim
            if (isClearGlass) {
                assertEquals(255, (fullTile[2 * w + 4] >>> 24) & 0xFF, "Interior glint (4,2) must stay intact");
                assertEquals(255, (fullTile[3 * w + 3] >>> 24) & 0xFF, "Interior glint (3,3) must stay intact");
                assertEquals(255, (fullTile[4 * w + 2] >>> 24) & 0xFF, "Interior glint (2,4) must stay intact");
                assertEquals(255, (fullTile[12 * w + 13] >>> 24) & 0xFF, "Interior glint (13,12) must stay intact");
                assertEquals(255, (fullTile[13 * w + 12] >>> 24) & 0xFF, "Interior glint (12,13) must stay intact");
            }

            // 2. Concave L-corner tile (UP | LEFT, UL=0): top & left strips erased, (0,0) inner corner kept!
            int cornerMask = ConnectedGlassRegistry.BIT_UP | ConnectedGlassRegistry.BIT_LEFT;
            int[] cornerTile = ConnectedGlassAtlasGenerator.synthesizeTileArgb(src, w, h, border, median, cornerMask);
            assertEquals(src[0], cornerTile[0], "Concave inner corner (0,0) must be preserved on " + baseId);
            assertEquals(medAlpha, (cornerTile[8] >>> 24) & 0xFF, "Connected top strip must be erased on " + baseId);
            assertEquals(medAlpha, (cornerTile[8 * w] >>> 24) & 0xFF, "Connected left strip must be erased on " + baseId);
            assertEquals(src[(h - 1) * w + 8], cornerTile[(h - 1) * w + 8], "Unconnected bottom border must stay on " + baseId);
            assertEquals(src[8 * w + (w - 1)], cornerTile[8 * w + (w - 1)], "Unconnected right border must stay on " + baseId);
        }
    }

    @Test
    void testCustomResourcePackResolutionsAndBorderStyles() {
        // A. 32x32 Faithful-style texture (2px border + glint pixel touching inner ring (2,2)!)
        int borderCol = 0xFFD0EAE9;
        int cornerBevel = 0xFFFFFFFF;
        int interiorCol = 0x00808080;
        int glintCol = 0xFFFFFFFF;
        int[] tex32 = makeFramedTexture(32, 32, 2, borderCol, cornerBevel, interiorCol);
        tex32[2 * 32 + 2] = glintCol; // Outlier glint touching border ring — must NOT bleed into seam!

        int med32 = ConnectedGlassAtlasGenerator.computeInteriorMedianArgb(tex32, 32, 32);
        int b32 = ConnectedGlassAtlasGenerator.detectBorderThickness(tex32, 32, 32, med32);
        assertEquals(2, b32, "32x32 2px-frame pack must detect border=2");

        int[] full32 = ConnectedGlassAtlasGenerator.synthesizeTileArgb(tex32, 32, 32, b32, med32, 0xFF);
        assertEquals(0, (full32[0] >>> 24) & 0xFF, "Glint at (2,2) must NOT smear onto erased (0,0) corner!");
        assertEquals(0, (full32[1 * 32 + 1] >>> 24) & 0xFF, "Glint at (2,2) must NOT smear onto erased (1,1) corner!");
        assertEquals(glintCol, full32[2 * 32 + 2], "Interior glint at (2,2) must remain preserved");

        // Horizontal continuation (!UP && LEFT): corner bevel must be replaced with straight top border strip!
        int[] horiz32 = ConnectedGlassAtlasGenerator.synthesizeTileArgb(
                tex32, 32, 32, b32, med32, ConnectedGlassRegistry.BIT_LEFT);
        assertEquals(borderCol, horiz32[0], "Straight horizontal frame continuation must copy straight border strip");

        // B. 32x32 sleek 1px thin border
        int[] thin32 = makeFramedTexture(32, 32, 1, borderCol, borderCol, interiorCol);
        int medThin = ConnectedGlassAtlasGenerator.computeInteriorMedianArgb(thin32, 32, 32);
        assertEquals(1, ConnectedGlassAtlasGenerator.detectBorderThickness(thin32, 32, 32, medThin));

        // C. 16x16 thick 2px medieval border
        int[] thick16 = makeFramedTexture(16, 16, 2, 0xFF554433, 0xFF554433, 0x664488CC);
        int medThick = ConnectedGlassAtlasGenerator.computeInteriorMedianArgb(thick16, 16, 16);
        assertEquals(2, ConnectedGlassAtlasGenerator.detectBorderThickness(thick16, 16, 16, medThick));

        // D. Already-borderless 16x16 pack (e.g., Vanilla Tweaks borderless glass)
        int[] borderless = makeFramedTexture(16, 16, 0, borderCol, borderCol, 0x66AA3333);
        int medNone = ConnectedGlassAtlasGenerator.computeInteriorMedianArgb(borderless, 16, 16);
        assertEquals(0, ConnectedGlassAtlasGenerator.detectBorderThickness(borderless, 16, 16, medNone));
    }

    @Test
    void testUvBasisExtractionAndPaneTopology() {
        // 1. template_glass_pane_side.json WEST face (north arm: z=[0..7/16], x=7/16, reversed UV=[16,0,9,16])
        float[] pxSide = {7f / 16f, 7f / 16f, 7f / 16f, 7f / 16f};
        float[] pySide = {1f, 0f, 0f, 1f};
        float[] pzSide = {0f, 0f, 7f / 16f, 7f / 16f};
        float[] luSide = {1f, 1f, 9f / 16f, 9f / 16f};
        float[] lvSide = {0f, 1f, 1f, 0f};
        Direction[] basisSide = ConnectedGlassEngine.extractUvBasis(pxSide, pySide, pzSide, luSide, lvSide);
        assertEquals(Direction.NORTH, basisSide[0], "Reversed west pane arm +U must point NORTH");
        assertEquals(Direction.DOWN, basisSide[1], "+V must point DOWN");
        assertEquals(PaneSubPart.ARM_NORTH,
                ConnectedGlassEngine.classifyPaneSubPart(7f / 16f, 7f / 16f, 0f, 7f / 16f));

        // 2. template_glass_pane_side_alt.json WEST face (south arm: z=[9/16..1], UV=[7,0,0,16])
        float[] pzAlt = {9f / 16f, 9f / 16f, 1f, 1f};
        float[] luAlt = {7f / 16f, 7f / 16f, 0f, 0f};
        Direction[] basisAlt = ConnectedGlassEngine.extractUvBasis(pxSide, pySide, pzAlt, luAlt, lvSide);
        assertEquals(Direction.NORTH, basisAlt[0], "South arm +U points NORTH, so outer SOUTH is -U (LEFT)");
        assertEquals(Direction.DOWN, basisAlt[1]);
        assertEquals(PaneSubPart.ARM_SOUTH,
                ConnectedGlassEngine.classifyPaneSubPart(7f / 16f, 7f / 16f, 9f / 16f, 1f));

        // 3. Center post classification
        assertEquals(PaneSubPart.CENTER_POST,
                ConnectedGlassEngine.classifyPaneSubPart(7f / 16f, 9f / 16f, 7f / 16f, 9f / 16f));
        assertEquals(PaneSubPart.ARM_EAST,
                ConnectedGlassEngine.classifyPaneSubPart(9f / 16f, 1f, 7f / 16f, 9f / 16f));
        assertEquals(PaneSubPart.ARM_WEST,
                ConnectedGlassEngine.classifyPaneSubPart(0f, 7f / 16f, 7f / 16f, 9f / 16f));
        assertEquals(PaneSubPart.NONE,
                ConnectedGlassEngine.classifyPaneSubPart(0f, 1f, 0f, 1f));

        // 4. Per-arm vertical pane rim culling (stepped L-wall: lower has east=true, upper has east=false)
        assertTrue(ConnectedGlassEngine.shouldCullVerticalPaneRim(
                PaneSubPart.CENTER_POST, true, false, false, false, false),
                "Center post rim must cull whenever identical pane sits above/below");
        assertFalse(ConnectedGlassEngine.shouldCullVerticalPaneRim(
                PaneSubPart.ARM_EAST, true, false, false, false, false),
                "Stepped pane East arm rim must NOT cull when upper pane lacks East arm (no holes!)");
        assertTrue(ConnectedGlassEngine.shouldCullVerticalPaneRim(
                PaneSubPart.ARM_EAST, true, false, false, true, false),
                "Stacked pane East arm rim must cull when upper pane also has East arm");
        assertFalse(ConnectedGlassEngine.shouldCullVerticalPaneRim(
                PaneSubPart.CENTER_POST, false, true, true, true, true),
                "Rim must NOT cull against air or different block");

        // 5. Pane center-post & arm inner/outer CTM masks (Guard #5: thick-border inner-edge safety + 90° inner corner)
        int postStackedMask = ConnectedGlassEngine.buildPaneCenterPostMask(true, true);
        assertEquals(0xFF, ConnectedGlassRegistry.canonicalizeMask(postStackedMask),
                "Vertically connected center post must map to fully borderless interior tile");

        // Pane arm wrapping a stone block at (up + outer): U=true, outer=true, diagUpOuter=false, outerIsRight=true
        int stoneCornerMask = ConnectedGlassEngine.buildPaneArmMask(true, true, false, true, false, false);
        int canonicalStoneCorner = ConnectedGlassRegistry.canonicalizeMask(stoneCornerMask);
        // Inner edge LEFT and UP_LEFT must be connected (preventing stray tick near post!),
        // while outer UP_RIGHT must be 0 to render the crisp 90-degree inner corner against the stone block!
        assertTrue((canonicalStoneCorner & ConnectedGlassRegistry.BIT_LEFT) != 0);
        assertTrue((canonicalStoneCorner & ConnectedGlassRegistry.BIT_UP_LEFT) != 0);
        assertTrue((canonicalStoneCorner & ConnectedGlassRegistry.BIT_RIGHT) != 0);
        assertTrue((canonicalStoneCorner & ConnectedGlassRegistry.BIT_UP) != 0);
        // Outer top corner next to stone block must remain 0 to draw crisp 90° inner corner
        assertEquals(0, canonicalStoneCorner & ConnectedGlassRegistry.BIT_UP_RIGHT,
                "Outer top corner next to stone block must remain 0 to draw crisp 90° inner corner");
    }

    @Test
    void testFabricEmitQuadsStackedPinkStainedGlassImage4Repro() {
        bootstrapMinecraftRegistries();
        Identifier baseId = Identifier.withDefaultNamespace("block/pink_stained_glass");
        List<AutoCloseable> toClose = new ArrayList<>();
        try {
            Map<Identifier, TextureAtlasSprite> regions = buildMockGlassAtlasRegions(baseId, null, toClose);
            ConnectedGlassEngine.onBlocksAtlasUploaded(regions);
            ConnectedGlassEngine.setEnabledOverrideForTesting(true);

            TextureAtlasSprite baseSprite = regions.get(baseId);
            BlockState pinkGlass = Blocks.STAINED_GLASS.pink().defaultBlockState();
            assertTrue(ConnectedGlassEngine.isCandidateState(pinkGlass), "pink_stained_glass must be a CTM candidate");
            assertTrue(ConnectedGlassEngine.isCandidateState(Blocks.GLASS.defaultBlockState()));
            assertTrue(ConnectedGlassEngine.isCandidateState(Blocks.TINTED_GLASS.defaultBlockState()));
            assertTrue(ConnectedGlassEngine.isCandidateState(Blocks.GLASS_PANE.defaultBlockState()));
            assertFalse(ConnectedGlassEngine.isCandidateState(Blocks.IRON_BARS.defaultBlockState()),
                    "Iron bars must NOT be treated as connected glass panes");
            assertFalse(ConnectedGlassEngine.isCandidateState(Blocks.STONE.defaultBlockState()));
            BlockStateModel wrapped = new StubBlockStateModel(List.of(makeCubePart(baseSprite, true)));
            ConnectedGlassBlockStateModel ctmModel = new ConnectedGlassBlockStateModel(wrapped);

            BlockPos bottomPos = new BlockPos(0, 10, 0);
            BlockPos topPos = new BlockPos(0, 11, 0);
            BlockPos isolatedPos = new BlockPos(5, 10, 0);
            FakeWorldRegion world = new FakeWorldRegion(Map.of(
                    bottomPos, pinkGlass,
                    topPos, pinkGlass,
                    isolatedPos, pinkGlass
            ));
            RandomSource random = RandomSource.create(42L);

            // Dynamic CTM model MUST return null geometry key so Fabric renderers never cache statically
            assertNull(ctmModel.createGeometryKey(world, bottomPos, pinkGlass, random),
                    "ConnectedGlassBlockStateModel.createGeometryKey must return null");

            // 1. Bottom block of 2-high stack (Image 4): UP culled, 4 vertical sides remapped to BIT_UP tile, DOWN stays base
            int expectedUpTile = ConnectedGlassRegistry.tileIndexForMask(ConnectedGlassRegistry.BIT_UP);
            Identifier expectedUpSpriteId = ConnectedGlassRegistry.variantSpriteIds(baseId)[expectedUpTile];
            TextureAtlasSprite expectedUpSprite = regions.get(expectedUpSpriteId);
            List<EmittedQuad> bottomQuads = emitWithIndigoCull(ctmModel, world, bottomPos, pinkGlass, random);
            assertEquals(5, bottomQuads.size(), "Bottom stacked glass should emit 5 faces (UP culled against top glass)");
            for (EmittedQuad eq : bottomQuads) {
                assertEquals(ChunkSectionLayer.TRANSLUCENT, eq.quad.materialInfo().layer());
                assertEquals(TriState.DEFAULT, eq.ao);
                assertEquals(ShadeMode.VANILLA, eq.shadeMode);
                if (eq.cullFace == Direction.DOWN) {
                    assertEquals(baseId, eq.quad.materialInfo().sprite().contents().name(),
                            "Exposed bottom face must stay on base sprite (tile 0)");
                } else {
                    assertEquals(expectedUpSpriteId, eq.quad.materialInfo().sprite().contents().name(),
                            "Vertical face " + eq.cullFace + " of lower stacked pink_stained_glass must remap to BIT_UP CTM tile");
                    assertQuadUvsInsideSprite(eq.quad, expectedUpSprite);
                }
            }

            // 2. Top block of 2-high stack (Image 4): DOWN culled, 4 vertical sides remapped to BIT_DOWN tile, UP stays base
            int expectedDownTile = ConnectedGlassRegistry.tileIndexForMask(ConnectedGlassRegistry.BIT_DOWN);
            Identifier expectedDownSpriteId = ConnectedGlassRegistry.variantSpriteIds(baseId)[expectedDownTile];
            TextureAtlasSprite expectedDownSprite = regions.get(expectedDownSpriteId);
            List<EmittedQuad> topQuads = emitWithIndigoCull(ctmModel, world, topPos, pinkGlass, random);
            assertEquals(5, topQuads.size(), "Top stacked glass should emit 5 faces (DOWN culled against bottom glass)");
            for (EmittedQuad eq : topQuads) {
                if (eq.cullFace == Direction.UP) {
                    assertEquals(baseId, eq.quad.materialInfo().sprite().contents().name());
                } else {
                    assertEquals(expectedDownSpriteId, eq.quad.materialInfo().sprite().contents().name(),
                            "Vertical face " + eq.cullFace + " of upper stacked pink_stained_glass must remap to BIT_DOWN CTM tile");
                    assertQuadUvsInsideSprite(eq.quad, expectedDownSprite);
                }
            }

            // 3. Isolated block: all 6 faces stay on unmodified base sprite
            List<EmittedQuad> isolatedQuads = emitWithIndigoCull(ctmModel, world, isolatedPos, pinkGlass, random);
            assertEquals(6, isolatedQuads.size());
            for (EmittedQuad eq : isolatedQuads) {
                assertEquals(baseId, eq.quad.materialInfo().sprite().contents().name());
            }

            // 4. BlockAndTintGetter.EMPTY (GUI/item) passes through unmodified
            List<EmittedQuad> emptyQuads = emitWithCull(ctmModel, BlockAndTintGetter.EMPTY, bottomPos, pinkGlass, random, d -> false);
            assertEquals(6, emptyQuads.size());
            for (EmittedQuad eq : emptyQuads) {
                assertEquals(baseId, eq.quad.materialInfo().sprite().contents().name(),
                        "GUI/EMPTY world must not apply connected glass remapping");
            }

            // 5. MovingBlockRenderState (piston/falling block) passes through unmodified
            MovingBlockRenderState movingState = new MovingBlockRenderState();
            List<EmittedQuad> movingQuads = emitWithCull(ctmModel, movingState, bottomPos, pinkGlass, random, d -> false);
            assertEquals(6, movingQuads.size());
            for (EmittedQuad eq : movingQuads) {
                assertEquals(baseId, eq.quad.materialInfo().sprite().contents().name(),
                        "MovingBlockRenderState must not apply connected glass remapping");
            }

            // 6. ConnectedGlass disabled toggle passes through unmodified
            ConnectedGlassEngine.setEnabledOverrideForTesting(false);
            List<EmittedQuad> disabledQuads = emitWithIndigoCull(ctmModel, world, bottomPos, pinkGlass, random);
            assertEquals(5, disabledQuads.size());
            for (EmittedQuad eq : disabledQuads) {
                assertEquals(baseId, eq.quad.materialInfo().sprite().contents().name(),
                        "Disabled Connected Glass setting must emit unmodified base sprites");
            }
        } finally {
            ConnectedGlassEngine.setEnabledOverrideForTesting(null);
            ConnectedGlassEngine.onBlocksAtlasUploaded(Map.of());
            closeQuietly(toClose);
        }
    }

    @Test
    void testFabricEmitQuadsStackedPinkGlassPaneImage5Repro() {
        bootstrapMinecraftRegistries();
        Identifier baseId = Identifier.withDefaultNamespace("block/pink_stained_glass");
        Identifier edgeId = Identifier.withDefaultNamespace("block/pink_stained_glass_pane_top");
        List<AutoCloseable> toClose = new ArrayList<>();
        try {
            Map<Identifier, TextureAtlasSprite> regions = buildMockGlassAtlasRegions(baseId, edgeId, toClose);
            ConnectedGlassEngine.onBlocksAtlasUploaded(regions);
            ConnectedGlassEngine.setEnabledOverrideForTesting(true);

            TextureAtlasSprite paneSprite = regions.get(baseId);
            TextureAtlasSprite edgeSprite = regions.get(edgeId);

            BlockState paneNorth = Blocks.STAINED_GLASS_PANE.pink().defaultBlockState()
                    .setValue(CrossCollisionBlock.NORTH, true);
            BlockState pinkFull = Blocks.STAINED_GLASS.pink().defaultBlockState();

            // Vanilla glass pane multipart pieces for north=true:
            // - post (UP & DOWN #edge, unculled)
            // - north arm side (UP & DOWN #edge unculled, NORTH #edge cullface=NORTH, WEST/EAST #pane unculled)
            // - east noside_alt (EAST #pane unculled on center post)
            BlockStateModelPart postPart = makePanePostPart(edgeSprite);
            BlockStateModelPart northArmPart = makePaneNorthArmPart(paneSprite, edgeSprite);
            BlockStateModelPart eastNoSidePart = makePaneCenterPostEastPart(paneSprite);

            BlockStateModel wrapped = new StubBlockStateModel(List.of(postPart, northArmPart, eastNoSidePart));
            ConnectedGlassBlockStateModel ctmModel = new ConnectedGlassBlockStateModel(wrapped);

            BlockPos bottomPanePos = new BlockPos(0, 10, 0);
            BlockPos topPanePos = new BlockPos(0, 11, 0);
            BlockPos northFullGlassPos = new BlockPos(0, 10, -1); // Flush matching full glass block!
            FakeWorldRegion world = new FakeWorldRegion(Map.of(
                    bottomPanePos, paneNorth,
                    topPanePos, paneNorth,
                    northFullGlassPos, pinkFull
            ));
            RandomSource random = RandomSource.create(7L);

            List<EmittedQuad> emitted = emitWithCull(ctmModel, world, bottomPanePos, paneNorth, random, d -> false);

            // Original model has 8 quads:
            //  - 2 UP #edge (post + north arm) -> BOTH CULLED against upper stacked pane!
            //  - 1 NORTH #edge (north arm end-cap) -> CULLED against flush matching pink_stained_glass!
            //  - 2 DOWN #edge (post + north arm) -> KEPT (exposed bottom edge above air)!
            //  - 2 ARM_NORTH #pane (WEST reversed UV + EAST normal UV) -> REMAPPED!
            //  - 1 CENTER_POST #pane (noside_alt EAST) -> REMAPPED!
            assertEquals(5, emitted.size(),
                    "Expected 3 internal pane rims culled (2 vertical UP + 1 flush NORTH against pink_stained_glass) and 5 quads emitted");

            int keptDownEdges = 0;
            int remappedArmSides = 0;
            int remappedCenterPost = 0;

            int expectedArmTile = ConnectedGlassRegistry.tileIndexForMask(
                    ConnectedGlassEngine.buildPaneArmMask(true, true, false, true, false, false));
            Identifier expectedArmSpriteId = ConnectedGlassRegistry.variantSpriteIds(baseId)[expectedArmTile];
            int expectedPostTile = ConnectedGlassRegistry.tileIndexForMask(
                    ConnectedGlassEngine.buildPaneCenterPostMask(true, false));
            Identifier expectedPostSpriteId = ConnectedGlassRegistry.variantSpriteIds(baseId)[expectedPostTile];

            for (EmittedQuad eq : emitted) {
                assertEquals(TriState.FALSE, eq.ao, "Vanilla glass pane parts have ambientOcclusion=false");
                assertEquals(ShadeMode.VANILLA, eq.shadeMode);
                Identifier spriteName = eq.quad.materialInfo().sprite().contents().name();
                if (edgeId.equals(spriteName)) {
                    assertEquals(Direction.DOWN, eq.quad.direction(),
                            "Only exposed DOWN pane rims may survive; UP and flush NORTH rims must be culled");
                    keptDownEdges++;
                } else {
                    float minZ = Math.min(Math.min(eq.quad.position0().z(), eq.quad.position1().z()),
                            Math.min(eq.quad.position2().z(), eq.quad.position3().z()));
                    if (minZ < 0.40f) {
                        assertEquals(expectedArmSpriteId, spriteName,
                                "North pane arm face " + eq.quad.direction() + " must remap to UP+OUTER connected arm CTM tile");
                        assertQuadUvsInsideSprite(eq.quad, regions.get(expectedArmSpriteId));
                        remappedArmSides++;
                    } else {
                        assertEquals(expectedPostSpriteId, spriteName,
                                "Center-post noside face must remap to vertically connected post CTM tile");
                        assertQuadUvsInsideSprite(eq.quad, regions.get(expectedPostSpriteId));
                        remappedCenterPost++;
                    }
                }
            }
            assertEquals(2, keptDownEdges, "Both exposed DOWN rim caps (post + north arm) must remain");
            assertEquals(2, remappedArmSides, "Both WEST and EAST north-arm glass faces must remap to CTM tile");
            assertEquals(1, remappedCenterPost, "Center-post noside face must remap to CTM tile");
        } finally {
            ConnectedGlassEngine.setEnabledOverrideForTesting(null);
            ConnectedGlassEngine.onBlocksAtlasUploaded(Map.of());
            closeQuietly(toClose);
        }
    }

    private static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Map<Identifier, TextureAtlasSprite> buildMockGlassAtlasRegions(
            Identifier baseId,
            Identifier optionalEdgeId,
            List<AutoCloseable> toClose
    ) {
        Map<Identifier, TextureAtlasSprite> regions = new HashMap<>(64);
        int atlasSize = 1024;
        int slot = 0;
        Identifier[] variants = ConnectedGlassRegistry.variantSpriteIds(baseId);
        assertNotNull(variants);
        for (Identifier id : variants) {
            regions.put(id, createMockSprite(id, atlasSize, slot++, toClose));
        }
        if (optionalEdgeId != null) {
            regions.put(optionalEdgeId, createMockSprite(optionalEdgeId, atlasSize, slot, toClose));
        }
        return regions;
    }

    private static TextureAtlasSprite createMockSprite(
            Identifier id,
            int atlasSize,
            int slotIndex,
            List<AutoCloseable> toClose
    ) {
        NativeImage img = new NativeImage(16, 16, false);
        SpriteContents contents = new SpriteContents(id, new FrameSize(16, 16), img);
        toClose.add(contents);
        int cols = atlasSize / 16;
        int x = (slotIndex % cols) * 16;
        int y = (slotIndex / cols) * 16;
        return new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, contents, atlasSize, atlasSize, x, y, 0) {};
    }

    private static void assertQuadUvsInsideSprite(BakedQuad quad, TextureAtlasSprite sprite) {
        long[] packed = {quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3()};
        float eps = 1e-6f;
        for (long uv : packed) {
            float u = UVPair.unpackU(uv);
            float v = UVPair.unpackV(uv);
            assertTrue(u >= sprite.getU0() - eps && u <= sprite.getU1() + eps,
                    "Quad U=" + u + " outside target sprite [" + sprite.getU0() + ", " + sprite.getU1() + "]");
            assertTrue(v >= sprite.getV0() - eps && v <= sprite.getV1() + eps,
                    "Quad V=" + v + " outside target sprite [" + sprite.getV0() + ", " + sprite.getV1() + "]");
        }
    }

    private static List<EmittedQuad> emitWithIndigoCull(
            BlockStateModel model,
            BlockAndTintGetter world,
            BlockPos pos,
            BlockState state,
            RandomSource random
    ) {
        Predicate<Direction> indigoCullTest = dir ->
                dir != null && !Block.shouldRenderFace(state, world.getBlockState(pos.relative(dir)), dir);
        return emitWithCull(model, world, pos, state, random, indigoCullTest);
    }

    private static List<EmittedQuad> emitWithCull(
            BlockStateModel model,
            BlockAndTintGetter world,
            BlockPos pos,
            BlockState state,
            RandomSource random,
            Predicate<Direction> cullTest
    ) {
        List<EmittedQuad> emitted = new ArrayList<>();
        QuadEmitter emitter = createRecordingQuadEmitter(emitted);
        model.emitQuads(emitter, world, pos, state, random, cullTest);
        return emitted;
    }

    private record EmittedQuad(Direction cullFace, BakedQuad quad, TriState ao, ShadeMode shadeMode) {}

    private static QuadEmitter createRecordingQuadEmitter(List<EmittedQuad> sink) {
        final Direction[] cullFace = new Direction[1];
        final BakedQuad[] bakedQuad = new BakedQuad[1];
        final TriState[] ao = new TriState[1];
        final ShadeMode[] shadeMode = new ShadeMode[1];
        return (QuadEmitter) Proxy.newProxyInstance(
                QuadEmitter.class.getClassLoader(),
                new Class<?>[]{QuadEmitter.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "cullFace" -> {
                        if (args != null && args.length == 1) {
                            cullFace[0] = (Direction) args[0];
                            yield proxy;
                        }
                        yield cullFace[0];
                    }
                    case "fromBakedQuad" -> {
                        bakedQuad[0] = (BakedQuad) args[0];
                        yield proxy;
                    }
                    case "ambientOcclusion" -> {
                        if (args != null && args.length == 1) {
                            ao[0] = (TriState) args[0];
                            yield proxy;
                        }
                        yield ao[0];
                    }
                    case "shadeMode" -> {
                        if (args != null && args.length == 1) {
                            shadeMode[0] = (ShadeMode) args[0];
                            yield proxy;
                        }
                        yield shadeMode[0];
                    }
                    case "emit" -> {
                        sink.add(new EmittedQuad(cullFace[0], bakedQuad[0], ao[0], shadeMode[0]));
                        cullFace[0] = null;
                        bakedQuad[0] = null;
                        ao[0] = null;
                        shadeMode[0] = null;
                        yield proxy;
                    }
                    default -> {
                        Class<?> ret = method.getReturnType();
                        if (ret == QuadEmitter.class || ret == MutableQuadView.class) {
                            yield proxy;
                        }
                        if (ret == boolean.class) yield false;
                        if (ret == int.class) yield 0;
                        if (ret == float.class) yield 0f;
                        yield null;
                    }
                }
        );
    }

    private static BakedQuad makeQuad(
            TextureAtlasSprite sprite,
            Direction dir,
            Vector3f p0, float lu0, float lv0,
            Vector3f p1, float lu1, float lv1,
            Vector3f p2, float lu2, float lv2,
            Vector3f p3, float lu3, float lv3
    ) {
        BakedQuad.MaterialInfo mi = new BakedQuad.MaterialInfo(
                sprite,
                ChunkSectionLayer.TRANSLUCENT,
                null,
                null,
                null,
                -1,
                null,
                0
        );
        return new BakedQuad(
                p0, p1, p2, p3,
                UVPair.pack(sprite.getU(lu0), sprite.getV(lv0)),
                UVPair.pack(sprite.getU(lu1), sprite.getV(lv1)),
                UVPair.pack(sprite.getU(lu2), sprite.getV(lv2)),
                UVPair.pack(sprite.getU(lu3), sprite.getV(lv3)),
                dir,
                mi
        );
    }

    private static BlockStateModelPart makeCubePart(TextureAtlasSprite sprite, boolean useAo) {
        Map<Direction, List<BakedQuad>> byFace = new HashMap<>();
        byFace.put(Direction.NORTH, List.of(makeQuad(sprite, Direction.NORTH,
                new Vector3f(1, 1, 0), 0, 0, new Vector3f(1, 0, 0), 0, 1,
                new Vector3f(0, 0, 0), 1, 1, new Vector3f(0, 1, 0), 1, 0)));
        byFace.put(Direction.SOUTH, List.of(makeQuad(sprite, Direction.SOUTH,
                new Vector3f(0, 1, 1), 0, 0, new Vector3f(0, 0, 1), 0, 1,
                new Vector3f(1, 0, 1), 1, 1, new Vector3f(1, 1, 1), 1, 0)));
        byFace.put(Direction.WEST, List.of(makeQuad(sprite, Direction.WEST,
                new Vector3f(0, 1, 0), 0, 0, new Vector3f(0, 0, 0), 0, 1,
                new Vector3f(0, 0, 1), 1, 1, new Vector3f(0, 1, 1), 1, 0)));
        byFace.put(Direction.EAST, List.of(makeQuad(sprite, Direction.EAST,
                new Vector3f(1, 1, 1), 0, 0, new Vector3f(1, 0, 1), 0, 1,
                new Vector3f(1, 0, 0), 1, 1, new Vector3f(1, 1, 0), 1, 0)));
        byFace.put(Direction.UP, List.of(makeQuad(sprite, Direction.UP,
                new Vector3f(0, 1, 0), 0, 0, new Vector3f(0, 1, 1), 0, 1,
                new Vector3f(1, 1, 1), 1, 1, new Vector3f(1, 1, 0), 1, 0)));
        byFace.put(Direction.DOWN, List.of(makeQuad(sprite, Direction.DOWN,
                new Vector3f(0, 0, 1), 0, 0, new Vector3f(0, 0, 0), 0, 1,
                new Vector3f(1, 0, 0), 1, 1, new Vector3f(1, 0, 1), 1, 0)));
        return new StubModelPart(byFace, List.of(), useAo);
    }

    private static BlockStateModelPart makePanePostPart(TextureAtlasSprite edgeSprite) {
        float a = 7f / 16f, b = 9f / 16f;
        BakedQuad up = makeQuad(edgeSprite, Direction.UP,
                new Vector3f(a, 1, a), a, a, new Vector3f(a, 1, b), a, b,
                new Vector3f(b, 1, b), b, b, new Vector3f(b, 1, a), b, a);
        BakedQuad down = makeQuad(edgeSprite, Direction.DOWN,
                new Vector3f(a, 0, b), a, a, new Vector3f(a, 0, a), a, b,
                new Vector3f(b, 0, a), b, b, new Vector3f(b, 0, b), b, a);
        return new StubModelPart(Map.of(), List.of(up, down), false);
    }

    private static BlockStateModelPart makePaneNorthArmPart(
            TextureAtlasSprite paneSprite,
            TextureAtlasSprite edgeSprite
    ) {
        float a = 7f / 16f, b = 9f / 16f;
        BakedQuad upEdge = makeQuad(edgeSprite, Direction.UP,
                new Vector3f(a, 1, 0), a, 0, new Vector3f(a, 1, a), a, a,
                new Vector3f(b, 1, a), b, a, new Vector3f(b, 1, 0), b, 0);
        BakedQuad downEdge = makeQuad(edgeSprite, Direction.DOWN,
                new Vector3f(a, 0, a), a, 0, new Vector3f(a, 0, 0), a, a,
                new Vector3f(b, 0, 0), b, a, new Vector3f(b, 0, a), b, 0);
        BakedQuad northEdge = makeQuad(edgeSprite, Direction.NORTH,
                new Vector3f(b, 1, 0), a, 0, new Vector3f(b, 0, 0), a, 1,
                new Vector3f(a, 0, 0), b, 1, new Vector3f(a, 1, 0), b, 0);
        // template_glass_pane_side.json: west uv=[16,0,9,16] (reversed!), east uv=[9,0,16,16]
        BakedQuad westPane = makeQuad(paneSprite, Direction.WEST,
                new Vector3f(a, 1, 0), 1f, 0f, new Vector3f(a, 0, 0), 1f, 1f,
                new Vector3f(a, 0, a), b, 1f, new Vector3f(a, 1, a), b, 0f);
        BakedQuad eastPane = makeQuad(paneSprite, Direction.EAST,
                new Vector3f(b, 1, a), b, 0f, new Vector3f(b, 0, a), b, 1f,
                new Vector3f(b, 0, 0), 1f, 1f, new Vector3f(b, 1, 0), 1f, 0f);
        return new StubModelPart(Map.of(Direction.NORTH, List.of(northEdge)),
                List.of(upEdge, downEdge, westPane, eastPane), false);
    }

    private static BlockStateModelPart makePaneCenterPostEastPart(TextureAtlasSprite paneSprite) {
        float a = 7f / 16f, b = 9f / 16f;
        BakedQuad eastPost = makeQuad(paneSprite, Direction.EAST,
                new Vector3f(b, 1, b), a, 0f, new Vector3f(b, 0, b), a, 1f,
                new Vector3f(b, 0, a), b, 1f, new Vector3f(b, 1, a), b, 0f);
        return new StubModelPart(Map.of(), List.of(eastPost), false);
    }

    private static final class StubModelPart implements BlockStateModelPart {
        private final Map<Direction, List<BakedQuad>> culled;
        private final List<BakedQuad> unculled;
        private final boolean ao;

        StubModelPart(Map<Direction, List<BakedQuad>> culled, List<BakedQuad> unculled, boolean ao) {
            this.culled = culled;
            this.unculled = unculled;
            this.ao = ao;
        }

        @Override
        public List<BakedQuad> getQuads(Direction dir) {
            return dir == null ? unculled : culled.getOrDefault(dir, List.of());
        }

        @Override
        public boolean useAmbientOcclusion() {
            return ao;
        }

        @Override
        public Material.Baked particleMaterial() {
            return null;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }

    private static final class StubBlockStateModel implements BlockStateModel {
        private final List<BlockStateModelPart> parts;

        StubBlockStateModel(List<BlockStateModelPart> parts) {
            this.parts = parts;
        }

        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> out) {
            out.addAll(parts);
        }

        @Override
        public void emitQuads(
                QuadEmitter emitter,
                BlockAndTintGetter level,
                BlockPos pos,
                BlockState state,
                RandomSource random,
                Predicate<Direction> cullTest
        ) {
            for (BlockStateModelPart part : parts) {
                VanillaBlockModelPartEncoder.emitQuads(part, emitter, cullTest);
            }
        }

        @Override
        public Material.Baked particleMaterial() {
            return null;
        }

        @Override
        public int materialFlags() {
            return 0;
        }
    }

    private static final class FakeWorldRegion implements BlockAndTintGetter {
        private final Map<BlockPos, BlockState> blocks;

        FakeWorldRegion(Map<BlockPos, BlockState> blocks) {
            this.blocks = blocks;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos.immutable(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public CardinalLighting cardinalLighting() {
            return new CardinalLighting(1f, 1f, 1f, 1f, 1f, 1f);
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return -1;
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public int getMinY() {
            return 0;
        }
    }

    private static void closeQuietly(List<AutoCloseable> closeables) {
        for (AutoCloseable c : closeables) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static int[] makeFramedTexture(int w, int h, int border, int edgeCol, int cornerCol, int interiorCol) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                boolean inX = x < border || x >= w - border;
                boolean inY = y < border || y >= h - border;
                if (inX && inY) {
                    px[y * w + x] = cornerCol;
                } else if (inX || inY) {
                    px[y * w + x] = edgeCol;
                } else {
                    px[y * w + x] = interiorCol;
                }
            }
        }
        return px;
    }
}

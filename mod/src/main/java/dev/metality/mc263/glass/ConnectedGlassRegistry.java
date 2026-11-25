package dev.metality.mc263.glass;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Engine-level Connected Glass &amp; Connected Glass Panes registry and canonical 47-tile CTM math.
 *
 * <p>Covers all 18 base glass textures ({@code glass}, {@code tinted_glass}, and 16 {@code *_stained_glass}),
 * all 18 full glass blocks, and all 17 glass panes ({@code glass_pane} and 16 {@code *_stained_glass_pane}).
 * Explicitly excludes non-glass {@code IronBarsBlock} blocks such as iron bars or copper bars.
 */
public final class ConnectedGlassRegistry {

    /** Total canonical 8-neighbor CTM states (0 = isolated original sprite, 1..46 = synthesized connected variants). */
    public static final int TILE_COUNT = 47;

    // 8-neighbor bitmask in texture (U, V) space:
    // U = -V (top), R = +U (right), D = +V (bottom), L = -U (left)
    // Diagonals are only active when both adjacent cardinal bits are set.
    public static final int BIT_UP = 1 << 0;
    public static final int BIT_RIGHT = 1 << 1;
    public static final int BIT_DOWN = 1 << 2;
    public static final int BIT_LEFT = 1 << 3;
    public static final int BIT_UP_LEFT = 1 << 4;
    public static final int BIT_UP_RIGHT = 1 << 5;
    public static final int BIT_DOWN_RIGHT = 1 << 6;
    public static final int BIT_DOWN_LEFT = 1 << 7;

    private static final int[] MASK_TO_TILE = new int[256];
    private static final int[] TILE_TO_CANONICAL_MASK = new int[TILE_COUNT];

    private static final Set<Identifier> GLASS_BASE_SPRITES;
    private static final Set<Identifier> PANE_EDGE_SPRITES;
    private static final Map<Identifier, Identifier[]> CTM_VARIANT_IDS;

    static {
        // Build the exact 256 -> 47 canonical 8-neighbor CTM bijection.
        int[] canonicalToTile = new int[256];
        java.util.Arrays.fill(canonicalToTile, -1);
        int nextTile = 0;
        for (int raw = 0; raw < 256; ++raw) {
            int canonical = canonicalizeMask(raw);
            int tile = canonicalToTile[canonical];
            if (tile < 0) {
                tile = nextTile++;
                canonicalToTile[canonical] = tile;
                TILE_TO_CANONICAL_MASK[tile] = canonical;
            }
            MASK_TO_TILE[raw] = tile;
        }
        if (nextTile != TILE_COUNT) {
            throw new IllegalStateException("Expected 47 canonical CTM states, got " + nextTile);
        }

        Set<Identifier> bases = new HashSet<>(24);
        Set<Identifier> edges = new HashSet<>(24);
        bases.add(Identifier.withDefaultNamespace("block/glass"));
        bases.add(Identifier.withDefaultNamespace("block/tinted_glass"));
        edges.add(Identifier.withDefaultNamespace("block/glass_pane_top"));

        for (DyeColor color : DyeColor.values()) {
            String name = color.getName();
            bases.add(Identifier.withDefaultNamespace("block/" + name + "_stained_glass"));
            edges.add(Identifier.withDefaultNamespace("block/" + name + "_stained_glass_pane_top"));
        }

        Map<Identifier, Identifier[]> variantMap = new HashMap<>(bases.size() * 2);
        for (Identifier base : bases) {
            String shortName = base.getPath();
            if (shortName.startsWith("block/")) {
                shortName = shortName.substring("block/".length());
            }
            Identifier[] ids = new Identifier[TILE_COUNT];
            ids[0] = base;
            for (int tile = 1; tile < TILE_COUNT; ++tile) {
                ids[tile] = Identifier.fromNamespaceAndPath("metality", "block/ctm/" + shortName + "_" + tile);
            }
            variantMap.put(base, ids);
        }

        GLASS_BASE_SPRITES = Collections.unmodifiableSet(bases);
        PANE_EDGE_SPRITES = Collections.unmodifiableSet(edges);
        CTM_VARIANT_IDS = Collections.unmodifiableMap(variantMap);
    }

    private ConnectedGlassRegistry() {}

    /** Masks out irrelevant diagonal bits whose adjacent cardinal edges are not both connected. */
    public static int canonicalizeMask(int rawMask) {
        int c = rawMask & 0x0F;
        if ((c & (BIT_UP | BIT_LEFT)) == (BIT_UP | BIT_LEFT)) {
            c |= (rawMask & BIT_UP_LEFT);
        }
        if ((c & (BIT_UP | BIT_RIGHT)) == (BIT_UP | BIT_RIGHT)) {
            c |= (rawMask & BIT_UP_RIGHT);
        }
        if ((c & (BIT_DOWN | BIT_RIGHT)) == (BIT_DOWN | BIT_RIGHT)) {
            c |= (rawMask & BIT_DOWN_RIGHT);
        }
        if ((c & (BIT_DOWN | BIT_LEFT)) == (BIT_DOWN | BIT_LEFT)) {
            c |= (rawMask & BIT_DOWN_LEFT);
        }
        return c;
    }

    /** Maps any raw 8-bit neighbor mask to its canonical tile index in {@code [0, 46]}. */
    public static int tileIndexForMask(int rawMask) {
        return MASK_TO_TILE[rawMask & 0xFF];
    }

    /** Returns the canonical 8-bit neighbor mask for tile index {@code tileIdx} in {@code [0, 46]}. */
    public static int canonicalMaskForTile(int tileIdx) {
        return TILE_TO_CANONICAL_MASK[tileIdx];
    }

    public static Set<Identifier> glassBaseSpriteIds() {
        return GLASS_BASE_SPRITES;
    }

    public static boolean isGlassBaseSprite(Identifier spriteId) {
        return spriteId != null && GLASS_BASE_SPRITES.contains(spriteId);
    }

    public static boolean isPaneEdgeSprite(Identifier spriteId) {
        return spriteId != null && PANE_EDGE_SPRITES.contains(spriteId);
    }

    /** Returns the 47 sprite IDs for a registered glass base sprite (index 0 = base itself), or null. */
    public static Identifier[] variantSpriteIds(Identifier baseSpriteId) {
        return CTM_VARIANT_IDS.get(baseSpriteId);
    }

    public static boolean isFullGlassBlock(Block block) {
        return block == Blocks.GLASS
                || block == Blocks.TINTED_GLASS
                || block instanceof StainedGlassBlock;
    }

    public static boolean isGlassPaneBlock(Block block) {
        return block == Blocks.GLASS_PANE
                || block instanceof StainedGlassPaneBlock;
    }

    public static boolean isConnectedGlassBlock(Block block) {
        return isFullGlassBlock(block) || isGlassPaneBlock(block);
    }

    /**
     * Returns true iff {@code fullBlock} is the matching-color full glass cube for {@code paneBlock}
     * (e.g. {@code glass_pane} -> {@code glass}, {@code red_stained_glass_pane} -> {@code red_stained_glass}).
     */
    public static boolean isMatchingFullGlassForPane(Block paneBlock, Block fullBlock) {
        if (paneBlock == Blocks.GLASS_PANE) {
            return fullBlock == Blocks.GLASS;
        }
        if (paneBlock instanceof StainedGlassPaneBlock sp && fullBlock instanceof StainedGlassBlock sg) {
            return sp.getColor() == sg.getColor();
        }
        return false;
    }

    public static BooleanProperty panePropertyForDirection(Direction horizontalDir) {
        return switch (horizontalDir) {
            case NORTH -> CrossCollisionBlock.NORTH;
            case EAST -> CrossCollisionBlock.EAST;
            case SOUTH -> CrossCollisionBlock.SOUTH;
            case WEST -> CrossCollisionBlock.WEST;
            default -> null;
        };
    }

    public static boolean paneHasArm(BlockState state, Direction horizontalDir) {
        BooleanProperty prop = panePropertyForDirection(horizontalDir);
        return prop != null && state.hasProperty(prop) && state.getValue(prop);
    }
}

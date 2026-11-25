package dev.metality.mc263.glass;

import com.mojang.blaze3d.platform.NativeImage;
import dev.metality.mc263.mixin.SpriteContentsAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Procedurally synthesizes the 46 connected glass CTM {@link SpriteContents} variants (tiles 1..46)
 * for each of the 18 base glass textures directly from the active resource pack's loaded images.
 *
 * <p>Every synthesized sprite owns its own dedicated {@link NativeImage} (never sharing native memory
 * with the source sprite) and preserves the source's {@link MipmapStrategy} (such as {@code MEAN} from
 * vanilla {@code glass.png.mcmeta}) so Minecraft's native stitcher builds clean, bleed-free mipmaps
 * and anisotropic border padding at any texture resolution.
 */
public final class ConnectedGlassAtlasGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Metality/ConnectedGlass");
    private static final int MIN_SPRITE_DIM = 8;
    private static final int MAX_SPRITE_DIM = 512;

    private ConnectedGlassAtlasGenerator() {}

    /**
     * Scans the block atlas's {@link SpriteContents} list and returns a new list containing all original
     * sprites plus the synthesized 46 CTM variants for each eligible glass texture.
     */
    public static List<SpriteContents> appendConnectedGlassSprites(
            List<SpriteContents> originalSprites,
            int maxSupportedTextureSize
    ) {
        if (originalSprites == null || originalSprites.isEmpty()) {
            return originalSprites;
        }
        long maxAtlasArea = (long) Math.max(1024, maxSupportedTextureSize) * Math.max(1024, maxSupportedTextureSize);
        long addedAreaBudget = maxAtlasArea / 4L;
        long addedArea = 0L;

        List<SpriteContents> extra = new ArrayList<>();
        int generatedFamilies = 0;

        for (SpriteContents src : originalSprites) {
            if (src == null || src.isAnimated()) {
                continue;
            }
            Identifier baseId = src.name();
            Identifier[] variantIds = ConnectedGlassRegistry.variantSpriteIds(baseId);
            if (variantIds == null) {
                continue;
            }

            int w = src.width();
            int h = src.height();
            if (w < MIN_SPRITE_DIM || h < MIN_SPRITE_DIM || w > MAX_SPRITE_DIM || h > MAX_SPRITE_DIM) {
                continue;
            }
            long familyArea = (long) (ConnectedGlassRegistry.TILE_COUNT - 1) * w * h;
            if (addedArea + familyArea > addedAreaBudget) {
                LOGGER.warn("[Metality] Skipping CTM synthesis for {} ({}x{}) to stay within atlas size budget ({}px)",
                        baseId, w, h, maxSupportedTextureSize);
                continue;
            }

            SpriteContentsAccessor accessor = (SpriteContentsAccessor) (Object) src;
            NativeImage srcImg = accessor.metality$getOriginalImage();
            if (srcImg == null || srcImg.format() != NativeImage.Format.RGBA
                    || srcImg.getWidth() < w || srcImg.getHeight() < h) {
                continue;
            }

            int[] srcArgb = new int[w * h];
            for (int y = 0; y < h; ++y) {
                int rowOff = y * w;
                for (int x = 0; x < w; ++x) {
                    srcArgb[rowOff + x] = srcImg.getPixel(x, y);
                }
            }

            int interiorMedian = computeInteriorMedianArgb(srcArgb, w, h);
            int border = detectBorderThickness(srcArgb, w, h, interiorMedian);
            if (border <= 0) {
                // Resource pack already ships borderless glass for this texture; preserve as-is.
                continue;
            }

            MipmapStrategy mipStrategy = accessor.metality$getMipmapStrategy();
            if (mipStrategy == null) {
                mipStrategy = MipmapStrategy.AUTO;
            }
            float alphaCutoffBias = accessor.metality$getAlphaCutoffBias();
            Optional<TextureMetadataSection> texMeta = Optional.of(
                    new TextureMetadataSection(false, false, mipStrategy, alphaCutoffBias)
            );
            FrameSize frameSize = new FrameSize(w, h);

            List<SpriteContents> familySprites = new ArrayList<>(ConnectedGlassRegistry.TILE_COUNT - 1);
            boolean familyOk = true;
            for (int tile = 1; tile < ConnectedGlassRegistry.TILE_COUNT; ++tile) {
                int canonicalMask = ConnectedGlassRegistry.canonicalMaskForTile(tile);
                int[] tileArgb = synthesizeTileArgb(srcArgb, w, h, border, interiorMedian, canonicalMask);
                NativeImage tileImg = null;
                try {
                    tileImg = new NativeImage(NativeImage.Format.RGBA, w, h, false);
                    for (int y = 0; y < h; ++y) {
                        int rowOff = y * w;
                        for (int x = 0; x < w; ++x) {
                            tileImg.setPixel(x, y, tileArgb[rowOff + x]);
                        }
                    }
                    SpriteContents variant = new SpriteContents(
                            variantIds[tile],
                            frameSize,
                            tileImg,
                            Optional.empty(),
                            List.of(),
                            texMeta
                    );
                    familySprites.add(variant);
                    tileImg = null; // Ownership transferred to SpriteContents
                } catch (Throwable t) {
                    if (tileImg != null) {
                        tileImg.close();
                    }
                    for (SpriteContents created : familySprites) {
                        created.close();
                    }
                    familySprites.clear();
                    familyOk = false;
                    LOGGER.warn("[Metality] Failed synthesizing CTM tile {} for {}: {}", tile, baseId, t.toString());
                    break;
                }
            }

            if (familyOk && familySprites.size() == ConnectedGlassRegistry.TILE_COUNT - 1) {
                extra.addAll(familySprites);
                addedArea += familyArea;
                generatedFamilies++;
            }
        }

        if (extra.isEmpty()) {
            return originalSprites;
        }
        List<SpriteContents> combined = new ArrayList<>(originalSprites.size() + extra.size());
        combined.addAll(originalSprites);
        combined.addAll(extra);
        LOGGER.info("[Metality] Synthesized {} connected-glass CTM sprites across {} glass textures",
                extra.size(), generatedFamilies);
        return combined;
    }

    /**
     * Computes the per-channel median ARGB pixel from the central 50% of the sprite.
     * Robust against diagonal specular glint pixels in vanilla and custom glass textures.
     */
    public static int computeInteriorMedianArgb(int[] pixels, int w, int h) {
        int x0 = w / 4;
        int x1 = Math.max(x0 + 1, w - (w / 4));
        int y0 = h / 4;
        int y1 = Math.max(y0 + 1, h - (h / 4));
        int count = (x1 - x0) * (y1 - y0);
        int[] aVals = new int[count];
        int[] rVals = new int[count];
        int[] gVals = new int[count];
        int[] bVals = new int[count];
        int idx = 0;
        for (int y = y0; y < y1; ++y) {
            int rowOff = y * w;
            for (int x = x0; x < x1; ++x) {
                int p = pixels[rowOff + x];
                aVals[idx] = (p >>> 24) & 0xFF;
                rVals[idx] = (p >>> 16) & 0xFF;
                gVals[idx] = (p >>> 8) & 0xFF;
                bVals[idx] = p & 0xFF;
                idx++;
            }
        }
        Arrays.sort(aVals);
        Arrays.sort(rVals);
        Arrays.sort(gVals);
        Arrays.sort(bVals);
        int mid = count / 2;
        return (aVals[mid] << 24) | (rVals[mid] << 16) | (gVals[mid] << 8) | bVals[mid];
    }

    /**
     * Detects the outer border thickness in pixels by walking inward from ring {@code r = 0}
     * up to {@code max(1, min(w, h) / 8)} while at least 75% of the ring differs significantly
     * from the interior median pixel. Returns {@code 0} for already-borderless glass textures.
     */
    public static int detectBorderThickness(int[] pixels, int w, int h, int interiorMedianArgb) {
        int maxBorder = Math.max(1, Math.min(w, h) / 8);
        int detected = 0;
        for (int r = 0; r < maxBorder; ++r) {
            int ringTotal = 0;
            int ringDiff = 0;
            for (int x = r; x < w - r; ++x) {
                ringTotal += 2;
                if (isDistinctFromInterior(pixels[r * w + x], interiorMedianArgb)) {
                    ringDiff++;
                }
                if (isDistinctFromInterior(pixels[(h - 1 - r) * w + x], interiorMedianArgb)) {
                    ringDiff++;
                }
            }
            for (int y = r + 1; y < h - 1 - r; ++y) {
                ringTotal += 2;
                if (isDistinctFromInterior(pixels[y * w + r], interiorMedianArgb)) {
                    ringDiff++;
                }
                if (isDistinctFromInterior(pixels[y * w + (w - 1 - r)], interiorMedianArgb)) {
                    ringDiff++;
                }
            }
            if (ringTotal > 0 && ringDiff * 4 >= ringTotal * 3) {
                detected = r + 1;
            } else {
                break;
            }
        }
        return detected;
    }

    /**
     * Synthesizes a single CTM tile pixel buffer (ARGB) from {@code src} given detected {@code border}
     * thickness, {@code interiorMedianArgb}, and a canonical 8-neighbor bitmask.
     */
    public static int[] synthesizeTileArgb(
            int[] src,
            int w,
            int h,
            int border,
            int interiorMedianArgb,
            int canonicalMask
    ) {
        int[] dst = Arrays.copyOf(src, w * h);
        if (border <= 0 || canonicalMask == 0 || border * 2 >= w || border * 2 >= h) {
            return dst;
        }

        boolean up = (canonicalMask & ConnectedGlassRegistry.BIT_UP) != 0;
        boolean right = (canonicalMask & ConnectedGlassRegistry.BIT_RIGHT) != 0;
        boolean down = (canonicalMask & ConnectedGlassRegistry.BIT_DOWN) != 0;
        boolean left = (canonicalMask & ConnectedGlassRegistry.BIT_LEFT) != 0;
        boolean upLeft = (canonicalMask & ConnectedGlassRegistry.BIT_UP_LEFT) != 0;
        boolean upRight = (canonicalMask & ConnectedGlassRegistry.BIT_UP_RIGHT) != 0;
        boolean downRight = (canonicalMask & ConnectedGlassRegistry.BIT_DOWN_RIGHT) != 0;
        boolean downLeft = (canonicalMask & ConnectedGlassRegistry.BIT_DOWN_LEFT) != 0;

        // 1. Cardinal edge strips (excluding the 4 border x border corners)
        if (up) {
            for (int y = 0; y < border; ++y) {
                for (int x = border; x < w - border; ++x) {
                    dst[y * w + x] = sampleCleanInterior(src, w, h, border, interiorMedianArgb, x, y);
                }
            }
        }
        if (down) {
            for (int y = h - border; y < h; ++y) {
                for (int x = border; x < w - border; ++x) {
                    dst[y * w + x] = sampleCleanInterior(src, w, h, border, interiorMedianArgb, x, y);
                }
            }
        }
        if (left) {
            for (int y = border; y < h - border; ++y) {
                for (int x = 0; x < border; ++x) {
                    dst[y * w + x] = sampleCleanInterior(src, w, h, border, interiorMedianArgb, x, y);
                }
            }
        }
        if (right) {
            for (int y = border; y < h - border; ++y) {
                for (int x = w - border; x < w; ++x) {
                    dst[y * w + x] = sampleCleanInterior(src, w, h, border, interiorMedianArgb, x, y);
                }
            }
        }

        // 2. Top-left corner (x in [0, border), y in [0, border))
        for (int y = 0; y < border; ++y) {
            for (int x = 0; x < border; ++x) {
                int out;
                if (up && left) {
                    out = upLeft
                            ? sampleCleanInterior(src, w, h, border, interiorMedianArgb, x, y)
                            : src[y * w + x]; // Concave inner corner
                } else if (!up && left) {
                    out = src[y * w + Math.min(w - 1 - border, border + x)]; // Smooth straight top border
                } else if (up) {
                    out = src[Math.min(h - 1 - border, border + y) * w + x]; // Smooth straight left border
                } else {
                    out = src[y * w + x]; // Outer corner
                }
                dst[y * w + x] = out;
            }
        }

        // 3. Top-right corner (x in [w - border, w), y in [0, border))
        for (int y = 0; y < border; ++y) {
            for (int x = w - border; x < w; ++x) {
                int out;
                if (up && right) {
                    out = upRight
                            ? sampleCleanInterior(src, w, h, border, interiorMedianArgb, x, y)
                            : src[y * w + x];
                } else if (!up && right) {
                    out = src[y * w + Math.max(border, x - border)];
                } else if (up) {
                    out = src[Math.min(h - 1 - border, border + y) * w + x];
                } else {
                    out = src[y * w + x];
                }
                dst[y * w + x] = out;
            }
        }

        // 4. Bottom-right corner (x in [w - border, w), y in [h - border, h))
        for (int y = h - border; y < h; ++y) {
            for (int x = w - border; x < w; ++x) {
                int out;
                if (down && right) {
                    out = downRight
                            ? sampleCleanInterior(src, w, h, border, interiorMedianArgb, x, y)
                            : src[y * w + x];
                } else if (!down && right) {
                    out = src[y * w + Math.max(border, x - border)];
                } else if (down) {
                    out = src[Math.max(border, y - border) * w + x];
                } else {
                    out = src[y * w + x];
                }
                dst[y * w + x] = out;
            }
        }

        // 5. Bottom-left corner (x in [0, border), y in [h - border, h))
        for (int y = h - border; y < h; ++y) {
            for (int x = 0; x < border; ++x) {
                int out;
                if (down && left) {
                    out = downLeft
                            ? sampleCleanInterior(src, w, h, border, interiorMedianArgb, x, y)
                            : src[y * w + x];
                } else if (!down && left) {
                    out = src[y * w + Math.min(w - 1 - border, border + x)];
                } else if (down) {
                    out = src[Math.max(border, y - border) * w + x];
                } else {
                    out = src[y * w + x];
                }
                dst[y * w + x] = out;
            }
        }

        return dst;
    }

    private static int sampleCleanInterior(
            int[] src,
            int w,
            int h,
            int border,
            int interiorMedianArgb,
            int x,
            int y
    ) {
        int cx = Math.clamp(x, border, w - 1 - border);
        int cy = Math.clamp(y, border, h - 1 - border);
        int candidate = src[cy * w + cx];
        // Reject outlier glint/highlight pixels so specular streaks never bleed into erased seams
        return isDistinctFromInterior(candidate, interiorMedianArgb) ? interiorMedianArgb : candidate;
    }

    static boolean isDistinctFromInterior(int pixelArgb, int interiorMedianArgb) {
        int pa = (pixelArgb >>> 24) & 0xFF;
        int ma = (interiorMedianArgb >>> 24) & 0xFF;
        if (Math.abs(pa - ma) >= 20) {
            return true;
        }
        if (pa <= 16 && ma <= 16) {
            return false;
        }
        int pr = (pixelArgb >>> 16) & 0xFF;
        int pg = (pixelArgb >>> 8) & 0xFF;
        int pb = pixelArgb & 0xFF;
        int mr = (interiorMedianArgb >>> 16) & 0xFF;
        int mg = (interiorMedianArgb >>> 8) & 0xFF;
        int mb = interiorMedianArgb & 0xFF;
        return Math.abs(pr - mr) + Math.abs(pg - mg) + Math.abs(pb - mb) >= 36;
    }
}

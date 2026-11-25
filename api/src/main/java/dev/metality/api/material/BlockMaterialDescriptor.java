package dev.metality.api.material;

import java.util.Arrays;
import java.util.Objects;

/**
 * 不可變的方塊／流體材質描述子（對應底層 18-byte 緊湊 C 結構及 LabPBR / 光追材質參數）。
 */
public record BlockMaterialDescriptor(
        String blockId,
        int[] faceSpriteIds,
        RenderLayerKind layer,
        boolean opaqueFullCube,
        int waveTag,
        int emissiveLevel,
        int alpha,
        float roughness,
        float metallicF0,
        float indexOfRefraction
) {
    public BlockMaterialDescriptor {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(faceSpriteIds, "faceSpriteIds");
        Objects.requireNonNull(layer, "layer");
        if (faceSpriteIds.length != 6) {
            throw new IllegalArgumentException("faceSpriteIds must have length 6 (-X, +X, -Y, +Y, -Z, +Z)");
        }
        faceSpriteIds = Arrays.copyOf(faceSpriteIds, 6);
        emissiveLevel = Math.clamp(emissiveLevel, 0, 15);
        alpha = Math.clamp(alpha, 0, 255);
        roughness = Math.clamp(roughness, 0.02f, 1.0f);
        metallicF0 = Math.clamp(metallicF0, 0.0f, 1.0f);
    }

    @Override
    public int[] faceSpriteIds() {
        return Arrays.copyOf(faceSpriteIds, 6);
    }

    public int spriteForFace(int faceIndex) {
        return faceSpriteIds[faceIndex];
    }

    public static Builder builder(String blockId) {
        return new Builder(blockId);
    }

    public static final class Builder {
        private final String blockId;
        private int[] faceSpriteIds = new int[]{0, 0, 0, 0, 0, 0};
        private RenderLayerKind layer = RenderLayerKind.SOLID;
        private boolean opaqueFullCube = true;
        private int waveTag = 0;
        private int emissiveLevel = 0;
        private int alpha = 255;
        private float roughness = 0.85f;
        private float metallicF0 = 0.04f;
        private float indexOfRefraction = 1.0f;

        private Builder(String blockId) {
            this.blockId = Objects.requireNonNull(blockId, "blockId");
        }

        public Builder allFacesSprite(int spriteId) {
            Arrays.fill(this.faceSpriteIds, spriteId);
            return this;
        }

        public Builder faceSprites(int west, int east, int down, int up, int north, int south) {
            this.faceSpriteIds = new int[]{west, east, down, up, north, south};
            return this;
        }

        public Builder layer(RenderLayerKind layer) {
            this.layer = layer;
            return this;
        }

        public Builder opaqueFullCube(boolean opaqueFullCube) {
            this.opaqueFullCube = opaqueFullCube;
            return this;
        }

        public Builder waveTag(int waveTag) {
            this.waveTag = waveTag;
            return this;
        }

        public Builder emissiveLevel(int emissiveLevel) {
            this.emissiveLevel = emissiveLevel;
            return this;
        }

        public Builder alpha(int alpha) {
            this.alpha = alpha;
            return this;
        }

        public Builder roughness(float roughness) {
            this.roughness = roughness;
            return this;
        }

        public Builder metallicF0(float metallicF0) {
            this.metallicF0 = metallicF0;
            return this;
        }

        public Builder indexOfRefraction(float ior) {
            this.indexOfRefraction = ior;
            return this;
        }

        public BlockMaterialDescriptor build() {
            return new BlockMaterialDescriptor(
                    blockId, faceSpriteIds, layer, opaqueFullCube,
                    waveTag, emissiveLevel, alpha, roughness, metallicF0, indexOfRefraction
            );
        }
    }
}

package dev.metality.mc263.mixin;

import dev.metality.mc263.glass.ConnectedGlassAtlasGenerator;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Injects procedurally synthesized 47-tile connected glass {@link SpriteContents} variants into
 * Minecraft's native {@code blocks} atlas right before {@code Stitcher} runs, ensuring native
 * mipmap generation and anisotropic border padding with zero border bleeding.
 */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderMixin {

    @Shadow
    @Final
    private Identifier location;

    @Shadow
    @Final
    private int maxSupportedTextureSize;

    @ModifyVariable(method = "stitch", at = @At("HEAD"), argsOnly = true)
    private List<SpriteContents> metality$appendConnectedGlassSprites(List<SpriteContents> sprites) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
            return ConnectedGlassAtlasGenerator.appendConnectedGlassSprites(sprites, this.maxSupportedTextureSize);
        }
        return sprites;
    }
}

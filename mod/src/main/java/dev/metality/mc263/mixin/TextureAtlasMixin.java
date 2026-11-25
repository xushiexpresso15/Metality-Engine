package dev.metality.mc263.mixin;

import dev.metality.mc263.glass.ConnectedGlassEngine;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Publishes the stitched 47-tile connected glass sprite snapshot and resets the per-quad CTM cache
 * on startup and every resource-pack reload ({@code F3+T}).
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {

    @Shadow
    @Final
    private Identifier location;

    @Inject(method = "upload", at = @At("RETURN"))
    private void metality$syncConnectedGlassSprites(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
            ConnectedGlassEngine.onBlocksAtlasUploaded(preparations.regions());
        }
    }
}

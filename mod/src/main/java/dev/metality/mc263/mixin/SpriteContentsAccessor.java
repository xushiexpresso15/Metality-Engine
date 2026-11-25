package dev.metality.mc263.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes source {@link NativeImage} and mipmap metadata on {@link SpriteContents}
 * so {@code ConnectedGlassAtlasGenerator} can synthesize bleed-free 47-tile CTM variants.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {

    @Accessor("originalImage")
    NativeImage metality$getOriginalImage();

    @Accessor("mipmapStrategy")
    MipmapStrategy metality$getMipmapStrategy();

    @Accessor("alphaCutoffBias")
    float metality$getAlphaCutoffBias();
}

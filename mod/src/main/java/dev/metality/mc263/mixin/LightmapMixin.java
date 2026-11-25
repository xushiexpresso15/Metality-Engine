package dev.metality.mc263.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.metality.mc263.shader.Mc263ShaderRuntime;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 當啟用 Aperture 原生可程式光影時，將原版 Lightmap 維持於純白 (1,1,1,1)，
 * 使 mainRenderTarget 輸出純淨反照率 (Albedo)，避免與即時光線追蹤產生雙重乘法疊黑。
 */
@Mixin(Lightmap.class)
public abstract class LightmapMixin {

    @Unique
    private static final Vector4f METALITY_NEUTRAL_WHITE = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);

    @Shadow
    @Final
    private GpuTexture texture;

    @Unique
    private boolean metality$neutralized = false;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void metality$neutralizeLightmap(LightmapRenderState state, CallbackInfo ci) {
        if (Mc263ShaderRuntime.isApertureWorldShaderActive()) {
            if (!metality$neutralized && this.texture != null) {
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(this.texture, METALITY_NEUTRAL_WHITE);
                metality$neutralized = true;
            }
            ci.cancel();
        } else {
            metality$neutralized = false;
        }
    }
}

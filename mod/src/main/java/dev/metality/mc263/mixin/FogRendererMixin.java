package dev.metality.mc263.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.metality.mc263.shader.Mc263ShaderRuntime;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 當啟用 Aperture 原生可程式光影時，停用原版平面地形霧效，讓地獄熔岩海與遠景保留真實輻射色彩並由光影統一霧散射。
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

    @Shadow
    @Final
    private GpuBuffer emptyBuffer;

    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void metality$neutralizeWorldFog(FogRenderer.FogMode mode, CallbackInfoReturnable<GpuBufferSlice> cir) {
        if (mode == FogRenderer.FogMode.WORLD && Mc263ShaderRuntime.isApertureWorldShaderActive()) {
            cir.setReturnValue(this.emptyBuffer.slice(0L, FogRenderer.FOG_UBO_SIZE));
        }
    }
}

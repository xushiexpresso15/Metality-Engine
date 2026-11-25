package dev.metality.mc263.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.metality.mc263.shader.Mc263ShaderRuntime;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 啟用 Aperture 世界光影時隱藏原版方形太陽與月亮，防止透過半透明水面或玻璃出現方塊破圖。
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

    @Inject(
            method = "renderSun(Lcom/mojang/renderpearl/api/commands/RenderPass;FLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metality$suppressVanillaSun(RenderPass pass, float rainBrightness, PoseStack poseStack, CallbackInfo ci) {
        if (Mc263ShaderRuntime.isApertureWorldShaderActive()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "renderMoon(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metality$suppressVanillaMoon(RenderPass pass, MoonPhase phase, float rainBrightness, PoseStack poseStack, CallbackInfo ci) {
        if (Mc263ShaderRuntime.isApertureWorldShaderActive()) {
            ci.cancel();
        }
    }
}

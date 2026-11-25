package dev.metality.mc263.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.metality.mc263.shader.Mc263ShaderRuntime;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 啟用 Aperture 世界光影時抑制原版方塊雲，避免與 Meteor Shader 體積雲重疊。
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metality$suppressVanillaClouds(CloudStatus status, RenderPass pass, CallbackInfo ci) {
        if (Mc263ShaderRuntime.isApertureWorldShaderActive()) {
            ci.cancel();
        }
    }

    @Inject(
            method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metality$suppressVanillaCloudPass(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
        if (Mc263ShaderRuntime.isApertureWorldShaderActive()) {
            ci.cancel();
        }
    }
}

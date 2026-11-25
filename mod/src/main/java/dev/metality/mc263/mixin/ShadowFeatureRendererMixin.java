package dev.metality.mc263.mixin;

import dev.metality.mc263.shader.Mc263ShaderRuntime;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 啟用 Aperture 世界光影時隱藏原版生物腳底圓形陰影貼圖，完全交由即時動態肢體光追陰影繪製；
 * 關閉光影時自動恢復原版圓形陰影。
 */
@Mixin(ShadowFeatureRenderer.class)
public abstract class ShadowFeatureRendererMixin {

    @Inject(method = "buildGroup", at = @At("HEAD"), cancellable = true)
    private void metality$suppressVanillaEntityBlobShadow(
            FeatureFrameContext context,
            List<ShadowFeatureRenderer.Submit> submits,
            CallbackInfo ci
    ) {
        if (Mc263ShaderRuntime.isApertureWorldShaderActive()) {
            ci.cancel();
        }
    }
}

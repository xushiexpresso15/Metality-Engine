package dev.metality.mc263.mixin;

import dev.metality.mc263.shader.Mc263ShaderRuntime;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 當在暫停選單（如設定介面）即時切換光影開關時，強制讓 LightmapRenderStateExtractor
 * 於下一幀重新評估並繪製原版 Lightmap 貼圖，避免停留在光影中性純白狀態。
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {

    @Shadow
    private boolean needsUpdate;

    @Inject(method = "extract", at = @At("HEAD"))
    private void metality$forceLightmapRefreshOnShaderToggle(
            LightmapRenderState renderState,
            float partialTick,
            CallbackInfo ci
    ) {
        if (Mc263ShaderRuntime.consumePendingLightmapRefresh()) {
            this.needsUpdate = true;
        }
    }
}

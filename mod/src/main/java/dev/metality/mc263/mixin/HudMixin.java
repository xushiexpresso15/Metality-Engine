package dev.metality.mc263.mixin;

import dev.metality.mc263.gui.MetalityTelemetryHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 於 HUD 繪製結尾疊加 Apple Silicon Metal 即時效能與 UMA 遙測資訊卡。
 */
@Mixin(Hud.class)
public abstract class HudMixin {

    @Shadow
    private boolean isHidden;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void metality$renderTelemetryHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!this.isHidden && !this.minecraft.getDebugOverlay().showDebugScreen()) {
            MetalityTelemetryHud.extractRenderState(graphics, this.minecraft);
        }
    }
}

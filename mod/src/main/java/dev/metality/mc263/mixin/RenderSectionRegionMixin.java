package dev.metality.mc263.mixin;

import dev.metality.mc263.shader.Mc263ShaderRuntime;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 停用區塊編譯階段的原版方向性面暗角，使 terrain pass 輸出純淨 PBR Albedo。
 */
@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionMixin {

    @Unique
    private static final CardinalLighting METALITY_NEUTRAL_CARDINAL =
            new CardinalLighting(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

    @Inject(method = "cardinalLighting", at = @At("HEAD"), cancellable = true)
    private void metality$neutralCardinal(CallbackInfoReturnable<CardinalLighting> cir) {
        if (Mc263ShaderRuntime.isApertureWorldShaderActive()) {
            cir.setReturnValue(METALITY_NEUTRAL_CARDINAL);
        }
    }
}

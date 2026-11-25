package dev.metality.mc263.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.metality.mc263.shader.Mc263ShaderRuntime;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 落實 Aperture pack.ts 的 {@code config.disableShade = true}：
 * 停用原版方向性面暗角（0.50 / 0.60 / 0.80）與原版頂點 AO 烘培（包含非完整方塊如 dirt_path 1x1 邊界黑塊），
 * 讓 PBR GTAO / HWRT 管線在乾淨反照率上獨立計算精確遮蔽與光照，避免雙重疊黑同時加速區塊網格建構。
 */
@Mixin(BlockModelLighter.class)
public abstract class BlockModelLighterMixin {

    @Shadow
    public abstract void prepareQuadFlat(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            int lightCoords,
            BakedQuad quad,
            QuadInstance instance
    );

    @Inject(method = "getDirectionalBrightness", at = @At("HEAD"), cancellable = true)
    private static void metality$neutralizeDirectionalShade(
            CardinalLighting cardinalLighting,
            BakedQuad quad,
            Direction direction,
            CallbackInfoReturnable<Float> cir
    ) {
        if (Mc263ShaderRuntime.isDisableShadeActive()) {
            cir.setReturnValue(1.0f);
        }
    }

    @Inject(method = "prepareQuadAmbientOcclusion", at = @At("HEAD"), cancellable = true)
    private void metality$bypassVanillaAo(
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            BakedQuad quad,
            QuadInstance instance,
            CallbackInfo ci
    ) {
        if (Mc263ShaderRuntime.isDisableShadeActive()) {
            this.prepareQuadFlat(level, state, pos, BlockModelLighter.CHECK_LIGHT, quad, instance);
            ci.cancel();
        }
    }
}

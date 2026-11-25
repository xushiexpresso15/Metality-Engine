package dev.metality.mc263.mixin;

import dev.metality.mc263.shader.Mc263ShaderRuntime;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 停用原版方塊化像素水網格幾何體（保留熔岩），由 Meteor Shader 3D 物理波浪與水下折射管線完整繪製水面。
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {

    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    private void metality$suppressVanillaWater(
            BlockAndTintGetter level,
            BlockPos pos,
            FluidRenderer.Output output,
            BlockState blockState,
            FluidState fluidState,
            CallbackInfo ci
    ) {
        if (fluidState != null && fluidState.is(FluidTags.WATER) && Mc263ShaderRuntime.shouldSuppressVanillaWaterMesh()) {
            ci.cancel();
        }
    }
}

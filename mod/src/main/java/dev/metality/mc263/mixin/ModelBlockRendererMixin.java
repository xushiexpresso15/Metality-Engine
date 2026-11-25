package dev.metality.mc263.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.metality.mc263.glass.ConnectedGlassEngine;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts {@link ModelBlockRenderer#putQuadWithTint} (called by both Smooth Lighting AO and Flat
 * tessellation paths) to cull redundant internal glass-pane rim caps and remap connected glass / glass-pane
 * quads to their 47-tile stitched CTM atlas sprites.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {

    @Shadow
    @Final
    private QuadInstance quadInstance;

    @Shadow
    private int getTintColor(BlockAndTintGetter level, BlockState state, BlockPos pos, int tintIndex) {
        throw new AssertionError();
    }

    @Inject(method = "putQuadWithTint", at = @At("HEAD"), cancellable = true)
    private void metality$applyConnectedGlass(
            BlockQuadOutput output,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockState state,
            BlockPos pos,
            BakedQuad quad,
            CallbackInfo ci
    ) {
        if (!ConnectedGlassEngine.isCandidateState(state)) {
            return;
        }
        if (ConnectedGlassEngine.shouldCullGlassPaneRimQuad(level, state, pos, quad)) {
            ci.cancel();
            return;
        }
        BakedQuad remapped = ConnectedGlassEngine.remapGlassQuad(level, state, pos, quad);
        if (remapped != quad) {
            int tintIndex = remapped.materialInfo().tintIndex();
            if (tintIndex != -1) {
                this.quadInstance.multiplyColor(this.getTintColor(level, state, pos, tintIndex));
            }
            output.put(x, y, z, remapped, this.quadInstance);
            ci.cancel();
        }
    }
}

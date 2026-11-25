package dev.metality.mc263.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.metality.mc263.shader.Mc263ShaderRuntime;
import dev.metality.mc263.world.Mc263ChunkAdapter;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 攔截 Minecraft 26.3 {@link LevelRenderer#render} 返回點，在繪製 HUD 與 Benchmark Readback 前
 * 執行 Metality Apple Silicon 即時多通道 HDR 世界光影合成。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    @Final
    private GameRenderer gameRenderer;

    @Shadow
    @Final
    private LevelRenderState levelRenderState;

    @Inject(method = "resetLevelRenderData", at = @At("HEAD"))
    private void metality$onResetLevelRenderData(CallbackInfo ci) {
        Mc263ChunkAdapter.invalidateAllSections();
        Mc263ShaderRuntime.resetTemporalHistory();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void metality$composeWorldShaders(
            GraphicsResourceAllocator allocator,
            boolean renderBlockOutline,
            CameraRenderState cameraRenderState,
            GpuBufferSlice fogBuffer,
            Vector4f fogColor,
            boolean shouldRenderSky,
            boolean shouldRenderHudEffects,
            CallbackInfo ci
    ) {
        Mc263ShaderRuntime.executeWorldPostComposition(
                this.gameRenderer,
                this.levelRenderState,
                cameraRenderState,
                fogColor
        );
    }
}

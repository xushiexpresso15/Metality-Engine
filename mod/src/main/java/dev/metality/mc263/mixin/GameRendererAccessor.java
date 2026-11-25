package dev.metality.mc263.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 公開 {@link GameRenderer#bobHurt} 與 {@link GameRenderer#bobView} 以同步視角晃動至世界光影投影矩陣。
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Invoker("bobHurt")
    void metality$bobHurt(CameraRenderState cameraRenderState, PoseStack poseStack);

    @Invoker("bobView")
    void metality$bobView(CameraRenderState cameraRenderState, PoseStack poseStack);
}

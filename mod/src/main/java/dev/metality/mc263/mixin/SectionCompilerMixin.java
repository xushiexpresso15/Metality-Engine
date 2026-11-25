package dev.metality.mc263.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.mc263.world.Mc263ChunkAdapter;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 攔截 Minecraft 26.3 SectionCompiler.compile()：將更新後之 16x16x16 區塊同步送入 Metality NEON 網格管線與 GPU ICB 資源池。
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    @Inject(method = "compile", at = @At("HEAD"))
    private void metality$feedNativeTerrainPipeline(
            SectionPos sectionPos,
            RenderSectionRegion region,
            VertexSorting vertexSorting,
            SectionBufferBuilderPack buffers,
            CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        MetalGpuDevice device = MetalGpuDevice.getInstance();
        if (device != null) {
            Mc263ChunkAdapter.onCompileSection(device, sectionPos, region);
        }
    }
}

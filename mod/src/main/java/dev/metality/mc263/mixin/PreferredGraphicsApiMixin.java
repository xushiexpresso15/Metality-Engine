package dev.metality.mc263.mixin;

import com.mojang.renderpearl.api.device.GpuBackend;
import dev.metality.mc263.renderpearl.MetalRenderPearlBackend;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 攔截 PreferredGraphicsApi.getBackendsToTry()：於 Apple Silicon macOS 環境將原生 Metal 後端排入首選。
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {

    @Inject(method = "getBackendsToTry()[Lcom/mojang/renderpearl/api/device/GpuBackend;", at = @At("RETURN"), cancellable = true)
    private void metality$prependMetalBackend(CallbackInfoReturnable<GpuBackend[]> cir) {
        if (!Boolean.parseBoolean(System.getProperty("metality.enableRenderPearlBackend", "true"))) {
            return;
        }
        String os = System.getProperty("os.name", "");
        String arch = System.getProperty("os.arch", "");
        if (!os.toLowerCase().contains("mac") || !"aarch64".equals(arch)) {
            return;
        }

        Path cacheRoot = Paths.get(System.getProperty("user.dir", "."));
        try {
            cacheRoot = FabricLoader.getInstance().getGameDir();
        } catch (Throwable ignored) {
        }

        GpuBackend[] original = cir.getReturnValue();
        int origLen = original != null ? original.length : 0;
        GpuBackend[] withMetal = new GpuBackend[origLen + 1];
        withMetal[0] = new MetalRenderPearlBackend(cacheRoot);
        if (origLen > 0) {
            System.arraycopy(original, 0, withMetal, 1, origLen);
        }
        cir.setReturnValue(withMetal);
    }
}

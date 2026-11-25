package dev.metality.mc263.mixin;

import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
import dev.metality.mc263.renderpearl.MetalRenderPearlBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 攔截 Minecraft 26.3 Window.createWindow 對 RenderPearl GpuBackend.createWindow 的呼叫：
 * 若使用 Metality Metal 後端，移除 SDL_WINDOW_OPENGL 與 SDL_WINDOW_VULKAN 旗標並掛載 SDL_WINDOW_METAL。
 */
@Mixin(Window.class)
public abstract class WindowBackendMixin {

    @Redirect(
            method = "createWindow(Lcom/mojang/renderpearl/api/device/GpuBackend;IILjava/lang/String;)J",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"
            )
    )
    private long metality$createNativeMetalSdlWindow(
            GpuBackend backend,
            String title,
            int width,
            int height,
            long sdlFlags
    ) {
        if (backend instanceof MetalRenderPearlBackend
                && Boolean.parseBoolean(System.getProperty("metality.nativeWindow", "true"))) {
            long metalFlags = (sdlFlags & ~(SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_VULKAN))
                    | SDLVideo.SDL_WINDOW_METAL;
            return backend.createWindow(title, width, height, metalFlags);
        }
        return backend.createWindow(title, width, height, sdlFlags);
    }
}

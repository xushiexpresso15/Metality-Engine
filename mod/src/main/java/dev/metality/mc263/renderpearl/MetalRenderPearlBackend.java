package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.lwjgl.sdl.SDLVideo;

/**
 * Minecraft 26.3 RenderPearl Apple Silicon Metal 後端入口。
 */
public final class MetalRenderPearlBackend implements GpuBackend {

    private final Path runtimeCacheRoot;

    public MetalRenderPearlBackend() {
        this(Paths.get(System.getProperty("user.dir", ".")));
    }

    public MetalRenderPearlBackend(Path runtimeCacheRoot) {
        this.runtimeCacheRoot = runtimeCacheRoot;
    }

    @Override
    public String getName() {
        return "Metality (Apple Silicon Metal)";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        String arch = System.getProperty("os.arch", "");
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase().contains("mac") || !arch.contains("aarch64")) {
            throw new BackendCreationException(
                    "Metality Metal backend requires Apple Silicon macOS (detected " + os + "/" + arch + ")",
                    BackendCreationException.Reason.PLATFORM_ERROR
            );
        }
    }

    @Override
    public void unloadLibrary() {
        // libmetality_native.dylib 由 MetalGpuDevice Arena 生命週期管理
    }

    @Override
    public long createWindow(String title, int width, int height, long sdlFlags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | sdlFlags);
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        return new FrontendGpuDevice(new MetalDeviceBackend(runtimeCacheRoot, debugOptions));
    }
}

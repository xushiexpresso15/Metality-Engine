package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.metality.backend.MetalGpuDevice;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.EnumSet;
import java.util.function.BooleanSupplier;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryStack;

/**
 * 將 SDL3 Cocoa NSWindow 直接接管至 Metality CAMetalLayer 之 RenderPearl GpuSurfaceBackend。
 */
public final class MetalSurfaceBackend implements GpuSurfaceBackend {

    private final MetalDeviceBackend deviceBackend;
    private final MetalGpuDevice metalDevice;
    private final long sdlWindowHandle;
    private final BooleanSupplier isVsync;
    private boolean layerAttached = false;
    private boolean closed = false;

    public MetalSurfaceBackend(MetalDeviceBackend deviceBackend, long sdlWindowHandle, BooleanSupplier isVsync) {
        this.deviceBackend = deviceBackend;
        this.metalDevice = deviceBackend.metalDevice();
        this.sdlWindowHandle = sdlWindowHandle;
        this.isVsync = isVsync;
    }

    @Override
    public void configure(GpuSurface.Configuration config) throws SurfaceException {
        if (closed) {
            throw new SurfaceException("Metal surface is already closed");
        }
        int width = Math.max(1, config.width());
        int height = Math.max(1, config.height());
        float contentScale = SDLVideo.SDL_GetWindowDisplayScale(sdlWindowHandle);
        if (contentScale <= 0.0f) {
            contentScale = 2.0f;
        }

        boolean vsync = (config.presentMode() == GpuSurface.PresentMode.FIFO) || isVsync.getAsBoolean();
        if (!layerAttached) {
            int props = SDLVideo.SDL_GetWindowProperties(sdlWindowHandle);
            long nsWindowPtr = SDLProperties.SDL_GetPointerProperty(
                    props, SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER, 0L
            );
            if (nsWindowPtr == 0L) {
                throw new SurfaceException("SDL3 window does not expose Cocoa NSWindow pointer");
            }
            int fbW = width;
            int fbH = height;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer pw = stack.mallocInt(1);
                IntBuffer ph = stack.mallocInt(1);
                if (SDLVideo.SDL_GetWindowSizeInPixels(sdlWindowHandle, pw, ph)) {
                    fbW = Math.max(1, pw.get(0));
                    fbH = Math.max(1, ph.get(0));
                }
            }
            metalDevice.bindCocoaWindow(nsWindowPtr, fbW, fbH, contentScale, vsync);
            layerAttached = true;
        } else {
            metalDevice.onFramebufferResize(width, height, contentScale);
            metalDevice.setDisplaySync(vsync);
        }
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (closed) {
            throw new SurfaceException("Metal surface is closed");
        }
        deviceBackend.onSurfaceAcquire();
        metalDevice.ensureFrameStarted();
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView view) {
        if (closed || !(view instanceof MetalPearlTextureView metalView)) {
            return;
        }
        if (encoder instanceof MetalCommandEncoderBackend metalEncoder) {
            metalEncoder.submitRenderPass();
        }
        metalDevice.commandEncoder().presentBlitTexture(metalView.metalTexture().nativeTexture());
        deviceBackend.onSurfaceBlitEncoded();
    }

    @Override
    public void present() {
        if (closed) {
            return;
        }
        deviceBackend.onSurfacePresent();
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return EnumSet.of(
                GpuSurface.PresentMode.FIFO,
                GpuSurface.PresentMode.IMMEDIATE,
                GpuSurface.PresentMode.MAILBOX
        );
    }

    @Override
    public void close() {
        closed = true;
    }
}

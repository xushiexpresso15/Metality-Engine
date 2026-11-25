package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.metality.backend.MetalGpuBuffer;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import dev.metality.config.MetalitySettingsConfig;
import dev.metality.impl.DefaultMetalityEngine;
import dev.metality.mc263.shader.LwjglShadercSpvcTranspiler;
import dev.metality.nativebridge.MetalityFFM;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * RenderPearl GpuDeviceBackend 之 Metal 原生實作（連接 Minecraft 26.3 與版本無關 :core 引擎）。
 */
public final class MetalDeviceBackend implements GpuDeviceBackend {

    private static volatile MetalDeviceBackend activeBackend;

    private final Path gameDir;
    private final MetalitySettingsConfig settingsConfig;
    private final MetalGpuDevice metalDevice;
    private final DefaultMetalityEngine publicEngine;
    private final MetalCommandEncoderBackend encoderBackend;
    private final DeviceInfo deviceInfo;
    private final boolean debuggingEnabled;
    private final ConcurrentLinkedQueue<Runnable> pendingReadbackCallbacks = new ConcurrentLinkedQueue<>();

    private MetalGpuBuffer sequentialQuadIndexBuffer;
    private int quadIndexCapacity = 0;
    private MetalGpuBuffer sequentialFanIndexBuffer;
    private int fanIndexCapacity = 0;

    private boolean surfaceAcquired = false;
    private boolean surfaceBlitEncoded = false;
    private boolean surfacePresented = false;

    public MetalDeviceBackend(Path gameDir, GpuDebugOptions debugOptions) throws BackendCreationException {
        try {
            this.gameDir = gameDir;
            this.settingsConfig = MetalitySettingsConfig.load(gameDir);
            this.metalDevice = new MetalGpuDevice(gameDir, this.settingsConfig.resolveEffectiveEdr());
            this.metalDevice.registerGlslTranspilerSpi(new LwjglShadercSpvcTranspiler(this.metalDevice));
            this.publicEngine = new DefaultMetalityEngine(this.metalDevice);
            this.encoderBackend = new MetalCommandEncoderBackend(this);
            activeBackend = this;
            this.debuggingEnabled = debugOptions != null && debugOptions.useValidationLayers();

            var caps = metalDevice.capabilities();
            this.metalDevice.setMaxFramesInFlight(this.settingsConfig.getMaxFramesInFlight());
            this.metalDevice.setShaderRenderScale(this.settingsConfig.getShaderRenderScale());
            this.publicEngine.rayTracing().setEnabled(this.settingsConfig.isHardwareRayTracing() && caps.hardwareRayTracing());
            DeviceLimits limits = new DeviceLimits(
                    16,
                    256,
                    16384,
                    caps.recommendedMaxWorkingSetBytes(),
                    4096,
                    8,
                    4096
            );
            DeviceFeatures features = new DeviceFeatures(
                    true,
                    false,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true
            );
            HintsAndWorkarounds hints = new HintsAndWorkarounds(true, false, true, false);
            this.deviceInfo = new DeviceInfo(
                    caps.deviceName(),
                    "Apple",
                    "Metal " + (caps.metal4CommandQueue() ? "4" : "3") + " (Apple" + caps.appleGpuFamily() + ")",
                    true,
                    "Metality Metal",
                    1.0f,
                    limits,
                    features,
                    Set.of("metal_tbdr", "uma_zero_copy", "metalfx", caps.hardwareRayTracing() ? "metal_hw_raytracing" : "voxel_rt_fallback"),
                    hints,
                    DeviceType.INTEGRATED
            );
        } catch (Throwable t) {
            throw new BackendCreationException(
                    "Failed to initialize Metality Metal GPU device: " + t.getMessage(),
                    BackendCreationException.Reason.PLATFORM_ERROR
            );
        }
    }

    public Path gameDir() {
        return gameDir;
    }

    public MetalitySettingsConfig settingsConfig() {
        return settingsConfig;
    }

    public MetalGpuDevice metalDevice() {
        return metalDevice;
    }

    public DefaultMetalityEngine publicEngine() {
        return publicEngine;
    }

    public synchronized MetalGpuBuffer ensureSequentialQuadIndexBuffer(int requiredIndices) {
        if (sequentialQuadIndexBuffer != null && !sequentialQuadIndexBuffer.isRetired() && quadIndexCapacity >= requiredIndices) {
            return sequentialQuadIndexBuffer;
        }
        int quadCount = Math.max(4096, (requiredIndices + 5) / 6);
        int idxCount = quadCount * 6;
        if (sequentialQuadIndexBuffer != null) {
            sequentialQuadIndexBuffer.close();
        }
        MetalGpuBuffer fresh = metalDevice.createBuffer((long) idxCount * Integer.BYTES, true);
        ByteBuffer view = fresh.cpuSegment().asByteBuffer().order(ByteOrder.nativeOrder());
        for (int q = 0; q < quadCount; ++q) {
            int baseV = q * 4;
            view.putInt(baseV);
            view.putInt(baseV + 1);
            view.putInt(baseV + 2);
            view.putInt(baseV + 2);
            view.putInt(baseV + 3);
            view.putInt(baseV);
        }
        sequentialQuadIndexBuffer = fresh;
        quadIndexCapacity = idxCount;
        return fresh;
    }

    public synchronized MetalGpuBuffer ensureSequentialFanIndexBuffer(int requiredIndices) {
        if (sequentialFanIndexBuffer != null && !sequentialFanIndexBuffer.isRetired() && fanIndexCapacity >= requiredIndices) {
            return sequentialFanIndexBuffer;
        }
        int triCount = Math.max(64, (requiredIndices + 2) / 3);
        int idxCount = triCount * 3;
        if (sequentialFanIndexBuffer != null) {
            sequentialFanIndexBuffer.close();
        }
        MetalGpuBuffer fresh = metalDevice.createBuffer((long) idxCount * Integer.BYTES, true);
        ByteBuffer view = fresh.cpuSegment().asByteBuffer().order(ByteOrder.nativeOrder());
        for (int t = 0; t < triCount; ++t) {
            view.putInt(0);
            view.putInt(t + 1);
            view.putInt(t + 2);
        }
        sequentialFanIndexBuffer = fresh;
        fanIndexCapacity = idxCount;
        return fresh;
    }

    void onSurfaceAcquire() {
        surfaceAcquired = true;
        surfaceBlitEncoded = false;
        surfacePresented = false;
    }

    void onSurfaceBlitEncoded() {
        surfaceBlitEncoded = true;
    }

    void markSurfacePresented() {
        surfacePresented = true;
    }

    void onSurfacePresent() {
        long submittedFrameId = 0L;
        boolean ended = false;
        if (surfaceBlitEncoded && !surfacePresented) {
            submittedFrameId = metalDevice.endFrame(true);
            ended = true;
        } else if (metalDevice.isFrameOpen()) {
            submittedFrameId = metalDevice.endFrame(false);
            ended = true;
        }
        if (ended) {
            encoderBackend.onExternalFrameSubmitted(submittedFrameId);
            flushReadbackCallbacks(submittedFrameId);
        }
        surfaceAcquired = false;
        surfaceBlitEncoded = false;
        surfacePresented = false;
    }

    public void flushOpenCommandBufferWithoutPresent() {
        encoderBackend.submitRenderPass();
        if (metalDevice.isFrameOpen()) {
            long submitted = metalDevice.endFrame(false);
            encoderBackend.onExternalFrameSubmitted(submitted);
            flushReadbackCallbacks(submitted);
        }
    }

    public void enqueueReadbackCallback(Runnable callback) {
        if (callback != null) {
            pendingReadbackCallbacks.offer(callback);
        }
    }

    public void flushReadbackCallbacks(long submittedFrameId) {
        if (pendingReadbackCallbacks.isEmpty()) {
            return;
        }
        if (submittedFrameId > 0L) {
            metalDevice.waitFrameComplete(submittedFrameId, 5000L);
        }
        Runnable task;
        while ((task = pendingReadbackCallbacks.poll()) != null) {
            task.run();
        }
    }

    boolean isSurfaceAcquired() {
        return surfaceAcquired;
    }

    boolean isSurfaceBlitEncoded() {
        return surfaceBlitEncoded;
    }

    boolean isSurfacePresented() {
        return surfacePresented;
    }

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isVsync) {
        return new MetalSurfaceBackend(this, windowHandle, isVsync);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return encoderBackend;
    }

    @Override
    public GpuSampler createSampler(
            AddressMode addressModeU,
            AddressMode addressModeV,
            FilterMode minFilter,
            FilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod
    ) {
        boolean linearMin = minFilter == FilterMode.LINEAR;
        boolean linearMag = magFilter == FilterMode.LINEAR;
        boolean repeatU = addressModeU == AddressMode.REPEAT;
        boolean repeatV = addressModeV == AddressMode.REPEAT;
        float clampedMaxLod = (float) (maxLod != null ? maxLod.orElse(1000.0) : 1000.0);
        long handle = metalDevice.getOrCreateSamplerEx(
                linearMin, linearMag, repeatU, repeatV, Math.max(1, maxAnisotropy), clampedMaxLod
        );
        return new MetalPearlSampler(handle, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        int nativeUsage = 0;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            nativeUsage |= MetalityFFM.TEX_USAGE_SAMPLED;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            nativeUsage |= MetalityFFM.TEX_USAGE_RENDER_TARGET;
        }
        if ((usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
            nativeUsage |= MetalityFFM.TEX_USAGE_CUBEMAP;
        }
        if (nativeUsage == 0) {
            nativeUsage = MetalityFFM.TEX_USAGE_SAMPLED;
        }
        int nativeFmt = MetalFormatMapper.mapPixelFormat(format);
        // When MetalFX scaling is active (shaderRenderScale < 1.0), grant STORAGE_WRITE to large color render targets
        // (mainRenderTarget) so MetalFX Spatial/Temporal writes directly without allocating a 21MB upscale_output_tex + copy blit.
        // At 1.0x native Retina, intentionally omit STORAGE_WRITE so Apple Silicon hardware lossless compression stays enabled!
        boolean isDepthFmt = (nativeFmt == MetalityFFM.FORMAT_DEPTH32_FLOAT);
        if (!isDepthFmt
                && (nativeUsage & MetalityFFM.TEX_USAGE_RENDER_TARGET) != 0
                && (nativeUsage & MetalityFFM.TEX_USAGE_CUBEMAP) == 0
                && width >= 512 && height >= 256
                && metalDevice.shaderRenderScale() < 0.999f) {
            nativeUsage |= MetalityFFM.TEX_USAGE_STORAGE_WRITE;
        }
        int safeW = Math.max(1, width);
        int safeH = Math.max(1, height);
        int safeMips = Math.max(1, mipLevels);
        MetalGpuTexture nativeTex = metalDevice.createTexture(safeW, safeH, safeMips, nativeFmt, nativeUsage);
        return new MetalPearlTexture(nativeTex, usage, label, format, safeW, safeH, Math.max(1, depthOrLayers), safeMips);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        if (!(texture instanceof MetalPearlTexture metalTex)) {
            throw new IllegalArgumentException("Expected MetalPearlTexture");
        }
        return new MetalPearlTextureView(metalTex, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        long safeSize = Math.max(16L, size);
        MetalGpuBuffer buf = metalDevice.createBuffer(safeSize, MetalPearlBuffer.isWriteCombined(usage));
        return new MetalPearlBuffer(buf, usage, safeSize);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
        long len = Math.max(16L, data.remaining());
        MetalGpuBuffer buf = metalDevice.createBuffer(len, MetalPearlBuffer.isWriteCombined(usage));
        if (data.remaining() > 0) {
            buf.writeBytes(data.duplicate(), 0L);
        }
        return new MetalPearlBuffer(buf, usage, Math.max(0L, data.remaining()));
    }

    @Override
    public List<String> getLastDebugMessages() {
        return Collections.emptyList();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return debuggingEnabled;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo info) {
        return MetalRenderPipeline.beginCompile(metalDevice, info);
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new MetalQueryPool(size);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    public static Optional<MetalDeviceBackend> activeInstance() {
        return Optional.ofNullable(activeBackend);
    }

    public void flushActiveRenderPass() {
        encoderBackend.submitRenderPass();
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return deviceInfo;
    }

    @Override
    public void close() {
        if (activeBackend == this) {
            activeBackend = null;
        }
        encoderBackend.close();
        if (sequentialQuadIndexBuffer != null) {
            sequentialQuadIndexBuffer.close();
            sequentialQuadIndexBuffer = null;
        }
        if (sequentialFanIndexBuffer != null) {
            sequentialFanIndexBuffer.close();
            sequentialFanIndexBuffer = null;
        }
        publicEngine.close();
        metalDevice.close();
    }
}

package dev.metality.backend;

import dev.metality.api.MetalityCapabilities;
import dev.metality.nativebridge.MetalityFFM;
import dev.metality.raytracing.HardwareRayTracingScene;
import dev.metality.spi.GlslSpirvTranspilerSpi;
import dev.metality.terrain.MetalityTerrainRenderer;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 版本無關之 Apple Silicon 原生 Metal 裝置中樞（置於 :core 模組，無任何 Minecraft / LWJGL 相依）。
 * 負責管理 FFM 原生上下文、3-Frames-in-Flight 生命週期、UMA 資源與 GPU-Driven 地形管線。
 */
public final class MetalGpuDevice implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("Metality/Core");
    private static volatile MetalGpuDevice instance;

    private final MetalityFFM ffm;
    private final MemorySegment nativeCtx;
    private final MetalityFFM.DeviceCapabilities rawCapabilities;
    private final MetalityCapabilities publicCapabilities;
    private final MetalCommandEncoder commandEncoder;
    private MetalityTerrainRenderer terrainRenderer;
    private HardwareRayTracingScene rayTracingScene;
    private volatile GlslSpirvTranspilerSpi glslTranspilerSpi;

    private volatile boolean edrEnabled;
    private volatile int maxFramesInFlight = 3;
    private volatile float shaderRenderScale = 1.0f;
    private long activeFrameId = 0L;
    private boolean frameOpen = false;

    public MetalGpuDevice(Path gameDir, boolean enableEdr) throws Exception {
        this(MetalityFFM.loadFromClasspath(gameDir.resolve("cache").resolve("metality")),
                gameDir.resolve("cache").resolve("metality"), enableEdr);
    }

    public MetalGpuDevice(MetalityFFM ffm, Path psoCacheDir, boolean enableEdr) {
        this.ffm = ffm;
        this.edrEnabled = enableEdr;
        this.nativeCtx = ffm.createContext(psoCacheDir, enableEdr);
        if (MemorySegment.NULL.equals(this.nativeCtx)) {
            throw new IllegalStateException("Metality requires an Apple Silicon Mac (M1 or newer) with Metal 3+.");
        }
        this.rawCapabilities = ffm.getCapabilities(this.nativeCtx);
        this.publicCapabilities = new MetalityCapabilities(
                rawCapabilities.deviceName(),
                rawCapabilities.appleGpuFamily(),
                rawCapabilities.recommendedMaxWorkingSetBytes(),
                rawCapabilities.unifiedMemory(),
                rawCapabilities.argumentBuffersTier2(),
                rawCapabilities.hardwareRayTracing(),
                rawCapabilities.metalFXTemporal(),
                rawCapabilities.hardwareRayTracing() && rawCapabilities.appleGpuFamily() >= 9,
                rawCapabilities.metal4CommandQueue()
        );
        this.commandEncoder = new MetalCommandEncoder(this);

        LOGGER.info("Initialized Native Metal Core on {} (Family: Apple{}, UMA Working Set: {} MB, HWRT: {}, MetalFX: {}, Metal4: {})",
                rawCapabilities.deviceName(),
                rawCapabilities.appleGpuFamily(),
                rawCapabilities.recommendedMaxWorkingSetBytes() / (1024 * 1024),
                rawCapabilities.hardwareRayTracing(),
                rawCapabilities.metalFXTemporal(),
                rawCapabilities.metal4CommandQueue());

        instance = this;
    }

    public static MetalGpuDevice getInstance() {
        return instance;
    }

    /**
     * 將原生 Cocoa NSWindow* 直接綁定至高更新率 EDR CAMetalLayer（視窗系統細節由 :mod 適配層解析）。
     */
    public void bindCocoaWindow(long cocoaNsWindowPtr, int fbWidth, int fbHeight, float contentScale, boolean vsync) {
        if (cocoaNsWindowPtr == 0L) {
            throw new IllegalStateException("Invalid null Cocoa NSWindow pointer!");
        }
        boolean attached = ffm.attachCocoaWindow(nativeCtx, cocoaNsWindowPtr, fbWidth, fbHeight, contentScale, vsync);
        if (!attached) {
            throw new IllegalStateException("Failed to attach CAMetalLayer to Cocoa NSWindow!");
        }
        LOGGER.info("Attached CAMetalLayer ({}x{} @ {}x scale) directly to Cocoa NSWindow.", fbWidth, fbHeight, contentScale);
    }

    public void onFramebufferResize(int fbWidth, int fbHeight, float contentScale) {
        ffm.resizeDrawable(nativeCtx, fbWidth, fbHeight, contentScale);
    }

    public boolean isEdrEnabled() {
        return edrEnabled;
    }

    public void setEdrEnabled(boolean enabled) {
        this.edrEnabled = enabled;
        ffm.setEdrEnabled(nativeCtx, enabled);
    }

    public void setDisplaySync(boolean enabled) {
        ffm.setDisplaySync(nativeCtx, enabled);
    }

    public int maxFramesInFlight() {
        return maxFramesInFlight;
    }

    public void setMaxFramesInFlight(int frames) {
        int clamped = Math.clamp(frames, 2, 3);
        this.maxFramesInFlight = clamped;
        ffm.setMaxFramesInFlight(nativeCtx, clamped);
    }

    public float shaderRenderScale() {
        return shaderRenderScale;
    }

    public void setShaderRenderScale(float scale) {
        float clamped = Math.max(0.5f, Math.min(1.0f, scale));
        this.shaderRenderScale = clamped;
        ffm.setShaderRenderScale(nativeCtx, clamped);
    }

    public synchronized MetalityTerrainRenderer getOrCreateTerrainRenderer() {
        if (terrainRenderer == null) {
            terrainRenderer = new MetalityTerrainRenderer(this);
        }
        return terrainRenderer;
    }

    public MetalityTerrainRenderer terrainRendererIfCreated() {
        return terrainRenderer;
    }

    public void attachRayTracingScene(HardwareRayTracingScene scene) {
        this.rayTracingScene = scene;
    }

    public HardwareRayTracingScene rayTracingSceneIfCreated() {
        return rayTracingScene;
    }

    public void copyTextureToBuffer(MetalGpuTexture srcTex, MetalGpuBuffer dstBuf, long dstOffset, int bytesPerRow, int width, int height) {
        ffm.cmdCopyTextureToBuffer(nativeCtx, srcTex.handle(), dstBuf.handle(), dstOffset, bytesPerRow, width, height);
    }

    public MetalGpuBuffer createBuffer(long sizeBytes, boolean writeCombined) {
        return new MetalGpuBuffer(this, sizeBytes, writeCombined);
    }

    public MetalGpuTexture createTexture(int width, int height, int mipLevels, int format, int usageFlags) {
        return new MetalGpuTexture(this, width, height, mipLevels, format, usageFlags);
    }

    public long getOrCreateSampler(boolean linearMinMag, boolean linearMipmap, boolean repeatWrap, int maxAnisotropy) {
        return ffm.getOrCreateSampler(nativeCtx, linearMinMag, linearMipmap, repeatWrap, maxAnisotropy);
    }

    public long getOrCreateSamplerEx(
            boolean linearMin,
            boolean linearMag,
            boolean repeatU,
            boolean repeatV,
            int maxAnisotropy,
            float maxLod
    ) {
        return ffm.getOrCreateSamplerEx(nativeCtx, linearMin, linearMag, repeatU, repeatV, maxAnisotropy, maxLod);
    }

    public long compileMslLibrary(String label, String mslSource, boolean fastMath) {
        return ffm.compileMslLibrary(nativeCtx, label, mslSource, fastMath);
    }

    public long createRenderPsoEx(
            long vertexLibrary,
            long fragmentLibrary,
            String vertexEntry,
            String fragmentEntry,
            int colorFormat,
            int depthFormat,
            int blendMode,
            int depthCompare,
            int cullMode,
            boolean depthWriteEnabled,
            int colorWriteMask,
            boolean wireframe,
            int topology,
            MetalityFFM.VertexBufferLayoutDesc[] vbLayouts,
            MetalityFFM.VertexAttributeDesc[] attributes
    ) {
        return ffm.createRenderPsoEx(
                nativeCtx, vertexLibrary, fragmentLibrary, vertexEntry, fragmentEntry,
                colorFormat, depthFormat, blendMode, depthCompare, cullMode,
                depthWriteEnabled, colorWriteMask, wireframe, topology, vbLayouts, attributes
        );
    }

    public long createRenderPsoMrt(
            long vertexLibrary,
            long fragmentLibrary,
            String vertexEntry,
            String fragmentEntry,
            int colorAttachmentCount,
            int[] colorFormats,
            int[] blendModes,
            int[] writeMasks,
            int depthFormat,
            int depthCompare,
            int cullMode,
            boolean depthWriteEnabled,
            boolean wireframe,
            int topology,
            MetalityFFM.VertexBufferLayoutDesc[] vbLayouts,
            MetalityFFM.VertexAttributeDesc[] attributes
    ) {
        return ffm.createRenderPsoMrt(
                nativeCtx, vertexLibrary, fragmentLibrary, vertexEntry, fragmentEntry,
                colorAttachmentCount, colorFormats, blendModes, writeMasks,
                depthFormat, depthCompare, cullMode, depthWriteEnabled, wireframe, topology,
                vbLayouts, attributes
        );
    }

    public MetalCommandEncoder commandEncoder() {
        return commandEncoder;
    }

    public synchronized long ensureFrameStarted() {
        if (!frameOpen) {
            activeFrameId = ffm.beginFrame(nativeCtx);
            frameOpen = true;
        }
        return activeFrameId;
    }

    public synchronized long beginFrame() {
        activeFrameId = ffm.beginFrame(nativeCtx);
        frameOpen = true;
        return activeFrameId;
    }

    public synchronized long endFrame(boolean presentSwapchain) {
        long submitted = activeFrameId;
        if (frameOpen) {
            ffm.endFrame(nativeCtx, presentSwapchain);
            frameOpen = false;
        }
        return submitted;
    }

    public boolean waitFrameComplete(long frameId, long timeoutMs) {
        return ffm.waitFrameComplete(nativeCtx, frameId, timeoutMs);
    }

    public long activeFrameId() {
        return activeFrameId;
    }

    public synchronized boolean isFrameOpen() {
        return frameOpen;
    }

    public void flushPipelineCache() {
        ffm.flushPipelineCache(nativeCtx);
    }

    public MetalityFFM.DeviceCapabilities capabilities() {
        return rawCapabilities;
    }

    public MetalityCapabilities publicCapabilities() {
        return publicCapabilities;
    }

    public MetalityFFM ffm() {
        return ffm;
    }

    public MemorySegment nativeContext() {
        return nativeCtx;
    }

    public void registerGlslTranspilerSpi(GlslSpirvTranspilerSpi spi) {
        this.glslTranspilerSpi = spi;
    }

    public Optional<GlslSpirvTranspilerSpi> glslTranspilerSpi() {
        return Optional.ofNullable(glslTranspilerSpi);
    }

    public MetalityFFM.UmaPressureInfo queryUmaPressure() {
        return ffm.queryUmaPressure(nativeCtx);
    }

    @Override
    public void close() {
        if (frameOpen) {
            endFrame(false);
        }
        if (terrainRenderer != null) {
            terrainRenderer.close();
            terrainRenderer = null;
        }
        ffm.flushPipelineCache(nativeCtx);
        ffm.destroyContext(nativeCtx);
        if (instance == this) {
            instance = null;
        }
    }
}

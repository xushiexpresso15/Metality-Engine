package dev.metality.impl;

import dev.metality.api.MetalityApi;
import dev.metality.api.MetalityCapabilities;
import dev.metality.api.MetalityEngine;
import dev.metality.api.MetalityTelemetry;
import dev.metality.api.material.BlockMaterialRegistry;
import dev.metality.api.pipeline.CustomRenderPassContext;
import dev.metality.api.pipeline.CustomRenderPassHook;
import dev.metality.api.pipeline.RenderGraphExtensionRegistry;
import dev.metality.api.raytracing.RayTracingService;
import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.api.shader.ShaderPackService;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import dev.metality.nativebridge.MetalityFFM;
import dev.metality.raytracing.HardwareRayTracingScene;
import dev.metality.shader.LiveShaderComposer;
import dev.metality.shader.UnifiedShaderPackManager;
import dev.metality.terrain.MetalityTerrainRenderer;
import java.lang.foreign.MemorySegment;

/**
 * :core 模組預設實作之對外穩定 MetalityEngine 單例，於裝置初始化後註冊至 MetalityApi。
 */
public final class DefaultMetalityEngine implements MetalityEngine, MetalityTelemetry, AutoCloseable {

    private final MetalGpuDevice gpuDevice;
    private final DefaultBlockMaterialRegistry materialRegistry;
    private final DefaultRenderGraphExtensionRegistry extensionRegistry;
    private final UnifiedShaderPackManager shaderManager;
    private final HardwareRayTracingScene rayTracingScene;

    public DefaultMetalityEngine(MetalGpuDevice gpuDevice) {
        this.gpuDevice = gpuDevice;
        this.materialRegistry = new DefaultBlockMaterialRegistry();
        this.extensionRegistry = new DefaultRenderGraphExtensionRegistry();
        this.shaderManager = new UnifiedShaderPackManager(gpuDevice);
        this.rayTracingScene = new HardwareRayTracingScene(gpuDevice, 4096, false);
        gpuDevice.attachRayTracingScene(this.rayTracingScene);
        MetalityApi.Bootstrap.register(this);
    }

    @Override
    public MetalityCapabilities capabilities() {
        return gpuDevice.publicCapabilities();
    }

    @Override
    public BlockMaterialRegistry materials() {
        return materialRegistry;
    }

    public DefaultBlockMaterialRegistry nativeMaterialRegistry() {
        return materialRegistry;
    }

    @Override
    public RenderGraphExtensionRegistry pipelineExtensions() {
        return extensionRegistry;
    }

    public DefaultRenderGraphExtensionRegistry extensionDispatcher() {
        return extensionRegistry;
    }

    @Override
    public ShaderPackService shaders() {
        return shaderManager;
    }

    public UnifiedShaderPackManager nativeShaders() {
        return shaderManager;
    }

    @Override
    public RayTracingService rayTracing() {
        return rayTracingScene;
    }

    public HardwareRayTracingScene nativeRayTracingScene() {
        return rayTracingScene;
    }

    public LiveShaderComposer liveComposer() {
        return shaderManager.liveComposer();
    }

    public boolean executeWorldShader(
            MetalGpuTexture sceneColor,
            MetalGpuTexture sceneDepth,
            MetalityFFM.WorldFrameUniforms frame
    ) {
        boolean isAperture = (shaderManager.activeFormat() == ShaderPackFormat.APERTURE_TS);
        MemorySegment rtHandle = isAperture
                ? rayTracingScene.nativeHandle()
                : MemorySegment.NULL;
        return shaderManager.executeWorldPipeline(rtHandle, sceneColor, sceneDepth, frame);
    }

    public void dispatchHookStage(CustomRenderPassHook.Stage stage, CustomRenderPassContext context) {
        extensionRegistry.dispatchStage(stage, context);
    }

    @Override
    public MetalityTelemetry telemetry() {
        return this;
    }

    @Override
    public double lastGpuFrameTimeMs() {
        return shaderManager.lastGpuFrameTimeMs();
    }

    @Override
    public float recommendedThermalRenderScale() {
        return shaderManager.recommendedThermalScale();
    }

    @Override
    public int activeSectionCount() {
        MetalityTerrainRenderer tr = gpuDevice.terrainRendererIfCreated();
        if (tr != null) {
            return tr.activeSectionCount();
        }
        return rayTracingScene != null ? rayTracingScene.activeSectionBlasCount() : 0;
    }

    @Override
    public long savedTbdrBytesPerFrame() {
        return shaderManager.activePackInfo()
                .map(ShaderPackService.ActivePackInfo::savedDramBytesPerFrame)
                .orElse(0L);
    }

    @Override
    public long currentAllocatedUmaBytes() {
        return gpuDevice.queryUmaPressure().currentAllocatedBytes();
    }

    @Override
    public int thermalStateLevel() {
        return gpuDevice.queryUmaPressure().thermalStateLevel();
    }

    @Override
    public float umaWorkingSetUtilization() {
        return gpuDevice.queryUmaPressure().utilizationRatio();
    }

    @Override
    public ShaderPackFormat activePackFormat() {
        return shaderManager.activeFormat();
    }

    @Override
    public void close() {
        gpuDevice.attachRayTracingScene(null);
        rayTracingScene.close();
        shaderManager.close();
        MetalityApi.Bootstrap.clear();
    }
}

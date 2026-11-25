package dev.metality.api;

import dev.metality.api.material.BlockMaterialRegistry;
import dev.metality.api.pipeline.RenderGraphExtensionRegistry;
import dev.metality.api.raytracing.RayTracingService;
import dev.metality.api.shader.ShaderPackService;

/**
 * Metality 核心引擎對外服務總介面。
 */
public interface MetalityEngine {

    MetalityCapabilities capabilities();

    BlockMaterialRegistry materials();

    RenderGraphExtensionRegistry pipelineExtensions();

    ShaderPackService shaders();

    RayTracingService rayTracing();

    MetalityTelemetry telemetry();
}

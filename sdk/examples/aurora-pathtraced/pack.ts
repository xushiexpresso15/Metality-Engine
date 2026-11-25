import {
  Format,
  GeometryStage,
  MetalFXMode,
  PipelineConfig,
  RendererConfig,
  StorageHint,
} from "../../src/index";

/**
 * Metality Phase 3 範例路徑追蹤光影包：
 * 針對 Apple Silicon M3/M4/M5 原生硬體光追 BLAS/TLAS + MetalFX Temporal Denoised Scaler 最佳化。
 */
export function configureRenderer(config: RendererConfig): void {
  config.disableShade = false;
  config.ambientOcclusionLevel = 1.0;

  config.shadow.enabled = true;
  config.shadow.resolution = 2048;
  config.shadow.cascades = 3;
  config.shadow.gpuDrivenCulling = true;

  config.appleSilicon.enableEDR = true;
  config.appleSilicon.enableHardwareRayTracing = true;
  config.appleSilicon.thermalAdaptiveScaling = true;
}

export function configurePipeline(pipeline: PipelineConfig): void {
  const { env } = pipeline;

  const albedoTex = pipeline
    .createTexture("pt_albedo")
    .format(Format.RGBA8)
    .size(env.internalWidth, env.internalHeight)
    .storage(StorageHint.ForceMemoryless)
    .build();

  const normalTex = pipeline
    .createTexture("pt_normal")
    .format(Format.RGBA16F)
    .size(env.internalWidth, env.internalHeight)
    .storage(StorageHint.Persistent)
    .build();

  const noisyGiTex = pipeline
    .createTexture("pt_noisy_gi")
    .format(Format.RGBA16F)
    .size(env.internalWidth, env.internalHeight)
    .storage(StorageHint.Persistent)
    .build();

  const finalDenoisedTex = pipeline
    .createTexture("pt_denoised_hdr")
    .format(Format.RGBA16F)
    .size(env.screenWidth, env.screenHeight)
    .storage(StorageHint.Persistent)
    .build();

  pipeline
    .forStage(GeometryStage.TerrainSolid)
    .vertex("shaders/terrain.slang", "vsTerrainMain")
    .fragment("shaders/terrain.slang", "fsTerrainSolid")
    .target(0, albedoTex)
    .target(1, normalTex)
    .blend(false)
    .register();

  pipeline
    .createCommandList("DEFERRED")
    .computeDispatch({
      name: "hw_raytraced_gi",
      shader: "shaders/raytracing_gi.slang",
      entry: "csPathtraceGi",
      workgroupSize: [8, 8, 1],
      reads: [albedoTex, normalTex],
      writes: [noisyGiTex],
      bindRayTracingScene: true,
    });

  pipeline
    .createCommandList("FINAL")
    .applyMetalFX({
      mode: MetalFXMode.TemporalDenoised,
      inputColor: noisyGiTex,
      depth: pipeline.mainDepthTex,
      motionVectors: pipeline.builtinMotionVectors,
      outputColor: finalDenoisedTex,
      enableRayTracingDenoiser: true,
    })
    .present(finalDenoisedTex);
}

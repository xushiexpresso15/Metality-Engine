import {
  CommandList,
  Format,
  GeometryStage,
  MetalFXMode,
  PipelineConfig,
  RendererConfig,
  StorageHint,
} from "../../src/index";

/**
 * Aurora TBDR — Metality 官方示範光影（Aperture 相容結構 + Apple Silicon 原生加速）
 */
export function configureRenderer(config: RendererConfig): void {
  config.disableShade = true;
  config.ambientOcclusionLevel = 0.0;
  config.render.sun = false;
  config.render.vanillaClouds = false;

  config.shadow.enabled = true;
  config.shadow.resolution = 2048;
  config.shadow.cascades = 3;
  config.shadow.gpuDrivenCulling = true;

  // 啟用 MacBook Pro XDR 高動態範圍、M3+ 硬體光追與筆電溫控自適應縮放
  config.appleSilicon.enableEDR = true;
  config.appleSilicon.enableHardwareRayTracing = true;
  config.appleSilicon.thermalAdaptiveScaling = true;
}

export function configurePipeline(pipeline: PipelineConfig): void {
  const { internalWidth, internalHeight, screenWidth, screenHeight } = pipeline.env;

  // 1. 建立 G-Buffer：標記為 AutoTileOptimal，引擎會自動將其放入 0MB 晶片內 Memoryless Tile SRAM
  const albedoTex = pipeline
    .createTexture("gbuffer_albedo")
    .format(Format.RGBA8_SRGB)
    .size(internalWidth, internalHeight)
    .storage(StorageHint.AutoTileOptimal)
    .build();

  const normalTex = pipeline
    .createTexture("gbuffer_normal")
    .format(Format.RGB10A2)
    .size(internalWidth, internalHeight)
    .storage(StorageHint.AutoTileOptimal)
    .build();

  const materialTex = pipeline
    .createTexture("gbuffer_labpbr")
    .format(Format.RGBA8)
    .size(internalWidth, internalHeight)
    .storage(StorageHint.AutoTileOptimal)
    .build();

  const litHdrTex = pipeline
    .createTexture("scene_hdr_internal")
    .format(Format.RGBA16F)
    .size(internalWidth, internalHeight)
    .storage(StorageHint.Persistent)
    .build();

  const finalDisplayTex = pipeline
    .createTexture("final_edr_display")
    .format(Format.RGBA16F)
    .size(screenWidth, screenHeight)
    .storage(StorageHint.Persistent)
    .build();

  // 2. 嚴格拆離 Solid 與 Cutout 方塊以 100% 保留 Apple GPU 硬體隱藏面消除 (HSR)
  pipeline
    .forStage(GeometryStage.TerrainSolid)
    .vertex("shaders/terrain.slang", "vsTerrain")
    .fragment("shaders/terrain.slang", "fsTerrainSolid")
    .target(0, albedoTex)
    .target(1, normalTex)
    .target(2, materialTex)
    .blend(false)
    .register();

  pipeline
    .forStage(GeometryStage.TerrainCutout)
    .vertex("shaders/terrain.slang", "vsTerrain")
    .fragment("shaders/terrain.slang", "fsTerrainCutout")
    .target(0, albedoTex)
    .target(1, normalTex)
    .target(2, materialTex)
    .blend(false)
    .register();

  // 3. 延遲光照：samplesNeighbors = false，自動與 Terrain Pass 融合進同一個 Tile RenderCommandEncoder！
  const deferredCommands: CommandList = pipeline.createCommandList("DEFERRED");
  deferredCommands.fullscreenPass({
    name: "tbdr_deferred_lighting",
    shader: "shaders/deferred_lighting.slang",
    entry: "fsDeferredMain",
    reads: [albedoTex, normalTex, materialTex, pipeline.solidDepthTex],
    writes: [litHdrTex],
    samplesNeighbors: false,
  });

  // 4. 透過 MetalFX Temporal Upscaler 放大至 Retina 原生解析度並輸出 XDR/EDR 畫面
  const finalCommands: CommandList = pipeline.createCommandList("FINAL");
  finalCommands
    .applyMetalFX({
      mode: pipeline.env.supportsMetalFXTemporal
        ? MetalFXMode.Temporal
        : MetalFXMode.Spatial,
      inputColor: litHdrTex,
      depth: pipeline.solidDepthTex,
      motionVectors: pipeline.builtinMotionVectors,
      outputColor: finalDisplayTex,
      enableRayTracingDenoiser: pipeline.env.supportsHardwareRayTracing,
      enableFrameInterpolation: pipeline.env.supportsMetal4,
    })
    .present(finalDisplayTex);
}

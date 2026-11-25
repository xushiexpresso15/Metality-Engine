/**
 * @metality/sdk
 *
 * 對齊 Iris Aperture 核心 API (`configureRenderer` / `configurePipeline` + Slang)，
 * 並針對 Apple Silicon (M1~M5) 原生擴充 TBDR Tile Memory、MetalFX 與硬體光追支援。
 */

export enum Format {
  RGBA8 = "RGBA8_UNORM",
  RGBA8_SRGB = "RGBA8_SRGB",
  RGB10A2 = "RGB10A2_UNORM",
  RG11B10F = "RG11B10_FLOAT",
  RGBA16F = "RGBA16_FLOAT",
  R16F = "R16_FLOAT",
  R8 = "R8_UNORM",
  R32F = "R32_FLOAT",
  DEPTH32F = "DEPTH32_FLOAT",
}

export enum GeometryStage {
  TerrainSolid = "TERRAIN_SOLID",       // 純不透明方塊（強制保護 Apple GPU 硬體 HSR）
  TerrainCutout = "TERRAIN_CUTOUT",     // 樹葉、草叢等鏤空方塊
  TerrainTranslucent = "TERRAIN_TRANSLUCENT", // 水體、染色玻璃（支援 OIT / 光追折射）
  Entities = "ENTITIES",
  BlockEntities = "BLOCK_ENTITIES",
  Particles = "PARTICLES",
  Sky = "SKY",
}

export enum StorageHint {
  /** 由 Metality TBDR 編譯器自動分析生命週期，若讀寫位於同一 Tile Group 自動轉為 0MB Memoryless */
  AutoTileOptimal = "AUTO_TILE_OPTIMAL",
  /** 強制宣告為晶片內 Memoryless（僅供同一融合 Render Pass 內的子階段讀取） */
  ForceMemoryless = "FORCE_MEMORYLESS",
  /** 跨幀保留（例如 TAA 歷史緩衝、曝光統計或需做鄰近像素採樣的模糊貼圖） */
  Persistent = "PERSISTENT",
}

export enum MetalFXMode {
  Off = "OFF",
  Spatial = "METALFX_SPATIAL",
  Temporal = "METALFX_TEMPORAL",
  TemporalDenoised = "METALFX_TEMPORAL_DENOISED",
}

export interface ShadowConfig {
  enabled: boolean;
  resolution: number;
  cascades?: 1 | 2 | 3 | 4;
  /** 啟用引擎底層 Metal Compute + Indirect Command Buffer 陰影視錐剔除 */
  gpuDrivenCulling?: boolean;
}

export interface RenderFlags {
  sun: boolean;
  moon: boolean;
  stars: boolean;
  vanillaClouds: boolean;
  vignette: boolean;
}

export interface AppleSiliconRendererExtensions {
  /** 啟用 MacBook Pro Liquid Retina XDR 高動態範圍輸出（峰值超過 1000 nits） */
  enableEDR: boolean;
  /** 當 M3/M4/M5 硬體支援時，自動維護區塊 BLAS/TLAS 加速結構供 Slang 呼叫 */
  enableHardwareRayTracing: boolean;
  /** 配合筆電溫控（NSProcessInfoThermalState）自動平滑調節內部渲染倍率 */
  thermalAdaptiveScaling: boolean;
}

/**
 * 對齊 Aperture `configureRenderer(config)` 規範，並提供 `appleSilicon` 命名空間擴充
 */
export interface RendererConfig {
  disableShade: boolean;
  ambientOcclusionLevel: number;
  shadow: ShadowConfig;
  render: RenderFlags;
  appleSilicon: AppleSiliconRendererExtensions;
}

export interface TextureHandle {
  readonly name: string;
  readonly format: Format;
  readonly width: number;
  readonly height: number;
  readonly storage: StorageHint;
}

export interface TextureBuilder {
  format(fmt: Format): TextureBuilder;
  size(width: number, height: number): TextureBuilder;
  /** Apple Silicon 擴充：依輸出螢幕解析度動態縮放（例如 0.5x 半解析度光追、0.25x Bloom） */
  scale(factor: number): TextureBuilder;
  /** Apple Silicon 擴充：提示或強制駐留於晶片內 Tile SRAM (.memoryless) */
  storage(hint: StorageHint): TextureBuilder;
  clear(clearColor?: [number, number, number, number]): TextureBuilder;
  build(): TextureHandle;
}

export interface ObjectShaderBuilder {
  vertex(slangPath: string, entry?: string): ObjectShaderBuilder;
  fragment(slangPath: string, entry?: string): ObjectShaderBuilder;
  target(attachmentIndex: number, texture: TextureHandle): ObjectShaderBuilder;
  blend(enabled: boolean): ObjectShaderBuilder;
  register(): void;
}

export interface FullscreenPassSpec {
  name: string;
  shader: string;
  entry?: string;
  reads: TextureHandle[];
  writes: TextureHandle[];
  /**
   * 關鍵 TBDR 最佳化提示：
   * 若為 false（預設，僅讀取當前像素 uv），引擎將自動把此 Pass 與前一階段融合進 Tile Memory！
   * 若為 true（如高斯模糊、SSAO 鄰近採樣），引擎會自動切分 RenderCommandEncoder。
   */
  samplesNeighbors?: boolean;
}

export interface ComputePassSpec {
  name: string;
  shader: string;
  entry?: string;
  workgroupSize: [number, number, number];
  reads?: TextureHandle[];
  writes?: TextureHandle[];
  /** 是否允許綁定引擎自動維護的硬體光追 TLAS（M3+ 原生光追 / M1-M2 Voxel DDA 降級） */
  bindRayTracingScene?: boolean;
}

export interface MetalFXUpscaleSpec {
  mode: MetalFXMode;
  inputColor: TextureHandle;
  depth?: TextureHandle;
  motionVectors?: TextureHandle;
  outputColor: TextureHandle;
  /** Metal 4 / M4+ 原生光追降噪整合 */
  enableRayTracingDenoiser?: boolean;
  /** Metal 4 原生 ProMotion 120Hz 補幀 */
  enableFrameInterpolation?: boolean;
}

export interface CommandList {
  fullscreenPass(spec: FullscreenPassSpec): CommandList;
  computeDispatch(spec: ComputePassSpec): CommandList;
  /** Apple Silicon 原生擴充：硬體加速 MetalFX 超解析度、光追降噪與補幀 */
  applyMetalFX(spec: MetalFXUpscaleSpec): CommandList;
  present(finalTexture: TextureHandle): void;
}

export interface DeviceEnvironment {
  readonly screenWidth: number;
  readonly screenHeight: number;
  readonly internalWidth: number;
  readonly internalHeight: number;
  readonly appleGpuFamily: number;
  readonly supportsHardwareRayTracing: boolean;
  readonly supportsMetalFXTemporal: boolean;
  readonly supportsMetal4: boolean;
}

/**
 * 對齊 Aperture `configurePipeline(pipeline)` 規範
 */
export interface PipelineConfig {
  readonly env: DeviceEnvironment;
  readonly mainDepthTex: TextureHandle;
  readonly solidDepthTex: TextureHandle;
  readonly builtinMotionVectors: TextureHandle;
  readonly builtinSceneColor: TextureHandle;
  readonly builtinShadowMap: TextureHandle;

  createTexture(name: string): TextureBuilder;
  forStage(stage: GeometryStage): ObjectShaderBuilder;
  createCommandList(phase: "DEFERRED" | "COMPOSITE" | "FINAL"): CommandList;
}

export declare function defineOption(
  name: string,
  defaultValue: string | number | boolean,
  allowedValues?: Array<string | number | boolean>,
  slider?: boolean,
  group?: string
): void;

export declare function defineProfile(
  name: string,
  overrides: Record<string, string | number | boolean>
): void;

export interface ApertureShaderPackModule {
  configureRenderer(config: RendererConfig): void;
  configurePipeline(pipeline: PipelineConfig): void;
}

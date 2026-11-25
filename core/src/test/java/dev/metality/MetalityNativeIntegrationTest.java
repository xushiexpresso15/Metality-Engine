package dev.metality;

import dev.metality.api.MetalityApi;
import dev.metality.api.material.BlockMaterialDescriptor;
import dev.metality.api.material.RenderLayerKind;
import dev.metality.api.pipeline.CustomRenderPassContext;
import dev.metality.api.pipeline.CustomRenderPassHook;
import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.api.shader.ShaderPackService;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.impl.DefaultBlockMaterialRegistry;
import dev.metality.impl.DefaultMetalityEngine;
import dev.metality.nativebridge.MetalityFFM;
import dev.metality.shader.MetalFXUpscaler;
import dev.metality.terrain.ChunkMeshingScratch;
import dev.metality.terrain.MetalityTerrainRenderer;
import dev.metality.backend.MetalGpuTexture;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenJDK 25 + Project Panama FFM + :api / :core 版本無關引擎與 Apple Silicon M4 Max 完整整合測試。
 */
public final class MetalityNativeIntegrationTest {

    @Test
    void testJava25PanamaFfmEngineApiAndRegistries() throws Exception {
        String dylibProp = System.getProperty("metality.native.dylib");
        assertNotNull(dylibProp, "metality.native.dylib system property must be set");
        Path dylibPath = Path.of(dylibProp);
        assertTrue(Files.exists(dylibPath), "Compiled libmetality_native.dylib missing at " + dylibPath);

        String packProp = System.getProperty("metality.example.pack");
        assertNotNull(packProp, "metality.example.pack system property must be set");
        Path examplePack = Path.of(packProp);
        assertTrue(Files.exists(examplePack.resolve("pack.ts")), "Example aurora-tbdr pack.ts missing");

        MetalityFFM ffm = new MetalityFFM(dylibPath);
        Path tmpCache = Files.createTempDirectory("metality_core_test_cache");

        try (MetalGpuDevice device = new MetalGpuDevice(ffm, tmpCache, true);
             DefaultMetalityEngine engine = new DefaultMetalityEngine(device)) {

            assertTrue(MetalityApi.isAvailable());
            assertTrue(MetalityApi.get().capabilities().unifiedMemory());
            assertTrue(MetalityApi.get().capabilities().argumentBuffersTier2());
            assertTrue(MetalityApi.get().capabilities().appleGpuFamily() >= 7);

            // 1. 驗證 :api 擴充材質表註冊與原生 18-byte Palette 扁平烘烤 + NEON Greedy Mesher
            DefaultBlockMaterialRegistry materials = engine.nativeMaterialRegistry();
            int stoneId = materials.register(BlockMaterialDescriptor.builder("minecraft:stone")
                    .allFacesSprite(10)
                    .layer(RenderLayerKind.SOLID)
                    .opaqueFullCube(true)
                    .build());
            int leavesId = materials.register(BlockMaterialDescriptor.builder("minecraft:oak_leaves")
                    .allFacesSprite(20)
                    .layer(RenderLayerKind.CUTOUT)
                    .opaqueFullCube(false)
                    .waveTag(2)
                    .build());
            int waterId = materials.register(BlockMaterialDescriptor.builder("minecraft:water")
                    .allFacesSprite(30)
                    .layer(RenderLayerKind.TRANSLUCENT)
                    .opaqueFullCube(false)
                    .waveTag(3)
                    .alpha(180)
                    .indexOfRefraction(1.333f)
                    .build());

            assertEquals(stoneId, materials.resolveId("minecraft:stone"));
            assertEquals(1, materials.resolveId("unknown_mod:mystery_block")); // 安全後備材質

            try (ChunkMeshingScratch scratch = new ChunkMeshingScratch();
                 Arena paletteArena = Arena.ofConfined()) {
                MemorySegment nativePalette = materials.bakeNativePalette(paletteArena);

                scratch.blockMaterialIds.fill((byte) 0);
                scratch.packedLight.fill((byte) 0xF0);
                scratch.biomeTintRgb.fill((byte) 0xFF);

                scratch.blockMaterialIds.set(ValueLayout.JAVA_SHORT, padOffset(2, 2, 2), (short) stoneId);
                scratch.blockMaterialIds.set(ValueLayout.JAVA_SHORT, padOffset(6, 6, 6), (short) leavesId);
                scratch.blockMaterialIds.set(ValueLayout.JAVA_SHORT, padOffset(10, 10, 10), (short) waterId);

                scratch.bindPalette(nativePalette, materials.size(), true);
                assertTrue(ffm.meshChunkSectionNeon(scratch.inputStruct, scratch.outputStruct));
                assertEquals(6, scratch.solidQuadCount(), "Isolated stone must emit 6 solid quads");
                assertEquals(6, scratch.cutoutQuadCount(), "Isolated leaf block must emit 6 cutout quads");
                assertEquals(6, scratch.translucentQuadCount(), "Isolated water block must emit 6 translucent quads");
                assertEquals(2.0f, scratch.aabbMin(0));
                assertEquals(11.0f, scratch.aabbMax(2));
            }

            // 2. 驗證 RenderGraphExtensionRegistry 擴充鉤子註冊與派發
            AtomicInteger hookCalls = new AtomicInteger(0);
            MetalityApi.get().pipelineExtensions().registerHook(
                    "distant_horizons_lod_test",
                    CustomRenderPassHook.Stage.POST_SOLID_TERRAIN,
                    ctx -> hookCalls.incrementAndGet()
            );
            assertEquals(1, MetalityApi.get().pipelineExtensions().totalRegisteredHooks());
            engine.extensionDispatcher().dispatchStage(
                    CustomRenderPassHook.Stage.POST_SOLID_TERRAIN,
                    new CustomRenderPassContext() {
                        @Override public long frameIndex() { return 1L; }
                        @Override public int viewportWidth() { return 1920; }
                        @Override public int viewportHeight() { return 1080; }
                        @Override public float[] viewProjectionMatrix() { return new float[16]; }
                        @Override public double cameraWorldX() { return 0; }
                        @Override public double cameraWorldY() { return 64; }
                        @Override public double cameraWorldZ() { return 0; }
                        @Override public void setCustomUniformFloat(String u, float v) {}
                        @Override public void setCustomUniformVec4(String u, float x, float y, float z, float w) {}
                    }
            );
            assertEquals(1, hookCalls.get());
            assertTrue(MetalityApi.get().pipelineExtensions().unregisterHook("distant_horizons_lod_test"));

            // 3. 驗證 ShaderPackService 載入 Aperture aurora-tbdr/pack.ts 並編譯 .slang -> Apple GPU PSO
            ShaderPackService.ActivePackInfo packInfo =
                    MetalityApi.get().shaders().loadPack(examplePack, 3456, 2234, 0.67f);
            assertEquals("aurora-tbdr", packInfo.packName());
            assertEquals(3, packInfo.memorylessTextures(), "3 G-Buffer textures must become 0MB Memoryless");
            assertTrue(packInfo.compiledPsos() >= 3, "Slang shaders must compile to Metal PSOs");
            assertTrue(packInfo.savedDramBytesPerFrame() > 30L * 1024L * 1024L);
            assertTrue(MetalityApi.get().telemetry().savedTbdrBytesPerFrame() == packInfo.savedDramBytesPerFrame());
            assertNotNull(MetalFXUpscaler.class.getName());
        }
    }

    @Test
    void testPhase3HardwareRayTracingAndMetalFxDenoiser() throws Exception {
        Path dylibPath = Path.of(System.getProperty("metality.native.dylib"));
        Path pathtracedPack = Path.of(System.getProperty("metality.example.pack"))
                .getParent().resolve("aurora-pathtraced");
        assertTrue(Files.exists(pathtracedPack.resolve("pack.ts")), "Missing aurora-pathtraced pack.ts");

        MetalityFFM ffm = new MetalityFFM(dylibPath);
        Path tmpCache = Files.createTempDirectory("metality_phase3_java_cache");

        try (MetalGpuDevice device = new MetalGpuDevice(ffm, tmpCache, true);
             DefaultMetalityEngine engine = new DefaultMetalityEngine(device)) {

            var rt = MetalityApi.get().rayTracing();
            assertEquals(device.capabilities().hardwareRayTracing(), rt.isHardwareAccelerated());
            rt.setEnabled(true);
            assertTrue(rt.isEnabled());

            // 1. 載入 aurora-pathtraced pack.ts（Compute HWRT + MetalFX Temporal Denoised Scaler）
            ShaderPackService.ActivePackInfo packInfo =
                    MetalityApi.get().shaders().loadPack(pathtracedPack, 1280, 720, 0.5f);
            assertEquals("aurora-pathtraced", packInfo.packName());
            assertTrue(packInfo.hardwareRayTracingEnabled());
            assertTrue(packInfo.edrEnabled());
            assertTrue(packInfo.compiledPsos() >= 2);

            // 2. 透過 MetalityTerrainRenderer 上傳區塊網格並驗證自動更新 RT BLAS
            DefaultBlockMaterialRegistry materials = engine.nativeMaterialRegistry();
            int stoneId = materials.register(BlockMaterialDescriptor.builder("minecraft:stone")
                    .allFacesSprite(1).layer(RenderLayerKind.SOLID).opaqueFullCube(true).build());

            try (ChunkMeshingScratch scratch = new ChunkMeshingScratch();
                 Arena palArena = Arena.ofConfined()) {
                MemorySegment palette = materials.bakeNativePalette(palArena);
                scratch.blockMaterialIds.fill((byte) 0);
                scratch.packedLight.fill((byte) 0xF0);
                scratch.biomeTintRgb.fill((byte) 0xE0);
                for (int x = 2; x <= 12; ++x) {
                    for (int z = 2; z <= 12; ++z) {
                        scratch.blockMaterialIds.set(ValueLayout.JAVA_SHORT, padOffset(x, 2, z), (short) stoneId);
                    }
                }
                scratch.bindPalette(palette, materials.size(), true);
                assertTrue(ffm.meshChunkSectionNeon(scratch.inputStruct, scratch.outputStruct));

                MetalityTerrainRenderer terrain = device.getOrCreateTerrainRenderer();
                terrain.uploadMeshedSection(0, 0, 0, scratch);
                assertEquals(1, rt.activeSectionBlasCount());

                // 驗證 3D 棋盤格高密度 Cutout (>4096 quads) 不會觸發 IndexOutOfBoundsException，且 clearAllSections() 正常清空
                scratch.blockMaterialIds.fill((byte) 0);
                int cutoutDenseId = materials.register(BlockMaterialDescriptor.builder("minecraft:oak_fence")
                        .allFacesSprite(21).layer(RenderLayerKind.CUTOUT).opaqueFullCube(false).build());
                palette = materials.bakeNativePalette(palArena);
                for (int y = 0; y < 16; ++y) {
                    for (int z = 0; z < 16; ++z) {
                        for (int x = 0; x < 16; ++x) {
                            if (((x + y + z) & 1) == 0) {
                                scratch.blockMaterialIds.set(ValueLayout.JAVA_SHORT, padOffset(x, y, z), (short) cutoutDenseId);
                            }
                        }
                    }
                }
                scratch.bindPalette(palette, materials.size(), true);
                assertTrue(ffm.meshChunkSectionNeon(scratch.inputStruct, scratch.outputStruct));
                assertEquals(12288, scratch.cutoutQuadCount(), "Checkerboard section must produce 12288 cutout quads");
                int denseSlot = terrain.uploadMeshedSection(1, 0, 0, scratch);
                assertTrue(denseSlot >= 0);
                terrain.clearAllSections();
                assertEquals(0, terrain.activeSectionCount());
            }

            // 3. 在真實 Apple Silicon GPU 排程 TLAS 重建 + PathTrace GI Compute + MetalFX Denoiser
            int rw = 320, rh = 180, ow = 640, oh = 360;
            int rtUsage = MetalityFFM.TEX_USAGE_SAMPLED | MetalityFFM.TEX_USAGE_STORAGE_WRITE;
            try (MetalGpuTexture rad = device.createTexture(rw, rh, 1, MetalityFFM.FORMAT_RGBA16_FLOAT, rtUsage | MetalityFFM.TEX_USAGE_RENDER_TARGET);
                 MetalGpuTexture dep = device.createTexture(rw, rh, 1, MetalityFFM.FORMAT_R32_FLOAT, rtUsage);
                 MetalGpuTexture mot = device.createTexture(rw, rh, 1, MetalityFFM.FORMAT_RG16_FLOAT, rtUsage);
                 MetalGpuTexture dif = device.createTexture(rw, rh, 1, MetalityFFM.FORMAT_RGBA8_UNORM, rtUsage);
                 MetalGpuTexture spc = device.createTexture(rw, rh, 1, MetalityFFM.FORMAT_RGBA8_UNORM, rtUsage);
                 MetalGpuTexture nrm = device.createTexture(rw, rh, 1, MetalityFFM.FORMAT_RGBA16_FLOAT, rtUsage);
                 MetalGpuTexture rgh = device.createTexture(rw, rh, 1, MetalityFFM.FORMAT_R16_FLOAT, rtUsage);
                 MetalGpuTexture out = device.createTexture(ow, oh, 1, MetalityFFM.FORMAT_RGBA16_FLOAT, rtUsage | MetalityFFM.TEX_USAGE_RENDER_TARGET);
                 MetalFXUpscaler denoiser = new MetalFXUpscaler(
                         device, MetalityFFM.UPSCALE_TEMPORAL_DENOISED,
                         rw, rh, ow, oh,
                         MetalityFFM.FORMAT_RGBA16_FLOAT, MetalityFFM.FORMAT_R32_FLOAT,
                         MetalityFFM.FORMAT_RG16_FLOAT, MetalityFFM.FORMAT_RGBA16_FLOAT,
                         true, false)) {

                long fid = device.beginFrame();
                var rtScene = engine.nativeRayTracingScene();
                assertTrue(rtScene.rebuildTlas(8.0f, 16.0f, 8.0f));
                float[] invVp = new float[]{
                        1f, 0f, 0f, 0f,
                        0f, 1f, 0f, 0f,
                        0f, 0f, -1f, 0f,
                        8f, 3f, 8f, 1f
                };
                assertTrue(rtScene.dispatchGlobalIllumination(
                        invVp, new float[]{8f, 16f, 8f}, new float[]{0.6f, 0.8f, 0f},
                        rw, rh, rad, dep, mot, dif, spc, nrm, rgh));
                assertTrue(denoiser.encodeDenoised(
                        rad, dep, mot, dif, spc, nrm, rgh, out,
                        0.0f, 0.0f, 1.0f, 1.0f, true));
                device.endFrame(false);
                assertTrue(device.waitFrameComplete(fid, 5000));
            }
        }
    }

    @Test
    void testPhase4LegacyIrisPackProfilesUmaTelemetryAndLiveComposer() throws Exception {
        Path dylib = Path.of(System.getProperty("metality.native.dylib"));
        Path legacyPack = Path.of(System.getProperty("metality.legacy.example.pack"));
        assertTrue(Files.exists(legacyPack.resolve("shaders/shaders.properties")));

        Path cacheDir = Files.createTempDirectory("metality-phase4-core-cache");
        MetalityFFM ffm = new MetalityFFM(dylib);

        try (MetalGpuDevice device = new MetalGpuDevice(ffm, cacheDir, true);
             DefaultMetalityEngine engine = new DefaultMetalityEngine(device)) {

            ShaderPackService shaders = engine.shaders();
            ShaderPackService.ActivePackInfo info = shaders.loadPack(legacyPack, 1920, 1200, 1.0f);
            assertEquals(ShaderPackFormat.LEGACY_IRIS_GLSL, info.format());
            assertEquals(ShaderPackFormat.LEGACY_IRIS_GLSL, engine.telemetry().activePackFormat());
            assertTrue(info.totalPasses() >= 4, "Expected at least 4 Iris passes");
            assertTrue(info.fusedGroups() < info.totalPasses(), "TBDR must fuse non-neighbor passes");
            assertTrue(info.savedDramBytesPerFrame() > 0L, "TBDR must save DRAM bandwidth");
            assertTrue(shaders.activePackOptions().size() >= 6, "Expected >= 6 parsed GLSL options");
            assertEquals(4, shaders.activePackProfiles().size(), "Expected LOW/MEDIUM/HIGH/ULTRA profiles");

            // 切換 Profile 與 Option 必須即時驅動 LiveShaderComposer 參數
            shaders.applyProfile("LOW");
            assertEquals(0.0f, engine.liveComposer().params().bloomStrength(), 1e-4f);
            assertEquals(0.0f, engine.liveComposer().params().ssaoStrength(), 1e-4f);

            shaders.applyProfile("ULTRA");
            assertTrue(engine.liveComposer().params().bloomStrength() > 0.2f);
            assertTrue(engine.liveComposer().params().ssaoStrength() > 0.5f);
            assertTrue(engine.liveComposer().params().godrayStrength() > 0.7f);

            shaders.updateOptions(Map.of("TONEMAP_EXPOSURE", "1.35"));
            assertEquals(1.35f, engine.liveComposer().params().exposure(), 1e-4f);

            // UMA & 溫控遙測
            assertTrue(engine.telemetry().thermalStateLevel() >= 0 && engine.telemetry().thermalStateLevel() <= 3);
            assertTrue(engine.telemetry().umaWorkingSetUtilization() >= 0.0f);

            // LiveShaderComposer 真實 GPU 執行與 Readback 驗證
            int w = 128, h = 96;
            int rtUsage = MetalityFFM.TEX_USAGE_SAMPLED | MetalityFFM.TEX_USAGE_RENDER_TARGET;
            try (MetalGpuTexture colorTex = device.createTexture(w, h, 1, MetalityFFM.FORMAT_RGBA8_UNORM, rtUsage);
                 MetalGpuTexture depthTex = device.createTexture(w, h, 1, MetalityFFM.FORMAT_DEPTH32_FLOAT, rtUsage)) {
                MetalityFFM.SharedUmaAllocation rb = ffm.allocateZeroCopyBuffer(device.nativeContext(), (long) w * h * 4L, false);
                try {
                    long fid = device.beginFrame();
                    ffm.cmdClearColorDepth(device.nativeContext(), colorTex.handle(), 0.70f, 0.50f, 0.32f, 1.0f, depthTex.handle(), 0.0);
                    ffm.cmdClearColorDepthRect(device.nativeContext(), colorTex.handle(), 0.20f, 0.35f, 0.20f, 1.0f, depthTex.handle(), 0.55, 0, 0, 0, w / 2, h / 2);

                    float[] invProj = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1};
                    float[] viewRot = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
                    float[] proj = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1, -1, 0, 0, -0.1f, 0};
                    assertTrue(engine.liveComposer().execute(
                            colorTex, depthTex, invProj, viewRot, proj,
                            0.0f, 0.2f, -0.98f, 0.7f, 0.5f, 0.3f, 2.0f, 0.0f, true));
                    ffm.cmdCopyTextureToBuffer(device.nativeContext(), colorTex.handle(), rb.handle(), 0L, w * 4, w, h);
                    device.endFrame(false);
                    assertTrue(device.waitFrameComplete(fid, 5000));

                    int terrainIdx = ((h / 4) * w + (w / 4)) * 4;
                    int skyIdx = ((h * 3 / 4) * w + (w / 2)) * 4;
                    int terrainR = Byte.toUnsignedInt(rb.cpuSegment().get(ValueLayout.JAVA_BYTE, terrainIdx));
                    int skyR = Byte.toUnsignedInt(rb.cpuSegment().get(ValueLayout.JAVA_BYTE, skyIdx));
                    assertTrue(skyR > terrainR, "Sunlit godray sky R (" + skyR + ") must exceed shaded terrain R (" + terrainR + ")");
                } finally {
                    ffm.retireBuffer(device.nativeContext(), rb.handle());
                }
            }
            assertTrue(engine.telemetry().currentAllocatedUmaBytes() > 0L);

            // Meteor Dusk (Non-RT) & Meteor Zenith (RT) End-to-End API + GPU Execution
            String duskProp = System.getProperty("metality.meteor.dusk.pack", System.getProperty("metality.meteor.std.pack"));
            String zenithProp = System.getProperty("metality.meteor.zenith.pack", System.getProperty("metality.meteor.rt.pack"));
            if (duskProp != null && zenithProp != null
                    && Files.exists(Path.of(duskProp, "pack.ts"))
                    && Files.exists(Path.of(zenithProp, "pack.ts"))) {
                Path duskDir = Path.of(duskProp);
                Path zenithDir = Path.of(zenithProp);
                ShaderPackService.ActivePackInfo duskInfo = shaders.loadPack(duskDir, w, h, 1.0f);
                assertEquals(ShaderPackFormat.APERTURE_TS, duskInfo.format());
                assertFalse(duskInfo.hardwareRayTracingEnabled(), "Meteor Dusk must NOT enable Hardware Ray Tracing");
                assertEquals(13, duskInfo.compiledPsos(), "Meteor Dusk must compile 13 Non-RT PSOs");
                assertEquals(24, shaders.activePackOptions().size());
                assertEquals(4, shaders.activePackProfiles().size());
                shaders.applyProfile("LOW");
                assertTrue(shaders.activePackOptions().stream().anyMatch(o -> "ENABLE_WATER_SHADER".equals(o.name()) && "false".equals(o.currentValue())),
                        "LOW profile must disable ENABLE_WATER_SHADER");
                shaders.applyProfile("ULTRA");
                assertTrue(shaders.activePackOptions().stream().anyMatch(o -> "ENABLE_WATER_SHADER".equals(o.name()) && "true".equals(o.currentValue())),
                        "ULTRA profile must enable ENABLE_WATER_SHADER");

                int rtUsage2 = MetalityFFM.TEX_USAGE_SAMPLED | MetalityFFM.TEX_USAGE_RENDER_TARGET | MetalityFFM.TEX_USAGE_STORAGE_WRITE;
                try (MetalGpuTexture mColor = device.createTexture(w, h, 1, MetalityFFM.FORMAT_RGBA8_UNORM, rtUsage2);
                     MetalGpuTexture mDepth = device.createTexture(w, h, 1, MetalityFFM.FORMAT_DEPTH32_FLOAT, rtUsage2)) {
                    float[] id = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
                    float[] invProj = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, -20.0f, 0, 0, -1, 0};
                    float[] proj = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, -1, 0, 0, 0.05f, 0};

                    long fidDusk = device.beginFrame();
                    ffm.cmdClearColorDepth(device.nativeContext(), mColor.handle(), 0.25f, 0.45f, 0.90f, 1.0f, mDepth.handle(), 0.0);
                    MetalityFFM.WorldFrameUniforms duskFrame = new MetalityFFM.WorldFrameUniforms(
                            invProj, proj, id, id, invProj, proj,
                            new float[]{8f, 65f, 10f, 1f},
                            new float[]{0.2f, 0.1f, -0.95f, 1f},
                            new float[]{-0.2f, -0.1f, 0.95f, 0.5f},
                            new float[]{0.5f, 0.7f, 1.0f, 0.1f},
                            new float[]{0.6f, 0.5f, 0.8f, 1f},
                            new float[]{0.9f, 0.45f, 0.2f, 0.8f},
                            new int[]{0, 0, 0, 0},
                            w, h, 5.0f, 0.0f, 0.0f, 63.0f, 1.0f,
                            0, 0, 0, 0, 0, null
                    );
                    assertTrue(engine.executeWorldShader(mColor, mDepth, duskFrame));
                    device.endFrame(false);
                    assertTrue(device.waitFrameComplete(fidDusk, 5000));

                    ShaderPackService.ActivePackInfo zenithInfo = shaders.loadPack(zenithDir, w, h, 1.0f);
                    assertEquals(ShaderPackFormat.APERTURE_TS, zenithInfo.format());
                    assertTrue(zenithInfo.hardwareRayTracingEnabled(), "Meteor Zenith must enable Hardware Ray Tracing");
                    assertTrue(zenithInfo.compiledPsos() >= 10);
                    assertEquals(4, shaders.activePackProfiles().size());
                    shaders.applyProfile("ULTRA");

                    long fid2 = device.beginFrame();
                    ffm.cmdClearColorDepth(device.nativeContext(), mColor.handle(), 0.25f, 0.45f, 0.90f, 1.0f, mDepth.handle(), 0.0);
                    MetalityFFM.WorldFrameUniforms frame = new MetalityFFM.WorldFrameUniforms(
                            invProj, proj, id, id, invProj, proj,
                            new float[]{8f, 65f, 10f, 1f},
                            new float[]{0.2f, 0.1f, -0.95f, 1f},
                            new float[]{-0.2f, -0.1f, 0.95f, 0.5f},
                            new float[]{0.5f, 0.7f, 1.0f, 0.1f},
                            new float[]{0.6f, 0.5f, 0.8f, 1f},
                            new float[]{0.9f, 0.45f, 0.2f, 0.8f},
                            new int[]{0, 0, 0, 0},
                            w, h, 5.0f, 0.0f, 0.0f, 63.0f, 1.0f,
                            0, 0, 0, 0, 0, null
                    );
                    assertTrue(engine.executeWorldShader(mColor, mDepth, frame));
                    device.endFrame(false);
                    assertTrue(device.waitFrameComplete(fid2, 5000));
                }
            }
        }
    }

    private static long padOffset(int x, int y, int z) {
        int px = x + 1;
        int py = y + 1;
        int pz = z + 1;
        return ((long) (py * 18 + pz) * 18L + px) * 2L;
    }
}

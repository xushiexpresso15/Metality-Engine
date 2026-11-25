package dev.metality;

import dev.metality.api.MetalityApi;
import dev.metality.api.material.BlockMaterialDescriptor;
import dev.metality.api.material.RenderLayerKind;
import dev.metality.api.shader.ShaderPackService;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import dev.metality.impl.DefaultBlockMaterialRegistry;
import dev.metality.impl.DefaultMetalityEngine;
import dev.metality.nativebridge.MetalityFFM;
import dev.metality.terrain.ChunkMeshingScratch;
import dev.metality.terrain.MetalityTerrainRenderer;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java 25 Project Panama FFM 橋接與 Metality 引擎 / Meteor 光影端對端基準測試（嚴格零 MC 位元組碼相依）。
 */
public final class MetalityEngineBenchmarkTest {

    @Test
    void benchmarkJavaFfmBridgeMeshingAndMeteorProfiles() throws Exception {
        Path dylib = Path.of(System.getProperty("metality.native.dylib"));
        Path zenithPack = Path.of(System.getProperty("metality.meteor.zenith.pack",
                System.getProperty("metality.meteor.rt.pack")));
        Path duskPack = Path.of(System.getProperty("metality.meteor.dusk.pack",
                System.getProperty("metality.meteor.std.pack")));
        assertTrue(Files.exists(dylib), "Missing compiled libmetality_native.dylib");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.exists(zenithPack.resolve("pack.ts")) && Files.exists(duskPack.resolve("pack.ts")),
                "Meteor shader packs not present locally (see xushiexpresso15/Meteor-shader)");

        Path cacheDir = Files.createTempDirectory("metality_java_bench_cache");
        MetalityFFM ffm = new MetalityFFM(dylib);

        try (MetalGpuDevice device = new MetalGpuDevice(ffm, cacheDir, false);
             DefaultMetalityEngine engine = new DefaultMetalityEngine(device)) {

            assertTrue(MetalityApi.isAvailable());

            // 1. Java 25 Panama FFM Downcall 開銷基準（含 JIT 預熱）
            for (int i = 0; i < 2000; ++i) {
                assertNotNull(device.queryUmaPressure());
            }
            final int downcallIters = 50_000;
            long d0 = System.nanoTime();
            for (int i = 0; i < downcallIters; ++i) {
                device.queryUmaPressure();
            }
            long d1 = System.nanoTime();
            double nsPerDowncall = (double) (d1 - d0) / downcallIters;

            // 2. Java -> Native NEON 區塊網格化 + GPU Quad Pool + HWRT BLAS 建置吞吐量
            DefaultBlockMaterialRegistry mats = engine.nativeMaterialRegistry();
            int stoneId = mats.register(BlockMaterialDescriptor.builder("minecraft:stone")
                    .allFacesSprite(1).layer(RenderLayerKind.SOLID).opaqueFullCube(true).build());
            int leavesId = mats.register(BlockMaterialDescriptor.builder("minecraft:oak_leaves")
                    .allFacesSprite(2).layer(RenderLayerKind.CUTOUT).opaqueFullCube(false).waveTag(2).build());
            int waterId = mats.register(BlockMaterialDescriptor.builder("minecraft:water")
                    .allFacesSprite(3).layer(RenderLayerKind.TRANSLUCENT).opaqueFullCube(false).waveTag(3).alpha(180).build());
            int oreId = mats.register(BlockMaterialDescriptor.builder("minecraft:diamond_ore")
                    .allFacesSprite(4).layer(RenderLayerKind.SOLID).opaqueFullCube(true).waveTag(8).emissiveLevel(11).build());

            try (ChunkMeshingScratch scratch = new ChunkMeshingScratch();
                 Arena palArena = Arena.ofConfined()) {
                MemorySegment palette = mats.bakeNativePalette(palArena);
                scratch.blockMaterialIds.fill((byte) 0);
                scratch.packedLight.fill((byte) 0xF0);
                scratch.biomeTintRgb.fill((byte) 0xE0);

                for (int py = 1; py <= 8; ++py) {
                    for (int pz = 1; pz <= 16; ++pz) {
                        for (int px = 1; px <= 16; ++px) {
                            int id = (py == 8 && ((px + pz) & 3) == 0) ? leavesId
                                    : (py >= 7 ? waterId : (((px + py + pz) % 17 == 0) ? oreId : stoneId));
                            scratch.blockMaterialIds.set(ValueLayout.JAVA_SHORT, padOffset(px - 1, py - 1, pz - 1), (short) id);
                        }
                    }
                }
                scratch.bindPalette(palette, mats.size(), true);

                // JIT + cache warmup
                for (int i = 0; i < 64; ++i) {
                    assertTrue(ffm.meshChunkSectionNeon(scratch.inputStruct, scratch.outputStruct));
                }

                final int meshIters = 1000;
                long m0 = System.nanoTime();
                for (int i = 0; i < meshIters; ++i) {
                    assertTrue(ffm.meshChunkSectionNeon(scratch.inputStruct, scratch.outputStruct));
                }
                long m1 = System.nanoTime();
                double usPerJavaMesh = (double) (m1 - m0) / (meshIters * 1000.0);

                MetalityTerrainRenderer terrain = device.getOrCreateTerrainRenderer();
                final int uploadSections = 36;
                long u0 = System.nanoTime();
                for (int sz = -3; sz < 3; ++sz) {
                    for (int sx = -3; sx < 3; ++sx) {
                        assertTrue(terrain.uploadMeshedSection(sx, 3, sz, scratch) >= 0);
                    }
                }
                assertTrue(engine.nativeRayTracingScene().rebuildTlas(0.0f, 65.0f, 4.0f));
                long u1 = System.nanoTime();
                double uploadBlasMs = (u1 - u0) / 1_000_000.0;

                System.out.printf(
                        "[Java Bench] Panama FFM Downcall: %.1f ns/call | Java->NEON Mesh: %.2f us/sec (%.0f sec/s) | %d-Sec Upload+BLAS+TLAS: %.2f ms%n",
                        nsPerDowncall, usPerJavaMesh, 1_000_000.0 / usPerJavaMesh, uploadSections, uploadBlasMs
                );
                assertTrue(nsPerDowncall < 10_000.0, "FFM downcall unexpectedly slow");
                assertTrue(terrain.activeSectionCount() == uploadSections);

                // 3. Java DefaultMetalityEngine Zenith (17-Pass RT) vs Dusk (11-Pass Non-RT) 執行基準 (1920x1080)
                final int w = 1920, h = 1080;
                int usage = MetalityFFM.TEX_USAGE_SAMPLED | MetalityFFM.TEX_USAGE_RENDER_TARGET | MetalityFFM.TEX_USAGE_STORAGE_WRITE;
                try (MetalGpuTexture ctex = device.createTexture(w, h, 1, MetalityFFM.FORMAT_RGBA8_UNORM, usage);
                     MetalGpuTexture dtex = device.createTexture(w, h, 1, MetalityFFM.FORMAT_DEPTH32_FLOAT, usage)) {

                    float[] id = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
                    float[] invProj = {0.9f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, 0, -20.0f, 0, 0, -1, 0};
                    float[] proj = {1.1f, 0, 0, 0, 0, 2.0f, 0, 0, 0, 0, 0, -1, 0, 0, 0.05f, 0};

                    for (var packEntry : List.of(Map.entry("Meteor Zenith", zenithPack), Map.entry("Meteor Dusk", duskPack))) {
                        String editionName = packEntry.getKey();
                        ShaderPackService.ActivePackInfo packInfo = engine.shaders().loadPack(packEntry.getValue(), w, h, 1.0f);
                        assertTrue(packInfo.compiledPsos() >= 10);

                        for (String profile : List.of("LOW", "MEDIUM", "HIGH", "ULTRA")) {
                            engine.shaders().applyProfile(profile);
                            float q = switch (profile) {
                                case "LOW" -> 0.5f;
                                case "MEDIUM" -> 1.0f;
                                case "HIGH" -> 1.5f;
                                default -> 2.0f;
                            };
                            float[] userParams = {0.92f, 1.15f, 1.15f, q};

                            for (int wu = 0; wu < 2; ++wu) {
                                long fid = device.beginFrame();
                                ffm.cmdClearColorDepth(device.nativeContext(), ctex.handle(), 0.25f, 0.45f, 0.90f, 1.0f, dtex.handle(), 0.0);
                                ffm.cmdClearColorDepthRect(device.nativeContext(), ctex.handle(), 0.35f, 0.55f, 0.28f, 1.0f, dtex.handle(), 0.008, 0, 0, 0, w / 2, h / 2);
                                ffm.cmdClearColorDepthRect(device.nativeContext(), ctex.handle(), 0.16f, 0.44f, 0.88f, 1.0f, dtex.handle(), 0.006, 0, w / 2, 0, w / 2, h / 2);
                                MetalityFFM.WorldFrameUniforms fu = new MetalityFFM.WorldFrameUniforms(
                                        invProj, proj, id, id, invProj, proj,
                                        new float[]{0f, 65f, 4f, wu},
                                        new float[]{0.35f, 0.65f, -0.67f, 1f},
                                        new float[]{-0.35f, -0.65f, 0.67f, 0f},
                                        new float[]{0.45f, 0.68f, 1.0f, 0.1f},
                                        new float[]{0.62f, 0.74f, 0.88f, 0.25f},
                                        new float[]{0.9f, 0.45f, 0.22f, 0.0f},
                                        new int[]{0, 0, 0, 0}, w, h, 10.0f + wu * 0.016f, 0.0f, 0.0f, 63.0f, 2.0f,
                                        0, 0, uploadSections, packInfo.hardwareRayTracingEnabled() ? 1 : 0, 0, userParams
                                );
                                assertTrue(engine.executeWorldShader(ctex, dtex, fu));
                                device.endFrame(false);
                                assertTrue(device.waitFrameComplete(fid, 5000));
                            }

                            final int kFrames = 6;
                            double cpuSumMs = 0.0;
                            double syncSumMs = 0.0;
                            double gpuSumMs = 0.0;
                            for (int f = 0; f < kFrames; ++f) {
                                long fid = device.beginFrame();
                                ffm.cmdClearColorDepth(device.nativeContext(), ctex.handle(), 0.25f, 0.45f, 0.90f, 1.0f, dtex.handle(), 0.0);
                                ffm.cmdClearColorDepthRect(device.nativeContext(), ctex.handle(), 0.35f, 0.55f, 0.28f, 1.0f, dtex.handle(), 0.008, 0, 0, 0, w / 2, h / 2);
                                ffm.cmdClearColorDepthRect(device.nativeContext(), ctex.handle(), 0.16f, 0.44f, 0.88f, 1.0f, dtex.handle(), 0.006, 0, w / 2, 0, w / 2, h / 2);
                                MetalityFFM.WorldFrameUniforms fu = new MetalityFFM.WorldFrameUniforms(
                                        invProj, proj, id, id, invProj, proj,
                                        new float[]{0f, 65f, 4f, f + 2},
                                        new float[]{0.35f, 0.65f, -0.67f, 1f},
                                        new float[]{-0.35f, -0.65f, 0.67f, 0f},
                                        new float[]{0.45f, 0.68f, 1.0f, 0.1f},
                                        new float[]{0.62f, 0.74f, 0.88f, 0.25f},
                                        new float[]{0.9f, 0.45f, 0.22f, 0.0f},
                                        new int[]{0, 0, 0, 0}, w, h, 12.0f + f * 0.016f, 0.0f, 0.0f, 63.0f, 2.0f,
                                        0, 0, uploadSections, packInfo.hardwareRayTracingEnabled() ? 1 : 0, 0, userParams
                                );
                                long t0 = System.nanoTime();
                                assertTrue(engine.executeWorldShader(ctex, dtex, fu));
                                long t1 = System.nanoTime();
                                device.endFrame(false);
                                long tw0 = System.nanoTime();
                                assertTrue(device.waitFrameComplete(fid, 5000));
                                long tw1 = System.nanoTime();
                                Thread.sleep(1);
                                double hw = engine.telemetry().lastGpuFrameTimeMs();
                                cpuSumMs += (t1 - t0) / 1_000_000.0;
                                syncSumMs += (tw1 - tw0) / 1_000_000.0;
                                gpuSumMs += hw > 0.0 ? hw : ((tw1 - tw0) / 1_000_000.0);
                            }
                            System.out.printf(
                                    "[Java Bench] %-13s | Profile=%-6s (1920x1080) | Java->Metal Submit: %.2f ms | GPU HW: %.2f ms | Sync: %.2f ms%n",
                                    editionName, profile, cpuSumMs / kFrames, gpuSumMs / kFrames, syncSumMs / kFrames
                            );
                        }
                    }
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

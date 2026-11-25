package dev.metality.mc263;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.metality.api.MetalityApi;
import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.api.shader.ShaderPackService;
import dev.metality.mc263.renderpearl.MetalDeviceBackend;
import dev.metality.mc263.renderpearl.MetalRenderPearlBackend;
import dev.metality.mc263.renderpearl.MetalRenderPipeline;
import dev.metality.mc263.renderpearl.SpvcMslShaderBridge;
import dev.metality.mc263.shader.LwjglShadercSpvcTranspiler;
import dev.metality.shader.legacy.LegacyGlslPreprocessor;
import dev.metality.shader.legacy.ShaderPackArchive;
import dev.metality.spi.GlslSpirvTranspilerSpi;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import org.joml.Vector4f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.util.shaderc.Shaderc;

public final class RenderPearlBackendIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testApiAndCoreContainZeroMinecraftOrMojangReferences() throws IOException {
        Path apiClasses = Paths.get(System.getProperty("metality.api.classes"));
        Path coreClasses = Paths.get(System.getProperty("metality.core.classes"));
        assertZeroForbiddenBytecodeRefs(apiClasses);
        assertZeroForbiddenBytecodeRefs(coreClasses);
    }

    private static void assertZeroForbiddenBytecodeRefs(Path classesRoot) throws IOException {
        assertTrue(Files.exists(classesRoot), "Missing compiled classes directory: " + classesRoot);
        try (Stream<Path> walk = Files.walk(classesRoot)) {
            List<Path> classFiles = walk.filter(p -> p.toString().endsWith(".class")).toList();
            assertFalse(classFiles.isEmpty(), "Expected compiled .class files in " + classesRoot);
            for (Path cls : classFiles) {
                byte[] bytes = Files.readAllBytes(cls);
                String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
                assertFalse(ascii.contains("net/minecraft/"),
                        "Version-agnostic module leaked net/minecraft reference in " + cls);
                assertFalse(ascii.contains("com/mojang/"),
                        "Version-agnostic module leaked com/mojang reference in " + cls);
            }
        }
    }

    @Test
    void testLiveRenderPearlMetalBackendSpvcPipelineAndRenderPass() throws Exception {
        MetalRenderPearlBackend gpuBackend = new MetalRenderPearlBackend(tempDir);
        gpuBackend.loadLibrary();
        assertEquals("Metality (Apple Silicon Metal)", gpuBackend.getName());

        MetalDeviceBackend backendDevice = new MetalDeviceBackend(tempDir, new GpuDebugOptions(0, false, false, false));
        GpuDevice frontendDevice = new FrontendGpuDevice(backendDevice);
        try {
            assertTrue(frontendDevice.getDeviceInfo().isZZeroToOne(),
                    "Metal backend must report NDC depth [0, 1] via isZZeroToOne()");
            assertTrue(MetalityApi.isAvailable() && MetalityApi.get() != null,
                    "MetalDeviceBackend must bootstrap public MetalityApi engine");

            // 1. Test zero-copy UMA GpuBuffer write + readback
            try (GpuBuffer umaBuf = frontendDevice.createBuffer(() -> "TestUmaBuf",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_VERTEX, 64)) {
                try (GpuBufferSlice.MappedView writeView = umaBuf.map(false, true)) {
                    writeView.data().putInt(0, 0xCAFEBABE);
                    writeView.data().putFloat(4, 3.25f);
                }
                try (GpuBufferSlice.MappedView readView = umaBuf.map(true, false)) {
                    assertEquals(0xCAFEBABE, readView.data().getInt(0));
                    assertEquals(3.25f, readView.data().getFloat(4), 1e-6f);
                }
            }

            // 2. Test Texture + TextureView + Sampler creation and upload
            GpuTexture colorTarget = frontendDevice.createTexture("OffscreenColor",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
                            | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, 64, 64, 1, 1);
            GpuTexture sampledTex = frontendDevice.createTexture("SampledAlbedo",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, 4, 4, 1, 1);
            GpuTextureView colorView = frontendDevice.createTextureView(colorTarget);
            GpuTextureView sampledView = frontendDevice.createTextureView(sampledTex);
            GpuSampler sampler = frontendDevice.createSampler(
                    AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());

            // 3. Compile real GLSL 450 -> SPIR-V (Shaderc) -> MSL (Spvc) -> Metal PSO
            String vsGlsl = """
                    #version 450
                    layout(location = 0) in vec3 inPos;
                    layout(location = 1) in vec2 inUv;
                    layout(set = 0, binding = 0) uniform Ubo { vec4 tint; } ubo;
                    layout(push_constant) uniform Pc { vec4 scale; } pc;
                    layout(location = 0) out vec2 outUv;
                    void main() {
                        outUv = inUv;
                        gl_Position = vec4(inPos * pc.scale.xyz, 1.0) + vec4(ubo.tint.rgb * 0.0, 0.0);
                    }
                    """;
            String fsGlsl = """
                    #version 450
                    layout(set = 0, binding = 0) uniform Ubo { vec4 tint; } ubo;
                    layout(set = 0, binding = 1) uniform sampler2D tex0;
                    layout(location = 0) in vec2 inUv;
                    layout(location = 0) out vec4 fragColor;
                    void main() {
                        fragColor = texture(tex0, inUv) * ubo.tint;
                    }
                    """;

            ByteBuffer vsSpv = compileGlslToSpirv(vsGlsl, Shaderc.shaderc_vertex_shader, "test.vsh");
            ByteBuffer fsSpv = compileGlslToSpirv(fsGlsl, Shaderc.shaderc_fragment_shader, "test.fsh");

            String vsMsl = SpvcMslShaderBridge.transpileSpvBytesToMsl(vsSpv);
            String fsMsl = SpvcMslShaderBridge.transpileSpvBytesToMsl(fsSpv);
            assertTrue(vsMsl.contains("main0"), "SPIRV-Cross must emit main0 entry point");
            assertTrue(vsMsl.contains("buffer(19)"), "Push constants must map to [[buffer(19)]]");
            assertTrue(fsMsl.contains("buffer(0)"), "UBO binding 0 must map to [[buffer(0)]]");
            assertTrue(fsMsl.contains("texture(1)") && fsMsl.contains("sampler(1)"),
                    "Combined image sampler binding 1 must map to texture(1)/sampler(1)");

            var createInfo = new BackendRenderPipeline.CreateInfo(
                    "MetalityRenderPearlTestPso",
                    List.of(
                            new BackendRenderPipeline.CreateInfo.Shader("test_vs", "main", simpleSpv(ShaderType.VERTEX, vsSpv)),
                            new BackendRenderPipeline.CreateInfo.Shader("test_fs", "main", simpleSpv(ShaderType.FRAGMENT, fsSpv))
                    ),
                    List.of(new BackendRenderPipeline.CreateInfo.VertexBuffer(0, 20, 0)),
                    List.of(
                            new BackendRenderPipeline.CreateInfo.AttribBinding(0, 0, 0, GpuFormat.RGB32_FLOAT),
                            new BackendRenderPipeline.CreateInfo.AttribBinding(0, 1, 12, GpuFormat.RG32_FLOAT)
                    ),
                    List.of(
                            new BindGroupLayout.UniformDescription("Ubo", UniformType.UNIFORM_BUFFER),
                            new BindGroupLayout.UniformDescription("tex0", UniformType.COMBINED_IMAGE_SAMPLER)
                    ),
                    16,
                    new DepthStencilState(CompareOp.ALWAYS_PASS, false),
                    PolygonMode.FILL,
                    false,
                    List.of(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)),
                    PrimitiveTopology.TRIANGLES
            );

            BackendRenderPipeline compiledPso = backendDevice.compilePipeline(createInfo).finishCompile();
            assertNotNull(compiledPso);
            assertTrue(compiledPso instanceof MetalRenderPipeline);
            assertNotEquals(0L, ((MetalRenderPipeline) compiledPso).getOrCreatePso(1, 0));

            // 4. Execute live GPU clear, render pass draw & fence completion
            CommandEncoder frontendEncoder = frontendDevice.createCommandEncoder();
            frontendEncoder.clearColorTexture(colorTarget, new Vector4f(0.1f, 0.2f, 0.3f, 1.0f));

            ByteBuffer pixels = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < 16; ++i) {
                pixels.putInt(0xFFFFFFFF);
            }
            pixels.flip();
            frontendEncoder.writeToTexture(sampledTex, pixels, 0, 0, 0, 0, 4, 4);

            ByteBuffer vboData = ByteBuffer.allocateDirect(60).order(ByteOrder.nativeOrder());
            vboData.putFloat(-0.5f).putFloat(-0.5f).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
            vboData.putFloat(0.5f).putFloat(-0.5f).putFloat(0.0f).putFloat(1.0f).putFloat(0.0f);
            vboData.putFloat(0.0f).putFloat(0.5f).putFloat(0.0f).putFloat(0.5f).putFloat(1.0f);
            vboData.flip();
            GpuBuffer vbo = frontendDevice.createBuffer(() -> "TriangleVbo", GpuBuffer.USAGE_VERTEX, vboData);

            ByteBuffer iboData = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
            iboData.putInt(0).putInt(1).putInt(2).flip();
            GpuBuffer ibo = frontendDevice.createBuffer(() -> "TriangleIbo", GpuBuffer.USAGE_INDEX, iboData);

            ByteBuffer uboData = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            uboData.putFloat(0.2f).putFloat(0.8f).putFloat(0.4f).putFloat(1.0f).flip();
            GpuBuffer ubo = frontendDevice.createBuffer(() -> "TintUbo", GpuBuffer.USAGE_UNIFORM, uboData);

            ByteBuffer pcData = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
            pcData.putFloat(1.0f).putFloat(1.0f).putFloat(1.0f).putFloat(1.0f).flip();

            var backendEnc = backendDevice.createCommandEncoder();
            var rpDesc = RenderPassDescriptor.builder(() -> "IntegrationTrianglePass")
                    .withColorAttachment(colorView, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))
                    .build();
            var rp = backendEnc.createRenderPass(rpDesc);
            rp.setPipeline(compiledPso);
            rp.setUniform(0, ubo.slice());
            rp.setUniform(1, new TextureViewAndSampler(sampledView, sampler));
            rp.pushConstants(pcData);
            rp.setVertexBuffer(0, vbo.slice());
            rp.setIndexBuffer(ibo, IndexType.INT);
            rp.drawIndexed(3, 1, 0, 0, 0);
            backendEnc.submitRenderPass();
            backendEnc.submit();

            try (GpuFence fence = backendEnc.createFence()) {
                assertTrue(fence.awaitCompletion(2_000_000_000L), "GPU fence must complete cleanly within 2s");
            }

            compiledPso.close();
            ubo.close();
            ibo.close();
            vbo.close();
            sampler.close();
            sampledView.close();
            sampledTex.close();
            colorView.close();
            colorTarget.close();
        } finally {
            frontendDevice.close();
        }
    }

    @Test
    void testPhase4LegacyIrisPackFullMetalCompilationAnd20Samplers() throws Exception {
        MetalRenderPearlBackend gpuBackend = new MetalRenderPearlBackend(tempDir);
        gpuBackend.loadLibrary();

        MetalDeviceBackend backendDevice = new MetalDeviceBackend(tempDir, new GpuDebugOptions(0, false, false, false));
        try {
            Path legacyPack = Path.of(System.getProperty("metality.legacy.example.pack"));
            assertTrue(Files.exists(legacyPack.resolve("shaders/gbuffers_terrain.fsh")));

            ShaderPackService shaders = backendDevice.publicEngine().shaders();
            ShaderPackService.ActivePackInfo info = shaders.loadPack(legacyPack, 1920, 1200, 1.0f);
            assertEquals(ShaderPackFormat.LEGACY_IRIS_GLSL, info.format());
            assertTrue(info.compiledPsos() >= 4,
                    "All 4 iris-aurora-legacy shader programs must compile on Apple M4 Max GPU, got " + info.compiledPsos());
            assertTrue(info.savedDramBytesPerFrame() > 0L);

            // Verify Solid HSR (0 OpKill) vs Cutout (>0 OpKill) + RelaxedPrecision FP16 on real gbuffers_terrain.fsh
            try (ShaderPackArchive archive = ShaderPackArchive.open(legacyPack)) {
                var varyingsSolid = new LegacyGlslPreprocessor.VaryingLocationTable();
                var fsSolid = LegacyGlslPreprocessor.preprocess(
                        archive, "shaders/gbuffers_terrain.fsh", GlslSpirvTranspilerSpi.ShaderStage.FRAGMENT,
                        Map.of("SHADOW_QUALITY", "2", "BLOOM_ENABLED", "1"), true, varyingsSolid);
                var fsCutout = LegacyGlslPreprocessor.preprocess(
                        archive, "shaders/gbuffers_terrain.fsh", GlslSpirvTranspilerSpi.ShaderStage.FRAGMENT,
                        Map.of("SHADOW_QUALITY", "2", "BLOOM_ENABLED", "1"), false, new LegacyGlslPreprocessor.VaryingLocationTable());

                var spi = backendDevice.metalDevice().glslTranspilerSpi().orElseThrow();
                var mslSolid = spi.transpileLegacyGlslToMsl("terrain_solid", GlslSpirvTranspilerSpi.ShaderStage.FRAGMENT, fsSolid.preprocessedGlsl(), true);
                var mslCutout = spi.transpileLegacyGlslToMsl("terrain_cutout", GlslSpirvTranspilerSpi.ShaderStage.FRAGMENT, fsCutout.preprocessedGlsl(), false);
                assertEquals(0, mslSolid.discardOpCount(), "Solid HSR terrain variant must have 0 OpKill discards");
                assertFalse(mslSolid.mslSource().contains("discard_fragment()"), "Solid MSL must not emit discard_fragment()");
                assertTrue(mslCutout.discardOpCount() > 0, "Cutout terrain variant must preserve OpKill alpha-test");
                assertTrue(mslSolid.relaxedFp16Decorations() > 0, "FP16 RelaxedPrecision must be injected on fragment ALU");

                long solidLib = backendDevice.metalDevice().compileMslLibrary("terrain_solid_test", mslSolid.mslSource(), true);
                long cutoutLib = backendDevice.metalDevice().compileMslLibrary("terrain_cutout_test", mslCutout.mslSource(), true);
                assertNotEquals(0L, solidLib);
                assertNotEquals(0L, cutoutLib);

                // Verify >16 sampler GLSL compiles end-to-end (GLSL -> Shaderc -> SPIRV opt -> Spvc -> Sampler fold -> Metal GPU)
                StringBuilder heavyGlsl = new StringBuilder("#version 450\nlayout(location = 0) in vec2 uv;\nlayout(location = 0) out vec4 fragColor;\n");
                for (int i = 0; i < 20; ++i) {
                    heavyGlsl.append("layout(binding = ").append(i).append(") uniform sampler2D t").append(i).append(";\n");
                }
                heavyGlsl.append("void main() {\n    vec4 sum = vec4(0.0);\n");
                for (int i = 0; i < 20; ++i) {
                    heavyGlsl.append("    sum += texture(t").append(i).append(", uv) * 0.05;\n");
                }
                heavyGlsl.append("    fragColor = sum;\n}\n");

                var heavyMsl = spi.transpileLegacyGlslToMsl("heavy_20_sampler_test", GlslSpirvTranspilerSpi.ShaderStage.FRAGMENT, heavyGlsl.toString(), true);
                assertTrue(heavyMsl.inlinedSamplers() >= 5, "Expected >= 5 overflow samplers folded into shared constexpr sampler, got " + heavyMsl.inlinedSamplers());
                long heavyLib = backendDevice.metalDevice().compileMslLibrary("heavy_20_sampler_lib", heavyMsl.mslSource(), true);
                assertNotEquals(0L, heavyLib);
            }
        } finally {
            backendDevice.close();
        }
    }

    @Test
    void testMeteorZenithAndDuskPacksThroughModBackend() throws Exception {
        String duskProp = System.getProperty("metality.meteor.dusk.pack", System.getProperty("metality.meteor.std.pack"));
        String zenithProp = System.getProperty("metality.meteor.zenith.pack", System.getProperty("metality.meteor.rt.pack"));
        Assumptions.assumeTrue(
                duskProp != null && zenithProp != null
                        && Files.exists(Path.of(duskProp, "pack.ts"))
                        && Files.exists(Path.of(zenithProp, "pack.ts")),
                "Meteor shaderpacks not present locally");

        MetalRenderPearlBackend gpuBackend = new MetalRenderPearlBackend(tempDir);
        gpuBackend.loadLibrary();
        MetalDeviceBackend backendDevice = new MetalDeviceBackend(tempDir, new GpuDebugOptions(0, false, false, false));
        try {
            ShaderPackService shaders = backendDevice.publicEngine().shaders();

            ShaderPackService.ActivePackInfo duskInfo = shaders.loadPack(Path.of(duskProp), 320, 180, 1.0f);
            assertEquals(ShaderPackFormat.APERTURE_TS, duskInfo.format());
            assertFalse(duskInfo.hardwareRayTracingEnabled());
            assertEquals(13, duskInfo.compiledPsos());
            assertFalse(shaders.activePackOptions().isEmpty());
            assertEquals(4, shaders.activePackProfiles().size());

            ShaderPackService.ActivePackInfo zenithInfo = shaders.loadPack(Path.of(zenithProp), 320, 180, 1.0f);
            assertEquals(ShaderPackFormat.APERTURE_TS, zenithInfo.format());
            assertTrue(zenithInfo.hardwareRayTracingEnabled());
            assertTrue(zenithInfo.compiledPsos() >= 10);
            assertEquals(4, shaders.activePackProfiles().size());
        } finally {
            backendDevice.close();
        }
    }

    @Test
    void testRealWorldZipShaderPacksReadOnlyDiscoveryAndPreprocessing() throws Exception {
        String customPacksDir = System.getProperty("metality.real.shaderpacks.dir", "");
        Path packsDir = customPacksDir.isBlank()
                ? Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/instances/26.3/minecraft/shaderpacks")
                : Path.of(customPacksDir);
        List<String> zipNames = List.of(
                "ComplementaryUnbound_r5.9.3.zip",
                "Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip",
                "Solas Shader V3.9.zip"
        );
        boolean anyPresent = zipNames.stream().anyMatch(n -> Files.exists(packsDir.resolve(n)));
        Assumptions.assumeTrue(anyPresent, "Real third-party shaderpack .zips not found on this host");

        MetalRenderPearlBackend gpuBackend = new MetalRenderPearlBackend(tempDir);
        gpuBackend.loadLibrary();
        MetalDeviceBackend backendDevice = new MetalDeviceBackend(tempDir, new GpuDebugOptions(0, false, false, false));
        try {
            ShaderPackService shaders = backendDevice.publicEngine().shaders();
            for (String zipName : zipNames) {
                Path zipPath = packsDir.resolve(zipName);
                if (!Files.exists(zipPath)) {
                    continue;
                }
                ShaderPackService.ActivePackInfo info = shaders.loadPack(zipPath, 1920, 1200, 1.0f);
                assertEquals(ShaderPackFormat.LEGACY_IRIS_GLSL, info.format(), zipName);
                assertTrue(info.totalPasses() >= 2, zipName + " expected >= 2 passes, got " + info.totalPasses());
                assertTrue(info.fusedGroups() < info.totalPasses(),
                        zipName + " expected Metal TBDR pass fusion (" + info.fusedGroups() + " < " + info.totalPasses() + ")");
                if (zipName.startsWith("Complementary") || zipName.startsWith("Solas")) {
                    assertTrue(info.savedDramBytesPerFrame() > 0L, zipName + " expected positive TBDR DRAM savings");
                }
                assertFalse(shaders.activePackOptions().isEmpty(), zipName + " expected parsed shader options");
                assertFalse(shaders.activePackProfiles().isEmpty(), zipName + " expected parsed profiles");

                try (ShaderPackArchive archive = ShaderPackArchive.open(zipPath)) {
                    Optional<String> candidateFsh = archive.listFilesUnder("shaders").stream()
                            .filter(p -> p.endsWith("gbuffers_terrain.fsh") || p.endsWith("composite.fsh") || p.endsWith("final.fsh"))
                            .findFirst();
                    assertTrue(candidateFsh.isPresent(), zipName + " missing entry fragment shader");
                    var pre = LegacyGlslPreprocessor.preprocess(
                            archive, candidateFsh.get(), GlslSpirvTranspilerSpi.ShaderStage.FRAGMENT,
                            Map.of(), false, new LegacyGlslPreprocessor.VaryingLocationTable());
                    assertFalse(
                            pre.preprocessedGlsl().lines().anyMatch(ln -> ln.trim().startsWith("#include ")),
                            zipName + " must resolve all active #include directives"
                    );
                }
            }
        } finally {
            backendDevice.close();
        }
    }

    private static SpvModule simpleSpv(ShaderType type, ByteBuffer bytes) {
        return new SpvModule() {
            @Override public void close() {}
            @Override public ByteBuffer spv() { return bytes.duplicate(); }
            @Override public ShaderType type() { return type; }
            @Override public Reflection reflect() { throw new UnsupportedOperationException(); }
            @Override public Reflection getReflectionInfoIfAvailable() { return null; }
        };
    }

    private static ByteBuffer compileGlslToSpirv(String source, int stageKind, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        final long res = Shaderc.shaderc_compile_into_spv(compiler, source, stageKind, name, "main", 0L);
        try {
            assertEquals(Shaderc.shaderc_compilation_status_success,
                    Shaderc.shaderc_result_get_compilation_status(res),
                    () -> "Shaderc failed: " + Shaderc.shaderc_result_get_error_message(res));
            ByteBuffer nativeBytes = Shaderc.shaderc_result_get_bytes(res);
            ByteBuffer copy = ByteBuffer.allocateDirect(nativeBytes.remaining()).order(ByteOrder.nativeOrder());
            copy.put(nativeBytes);
            copy.flip();
            return copy;
        } finally {
            if (res != 0L) {
                Shaderc.shaderc_result_release(res);
            }
            if (compiler != 0L) {
                Shaderc.shaderc_compiler_release(compiler);
            }
        }
    }
}

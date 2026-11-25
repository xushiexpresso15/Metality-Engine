package dev.metality.mc263.shader;

import dev.metality.backend.MetalGpuDevice;
import dev.metality.mc263.renderpearl.SpvcMslShaderBridge;
import dev.metality.nativebridge.MetalityFFM;
import dev.metality.spi.GlslSpirvTranspilerSpi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Objects;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * 基於 LWJGL Shaderc + SPIRV-Cross (Spvc) + Metality 原生 C++ Apple GPU Best Practices Best-Path 之轉譯橋接：
 * GLSL -> Vulkan SPIR-V -> {@code metality_spirv_optimize_for_apple_gpu} (FP16 RelaxedPrecision & OpKill 清點)
 * -> Spvc MSL 3.1 -> {@code metality_rewrite_msl_samplers_and_fp16} (>16 Sampler Inline & HSR 零 discard)。
 */
public final class LwjglShadercSpvcTranspiler implements GlslSpirvTranspilerSpi {

    private final MetalGpuDevice gpuDevice;

    public LwjglShadercSpvcTranspiler(MetalGpuDevice gpuDevice) {
        this.gpuDevice = Objects.requireNonNull(gpuDevice, "gpuDevice");
    }

    @Override
    public TranspiledMsl transpileLegacyGlslToMsl(
            String shaderLabel,
            ShaderStage stage,
            String preprocessedGlsl,
            boolean stripDiscardForSolidHsr
    ) {
        int[] rawWords = compileGlslToSpirvWords(shaderLabel, stage, preprocessedGlsl);
        boolean injectFp16 = (stage == ShaderStage.FRAGMENT);
        MetalityFFM.SpirvOptimizeResult opt = gpuDevice.ffm().spirvOptimizeForAppleGpu(rawWords, injectFp16);

        int[] optWords = opt.optimizedWords();
        ByteBuffer spvBuf = ByteBuffer.allocateDirect(optWords.length * 4).order(ByteOrder.nativeOrder());
        spvBuf.asIntBuffer().put(optWords);
        spvBuf.rewind();

        String rawMsl = SpvcMslShaderBridge.transpileSpvBytesToMsl(spvBuf);
        MetalityFFM.MslRewriteResult rewritten = gpuDevice.ffm().rewriteMslSamplersAndFp16(rawMsl, stripDiscardForSolidHsr);

        return new TranspiledMsl(
                rewritten.mslSource(),
                "main0",
                opt.stats().discardOpCount(),
                opt.stats().relaxedFp16Decorations(),
                rewritten.inlinedSamplers()
        );
    }

    private static int[] compileGlslToSpirvWords(String label, ShaderStage stage, String source) {
        int shadercKind = switch (stage) {
            case VERTEX -> Shaderc.shaderc_glsl_vertex_shader;
            case FRAGMENT -> Shaderc.shaderc_glsl_fragment_shader;
            case COMPUTE -> Shaderc.shaderc_glsl_compute_shader;
        };

        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0L;
        try {
            Shaderc.shaderc_compile_options_set_target_env(
                    options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(options, true);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            Shaderc.shaderc_compile_options_set_optimization_level(
                    options, Shaderc.shaderc_optimization_level_performance);

            result = Shaderc.shaderc_compile_into_spv(compiler, source, shadercKind, label, "main", options);
            if (result == 0L || Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                String err = result != 0L ? Shaderc.shaderc_result_get_error_message(result) : "null shaderc result";
                throw new IllegalStateException("Shaderc GLSL->SPIR-V failed for " + label + ": " + err);
            }
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            IntBuffer ints = bytes.order(ByteOrder.nativeOrder()).asIntBuffer();
            int[] words = new int[ints.remaining()];
            ints.get(words);
            return words;
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            if (options != 0L) {
                Shaderc.shaderc_compile_options_release(options);
            }
            if (compiler != 0L) {
                Shaderc.shaderc_compiler_release(compiler);
            }
        }
    }
}

package dev.metality.spi;

/**
 * 版本無關之 GLSL -> SPIR-V -> MSL 轉譯服務供應介面（由 :mod 透過 LWJGL Shaderc / Spvc 註冊實作）。
 * 使 :core 保持零 Minecraft / LWJGL / Vulkan 位元組碼相依。
 */
@FunctionalInterface
public interface GlslSpirvTranspilerSpi {

    enum ShaderStage {
        VERTEX,
        FRAGMENT,
        COMPUTE
    }

    record TranspiledMsl(
            String mslSource,
            String entryPointName,
            int discardOpCount,
            int relaxedFp16Decorations,
            int inlinedSamplers
    ) {}

    TranspiledMsl transpileLegacyGlslToMsl(
            String shaderLabel,
            ShaderStage stage,
            String preprocessedGlsl,
            boolean stripDiscardForSolidHsr
    );
}

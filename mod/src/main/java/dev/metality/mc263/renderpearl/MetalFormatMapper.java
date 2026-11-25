package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendEquation;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import dev.metality.nativebridge.MetalityFFM;
import java.util.Optional;

/**
 * 將 Minecraft 26.3 RenderPearl 列舉常數對應至 Metality 原生 Metal常數。
 */
public final class MetalFormatMapper {

    private MetalFormatMapper() {}

    public static int mapPixelFormat(GpuFormat format) {
        if (format == null) {
            return 0;
        }
        return switch (format) {
            case R8_UNORM -> MetalityFFM.FORMAT_R8_UNORM;
            case R8_SINT -> MetalityFFM.FORMAT_R8_SINT;
            case R16_FLOAT -> MetalityFFM.FORMAT_R16_FLOAT;
            case RG16_FLOAT -> MetalityFFM.FORMAT_RG16_FLOAT;
            case RGBA16_FLOAT, RGB16_FLOAT -> MetalityFFM.FORMAT_RGBA16_FLOAT;
            case RGBA16_SINT, RGB16_SINT -> MetalityFFM.FORMAT_RGBA16_SINT;
            case R32_FLOAT -> MetalityFFM.FORMAT_R32_FLOAT;
            case R32_SINT -> MetalityFFM.FORMAT_R32_SINT;
            case RGBA32_FLOAT, RGB32_FLOAT -> MetalityFFM.FORMAT_RGBA32_FLOAT;
            case RGB10A2_UNORM -> MetalityFFM.FORMAT_RGB10A2_UNORM;
            case RG11B10_FLOAT -> MetalityFFM.FORMAT_RG11B10_FLOAT;
            case D32_FLOAT, D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT, D16_UNORM -> MetalityFFM.FORMAT_DEPTH32_FLOAT;
            default -> MetalityFFM.FORMAT_RGBA8_UNORM;
        };
    }

    public static int mapVertexFormat(GpuFormat format) {
        return switch (format) {
            case R32_FLOAT -> MetalityFFM.VTX_FLOAT;
            case RG32_FLOAT -> MetalityFFM.VTX_FLOAT2;
            case RGB32_FLOAT -> MetalityFFM.VTX_FLOAT3;
            case RGBA32_FLOAT -> MetalityFFM.VTX_FLOAT4;
            case RGBA8_UNORM -> MetalityFFM.VTX_UBYTE4_NORM;
            case RGBA8_SNORM -> MetalityFFM.VTX_BYTE4_NORM;
            case RGBA8_UINT -> MetalityFFM.VTX_UBYTE4;
            case RGBA8_SINT -> MetalityFFM.VTX_BYTE4;
            case RGB8_UNORM -> MetalityFFM.VTX_UBYTE3_NORM;
            case RGB8_SNORM -> MetalityFFM.VTX_BYTE3_NORM;
            case RGB8_UINT -> MetalityFFM.VTX_UBYTE3;
            case RGB8_SINT -> MetalityFFM.VTX_BYTE3;
            case RG16_UINT -> MetalityFFM.VTX_USHORT2;
            case RG16_SINT -> MetalityFFM.VTX_SHORT2;
            case RG16_UNORM -> MetalityFFM.VTX_USHORT2_NORM;
            case RG16_SNORM -> MetalityFFM.VTX_SHORT2_NORM;
            case R16_UINT -> MetalityFFM.VTX_USHORT;
            case R16_SINT -> MetalityFFM.VTX_SHORT;
            case R8_UINT -> MetalityFFM.VTX_UBYTE;
            case R8_SINT -> MetalityFFM.VTX_BYTE;
            case R32_SINT -> MetalityFFM.VTX_INT;
            case RG32_SINT -> MetalityFFM.VTX_INT2;
            case RGB32_SINT -> MetalityFFM.VTX_INT3;
            case RGBA32_SINT -> MetalityFFM.VTX_INT4;
            case R32_UINT -> MetalityFFM.VTX_UINT;
            case RG32_UINT -> MetalityFFM.VTX_UINT2;
            case RGB32_UINT -> MetalityFFM.VTX_UINT3;
            case RGBA32_UINT -> MetalityFFM.VTX_UINT4;
            default -> MetalityFFM.VTX_FLOAT4;
        };
    }

    public static int mapBlendMode(Optional<BlendFunction> blendOpt) {
        if (blendOpt == null || blendOpt.isEmpty()) {
            return MetalityFFM.BLEND_OPAQUE;
        }
        BlendFunction fn = blendOpt.get();
        BlendEquation color = fn.color();
        BlendEquation alpha = fn.alpha();
        int srcColor = mapBlendFactorCode(color.sourceFactor());
        int dstColor = mapBlendFactorCode(color.destFactor());
        int srcAlpha = mapBlendFactorCode(alpha.sourceFactor());
        int dstAlpha = mapBlendFactorCode(alpha.destFactor());
        int colorOp = mapBlendOpCode(color.op());
        int alphaOp = mapBlendOpCode(alpha.op());
        return MetalityFFM.CUSTOM_BLEND_FLAG
                | (srcColor & 0xF)
                | ((dstColor & 0xF) << 4)
                | ((srcAlpha & 0xF) << 8)
                | ((dstAlpha & 0xF) << 12)
                | ((colorOp & 0x7) << 16)
                | ((alphaOp & 0x7) << 19);
    }

    private static int mapBlendFactorCode(BlendFactor factor) {
        return switch (factor) {
            case ZERO -> 0;
            case ONE -> 1;
            case SRC_COLOR -> 2;
            case ONE_MINUS_SRC_COLOR -> 3;
            case DST_COLOR -> 4;
            case ONE_MINUS_DST_COLOR -> 5;
            case SRC_ALPHA -> 6;
            case ONE_MINUS_SRC_ALPHA -> 7;
            case DST_ALPHA -> 8;
            case ONE_MINUS_DST_ALPHA -> 9;
            case CONSTANT_COLOR -> 10;
            case ONE_MINUS_CONSTANT_COLOR -> 11;
            case CONSTANT_ALPHA -> 12;
            case ONE_MINUS_CONSTANT_ALPHA -> 13;
            case SRC_ALPHA_SATURATE -> 14;
        };
    }

    private static int mapBlendOpCode(BlendOp op) {
        return switch (op) {
            case ADD -> 0;
            case SUBTRACT -> 1;
            case REVERSE_SUBTRACT -> 2;
            case MIN -> 3;
            case MAX -> 4;
        };
    }

    public static int mapDepthCompare(CompareOp op) {
        if (op == null) {
            return MetalityFFM.DEPTH_ALWAYS;
        }
        return switch (op) {
            case ALWAYS_PASS -> MetalityFFM.DEPTH_ALWAYS;
            case LESS_THAN -> MetalityFFM.DEPTH_LESS;
            case LESS_THAN_OR_EQUAL -> MetalityFFM.DEPTH_LEQUAL;
            case EQUAL -> MetalityFFM.DEPTH_EQUAL;
            case GREATER_THAN -> MetalityFFM.DEPTH_GREATER;
            case GREATER_THAN_OR_EQUAL -> MetalityFFM.DEPTH_GEQUAL;
            case NEVER_PASS -> MetalityFFM.DEPTH_NEVER;
            case NOT_EQUAL -> MetalityFFM.DEPTH_NOTEQUAL;
        };
    }

    public static int mapTopology(PrimitiveTopology topology) {
        if (topology == null) {
            return MetalityFFM.TOPOLOGY_TRIANGLES;
        }
        return switch (topology) {
            case LINES, DEBUG_LINES -> MetalityFFM.TOPOLOGY_LINES;
            case DEBUG_LINE_STRIP -> MetalityFFM.TOPOLOGY_LINE_STRIP;
            case POINTS -> MetalityFFM.TOPOLOGY_POINTS;
            case TRIANGLE_STRIP -> MetalityFFM.TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLES, TRIANGLE_FAN, QUADS -> MetalityFFM.TOPOLOGY_TRIANGLES;
        };
    }
}

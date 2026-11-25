package dev.metality.api;

import dev.metality.api.shader.ShaderPackFormat;

/**
 * 引擎即時效能與 TBDR/UMA 資源遙測介面。
 */
public interface MetalityTelemetry {

    double lastGpuFrameTimeMs();

    float recommendedThermalRenderScale();

    int activeSectionCount();

    long savedTbdrBytesPerFrame();

    default long currentAllocatedUmaBytes() {
        return 0L;
    }

    default int thermalStateLevel() {
        return 0;
    }

    default float umaWorkingSetUtilization() {
        return 0.0f;
    }

    default ShaderPackFormat activePackFormat() {
        return ShaderPackFormat.UNKNOWN;
    }
}

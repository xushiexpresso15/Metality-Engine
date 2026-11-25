package dev.metality.api;

/**
 * 唯讀硬體能力摘要，供外部模組與光影系統進行功能降級或高階特性切換。
 */
public record MetalityCapabilities(
        String deviceName,
        int appleGpuFamily,
        long recommendedWorkingSetBytes,
        boolean unifiedMemory,
        boolean argumentBuffersTier2,
        boolean hardwareRayTracing,
        boolean metalFXTemporal,
        boolean metalFXDenoised,
        boolean metal4CommandQueue
) {}

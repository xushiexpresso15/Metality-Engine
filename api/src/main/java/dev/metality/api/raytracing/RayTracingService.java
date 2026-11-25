package dev.metality.api.raytracing;

/**
 * 對外穩定光線追蹤服務介面（支援 M3/M4/M5 硬體 BVH 光追與 M1/M2 體素 DDA 後備管線）。
 */
public interface RayTracingService {

    boolean isHardwareAccelerated();

    boolean isEnabled();

    void setEnabled(boolean enabled);

    int activeSectionBlasCount();
}

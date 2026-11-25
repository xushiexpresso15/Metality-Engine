package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.commands.GpuFence;
import dev.metality.backend.MetalGpuDevice;

/**
 * 基於 MTLSharedEvent frameId 之 RenderPearl GPU Fence 實作。
 */
public final class MetalPearlFence implements GpuFence {

    private final MetalDeviceBackend deviceBackend;
    private final long targetFrameId;
    private boolean signaled;
    private boolean closed;

    public MetalPearlFence(MetalDeviceBackend deviceBackend, long targetFrameId) {
        this.deviceBackend = deviceBackend;
        this.targetFrameId = targetFrameId;
        this.signaled = targetFrameId <= 0L;
    }

    @Override
    public boolean awaitCompletion(long timeoutNs) {
        if (signaled || closed) {
            return true;
        }
        MetalGpuDevice metalDevice = deviceBackend.metalDevice();
        if (metalDevice.isFrameOpen() && targetFrameId >= metalDevice.activeFrameId()) {
            if (timeoutNs == 0L) {
                return false;
            }
            deviceBackend.flushOpenCommandBufferWithoutPresent();
        }
        long timeoutMs = timeoutNs == NO_TIMEOUT
                ? 5000L
                : (timeoutNs <= 0L ? 0L : Math.max(1L, Math.min(5000L, timeoutNs / 1_000_000L)));
        boolean ok = metalDevice.waitFrameComplete(targetFrameId, timeoutMs);
        if (ok) {
            signaled = true;
        }
        return ok;
    }

    @Override
    public void close() {
        closed = true;
    }
}

package dev.metality.backend;

import dev.metality.nativebridge.MetalityFFM;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * 對應底層 Metal UMA 真零拷貝共用緩衝區，關閉時透過 MTLSharedEvent 延遲回收。
 */
public final class MetalGpuBuffer implements AutoCloseable {

    private final MetalGpuDevice device;
    private final long handle;
    private final MemorySegment cpuSegment;
    private final long gpuAddress;
    private boolean retired = false;

    public MetalGpuBuffer(MetalGpuDevice device, long sizeBytes, boolean writeCombined) {
        this.device = device;
        MetalityFFM.SharedUmaAllocation alloc =
                device.ffm().allocateZeroCopyBuffer(device.nativeContext(), sizeBytes, writeCombined);
        this.handle = alloc.handle();
        this.cpuSegment = alloc.cpuSegment();
        this.gpuAddress = alloc.gpuAddress();
    }

    public void writeBytes(ByteBuffer src, long dstByteOffset) {
        if (retired) {
            throw new IllegalStateException("Cannot write to a retired MetalGpuBuffer");
        }
        int remaining = src.remaining();
        if (remaining == 0) {
            return;
        }
        MemorySegment.copy(MemorySegment.ofBuffer(src), 0, cpuSegment, dstByteOffset, remaining);
    }

    public long handle() {
        return handle;
    }

    public long sizeBytes() {
        return cpuSegment.byteSize();
    }

    public long gpuAddress() {
        return gpuAddress;
    }

    public MemorySegment cpuSegment() {
        return cpuSegment;
    }

    public boolean isRetired() {
        return retired;
    }

    @Override
    public void close() {
        if (!retired) {
            retired = true;
            device.ffm().retireBuffer(device.nativeContext(), handle);
        }
    }
}

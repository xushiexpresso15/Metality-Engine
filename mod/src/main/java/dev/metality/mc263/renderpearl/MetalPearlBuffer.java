package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import dev.metality.backend.MetalGpuBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * RenderPearl GpuBuffer 之 UMA 真零拷貝實作：map() 直接回傳 MTLBuffer 實體共用記憶體視圖。
 */
public final class MetalPearlBuffer extends BaseGpuBuffer {

    private final MetalGpuBuffer nativeBuffer;

    public MetalPearlBuffer(MetalGpuBuffer nativeBuffer, int usage, long logicalSize) {
        super(usage, logicalSize);
        this.nativeBuffer = nativeBuffer;
    }

    public MetalGpuBuffer nativeBuffer() {
        return nativeBuffer;
    }

    @Override
    public boolean isClosed() {
        return nativeBuffer.isRetired();
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        checkCanBeUsed();
        if (offset < 0 || length < 0 || offset + length > size()) {
            throw new IllegalArgumentException("Map range [" + offset + ", " + (offset + length) + ") out of bounds (" + size() + ")");
        }
        ByteBuffer mapped = nativeBuffer.cpuSegment()
                .asSlice(offset, length)
                .asByteBuffer()
                .order(ByteOrder.nativeOrder());
        return new GpuBufferSlice.MappedView(slice(offset, length), mapped, () -> {});
    }

    @Override
    public void close() {
        nativeBuffer.close();
    }

    public static boolean isWriteCombined(int usage) {
        return (usage & GpuBuffer.USAGE_MAP_READ) == 0;
    }
}

package dev.metality.backend;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * 原生 MTLTexture 封裝（支援 UMA Shared 直寫、Private 渲染目標與 Memoryless TBDR 附件）。
 */
public final class MetalGpuTexture implements AutoCloseable {

    private final MetalGpuDevice device;
    private final long handle;
    private final int width;
    private final int height;
    private final int mipLevels;
    private final int format;
    private boolean closed = false;

    public MetalGpuTexture(MetalGpuDevice device, int width, int height, int mipLevels, int format, int usageFlags) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.mipLevels = mipLevels;
        this.format = format;
        this.handle = device.ffm().createTexture(device.nativeContext(), width, height, mipLevels, format, usageFlags);
        if (this.handle == 0L) {
            throw new IllegalStateException("Failed to allocate native MTLTexture (" + width + "x" + height + ")");
        }
    }

    private MetalGpuTexture(MetalGpuDevice device, long viewHandle, int width, int height, int mipLevels, int format) {
        this.device = device;
        this.handle = viewHandle;
        this.width = width;
        this.height = height;
        this.mipLevels = mipLevels;
        this.format = format;
    }

    public MetalGpuTexture createMipView(int baseMipLevel, int mipLevelCount) {
        if (closed) {
            throw new IllegalStateException("Texture already closed");
        }
        int safeBase = Math.max(0, Math.min(baseMipLevel, mipLevels - 1));
        int safeCount = Math.max(1, Math.min(mipLevelCount, mipLevels - safeBase));
        long viewHandle = device.ffm().createTextureView(device.nativeContext(), handle, safeBase, safeCount);
        if (viewHandle == 0L) {
            throw new IllegalStateException("Failed to create MTLTexture view (baseMip=" + safeBase + ", count=" + safeCount + ")");
        }
        return new MetalGpuTexture(
                device,
                viewHandle,
                Math.max(1, width >> safeBase),
                Math.max(1, height >> safeBase),
                safeCount,
                format
        );
    }

    public void uploadSubImage(int mipLevel, int x, int y, int regionWidth, int regionHeight, ByteBuffer pixels, int bytesPerRow) {
        uploadSliceSubImage(mipLevel, 0, x, y, regionWidth, regionHeight, pixels, bytesPerRow);
    }

    public void uploadSliceSubImage(int mipLevel, int arraySlice, int x, int y, int regionWidth, int regionHeight, ByteBuffer pixels, int bytesPerRow) {
        if (closed) {
            throw new IllegalStateException("Texture already closed");
        }
        if (pixels.isDirect()) {
            MemorySegment seg = MemorySegment.ofBuffer(pixels);
            device.ffm().uploadTextureSliceRegion(
                    device.nativeContext(), handle, mipLevel, arraySlice, x, y, regionWidth, regionHeight, seg, bytesPerRow
            );
        } else {
            try (java.lang.foreign.Arena temp = java.lang.foreign.Arena.ofConfined()) {
                int len = pixels.remaining();
                MemorySegment staging = temp.allocate(len);
                MemorySegment.copy(MemorySegment.ofBuffer(pixels), 0, staging, 0, len);
                device.ffm().uploadTextureSliceRegion(
                        device.nativeContext(), handle, mipLevel, arraySlice, x, y, regionWidth, regionHeight, staging, bytesPerRow
                );
            }
        }
    }

    public long handle() {
        return handle;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int mipLevels() {
        return mipLevels;
    }

    public int format() {
        return format;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            device.ffm().destroyTexture(device.nativeContext(), handle);
        }
    }
}

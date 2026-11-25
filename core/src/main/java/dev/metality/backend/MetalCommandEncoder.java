package dev.metality.backend;

import dev.metality.nativebridge.MetalityFFM;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/**
 * 原生 Metal CommandEncoder 封裝：負責排程 RenderPass、UMA 寫入與非渲染清理指令。
 */
public final class MetalCommandEncoder {

    private final MetalGpuDevice device;

    public MetalCommandEncoder(MetalGpuDevice device) {
        this.device = device;
    }

    public MetalRenderPass beginRenderPass(
            MetalGpuTexture colorTarget,
            MetalGpuTexture depthTarget,
            float[] clearRgba,
            Double clearDepth,
            boolean storeColor,
            boolean storeDepth
    ) {
        device.ensureFrameStarted();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment info = temp.allocate(MetalityFFM.RENDER_PASS_BEGIN_LAYOUT);
            info.set(ValueLayout.JAVA_LONG, 0, colorTarget != null ? colorTarget.handle() : 0L);
            info.set(ValueLayout.JAVA_LONG, 8, depthTarget != null ? depthTarget.handle() : 0L);

            if (clearRgba != null && clearRgba.length == 4) {
                info.set(ValueLayout.JAVA_FLOAT, 16, clearRgba[0]);
                info.set(ValueLayout.JAVA_FLOAT, 20, clearRgba[1]);
                info.set(ValueLayout.JAVA_FLOAT, 24, clearRgba[2]);
                info.set(ValueLayout.JAVA_FLOAT, 28, clearRgba[3]);
                info.set(ValueLayout.JAVA_BYTE, 40, (byte) 1);
            } else {
                info.set(ValueLayout.JAVA_BYTE, 40, (byte) 0);
            }

            if (clearDepth != null) {
                info.set(ValueLayout.JAVA_DOUBLE, 32, clearDepth);
                info.set(ValueLayout.JAVA_BYTE, 41, (byte) 1);
            } else {
                info.set(ValueLayout.JAVA_BYTE, 41, (byte) 0);
            }

            info.set(ValueLayout.JAVA_BYTE, 42, (byte) (storeColor ? 1 : 0));
            info.set(ValueLayout.JAVA_BYTE, 43, (byte) (storeDepth ? 1 : 0));

            boolean started = device.ffm().cmdBeginRenderPass(device.nativeContext(), info);
            if (!started) {
                throw new IllegalStateException("Failed to begin Metal RenderPass!");
            }
            return new MetalRenderPass(device);
        }
    }

    public MetalRenderPass beginMrtRenderPass(
            MetalGpuTexture[] colorTargets,
            MetalGpuTexture depthTarget,
            float[][] clearRgbas,
            Double clearDepth,
            boolean[] storeColors,
            boolean storeDepth
    ) {
        device.ensureFrameStarted();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment info = temp.allocate(MetalityFFM.MRT_RENDER_PASS_BEGIN_LAYOUT);
            int colorCount = Math.min(colorTargets != null ? colorTargets.length : 0, 4);
            for (int i = 0; i < colorCount; ++i) {
                info.set(ValueLayout.JAVA_LONG, (long) i * 8L, colorTargets[i] != null ? colorTargets[i].handle() : 0L);
                float[] rgba = (clearRgbas != null && i < clearRgbas.length) ? clearRgbas[i] : null;
                if (rgba != null && rgba.length == 4) {
                    long base = 40L + (long) i * 16L;
                    info.set(ValueLayout.JAVA_FLOAT, base, rgba[0]);
                    info.set(ValueLayout.JAVA_FLOAT, base + 4L, rgba[1]);
                    info.set(ValueLayout.JAVA_FLOAT, base + 8L, rgba[2]);
                    info.set(ValueLayout.JAVA_FLOAT, base + 12L, rgba[3]);
                    info.set(ValueLayout.JAVA_BYTE, 112L + i, (byte) 1);
                } else {
                    info.set(ValueLayout.JAVA_BYTE, 112L + i, (byte) 0);
                }
                boolean store = storeColors == null || i >= storeColors.length || storeColors[i];
                info.set(ValueLayout.JAVA_BYTE, 116L + i, (byte) (store ? 1 : 0));
            }
            info.set(ValueLayout.JAVA_LONG, 32L, depthTarget != null ? depthTarget.handle() : 0L);
            if (clearDepth != null) {
                info.set(ValueLayout.JAVA_DOUBLE, 104L, clearDepth);
                info.set(ValueLayout.JAVA_BYTE, 121L, (byte) 1);
            } else {
                info.set(ValueLayout.JAVA_BYTE, 121L, (byte) 0);
            }
            info.set(ValueLayout.JAVA_BYTE, 120L, (byte) colorCount);
            info.set(ValueLayout.JAVA_BYTE, 122L, (byte) (storeDepth ? 1 : 0));

            boolean started = device.ffm().cmdBeginRenderPassMrt(device.nativeContext(), info);
            if (!started) {
                throw new IllegalStateException("Failed to begin Metal MRT RenderPass!");
            }
            return new MetalRenderPass(device);
        }
    }

    public void clearColorAndDepth(
            MetalGpuTexture colorTarget,
            float r, float g, float b, float a,
            MetalGpuTexture depthTarget,
            double clearDepth
    ) {
        device.ensureFrameStarted();
        device.ffm().cmdClearColorDepth(
                device.nativeContext(),
                colorTarget != null ? colorTarget.handle() : 0L,
                r, g, b, a,
                depthTarget != null ? depthTarget.handle() : 0L,
                clearDepth
        );
    }

    public void clearColorAndDepthRegion(
            MetalGpuTexture colorTarget,
            float r, float g, float b, float a,
            MetalGpuTexture depthTarget,
            double clearDepth,
            int mipLevel,
            int x,
            int y,
            int width,
            int height
    ) {
        device.ensureFrameStarted();
        device.ffm().cmdClearColorDepthRect(
                device.nativeContext(),
                colorTarget != null ? colorTarget.handle() : 0L,
                r, g, b, a,
                depthTarget != null ? depthTarget.handle() : 0L,
                clearDepth,
                mipLevel, x, y, width, height
        );
    }

    public void writeToBuffer(MetalGpuBuffer target, ByteBuffer source, long offset) {
        target.writeBytes(source, offset);
    }

    public void writeToTexture(MetalGpuTexture target, int mipLevel, int x, int y, int width, int height, ByteBuffer directPixels, int bytesPerRow) {
        target.uploadSubImage(mipLevel, x, y, width, height, directPixels, bytesPerRow);
    }

    public void writeSliceToTexture(MetalGpuTexture target, int mipLevel, int arraySlice, int x, int y, int width, int height, ByteBuffer pixels, int bytesPerRow) {
        target.uploadSliceSubImage(mipLevel, arraySlice, x, y, width, height, pixels, bytesPerRow);
    }

    public void copyBuffer(MetalGpuBuffer source, long sourceOffset, MetalGpuBuffer destination, long destOffset, long sizeBytes) {
        device.ensureFrameStarted();
        device.ffm().cmdCopyBuffer(device.nativeContext(), source.handle(), sourceOffset, destination.handle(), destOffset, sizeBytes);
    }

    public void copyTextureToBuffer(MetalGpuTexture source, MetalGpuBuffer destination, long destOffset, int bytesPerRow, int width, int height) {
        device.ensureFrameStarted();
        device.ffm().cmdCopyTextureToBuffer(device.nativeContext(), source.handle(), destination.handle(), destOffset, bytesPerRow, width, height);
    }

    public void copyTextureRegion(MetalGpuTexture source, MetalGpuTexture destination, int mipLevel, int dstX, int dstY, int srcX, int srcY, int width, int height) {
        device.ensureFrameStarted();
        device.ffm().cmdCopyTextureRegion(device.nativeContext(), source.handle(), destination.handle(), mipLevel, dstX, dstY, srcX, srcY, width, height);
    }

    public void presentBlitTexture(MetalGpuTexture sourceColorTexture) {
        device.ensureFrameStarted();
        device.ffm().cmdPresentBlitTexture(device.nativeContext(), sourceColorTexture.handle());
    }
}

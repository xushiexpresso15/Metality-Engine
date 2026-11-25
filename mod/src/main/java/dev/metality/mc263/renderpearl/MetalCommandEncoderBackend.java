package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import dev.metality.backend.MetalCommandEncoder;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import dev.metality.backend.MetalRenderPass;
import dev.metality.nativebridge.MetalityFFM;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;

/**
 * RenderPearl CommandEncoderBackend 之 Metal 實作。
 */
public final class MetalCommandEncoderBackend implements CommandEncoderBackend {

    private final MetalDeviceBackend deviceBackend;
    private final MetalGpuDevice metalDevice;
    private final MetalCommandEncoder nativeEncoder;
    private final MetalTransientMemory transientMemory;
    private MetalRenderPassBackend activePass;
    private long lastSubmittedFrameId = 0L;

    public MetalCommandEncoderBackend(MetalDeviceBackend deviceBackend) {
        this.deviceBackend = deviceBackend;
        this.metalDevice = deviceBackend.metalDevice();
        this.nativeEncoder = metalDevice.commandEncoder();
        this.transientMemory = new MetalTransientMemory(metalDevice);
    }

    @Override
    public void submit() {
        submitRenderPass();
        boolean shouldPresent = deviceBackend.isSurfaceBlitEncoded() && !deviceBackend.isSurfacePresented();
        if (metalDevice.isFrameOpen() || shouldPresent) {
            if (shouldPresent) {
                metalDevice.ensureFrameStarted();
            }
            lastSubmittedFrameId = metalDevice.endFrame(shouldPresent);
            if (shouldPresent) {
                deviceBackend.markSurfacePresented();
            }
            deviceBackend.flushReadbackCallbacks(lastSubmittedFrameId);
            transientMemory.rotateFrame(lastSubmittedFrameId);
        }
    }

    void onExternalFrameSubmitted(long frameId) {
        this.lastSubmittedFrameId = Math.max(this.lastSubmittedFrameId, frameId);
        transientMemory.rotateFrame(frameId);
    }

    @Override
    public TransientMemory transientMemory() {
        return transientMemory;
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        submitRenderPass();

        var colorAtts = descriptor.colorAttachments();
        int rawCount = colorAtts != null ? Math.min(4, colorAtts.size()) : 0;
        MetalGpuTexture[] colorTexs = new MetalGpuTexture[rawCount];
        int[] colorFormats = new int[rawCount];
        float[][] clearRgbas = new float[rawCount][];
        boolean[] storeColors = new boolean[rawCount];
        int width = 1;
        int height = 1;

        for (int i = 0; i < rawCount; ++i) {
            var att = colorAtts.get(i);
            storeColors[i] = true;
            if (att != null && att.textureView() instanceof MetalPearlTextureView view) {
                MetalGpuTexture tex = view.resolvedNativeTexture();
                colorTexs[i] = tex;
                colorFormats[i] = tex.format();
                width = Math.max(width, view.getWidth(0));
                height = Math.max(height, view.getHeight(0));
                Optional<Vector4fc> clearOpt = att.clearValue();
                if (clearOpt != null && clearOpt.isPresent()) {
                    Vector4fc v = clearOpt.get();
                    clearRgbas[i] = new float[]{v.x(), v.y(), v.z(), v.w()};
                }
            }
        }

        MetalGpuTexture depthTex = null;
        int depthFormat = 0;
        Double clearDepth = null;
        var depthAtt = descriptor.depthAttachment();
        if (depthAtt != null && depthAtt.textureView() instanceof MetalPearlTextureView dView) {
            depthTex = dView.resolvedNativeTexture();
            depthFormat = depthTex.format();
            if (rawCount == 0 || colorTexs[0] == null) {
                width = Math.max(width, dView.getWidth(0));
                height = Math.max(height, dView.getHeight(0));
            }
            OptionalDouble cd = depthAtt.clearValue();
            if (cd != null && cd.isPresent()) {
                clearDepth = cd.getAsDouble();
            }
        }

        if (descriptor.renderArea() != null) {
            width = Math.max(width, descriptor.renderArea().width());
            height = Math.max(height, descriptor.renderArea().height());
        }

        MetalRenderPass pass = rawCount <= 1
                ? nativeEncoder.beginRenderPass(
                        rawCount == 1 ? colorTexs[0] : null,
                        depthTex,
                        rawCount == 1 ? clearRgbas[0] : null,
                        clearDepth,
                        true,
                        true
                )
                : nativeEncoder.beginMrtRenderPass(colorTexs, depthTex, clearRgbas, clearDepth, storeColors, true);
        activePass = new MetalRenderPassBackend(deviceBackend, pass, colorFormats, depthFormat, width, height);
        return activePass;
    }

    @Override
    public void submitRenderPass() {
        if (activePass != null) {
            activePass.finish();
            activePass = null;
        }
    }

    @Override
    public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
        submitRenderPass();
        if (colorTexture instanceof MetalPearlTexture tex) {
            nativeEncoder.clearColorAndDepth(
                    tex.nativeTexture(), clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), null, 1.0
            );
        }
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
        submitRenderPass();
        MetalGpuTexture ct = colorTexture instanceof MetalPearlTexture pt ? pt.nativeTexture() : null;
        MetalGpuTexture dt = depthTexture instanceof MetalPearlTexture dtTex ? dtTex.nativeTexture() : null;
        nativeEncoder.clearColorAndDepth(
                ct, clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), dt, clearDepth
        );
    }

    @Override
    public void clearColorAndDepthTextures(
            GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth,
            int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel
    ) {
        submitRenderPass();
        MetalGpuTexture ct = colorTexture instanceof MetalPearlTexture pt ? pt.nativeTexture() : null;
        MetalGpuTexture dt = depthTexture instanceof MetalPearlTexture dtTex ? dtTex.nativeTexture() : null;
        nativeEncoder.clearColorAndDepthRegion(
                ct, clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(),
                dt, clearDepth, mipLevel, regionX, regionY, regionWidth, regionHeight
        );
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
        submitRenderPass();
        if (depthTexture instanceof MetalPearlTexture dtTex) {
            nativeEncoder.clearColorAndDepth(null, 0f, 0f, 0f, 1f, dtTex.nativeTexture(), clearDepth);
        }
    }

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        submitRenderPass();
        if (destination == null || data == null || !(destination.buffer() instanceof MetalPearlBuffer buf)) {
            return;
        }
        int len = (int) Math.min(data.remaining(), destination.length());
        if (len <= 0) {
            return;
        }
        ByteBuffer sliceData = data.duplicate();
        sliceData.limit(sliceData.position() + len);
        GpuBufferSlice staging = transientMemory.uploadStaging(
                java.util.List.of(sliceData), 16L, GpuBuffer.USAGE_COPY_SRC, 0L, 0L);
        if (staging.buffer() instanceof MetalPearlBuffer stagingBuf) {
            nativeEncoder.copyBuffer(
                    stagingBuf.nativeBuffer(), staging.offset(), buf.nativeBuffer(), destination.offset(), len);
        }
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        submitRenderPass();
        if (source != null && target != null
                && source.buffer() instanceof MetalPearlBuffer srcBuf
                && target.buffer() instanceof MetalPearlBuffer dstBuf) {
            long len = Math.min(source.length(), target.length());
            if (len > 0L) {
                nativeEncoder.copyBuffer(srcBuf.nativeBuffer(), source.offset(), dstBuf.nativeBuffer(), target.offset(), len);
            }
        }
    }

    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
        submitRenderPass();
        if (destination instanceof MetalPearlTexture tex && source != null && width > 0 && height > 0) {
            int bytesPerPixel = Math.max(1, destination.getFormat().blockSize());
            int bpr = width * bytesPerPixel;
            nativeEncoder.writeSliceToTexture(tex.nativeTexture(), mipLevel, depthOrLayer, destX, destY, width, height, source.duplicate(), bpr);
        }
    }

    @Override
    public void copyBufferToTexture(
            GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight,
            GpuTexture destination, int destinationX, int destinationY, int copyWidth, int copyHeight,
            int mipLevel, int arrayLayer
    ) {
        submitRenderPass();
        if (source == null || !(source.buffer() instanceof MetalPearlBuffer srcBuf)
                || !(destination instanceof MetalPearlTexture dstTex) || copyWidth <= 0 || copyHeight <= 0) {
            return;
        }
        int bpp = Math.max(1, destination.getFormat().blockSize());
        int rowStride = Math.max(1, sourceWidth) * bpp;
        long startByteOffset = source.offset() + (long) sourceY * rowStride + (long) sourceX * bpp;
        long sliceBytes = Math.min(source.buffer().size() - startByteOffset, (long) Math.max(1, copyHeight) * rowStride);
        if (sliceBytes <= 0) {
            return;
        }
        ByteBuffer cpuView = srcBuf.nativeBuffer().cpuSegment().asSlice(startByteOffset, sliceBytes).asByteBuffer();
        nativeEncoder.writeSliceToTexture(dstTex.nativeTexture(), mipLevel, arrayLayer, destinationX, destinationY, copyWidth, copyHeight, cpuView, rowStride);
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        if (source == null) {
            return;
        }
        copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int width, int height) {
        submitRenderPass();
        if (source instanceof MetalPearlTexture srcTex && destination instanceof MetalPearlBuffer dstBuf
                && width > 0 && height > 0) {
            int bpp = Math.max(1, source.getFormat().blockSize());
            int bpr = Math.max(1, width) * bpp;
            nativeEncoder.copyTextureToBuffer(srcTex.nativeTexture(), dstBuf.nativeBuffer(), offset, bpr, width, height);
        }
        deviceBackend.enqueueReadbackCallback(callback);
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width, int height) {
        submitRenderPass();
        if (source instanceof MetalPearlTexture srcTex && destination instanceof MetalPearlTexture dstTex
                && width > 0 && height > 0) {
            nativeEncoder.copyTextureRegion(
                    srcTex.nativeTexture(), dstTex.nativeTexture(), mipLevel, destX, destY, sourceX, sourceY, width, height
            );
        }
    }

    @Override
    public GpuFence createFence() {
        long targetFrame = metalDevice.isFrameOpen() ? metalDevice.activeFrameId() : lastSubmittedFrameId;
        return new MetalPearlFence(deviceBackend, targetFrame);
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        if (pool instanceof MetalQueryPool metalPool) {
            metalPool.record(index, System.nanoTime());
        }
    }

    public void close() {
        submitRenderPass();
        transientMemory.close();
    }
}

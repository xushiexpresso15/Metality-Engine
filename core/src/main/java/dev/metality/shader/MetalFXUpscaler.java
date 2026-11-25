package dev.metality.shader;

import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import dev.metality.nativebridge.MetalityFFM;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Apple Silicon 原生 MetalFX 超解析度管理封裝（Temporal / Spatial）。
 */
public final class MetalFXUpscaler implements AutoCloseable {

    private final MetalGpuDevice device;
    private final int mode;
    private final int inputWidth;
    private final int inputHeight;
    private final int outputWidth;
    private final int outputHeight;
    private final MemorySegment nativeHandle;
    private boolean closed = false;

    public MetalFXUpscaler(
            MetalGpuDevice device,
            int mode,
            int inputWidth,
            int inputHeight,
            int outputWidth,
            int outputHeight,
            int colorFormat,
            int depthFormat,
            int motionFormat,
            int outputFormat,
            boolean autoExposure,
            boolean depthReversed
    ) {
        this.device = device;
        this.mode = mode;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment desc = temp.allocate(MetalityFFM.UPSCALER_DESC_LAYOUT);
            desc.set(ValueLayout.JAVA_INT, 0, mode);
            desc.set(ValueLayout.JAVA_INT, 4, inputWidth);
            desc.set(ValueLayout.JAVA_INT, 8, inputHeight);
            desc.set(ValueLayout.JAVA_INT, 12, outputWidth);
            desc.set(ValueLayout.JAVA_INT, 16, outputHeight);
            desc.set(ValueLayout.JAVA_INT, 20, colorFormat);
            desc.set(ValueLayout.JAVA_INT, 24, depthFormat);
            desc.set(ValueLayout.JAVA_INT, 28, motionFormat);
            desc.set(ValueLayout.JAVA_INT, 32, outputFormat);
            desc.set(ValueLayout.JAVA_BYTE, 36, (byte) (autoExposure ? 1 : 0));
            desc.set(ValueLayout.JAVA_BYTE, 37, (byte) (depthReversed ? 1 : 0));

            this.nativeHandle = device.ffm().createUpscaler(device.nativeContext(), desc);
            if (MemorySegment.NULL.equals(this.nativeHandle)) {
                throw new IllegalStateException("Failed to create MetalFX scaler (" + inputWidth + "x" + inputHeight
                        + " -> " + outputWidth + "x" + outputHeight + ")");
            }
        }
    }

    public float[] nextHaltonJitter(long frameIndex) {
        return device.ffm().haltonJitter(frameIndex, inputWidth, outputWidth);
    }

    public boolean encode(
            MetalGpuTexture colorTex,
            MetalGpuTexture depthTex,
            MetalGpuTexture motionTex,
            MetalGpuTexture outputTex,
            int contentWidth,
            int contentHeight,
            float jitterX,
            float jitterY,
            float motionScaleX,
            float motionScaleY,
            boolean resetHistory
    ) {
        if (closed) {
            throw new IllegalStateException("MetalFXUpscaler already closed");
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment info = temp.allocate(MetalityFFM.UPSCALE_DISPATCH_LAYOUT);
            info.set(ValueLayout.JAVA_LONG, 0, colorTex.handle());
            info.set(ValueLayout.JAVA_LONG, 8, depthTex != null ? depthTex.handle() : 0L);
            info.set(ValueLayout.JAVA_LONG, 16, motionTex != null ? motionTex.handle() : 0L);
            info.set(ValueLayout.JAVA_LONG, 24, outputTex.handle());
            info.set(ValueLayout.JAVA_INT, 32, contentWidth);
            info.set(ValueLayout.JAVA_INT, 36, contentHeight);
            info.set(ValueLayout.JAVA_FLOAT, 40, jitterX);
            info.set(ValueLayout.JAVA_FLOAT, 44, jitterY);
            info.set(ValueLayout.JAVA_FLOAT, 48, motionScaleX);
            info.set(ValueLayout.JAVA_FLOAT, 52, motionScaleY);
            info.set(ValueLayout.JAVA_BYTE, 56, (byte) (resetHistory ? 1 : 0));
            return device.ffm().encodeUpscaler(device.nativeContext(), nativeHandle, info);
        }
    }

    public boolean encodeDenoised(
            MetalGpuTexture colorTex,
            MetalGpuTexture depthTex,
            MetalGpuTexture motionTex,
            MetalGpuTexture diffuseAlbedoTex,
            MetalGpuTexture specularAlbedoTex,
            MetalGpuTexture normalTex,
            MetalGpuTexture roughnessTex,
            MetalGpuTexture outputTex,
            float jitterX,
            float jitterY,
            float motionScaleX,
            float motionScaleY,
            boolean resetHistory
    ) {
        if (closed) {
            throw new IllegalStateException("MetalFXUpscaler already closed");
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment info = temp.allocate(MetalityFFM.DENOISED_UPSCALE_DISPATCH_LAYOUT);
            info.set(ValueLayout.JAVA_LONG, 0, colorTex.handle());
            info.set(ValueLayout.JAVA_LONG, 8, depthTex.handle());
            info.set(ValueLayout.JAVA_LONG, 16, motionTex.handle());
            info.set(ValueLayout.JAVA_LONG, 24, diffuseAlbedoTex.handle());
            info.set(ValueLayout.JAVA_LONG, 32, specularAlbedoTex.handle());
            info.set(ValueLayout.JAVA_LONG, 40, normalTex.handle());
            info.set(ValueLayout.JAVA_LONG, 48, roughnessTex.handle());
            info.set(ValueLayout.JAVA_LONG, 56, outputTex.handle());
            info.set(ValueLayout.JAVA_FLOAT, 64, jitterX);
            info.set(ValueLayout.JAVA_FLOAT, 68, jitterY);
            info.set(ValueLayout.JAVA_FLOAT, 72, motionScaleX);
            info.set(ValueLayout.JAVA_FLOAT, 76, motionScaleY);
            info.set(ValueLayout.JAVA_BYTE, 80, (byte) (resetHistory ? 1 : 0));
            return device.ffm().encodeUpscalerDenoised(device.nativeContext(), nativeHandle, info);
        }
    }

    public int mode() {
        return mode;
    }

    public int outputWidth() {
        return outputWidth;
    }

    public int outputHeight() {
        return outputHeight;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            device.ffm().destroyUpscaler(nativeHandle);
        }
    }
}

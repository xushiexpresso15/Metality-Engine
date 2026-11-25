package dev.metality.shader;

import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * 即時多通道 HDR 世界光影合成器（Horizon SSAO + 陽光體積散射 + Dual-Kawase Bloom + Rain Sheen + ACES）。
 * 可直接由 Legacy Iris 光影選項驅動參數，於 Apple Silicon Metal 幀管線中就地合成至 MainTarget。
 */
public final class LiveShaderComposer implements AutoCloseable {

    public record ComposerParams(
            boolean enabled,
            float bloomStrength,
            float ssaoStrength,
            float godrayStrength,
            float exposure,
            float fogDensity,
            int tonemapMode
    ) {
        public static ComposerParams defaults() {
            return new ComposerParams(true, 0.42f, 0.65f, 0.72f, 1.12f, 0.0f, 0);
        }
    }

    private final MetalGpuDevice device;
    private MemorySegment nativeComposer;
    private volatile ComposerParams params = ComposerParams.defaults();

    public LiveShaderComposer(MetalGpuDevice device) {
        this.device = Objects.requireNonNull(device, "device");
        this.nativeComposer = MemorySegment.NULL;
    }

    private MemorySegment ensureNativeComposer() {
        if (MemorySegment.NULL.equals(nativeComposer)) {
            nativeComposer = device.ffm().composerCreate(device.nativeContext());
            if (MemorySegment.NULL.equals(nativeComposer)) {
                throw new IllegalStateException("Failed to create native MetalityComposer");
            }
        }
        return nativeComposer;
    }

    public void updateParams(ComposerParams params) {
        this.params = Objects.requireNonNull(params, "params");
    }

    public ComposerParams params() {
        return params;
    }

    public synchronized boolean execute(
            MetalGpuTexture colorTex,
            MetalGpuTexture depthTex,
            float[] invProj16,
            float[] viewRot16,
            float[] proj16,
            float sunX, float sunY, float sunZ,
            float skyR, float skyG, float skyB,
            float timeSeconds,
            float rainStrength,
            boolean depthReversed
    ) {
        ComposerParams p = this.params;
        if (!p.enabled() || colorTex == null || depthTex == null) {
            return false;
        }
        MemorySegment comp = ensureNativeComposer();
        return device.ffm().composerExecute(
                device.nativeContext(),
                comp,
                colorTex.handle(),
                depthTex.handle(),
                invProj16,
                viewRot16,
                proj16,
                sunX, sunY, sunZ,
                skyR, skyG, skyB,
                colorTex.width(),
                colorTex.height(),
                timeSeconds,
                rainStrength,
                p.bloomStrength(),
                p.ssaoStrength(),
                p.godrayStrength(),
                p.exposure(),
                p.fogDensity(),
                p.tonemapMode(),
                depthReversed
        );
    }

    @Override
    public synchronized void close() {
        if (!MemorySegment.NULL.equals(nativeComposer)) {
            device.ffm().composerDestroy(nativeComposer);
            nativeComposer = MemorySegment.NULL;
        }
    }
}

package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import dev.metality.backend.MetalGpuTexture;

/**
 * RenderPearl GpuTexture 之原生 MTLTexture 實作。
 */
public final class MetalPearlTexture extends BaseGpuTexture {

    private final MetalGpuTexture nativeTexture;

    public MetalPearlTexture(MetalGpuTexture nativeTexture, int usage, String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.nativeTexture = nativeTexture;
    }

    public MetalGpuTexture nativeTexture() {
        return nativeTexture;
    }

    @Override
    public boolean isClosed() {
        return nativeTexture.isClosed();
    }

    @Override
    public void close() {
        nativeTexture.close();
    }
}

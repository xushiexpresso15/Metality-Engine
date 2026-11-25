package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import dev.metality.backend.MetalGpuTexture;

/**
 * RenderPearl GpuTextureView 之原生 Metal TextureView 實作（精準隔離 baseMipLevel 與 mipLevels）。
 */
public final class MetalPearlTextureView extends BaseGpuTextureView {

    private final MetalGpuTexture resolvedTexture;
    private final boolean ownsSubView;
    private boolean closed = false;

    public MetalPearlTextureView(MetalPearlTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        MetalGpuTexture parent = texture.nativeTexture();
        if (baseMipLevel == 0 && mipLevels == texture.getMipLevels()) {
            this.resolvedTexture = parent;
            this.ownsSubView = false;
        } else {
            this.resolvedTexture = parent.createMipView(baseMipLevel, mipLevels);
            this.ownsSubView = true;
        }
    }

    public MetalPearlTexture metalTexture() {
        return (MetalPearlTexture) texture();
    }

    public MetalGpuTexture resolvedNativeTexture() {
        return resolvedTexture;
    }

    @Override
    public boolean isClosed() {
        return closed || texture().isClosed();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (ownsSubView) {
                resolvedTexture.close();
            }
        }
    }
}

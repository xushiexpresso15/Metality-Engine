package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

/**
 * RenderPearl GpuSampler 之原生 MTLSamplerState 包裝。
 */
public final class MetalPearlSampler implements GpuSampler {

    private final long nativeHandle;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed = false;

    public MetalPearlSampler(
            long nativeHandle,
            AddressMode addressModeU,
            AddressMode addressModeV,
            FilterMode minFilter,
            FilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod
    ) {
        this.nativeHandle = nativeHandle;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
    }

    public long nativeHandle() {
        return nativeHandle;
    }

    @Override public AddressMode getAddressModeU() { return addressModeU; }
    @Override public AddressMode getAddressModeV() { return addressModeV; }
    @Override public FilterMode getMinFilter() { return minFilter; }
    @Override public FilterMode getMagFilter() { return magFilter; }
    @Override public int getMaxAnisotropy() { return maxAnisotropy; }
    @Override public OptionalDouble getMaxLod() { return maxLod; }
    @Override public boolean isClosed() { return closed; }

    @Override
    public void close() {
        closed = true;
    }
}

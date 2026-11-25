package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.Arrays;
import java.util.OptionalLong;

/**
 * RenderPearl GPU 時間戳記查詢池。
 */
public final class MetalQueryPool implements GpuQueryPool {

    private final long[] samples;
    private boolean closed = false;

    public MetalQueryPool(int size) {
        this.samples = new long[Math.max(1, size)];
        Arrays.fill(this.samples, -1L);
    }

    public void record(int index, long timestampNs) {
        if (!closed && index >= 0 && index < samples.length) {
            samples[index] = timestampNs;
        }
    }

    @Override
    public int size() {
        return samples.length;
    }

    @Override
    public OptionalLong getValue(int index) {
        if (closed || index < 0 || index >= samples.length || samples[index] < 0L) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(samples[index]);
    }

    @Override
    public OptionalLong[] getValues(int startIndex, int count) {
        OptionalLong[] out = new OptionalLong[count];
        for (int i = 0; i < count; ++i) {
            out[i] = getValue(startIndex + i);
        }
        return out;
    }

    @Override
    public void close() {
        closed = true;
    }
}

package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import dev.metality.backend.MetalGpuDevice;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Apple Silicon UMA 零拷貝暫存記憶體分配器，供每幀 Uniform、動態頂點與 Staging 上傳使用。
 */
public final class MetalTransientMemory implements TransientMemory {

    private static final long DEFAULT_SLAB_BYTES = 4L * 1024L * 1024L;
    private static final int RING_SLOTS = 4;

    private final MetalGpuDevice metalDevice;
    private final List<Slab> activeSlabs = new ArrayList<>();
    private final Slab[] ringSlabs = new Slab[RING_SLOTS];
    private final long[] ringLastFrameIds = new long[RING_SLOTS];
    private int frameCursor = 0;
    private boolean currentSlabDirty = false;

    private record Slab(MetalPearlBuffer buffer, long capacity, long[] offsetPtr) {
        long remaining() {
            return capacity - offsetPtr[0];
        }
    }

    public MetalTransientMemory(MetalGpuDevice metalDevice) {
        this.metalDevice = metalDevice;
    }

    public synchronized void rotateFrame() {
        rotateFrame(metalDevice.activeFrameId());
    }

    public synchronized void rotateFrame(long submittedFrameId) {
        if (ringSlabs[frameCursor] != null && currentSlabDirty) {
            ringLastFrameIds[frameCursor] = submittedFrameId;
        }
        currentSlabDirty = false;
        for (Slab s : activeSlabs) {
            boolean inRing = false;
            for (Slab rs : ringSlabs) {
                if (s == rs) {
                    inRing = true;
                    break;
                }
            }
            if (!inRing) {
                s.buffer().close();
            }
        }
        activeSlabs.clear();
        frameCursor = (frameCursor + 1) % RING_SLOTS;
        Slab reused = ringSlabs[frameCursor];
        if (reused != null && !reused.buffer().isClosed()) {
            long prevFrameId = ringLastFrameIds[frameCursor];
            if (prevFrameId > 0L) {
                metalDevice.waitFrameComplete(prevFrameId, 5000L);
            }
            reused.offsetPtr()[0] = 0L;
            activeSlabs.add(reused);
        } else {
            ringSlabs[frameCursor] = null;
            ringLastFrameIds[frameCursor] = 0L;
        }
    }

    private synchronized GpuBufferSlice allocateSlice(long size, long alignment) {
        metalDevice.ensureFrameStarted();
        long align = Math.max(16L, alignment);
        long safeSize = Math.max(16L, size);
        for (Slab s : activeSlabs) {
            long alignedOff = (s.offsetPtr()[0] + align - 1L) & ~(align - 1L);
            if (alignedOff + safeSize <= s.capacity()) {
                s.offsetPtr()[0] = alignedOff + safeSize;
                if (s == ringSlabs[frameCursor]) {
                    currentSlabDirty = true;
                }
                return s.buffer().slice(alignedOff, safeSize);
            }
        }

        long slabSize = Math.max(DEFAULT_SLAB_BYTES, (safeSize + align + 4095L) & ~4095L);
        int usage = GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX
                | GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_SRC;
        var nativeBuf = metalDevice.createBuffer(slabSize, true);
        MetalPearlBuffer pearlBuf = new MetalPearlBuffer(nativeBuf, usage, slabSize);
        Slab fresh = new Slab(pearlBuf, slabSize, new long[]{safeSize});
        activeSlabs.add(fresh);
        if (slabSize == DEFAULT_SLAB_BYTES && ringSlabs[frameCursor] == null) {
            ringSlabs[frameCursor] = fresh;
            ringLastFrameIds[frameCursor] = 0L;
            currentSlabDirty = true;
        }
        return pearlBuf.slice(0L, safeSize);
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long unused0, long unused1) {
        return allocateSlice(size, alignment).map(false, true).data();
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long unused0, long unused1) {
        return allocateSlice(size, alignment).map(false, true);
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long unused0, long unused1) {
        return allocateSlice(size, alignment);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long unused0, long unused1) {
        return allocateSlice(size, alignment).map(false, true);
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> buffers, long alignment, int usage, long unused0, long unused1) {
        return uploadConcatenated(buffers, alignment);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> buffers, long alignment, int usage, long unused0, long unused1) {
        return uploadConcatenated(buffers, alignment);
    }

    private GpuBufferSlice uploadConcatenated(List<ByteBuffer> buffers, long alignment) {
        long total = 0L;
        for (ByteBuffer b : buffers) {
            total += b.remaining();
        }
        GpuBufferSlice slice = allocateSlice(total, alignment);
        try (GpuBufferSlice.MappedView mapped = slice.map(false, true)) {
            ByteBuffer dst = mapped.data().order(ByteOrder.nativeOrder());
            for (ByteBuffer b : buffers) {
                dst.put(b.duplicate());
            }
        }
        return slice;
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> buffers, long alignment, int usage) {
        return multiUploadList(buffers, alignment);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> buffers, long alignment, int usage) {
        return multiUploadList(buffers, alignment);
    }

    private List<GpuBufferSlice> multiUploadList(List<ByteBuffer> buffers, long alignment) {
        List<GpuBufferSlice> out = new ArrayList<>(buffers.size());
        for (ByteBuffer b : buffers) {
            int rem = b.remaining();
            GpuBufferSlice slice = allocateSlice(rem, alignment);
            if (rem > 0) {
                ((MetalPearlBuffer) slice.buffer()).nativeBuffer().writeBytes(b.duplicate(), slice.offset());
            }
            out.add(slice);
        }
        return out;
    }

    public synchronized void close() {
        for (Slab s : activeSlabs) {
            s.buffer().close();
        }
        activeSlabs.clear();
        for (int i = 0; i < ringSlabs.length; ++i) {
            if (ringSlabs[i] != null && !ringSlabs[i].buffer().isClosed()) {
                ringSlabs[i].buffer().close();
            }
            ringSlabs[i] = null;
        }
    }
}

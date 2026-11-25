package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import dev.metality.backend.MetalGpuBuffer;
import dev.metality.backend.MetalRenderPass;
import dev.metality.nativebridge.MetalityFFM;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;

/**
 * 將 RenderPearl RenderPass 指令即時錄製至原生 MTLRenderCommandEncoder。
 */
public final class MetalRenderPassBackend implements RenderPassBackend {

    private final MetalDeviceBackend deviceBackend;
    private final MetalRenderPass nativePass;
    private final int[] colorFormats;
    private final int depthFormat;
    private final int passWidth;
    private final int passHeight;

    private MetalRenderPipeline boundPipeline;
    private MetalPearlBuffer boundIndexBuffer;
    private IndexType boundIndexType = IndexType.INT;
    private boolean ended = false;

    public MetalRenderPassBackend(
            MetalDeviceBackend deviceBackend,
            MetalRenderPass nativePass,
            int colorFormat,
            int depthFormat,
            int passWidth,
            int passHeight
    ) {
        this(
                deviceBackend,
                nativePass,
                colorFormat != 0 ? new int[]{colorFormat} : new int[0],
                depthFormat,
                passWidth,
                passHeight
        );
    }

    public MetalRenderPassBackend(
            MetalDeviceBackend deviceBackend,
            MetalRenderPass nativePass,
            int[] colorFormats,
            int depthFormat,
            int passWidth,
            int passHeight
    ) {
        this.deviceBackend = deviceBackend;
        this.nativePass = nativePass;
        this.colorFormats = colorFormats != null ? colorFormats.clone() : new int[0];
        this.depthFormat = depthFormat;
        this.passWidth = Math.max(1, passWidth);
        this.passHeight = Math.max(1, passHeight);
        this.nativePass.setViewport(0.0f, 0.0f, this.passWidth, this.passHeight, 0.0f, 1.0f);
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        // 保留低開銷：Apple Metal Frame Capture 標籤可透過 MTLCommandEncoder 除錯標記擴展
    }

    @Override
    public void popDebugGroup() {
    }

    @Override
    public void setPipeline(BackendRenderPipeline pipeline) {
        if (ended || !(pipeline instanceof MetalRenderPipeline metalPipeline)) {
            return;
        }
        if (this.boundPipeline == metalPipeline) {
            return;
        }
        this.boundPipeline = metalPipeline;
        long pso = metalPipeline.getOrCreatePso(colorFormats, depthFormat);
        nativePass.setPipeline(pso);
        nativePass.setDepthBias(metalPipeline.depthBiasConstant(), metalPipeline.depthBiasSlope());
    }

    @Override
    public void setUniform(int index, Object value) {
        if (ended || value == null) {
            return;
        }
        if (value instanceof TextureViewAndSampler tvs) {
            if (tvs.view() instanceof MetalPearlTextureView texView && tvs.sampler() instanceof MetalPearlSampler sampler) {
                nativePass.bindSampler(index, texView.resolvedNativeTexture(), sampler.nativeHandle());
            }
            return;
        }
        BindGroupLayout.UniformDescription desc = boundPipeline != null ? boundPipeline.uniformAt(index) : null;
        boolean isTexelBuffer = desc != null && desc.type() == UniformType.TEXEL_BUFFER;
        if (value instanceof GpuBufferSlice slice && slice.buffer() instanceof MetalPearlBuffer buf) {
            if (isTexelBuffer) {
                int pixFmt = MetalFormatMapper.mapPixelFormat(desc.gpuFormat());
                nativePass.bindTexelBuffer(index, buf.nativeBuffer(), slice.offset(), slice.length(), pixFmt);
            } else {
                nativePass.bindUniformBuffer(index, buf.nativeBuffer(), slice.offset());
            }
        } else if (value instanceof MetalPearlBuffer buf) {
            if (isTexelBuffer) {
                int pixFmt = MetalFormatMapper.mapPixelFormat(desc.gpuFormat());
                nativePass.bindTexelBuffer(index, buf.nativeBuffer(), 0L, buf.size(), pixFmt);
            } else {
                nativePass.bindUniformBuffer(index, buf.nativeBuffer(), 0L);
            }
        }
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        if (ended || value == null || value.remaining() == 0) {
            return;
        }
        int len = Math.min(4096, value.remaining());
        if (value.isDirect()) {
            nativePass.bindUniformInline(MetalityFFM.PUSH_CONSTANT_SLOT, MemorySegment.ofBuffer(value), len);
        } else {
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment seg = temp.allocate(len);
                MemorySegment.copy(MemorySegment.ofBuffer(value.duplicate().limit(value.position() + len)), 0, seg, 0, len);
                nativePass.bindUniformInline(MetalityFFM.PUSH_CONSTANT_SLOT, seg, len);
            }
        }
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        if (ended) {
            return;
        }
        int sx = Math.max(0, Math.min(x, passWidth));
        int sy = Math.max(0, Math.min(y, passHeight));
        int sw = Math.max(0, Math.min(width, passWidth - sx));
        int sh = Math.max(0, Math.min(height, passHeight - sy));
        if (sw > 0 && sh > 0) {
            nativePass.enableScissor(sx, sy, sw, sh);
        }
    }

    @Override
    public void disableScissor() {
        if (!ended) {
            nativePass.enableScissor(0, 0, passWidth, passHeight);
        }
    }

    @Override
    public void setVertexBuffer(int slot, GpuBufferSlice vertexBuffer) {
        if (ended || vertexBuffer == null || !(vertexBuffer.buffer() instanceof MetalPearlBuffer buf)) {
            return;
        }
        nativePass.bindVertexBuffer(MetalityFFM.VERTEX_BUFFER_SLOT_BASE + slot, buf.nativeBuffer(), vertexBuffer.offset());
    }

    @Override
    public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
        if (indexBuffer instanceof MetalPearlBuffer buf) {
            this.boundIndexBuffer = buf;
            this.boundIndexType = indexType != null ? indexType : IndexType.INT;
        } else {
            this.boundIndexBuffer = null;
        }
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        if (ended || boundIndexBuffer == null || indexCount <= 0 || instanceCount <= 0) {
            return;
        }
        long byteOffset = (long) firstIndex * boundIndexType.bytes;
        boolean isU16 = boundIndexType == IndexType.SHORT;
        nativePass.drawIndexedInstanced(
                boundIndexBuffer.nativeBuffer(),
                byteOffset,
                indexCount,
                isU16,
                instanceCount,
                vertexOffset,
                firstInstance
        );
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        if (ended || boundIndexBuffer == null || drawParameters == null || drawCount <= 0 || instanceCount <= 0) {
            return;
        }
        IntBuffer dup = drawParameters.duplicate();
        boolean isU16 = boundIndexType == IndexType.SHORT;
        for (int i = 0; i < drawCount && dup.remaining() >= 3; ++i) {
            int indexCount = dup.get();
            int firstIndex = dup.get();
            int vertexOffset = dup.get();
            if (indexCount > 0) {
                long byteOffset = (long) firstIndex * boundIndexType.bytes;
                nativePass.drawIndexedInstanced(
                        boundIndexBuffer.nativeBuffer(), byteOffset, indexCount, isU16,
                        instanceCount, vertexOffset, firstInstance
                );
            }
        }
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        if (ended || boundIndexBuffer == null || drawCount <= 0) {
            return;
        }
        boolean isU16 = boundIndexType == IndexType.SHORT;
        int offBase = firstIndexOffsets.position();
        int cntBase = indexCounts.position();
        int vtxBase = vertexOffsets != null ? vertexOffsets.position() : 0;
        for (int i = 0; i < drawCount; ++i) {
            int count = indexCounts.get(cntBase + i);
            if (count > 0) {
                long byteOffset = firstIndexOffsets.get(offBase + i);
                int baseVertex = vertexOffsets != null ? vertexOffsets.get(vtxBase + i) : 0;
                nativePass.drawIndexedInstanced(
                        boundIndexBuffer.nativeBuffer(), byteOffset, count, isU16, 1, baseVertex, 0
                );
            }
        }
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        if (ended || boundIndexBuffer == null || commands == null || drawCount <= 0
                || !(commands.buffer() instanceof MetalPearlBuffer cmdBuf)) {
            return;
        }
        boolean isU16 = boundIndexType == IndexType.SHORT;
        nativePass.drawIndexedIndirect(
                boundIndexBuffer.nativeBuffer(),
                isU16,
                cmdBuf.nativeBuffer(),
                commands.offset(),
                drawCount
        );
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (ended || vertexCount <= 0 || instanceCount <= 0) {
            return;
        }
        if (boundPipeline != null && boundPipeline.isQuads()) {
            int quadCount = vertexCount / 4;
            int indexCount = quadCount * 6;
            if (indexCount > 0) {
                MetalGpuBuffer quadIdx = deviceBackend.ensureSequentialQuadIndexBuffer(indexCount);
                nativePass.drawIndexedInstanced(quadIdx, 0L, indexCount, false, instanceCount, firstVertex, firstInstance);
            }
            return;
        }
        if (boundPipeline != null && boundPipeline.isTriangleFan()) {
            if (vertexCount >= 3) {
                int triCount = vertexCount - 2;
                int indexCount = triCount * 3;
                MetalGpuBuffer fanIdx = deviceBackend.ensureSequentialFanIndexBuffer(indexCount);
                nativePass.drawIndexedInstanced(fanIdx, 0L, indexCount, false, instanceCount, firstVertex, firstInstance);
            }
            return;
        }
        nativePass.draw(firstVertex, vertexCount);
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        if (ended || drawParameters == null || drawCount <= 0) {
            return;
        }
        IntBuffer dup = drawParameters.duplicate();
        for (int i = 0; i < drawCount && dup.remaining() >= 2; ++i) {
            int count = dup.get();
            int firstVertex = dup.get();
            draw(count, instanceCount, firstVertex, firstInstance);
        }
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        if (ended || drawCount <= 0) {
            return;
        }
        int fvBase = firstVertices.position();
        int vcBase = vertexCounts.position();
        for (int i = 0; i < drawCount; ++i) {
            draw(vertexCounts.get(vcBase + i), 1, firstVertices.get(fvBase + i), 0);
        }
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        if (ended || commands == null || drawCount <= 0
                || !(commands.buffer() instanceof MetalPearlBuffer cmdBuf)) {
            return;
        }
        ByteBuffer view = cmdBuf.nativeBuffer().cpuSegment()
                .asSlice(commands.offset(), (long) drawCount * 16L)
                .asByteBuffer()
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < drawCount; ++i) {
            int vertexCount = view.getInt();
            int instanceCount = view.getInt();
            int firstVertex = view.getInt();
            int firstInstance = view.getInt();
            draw(vertexCount, instanceCount, firstVertex, firstInstance);
        }
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
        if (pool instanceof MetalQueryPool metalPool) {
            metalPool.record(index, System.nanoTime());
        }
    }

    public void finish() {
        if (!ended) {
            ended = true;
            nativePass.close();
        }
    }
}

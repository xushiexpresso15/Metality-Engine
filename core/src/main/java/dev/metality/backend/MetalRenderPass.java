package dev.metality.backend;

import dev.metality.nativebridge.MetalityFFM;
import java.lang.foreign.MemorySegment;

/**
 * 原生 MTLRenderCommandEncoder 狀態封裝。
 */
public final class MetalRenderPass implements AutoCloseable {

    private final MetalGpuDevice device;
    private boolean ended = false;

    MetalRenderPass(MetalGpuDevice device) {
        this.device = device;
    }

    public void setPipeline(long psoHandle) {
        ensureActive();
        device.ffm().cmdSetPso(device.nativeContext(), psoHandle);
    }

    public void setDepthBias(float depthBias, float slopeScale) {
        ensureActive();
        device.ffm().cmdSetDepthBias(device.nativeContext(), depthBias, slopeScale);
    }

    public void setViewport(float x, float y, float width, float height, float zNear, float zFar) {
        ensureActive();
        device.ffm().cmdSetViewport(device.nativeContext(), x, y, width, height, zNear, zFar);
    }

    public void enableScissor(int x, int y, int width, int height) {
        ensureActive();
        device.ffm().cmdSetScissor(device.nativeContext(), x, y, width, height);
    }

    public void bindVertexBuffer(int slot, MetalGpuBuffer buffer, long byteOffset) {
        ensureActive();
        device.ffm().cmdBindVertexBuffer(device.nativeContext(), slot, buffer.handle(), byteOffset);
    }

    public void bindUniformBuffer(int slot, MetalGpuBuffer buffer, long byteOffset) {
        ensureActive();
        device.ffm().cmdBindUniformBuffer(device.nativeContext(), slot, buffer.handle(), byteOffset);
    }

    public void bindUniformInline(int slot, MemorySegment data, int byteLength) {
        ensureActive();
        device.ffm().cmdBindUniformBytes(device.nativeContext(), slot, data, byteLength);
    }

    public void bindSampler(int slot, MetalGpuTexture texture, long samplerHandle) {
        ensureActive();
        device.ffm().cmdBindTexture(device.nativeContext(), slot, texture.handle(), samplerHandle);
    }

    public void bindTexelBuffer(int slot, MetalGpuBuffer buffer, long offset, long sizeBytes, int pixelFormat) {
        ensureActive();
        device.ffm().cmdBindTexelBuffer(device.nativeContext(), slot, buffer.handle(), offset, sizeBytes, pixelFormat);
    }

    public void draw(int vertexStart, int vertexCount) {
        ensureActive();
        device.ffm().cmdDraw(device.nativeContext(), vertexStart, vertexCount);
    }

    public void drawIndexedU32(MetalGpuBuffer indexBuffer, long indexByteOffset, int indexCount) {
        ensureActive();
        device.ffm().cmdDrawIndexedU32(device.nativeContext(), indexBuffer.handle(), indexByteOffset, indexCount);
    }

    public void drawIndexedInstanced(
            MetalGpuBuffer indexBuffer,
            long indexByteOffset,
            int indexCount,
            boolean indexIsU16,
            int instanceCount,
            int baseVertex,
            int baseInstance
    ) {
        ensureActive();
        device.ffm().cmdDrawIndexedInstanced(
                device.nativeContext(),
                indexBuffer.handle(),
                indexByteOffset,
                indexCount,
                indexIsU16,
                instanceCount,
                baseVertex,
                baseInstance
        );
    }

    public void drawIndexedIndirect(
            MetalGpuBuffer indexBuffer,
            boolean indexIsU16,
            MetalGpuBuffer indirectBuffer,
            long indirectByteOffset,
            int drawCount
    ) {
        ensureActive();
        device.ffm().cmdDrawIndexedIndirect(
                device.nativeContext(),
                indexBuffer.handle(),
                indexIsU16,
                indirectBuffer.handle(),
                indirectByteOffset,
                drawCount
        );
    }

    private void ensureActive() {
        if (ended) {
            throw new IllegalStateException("MetalRenderPass has already been closed!");
        }
    }

    @Override
    public void close() {
        if (!ended) {
            ended = true;
            MetalityFFM ffm = device.ffm();
            ffm.cmdEndRenderPass(device.nativeContext());
        }
    }
}

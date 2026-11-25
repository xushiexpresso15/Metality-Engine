package dev.metality.raytracing;

import dev.metality.api.raytracing.RayTracingService;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import dev.metality.terrain.ChunkMeshingScratch;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * :core 內部 Apple Silicon 動態光追場景管理器：
 * 自動依硬體偵測選用 M3/M4/M5 Metal BLAS/TLAS 或 M1/M2 Voxel DDA 後備管線。
 */
public final class HardwareRayTracingScene implements RayTracingService, AutoCloseable {

    private record SectionCoord(int x, int y, int z) {}

    private final MetalGpuDevice device;
    private final MemorySegment nativeScene;
    private final boolean hardwareAccelerated;
    private final AtomicBoolean enabled;
    private final Set<SectionCoord> activeSections = ConcurrentHashMap.newKeySet();
    private final Arena stagingArena = Arena.ofShared();
    private final MemorySegment sceneArrayStaging = stagingArena.allocate(ValueLayout.JAVA_FLOAT, 256L * 8L);
    private volatile boolean closed = false;

    public HardwareRayTracingScene(MetalGpuDevice device, int maxSections, boolean forceVoxelFallback) {
        this.device = device;
        this.nativeScene = device.ffm().rtSceneCreate(device.nativeContext(), maxSections, forceVoxelFallback);
        if (MemorySegment.NULL.equals(this.nativeScene)) {
            throw new IllegalStateException("Failed to create native Metal RayTracingScene");
        }
        this.hardwareAccelerated = device.ffm().rtSceneUsesHardwareBvh(this.nativeScene);
        this.enabled = new AtomicBoolean(device.capabilities().hardwareRayTracing());
    }

    @Override
    public boolean isHardwareAccelerated() {
        return hardwareAccelerated;
    }

    @Override
    public boolean isEnabled() {
        return !closed && enabled.get();
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    @Override
    public int activeSectionBlasCount() {
        if (closed) {
            return 0;
        }
        return device.ffm().rtSceneActiveBlasCount(nativeScene);
    }

    public MemorySegment nativeHandle() {
        return closed ? MemorySegment.NULL : nativeScene;
    }

    public boolean updateSectionQuads(int secX, int secY, int secZ, ChunkMeshingScratch meshed) {
        if (closed) {
            return false;
        }
        boolean ok = device.ffm().rtSceneUpdateSectionV2(
                device.nativeContext(),
                nativeScene,
                secX, secY, secZ,
                meshed.solidStaging, meshed.solidQuadCount(),
                meshed.cutoutStaging, meshed.cutoutQuadCount(),
                meshed.translucentStaging, meshed.translucentQuadCount()
        );
        if (ok) {
            if (meshed.solidQuadCount() + meshed.cutoutQuadCount() + meshed.translucentQuadCount() > 0) {
                activeSections.add(new SectionCoord(secX, secY, secZ));
            } else {
                activeSections.remove(new SectionCoord(secX, secY, secZ));
            }
        }
        return ok;
    }

    public void removeSection(int secX, int secY, int secZ) {
        if (!closed) {
            activeSections.remove(new SectionCoord(secX, secY, secZ));
            device.ffm().rtSceneRemoveSection(nativeScene, secX, secY, secZ);
        }
    }

    public synchronized void clearAllSections() {
        if (closed) {
            return;
        }
        activeSections.clear();
        device.ffm().rtSceneClearAll(nativeScene);
    }

    public void setDynamicLights(float[] packedLights8f, int lightCount) {
        setSceneArray8f(packedLights8f, lightCount, 128, 0);
    }

    public void setWaterRipples(float[] packedRipples8f, int rippleCount) {
        setSceneArray8f(packedRipples8f, rippleCount, 64, 1);
    }

    public void setEntityShadows(float[] packedCasters8f, int casterCount) {
        setSceneArray8f(packedCasters8f, casterCount, 256, 2);
    }

    private synchronized void setSceneArray8f(float[] packedData8f, int rawCount, int maxItems, int kind) {
        if (closed) {
            return;
        }
        int count = (packedData8f == null || rawCount <= 0)
                ? 0
                : Math.min(rawCount, Math.min(maxItems, packedData8f.length / 8));
        if (count <= 0) {
            dispatchSceneArrayUpdate(MemorySegment.NULL, 0, kind);
            return;
        }
        MemorySegment.copy(packedData8f, 0, sceneArrayStaging, ValueLayout.JAVA_FLOAT, 0L, count * 8);
        dispatchSceneArrayUpdate(sceneArrayStaging, count, kind);
    }

    private void dispatchSceneArrayUpdate(MemorySegment seg, int count, int kind) {
        switch (kind) {
            case 0 -> device.ffm().rtSceneSetDynamicLights(nativeScene, seg, count);
            case 1 -> device.ffm().rtSceneSetWaterRipples(nativeScene, seg, count);
            default -> device.ffm().rtSceneSetEntityShadows(nativeScene, seg, count);
        }
    }

    public boolean rebuildTlas(float camX, float camY, float camZ) {
        if (closed) {
            return false;
        }
        return device.ffm().rtSceneRebuildTlas(device.nativeContext(), nativeScene, camX, camY, camZ);
    }

    public boolean syncWorldDataOnly(float camX, float camY, float camZ) {
        if (closed) {
            return false;
        }
        return device.ffm().syncRtSceneWorldData(device.nativeContext(), nativeScene, camX, camY, camZ);
    }

    public boolean dispatchGlobalIllumination(
            float[] invViewProj16,
            float[] camPos3,
            float[] sunDir3,
            int width,
            int height,
            MetalGpuTexture outNoisyRadiance,
            MetalGpuTexture outDepth,
            MetalGpuTexture outMotion,
            MetalGpuTexture outDiffuseAlbedo,
            MetalGpuTexture outSpecularAlbedo,
            MetalGpuTexture outNormal,
            MetalGpuTexture outRoughness
    ) {
        if (closed) {
            return false;
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment ivpSeg = temp.allocateFrom(ValueLayout.JAVA_FLOAT, invViewProj16);
            MemorySegment camSeg = temp.allocateFrom(ValueLayout.JAVA_FLOAT, camPos3);
            MemorySegment sunSeg = temp.allocateFrom(ValueLayout.JAVA_FLOAT, sunDir3);
            return device.ffm().rtDispatchGi(
                    device.nativeContext(),
                    nativeScene,
                    ivpSeg,
                    camSeg,
                    sunSeg,
                    width,
                    height,
                    outNoisyRadiance.handle(),
                    outDepth.handle(),
                    outMotion.handle(),
                    outDiffuseAlbedo.handle(),
                    outSpecularAlbedo.handle(),
                    outNormal.handle(),
                    outRoughness.handle()
            );
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            device.ffm().rtSceneDestroy(nativeScene);
            stagingArena.close();
        }
    }
}

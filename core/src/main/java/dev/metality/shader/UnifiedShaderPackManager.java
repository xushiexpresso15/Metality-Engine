package dev.metality.shader;

import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.api.shader.ShaderPackOption;
import dev.metality.api.shader.ShaderPackProfile;
import dev.metality.api.shader.ShaderPackService;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import dev.metality.nativebridge.MetalityFFM;
import dev.metality.shader.LiveShaderComposer.ComposerParams;
import dev.metality.shader.legacy.LegacyIrisPackLoader;
import dev.metality.shader.legacy.ShaderPackArchive;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 雙軌光影統一調度器：
 * <ul>
 *   <li>Track A ({@link ShaderPackFormat#APERTURE_TS})：原生 {@code pack.ts + Slang-to-MSL} 次世代光影包。</li>
 *   <li>Track B ({@link ShaderPackFormat#LEGACY_IRIS_GLSL})：市面相容 {@code Iris / OptiFine} 目錄或 {@code .zip} 光影包。</li>
 * </ul>
 */
public final class UnifiedShaderPackManager implements ShaderPackService, AutoCloseable {

    private final MetalGpuDevice gpuDevice;
    private final AperturePackManager apertureManager;
    private final LiveShaderComposer liveComposer;

    private volatile ShaderPackFormat activeFormat = ShaderPackFormat.UNKNOWN;
    private volatile ActivePackInfo activeLegacyInfo;
    private volatile List<ShaderPackOption> activeOptions = List.of();
    private volatile List<ShaderPackProfile> activeProfiles = List.of();

    private Path lastPackPath;
    private int lastWidth;
    private int lastHeight;
    private float lastScale;
    private Map<String, String> lastOverrides = new LinkedHashMap<>();

    public UnifiedShaderPackManager(MetalGpuDevice gpuDevice) {
        this.gpuDevice = gpuDevice;
        this.apertureManager = new AperturePackManager(gpuDevice);
        this.liveComposer = new LiveShaderComposer(gpuDevice);
    }

    @Override
    public ActivePackInfo loadPack(
            Path packRootDir,
            int screenWidth,
            int screenHeight,
            float internalScale
    ) {
        return loadPack(packRootDir, screenWidth, screenHeight, internalScale, Map.of());
    }

    @Override
    public synchronized ActivePackInfo loadPack(
            Path packRootOrZip,
            int screenWidth,
            int screenHeight,
            float internalScale,
            Map<String, String> optionOverrides
    ) {
        this.lastPackPath = packRootOrZip;
        this.lastWidth = screenWidth;
        this.lastHeight = screenHeight;
        this.lastScale = internalScale;
        this.lastOverrides = optionOverrides != null ? new LinkedHashMap<>(optionOverrides) : new LinkedHashMap<>();

        if (Files.exists(packRootOrZip.resolve("pack.ts"))) {
            this.activeLegacyInfo = null;
            ActivePackInfo rawInfo = apertureManager.loadPack(
                    packRootOrZip, screenWidth, screenHeight, internalScale, this.lastOverrides);
            this.activeOptions = apertureManager.activePackOptions();
            this.activeProfiles = apertureManager.activePackProfiles();
            this.activeFormat = ShaderPackFormat.APERTURE_TS;
            this.liveComposer.updateParams(ComposerParams.defaults());
            return rawInfo;
        }

        if (ShaderPackArchive.looksLikeLegacyIrisPack(packRootOrZip)) {
            apertureManager.unloadPack();
            var loaded = LegacyIrisPackLoader.load(
                    gpuDevice, packRootOrZip, screenWidth, screenHeight, internalScale, this.lastOverrides
            );
            this.activeLegacyInfo = loaded.info();
            this.activeOptions = loaded.options();
            this.activeProfiles = loaded.profiles();
            this.activeFormat = ShaderPackFormat.LEGACY_IRIS_GLSL;
            this.liveComposer.updateParams(loaded.composerParams());
            return loaded.info();
        }

        throw new IllegalArgumentException(
                "Unrecognized shader pack format at " + packRootOrZip
                        + " (expected Aperture pack.ts or Iris/OptiFine shaders/ directory/.zip)");
    }

    @Override
    public synchronized ActivePackInfo reloadActivePack() {
        if (lastPackPath == null) {
            throw new IllegalStateException("No shader pack has been loaded yet");
        }
        return loadPack(lastPackPath, lastWidth, lastHeight, lastScale, lastOverrides);
    }

    @Override
    public synchronized void unloadPack() {
        apertureManager.unloadPack();
        this.activeLegacyInfo = null;
        this.activeOptions = List.of();
        this.activeProfiles = List.of();
        this.activeFormat = ShaderPackFormat.UNKNOWN;
    }

    @Override
    public Optional<ActivePackInfo> activePackInfo() {
        if (activeFormat == ShaderPackFormat.APERTURE_TS) {
            return apertureManager.activePackInfo().map(raw -> new ActivePackInfo(
                    raw.packName(),
                    raw.totalPasses(),
                    raw.fusedGroups(),
                    raw.memorylessTextures(),
                    raw.declaredTextures(),
                    raw.compiledPsos(),
                    raw.savedDramBytesPerFrame(),
                    raw.hardwareRayTracingEnabled(),
                    raw.edrEnabled(),
                    ShaderPackFormat.APERTURE_TS
            ));
        }
        return Optional.ofNullable(activeLegacyInfo);
    }

    @Override
    public synchronized Optional<Path> activePackPath() {
        if (activeFormat == ShaderPackFormat.UNKNOWN) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastPackPath);
    }

    @Override
    public List<ShaderPackOption> activePackOptions() {
        return activeOptions;
    }

    @Override
    public List<ShaderPackProfile> activePackProfiles() {
        return activeProfiles;
    }

    public synchronized Map<String, String> currentOptionOverrides() {
        return Map.copyOf(lastOverrides);
    }

    @Override
    public synchronized ActivePackInfo applyProfile(String profileName) {
        if (lastPackPath == null) {
            throw new IllegalStateException("No active shader pack to apply profile onto");
        }
        ShaderPackProfile match = activeProfiles.stream()
                .filter(p -> p.name().equalsIgnoreCase(profileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown shader pack profile: " + profileName));
        Map<String, String> next = new LinkedHashMap<>(match.optionOverrides());
        this.lastOverrides = next;
        if (activeFormat == ShaderPackFormat.APERTURE_TS) {
            ActivePackInfo updated = apertureManager.applyProfile(profileName);
            this.activeOptions = apertureManager.activePackOptions();
            return updated;
        }
        return loadPack(lastPackPath, lastWidth, lastHeight, lastScale, next);
    }

    @Override
    public synchronized ActivePackInfo resetOptions() {
        if (lastPackPath == null) {
            throw new IllegalStateException("No active shader pack to reset options");
        }
        this.lastOverrides = new LinkedHashMap<>();
        if (activeFormat == ShaderPackFormat.APERTURE_TS) {
            ActivePackInfo updated = apertureManager.resetOptions();
            this.activeOptions = apertureManager.activePackOptions();
            return updated;
        }
        return loadPack(lastPackPath, lastWidth, lastHeight, lastScale, Map.of());
    }

    @Override
    public synchronized ActivePackInfo updateOptions(Map<String, String> optionOverrides) {
        if (lastPackPath == null) {
            throw new IllegalStateException("No active shader pack to update options");
        }
        Map<String, String> merged = new LinkedHashMap<>(lastOverrides);
        if (optionOverrides != null) {
            merged.putAll(optionOverrides);
        }
        this.lastOverrides = merged;
        if (activeFormat == ShaderPackFormat.APERTURE_TS) {
            ActivePackInfo updated = apertureManager.updateOptions(optionOverrides);
            this.activeOptions = apertureManager.activePackOptions();
            return updated;
        }
        return loadPack(lastPackPath, lastWidth, lastHeight, lastScale, merged);
    }

    public boolean executeWorldPipeline(
            MemorySegment optRtSceneHandle,
            MetalGpuTexture sceneColor,
            MetalGpuTexture sceneDepth,
            MetalityFFM.WorldFrameUniforms frame
    ) {
        if (activeFormat == ShaderPackFormat.APERTURE_TS) {
            return apertureManager.executeActivePack(optRtSceneHandle, sceneColor, sceneDepth, frame);
        }
        if (activeFormat == ShaderPackFormat.LEGACY_IRIS_GLSL) {
            return liveComposer.execute(
                    sceneColor,
                    sceneDepth,
                    frame.invProj16(),
                    frame.viewRot16(),
                    frame.proj16(),
                    frame.sunWorldDir4()[0],
                    frame.sunWorldDir4()[1],
                    frame.sunWorldDir4()[2],
                    frame.skyColor4()[0],
                    frame.skyColor4()[1],
                    frame.skyColor4()[2],
                    frame.timeSeconds(),
                    frame.rainStrength(),
                    true
            );
        }
        return false;
    }

    public LiveShaderComposer liveComposer() {
        return liveComposer;
    }

    public ShaderPackFormat activeFormat() {
        return activeFormat;
    }

    public boolean isDisableShadeEnabled() {
        return activeFormat == ShaderPackFormat.APERTURE_TS && apertureManager.isDisableShadeEnabled();
    }

    public boolean isWaterShaderEnabled() {
        return activeFormat == ShaderPackFormat.APERTURE_TS && apertureManager.isWaterShaderEnabled();
    }

    public double lastGpuFrameTimeMs() {
        return apertureManager.lastGpuFrameTimeMs();
    }

    public float recommendedThermalScale() {
        return apertureManager.recommendedThermalScale();
    }

    @Override
    public void close() {
        liveComposer.close();
        apertureManager.close();
    }
}

package dev.metality.shader;

import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.api.shader.ShaderPackOption;
import dev.metality.api.shader.ShaderPackProfile;
import dev.metality.api.shader.ShaderPackService;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.backend.MetalGpuTexture;
import dev.metality.nativebridge.MetalityFFM;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aperture 光影包（pack.ts + .slang）載入器與即時多通道執行器，實作對外穩定的 ShaderPackService。
 */
public final class AperturePackManager implements ShaderPackService, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("Metality/Aperture");

    private static final Pattern DEFINE_OPTION_PATTERN = Pattern.compile(
            "defineOption\\s*\\(\\s*\"([^\"]+)\"\\s*,\\s*([^,\\s)]+)\\s*,\\s*\\[([^\\]]*)\\]\\s*,\\s*(true|false)\\s*,\\s*\"([^\"]*)\"\\s*\\)"
    );
    private static final Pattern DEFINE_PROFILE_PATTERN = Pattern.compile(
            "defineProfile\\s*\\(\\s*\"([^\"]+)\"\\s*,\\s*\\{([^}]*)\\}\\s*\\)",
            Pattern.DOTALL
    );
    private static final Pattern PROFILE_ENTRY_PATTERN = Pattern.compile(
            "([A-Za-z0-9_]+)\\s*:\\s*\"?([^\n\",}\\s]+)\"?"
    );
    private static final Pattern DISABLE_SHADE_PATTERN = Pattern.compile(
            "disableShade\\s*[:=]\\s*(true|false)"
    );

    private static final Set<String> UNIFORM_ONLY_OPTIONS = Set.of(
            "BLOOM_STRENGTH", "GODRAY_STRENGTH", "WATER_CLARITY", "RT_QUALITY", "SHADOW_QUALITY",
            "RT_BOUNCE_SAMPLES", "CLOUD_QUALITY",
            "EXPOSURE", "CONTRAST", "SATURATION", "BLOOM_THRESHOLD",
            "HIGHLIGHT_BOOST", "SHADOW_DEPTH", "EMISSIVE_INTENSITY", "ATMOS_HAZE",
            "WATER_WAVE_HEIGHT", "WATER_RIPPLE_STRENGTH", "SPECULAR_STRENGTH", "REFLECTION_STRENGTH",
            "ENABLE_SHADOWS", "ENABLE_REFLECTIONS", "ENABLE_WATER_SHADER", "ENABLE_VOLUMETRIC_CLOUDS",
            "ENABLE_GODRAYS", "ENABLE_BLOOM", "ENABLE_GI_AO", "ENABLE_PBR_RAIN"
    );

    private static final int DEFAULT_FEATURE_FLAGS = 0x1FF00 | (2 << 17);

    private final MetalGpuDevice device;
    private Path currentPackDir;
    private int currentScreenW;
    private int currentScreenH;
    private float currentInternalScale = 0.67f;
    private Map<String, String> currentOverrides = new LinkedHashMap<>();
    private MemorySegment activePackHandle = MemorySegment.NULL;
    private MetalityFFM.PackManifestSummary activeSummary;
    private ActivePackInfo activeInfo;
    private List<ShaderPackOption> parsedOptions = List.of();
    private List<ShaderPackProfile> parsedProfiles = List.of();
    private float[] userParams = new float[]{1.0f, 1.0f, 1.0f, 1.0f};
    private float[] tonemapParams = new float[]{0.90f, 1.16f, 1.16f, 1.25f};
    private float[] lightingParams = new float[]{1.22f, 1.18f, 1.12f, 0.65f};
    private float[] surfaceParams = new float[]{1.15f, 1.15f, 1.15f, 1.10f};
    private int featureFlags = DEFAULT_FEATURE_FLAGS;
    private volatile boolean disableShade = false;

    public AperturePackManager(MetalGpuDevice device) {
        this.device = device;
    }

    @Override
    public synchronized ActivePackInfo loadPack(
            Path packRootDir,
            int screenWidth,
            int screenHeight,
            float internalScale
    ) {
        return loadPack(packRootDir, screenWidth, screenHeight, internalScale, Map.of());
    }

    @Override
    public synchronized ActivePackInfo loadPack(
            Path packRootDir,
            int screenWidth,
            int screenHeight,
            float internalScale,
            Map<String, String> optionOverrides
    ) {
        unloadPack();
        MetalityFFM.LoadedShaderPack loaded = device.ffm().loadShaderPack(
                device.nativeContext(),
                packRootDir,
                screenWidth,
                screenHeight,
                internalScale
        );
        this.currentPackDir = packRootDir;
        this.currentScreenW = screenWidth;
        this.currentScreenH = screenHeight;
        this.currentInternalScale = internalScale;
        this.currentOverrides = optionOverrides != null ? new LinkedHashMap<>(optionOverrides) : new LinkedHashMap<>();
        this.activePackHandle = loaded.handle();
        this.activeSummary = loaded.summary();
        parsePackMetadata(packRootDir.resolve("pack.ts"), this.currentOverrides);

        this.activeInfo = new ActivePackInfo(
                packRootDir.getFileName().toString(),
                activeSummary.totalPassCount(),
                activeSummary.fusedGroupCount(),
                activeSummary.memorylessTextureCount(),
                activeSummary.declaredTextureCount(),
                activeSummary.compiledPsoCount(),
                activeSummary.savedDramBytesPerFrame(),
                activeSummary.enableHwRaytracing(),
                activeSummary.enableEdr(),
                ShaderPackFormat.APERTURE_TS
        );

        LOGGER.info("Loaded Aperture shader pack [{}]: Passes={} -> FusedGroups={}, Memoryless={}/{} (Saved {} MB/frame), PSOs={}, HWRT={}, Options={}, Profiles={}",
                activeInfo.packName(),
                activeSummary.totalPassCount(),
                activeSummary.fusedGroupCount(),
                activeSummary.memorylessTextureCount(),
                activeSummary.declaredTextureCount(),
                String.format("%.2f", activeSummary.savedDramBytesPerFrame() / (1024.0 * 1024.0)),
                activeSummary.compiledPsoCount(),
                activeSummary.enableHwRaytracing(),
                parsedOptions.size(),
                parsedProfiles.size());

        return activeInfo;
    }

    @Override
    public synchronized ActivePackInfo reloadActivePack() {
        if (currentPackDir == null) {
            throw new IllegalStateException("No Aperture shader pack currently loaded");
        }
        return loadPack(currentPackDir, currentScreenW, currentScreenH, currentInternalScale, currentOverrides);
    }

    @Override
    public synchronized Optional<ActivePackInfo> activePackInfo() {
        return Optional.ofNullable(activeInfo);
    }

    @Override
    public synchronized Optional<Path> activePackPath() {
        return Optional.ofNullable(currentPackDir);
    }

    @Override
    public List<ShaderPackOption> activePackOptions() {
        return parsedOptions;
    }

    @Override
    public List<ShaderPackProfile> activePackProfiles() {
        return parsedProfiles;
    }

    @Override
    public synchronized ActivePackInfo applyProfile(String profileName) {
        if (currentPackDir == null) {
            throw new IllegalStateException("No Aperture shader pack currently loaded");
        }
        ShaderPackProfile match = parsedProfiles.stream()
                .filter(p -> p.name().equalsIgnoreCase(profileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Aperture profile: " + profileName));
        Map<String, String> next = new LinkedHashMap<>(match.optionOverrides());
        boolean uniformOnly = !MemorySegment.NULL.equals(activePackHandle) && activeInfo != null
                && UNIFORM_ONLY_OPTIONS.containsAll(currentOverrides.keySet())
                && UNIFORM_ONLY_OPTIONS.containsAll(next.keySet());
        if (uniformOnly) {
            this.currentOverrides = next;
            parsePackMetadata(currentPackDir.resolve("pack.ts"), this.currentOverrides);
            return activeInfo;
        }
        return loadPack(currentPackDir, currentScreenW, currentScreenH, currentInternalScale, next);
    }

    @Override
    public synchronized ActivePackInfo resetOptions() {
        if (currentPackDir == null) {
            throw new IllegalStateException("No Aperture shader pack currently loaded");
        }
        boolean uniformOnly = !MemorySegment.NULL.equals(activePackHandle) && activeInfo != null
                && UNIFORM_ONLY_OPTIONS.containsAll(currentOverrides.keySet());
        if (uniformOnly) {
            this.currentOverrides = new LinkedHashMap<>();
            parsePackMetadata(currentPackDir.resolve("pack.ts"), this.currentOverrides);
            return activeInfo;
        }
        return loadPack(currentPackDir, currentScreenW, currentScreenH, currentInternalScale, Map.of());
    }

    @Override
    public synchronized ActivePackInfo updateOptions(Map<String, String> optionOverrides) {
        if (currentPackDir == null) {
            throw new IllegalStateException("No Aperture shader pack currently loaded");
        }
        Map<String, String> merged = new LinkedHashMap<>(currentOverrides);
        boolean uniformOnly = !MemorySegment.NULL.equals(activePackHandle) && activeInfo != null;
        if (optionOverrides != null) {
            for (var entry : optionOverrides.entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
                if (!UNIFORM_ONLY_OPTIONS.contains(entry.getKey())) {
                    uniformOnly = false;
                }
            }
        }
        if (uniformOnly) {
            this.currentOverrides = merged;
            parsePackMetadata(currentPackDir.resolve("pack.ts"), this.currentOverrides);
            return activeInfo;
        }
        return loadPack(currentPackDir, currentScreenW, currentScreenH, currentInternalScale, merged);
    }

    public synchronized boolean executeActivePack(
            MemorySegment optRtSceneHandle,
            MetalGpuTexture sceneColor,
            MetalGpuTexture sceneDepth,
            MetalityFFM.WorldFrameUniforms frame
    ) {
        if (MemorySegment.NULL.equals(activePackHandle) || sceneColor == null || sceneDepth == null || frame == null) {
            return false;
        }
        int effectiveFlags = (frame.tonemapMode() & 1) | this.featureFlags;
        MetalityFFM.WorldFrameUniforms effective = frame.withPackParams(
                effectiveFlags,
                frame.userParams4() != null ? frame.userParams4() : this.userParams,
                frame.tonemapParams4() != null ? frame.tonemapParams4() : this.tonemapParams,
                frame.lightingParams4() != null ? frame.lightingParams4() : this.lightingParams,
                frame.surfaceParams4() != null ? frame.surfaceParams4() : this.surfaceParams
        );
        return device.ffm().packExecute(
                device.nativeContext(),
                activePackHandle,
                optRtSceneHandle,
                sceneColor.handle(),
                sceneDepth.handle(),
                effective
        );
    }

    public float[] currentUserParams() {
        return userParams.clone();
    }

    public float[] currentTonemapParams() {
        return tonemapParams.clone();
    }

    public float[] currentLightingParams() {
        return lightingParams.clone();
    }

    public float[] currentSurfaceParams() {
        return surfaceParams.clone();
    }

    public int activeFeatureFlags() {
        return featureFlags;
    }

    public boolean isWaterShaderEnabled() {
        return (featureFlags & 0x800) != 0;
    }

    public boolean isDisableShadeEnabled() {
        return disableShade;
    }

    public double lastGpuFrameTimeMs() {
        return device.ffm().lastGpuFrameMs(device.nativeContext());
    }

    public float recommendedThermalScale() {
        boolean thermalAdaptive = activeSummary != null && activeSummary.thermalAdaptive();
        return device.ffm().recommendedRenderScale(device.nativeContext(), thermalAdaptive);
    }

    public MetalityFFM.PackManifestSummary activeSummary() {
        return activeSummary;
    }

    @Override
    public synchronized void unloadPack() {
        if (!MemorySegment.NULL.equals(activePackHandle)) {
            device.ffm().destroyShaderPack(activePackHandle);
            activePackHandle = MemorySegment.NULL;
            activeSummary = null;
            activeInfo = null;
            parsedOptions = List.of();
            parsedProfiles = List.of();
            userParams = new float[]{1.0f, 1.0f, 1.0f, 1.0f};
            tonemapParams = new float[]{0.90f, 1.16f, 1.16f, 1.25f};
            lightingParams = new float[]{1.22f, 1.18f, 1.12f, 0.65f};
            surfaceParams = new float[]{1.15f, 1.15f, 1.15f, 1.10f};
            featureFlags = DEFAULT_FEATURE_FLAGS;
            disableShade = false;
        }
    }

    @Override
    public void close() {
        unloadPack();
    }

    private void parsePackMetadata(Path packTsPath, Map<String, String> overrides) {
        List<ShaderPackOption> options = new ArrayList<>();
        List<ShaderPackProfile> profiles = new ArrayList<>();
        Map<String, Float> resolvedFloats = new LinkedHashMap<>();
        Map<String, String> resolvedValues = new LinkedHashMap<>();

        boolean parsedDisableShade = false;
        if (packTsPath != null && Files.isRegularFile(packTsPath)) {
            try {
                String src = Files.readString(packTsPath, StandardCharsets.UTF_8);
                Matcher dsM = DISABLE_SHADE_PATTERN.matcher(src);
                while (dsM.find()) {
                    parsedDisableShade = Boolean.parseBoolean(dsM.group(1));
                }
                Matcher optM = DEFINE_OPTION_PATTERN.matcher(src);
                while (optM.find()) {
                    String name = optM.group(1);
                    String defVal = stripQuotes(optM.group(2).trim());
                    String rawAllowed = optM.group(3);
                    boolean slider = Boolean.parseBoolean(optM.group(4));
                    String group = optM.group(5);

                    List<String> allowed = new ArrayList<>();
                    for (String tok : rawAllowed.split(",")) {
                        String clean = stripQuotes(tok.trim());
                        if (!clean.isEmpty()) {
                            allowed.add(clean);
                        }
                    }
                    if (allowed.isEmpty()) {
                        allowed.add(defVal);
                    }
                    String curVal = overrides.getOrDefault(name, defVal);
                    options.add(new ShaderPackOption(name, curVal, defVal, allowed, slider, group));
                    resolvedValues.put(name, curVal);
                    parseFloatSafe(curVal).ifPresent(fv -> resolvedFloats.put(name, fv));
                }

                Matcher profM = DEFINE_PROFILE_PATTERN.matcher(src);
                while (profM.find()) {
                    String pName = profM.group(1);
                    String body = profM.group(2);
                    Map<String, String> map = new LinkedHashMap<>();
                    Matcher kvM = PROFILE_ENTRY_PATTERN.matcher(body);
                    while (kvM.find()) {
                        map.put(kvM.group(1), kvM.group(2));
                    }
                    profiles.add(new ShaderPackProfile(pName, map));
                }
            } catch (IOException ex) {
                LOGGER.warn("Unable to read pack.ts metadata from {}: {}", packTsPath, ex.getMessage());
            }
        }

        this.parsedOptions = List.copyOf(options);
        this.parsedProfiles = List.copyOf(profiles);
        this.disableShade = parsedDisableShade;

        float bloom = resolvedFloats.getOrDefault("BLOOM_STRENGTH", 1.0f);
        float godray = resolvedFloats.getOrDefault("GODRAY_STRENGTH", 1.0f);
        float water = resolvedFloats.getOrDefault("WATER_CLARITY", 1.0f);
        float quality = resolvedFloats.containsKey("RT_QUALITY")
                ? resolvedFloats.get("RT_QUALITY")
                : (resolvedFloats.containsKey("SHADOW_QUALITY")
                        ? resolvedFloats.get("SHADOW_QUALITY")
                        : (resolvedFloats.containsKey("RT_BOUNCE_SAMPLES")
                                ? resolvedFloats.get("RT_BOUNCE_SAMPLES")
                                : resolvedFloats.getOrDefault("CLOUD_QUALITY", 1.0f)));
        this.userParams = new float[]{bloom, godray, water, quality};
        this.tonemapParams = new float[]{
                resolvedFloats.getOrDefault("EXPOSURE", 0.90f),
                resolvedFloats.getOrDefault("CONTRAST", 1.16f),
                resolvedFloats.getOrDefault("SATURATION", 1.16f),
                resolvedFloats.getOrDefault("BLOOM_THRESHOLD", 1.25f)
        };
        this.lightingParams = new float[]{
                resolvedFloats.getOrDefault("HIGHLIGHT_BOOST", 1.22f),
                resolvedFloats.getOrDefault("SHADOW_DEPTH", 1.18f),
                resolvedFloats.getOrDefault("EMISSIVE_INTENSITY", 1.12f),
                resolvedFloats.getOrDefault("ATMOS_HAZE", 0.65f)
        };
        this.surfaceParams = new float[]{
                resolvedFloats.getOrDefault("WATER_WAVE_HEIGHT", 1.15f),
                resolvedFloats.getOrDefault("WATER_RIPPLE_STRENGTH", 1.15f),
                resolvedFloats.getOrDefault("SPECULAR_STRENGTH", 1.15f),
                resolvedFloats.getOrDefault("REFLECTION_STRENGTH", 1.10f)
        };
        int flags = 0x100;
        if (isBoolOptionEnabled(resolvedValues, "ENABLE_SHADOWS", true)) flags |= 0x200;
        if (isBoolOptionEnabled(resolvedValues, "ENABLE_REFLECTIONS", true)) flags |= 0x400;
        if (isBoolOptionEnabled(resolvedValues, "ENABLE_WATER_SHADER", true)) flags |= 0x800;
        if (isBoolOptionEnabled(resolvedValues, "ENABLE_VOLUMETRIC_CLOUDS", true)) flags |= 0x1000;
        if (isBoolOptionEnabled(resolvedValues, "ENABLE_GODRAYS", true)) flags |= 0x2000;
        if (isBoolOptionEnabled(resolvedValues, "ENABLE_BLOOM", true)) flags |= 0x4000;
        if (isBoolOptionEnabled(resolvedValues, "ENABLE_GI_AO", true)) flags |= 0x8000;
        if (isBoolOptionEnabled(resolvedValues, "ENABLE_PBR_RAIN", true)) flags |= 0x10000;
        int shadowTier = 2;
        if (quality < 0.72f) {
            shadowTier = 0;
        } else if (quality < 0.90f) {
            shadowTier = 1;
        } else if (quality >= 1.15f) {
            shadowTier = 3;
        }
        this.featureFlags = flags | ((shadowTier & 3) << 17);
    }

    private static boolean isBoolOptionEnabled(Map<String, String> values, String key, boolean defaultVal) {
        String v = values.get(key);
        if (v == null) {
            return defaultVal;
        }
        return !"false".equalsIgnoreCase(v) && !"0".equals(v);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static Optional<Float> parseFloatSafe(String s) {
        try {
            return Optional.of(Float.parseFloat(s));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}

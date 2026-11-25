package dev.metality.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metality 引擎與光影持久化設定（儲存於 {@code config/metality.properties}，
 * 並以 Iris 慣例將各光影包覆寫參數儲存於 {@code shaderpacks/<packKey>.properties}）。
 */
public final class MetalitySettingsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("Metality/Config");

    public enum TelemetryHudMode {
        OFF,
        COMPACT,
        DETAILED
    }

    private final Path configFile;

    private boolean shadersEnabled = true;
    private String activeShaderPack = "";
    private boolean hardwareRayTracing = true;
    private boolean enableEdr = true;
    private boolean thermalAdaptiveScaling = true;
    private float shaderRenderScale = 1.0f;
    private int maxFramesInFlight = 2;
    private boolean suppressVanillaWater = true;
    private boolean connectedGlass = true;
    private boolean dynamicLightsEnabled = true;
    private boolean interactiveRipplesEnabled = true;
    private TelemetryHudMode telemetryHudMode = TelemetryHudMode.OFF;
    private boolean liveShaderPreviewBackdrop = true;

    private MetalitySettingsConfig(Path configFile) {
        this.configFile = Objects.requireNonNull(configFile, "configFile");
    }

    public static Path configFilePath(Path gameDir) {
        return gameDir.resolve("config").resolve("metality.properties");
    }

    public static MetalitySettingsConfig load(Path gameDir) {
        Path file = configFilePath(gameDir);
        MetalitySettingsConfig cfg = new MetalitySettingsConfig(file);
        if (!Files.isRegularFile(file)) {
            return cfg;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            cfg.shadersEnabled = parseBoolean(props, "shadersEnabled", true);
            cfg.activeShaderPack = props.getProperty("activeShaderPack", "").trim();
            cfg.hardwareRayTracing = parseBoolean(props, "hardwareRayTracing", true);
            cfg.enableEdr = parseBoolean(props, "enableEdr", true);
            cfg.thermalAdaptiveScaling = parseBoolean(props, "thermalAdaptiveScaling", true);
            cfg.shaderRenderScale = clampRenderScale(parseFloat(props, "shaderRenderScale", 1.0f));
            cfg.maxFramesInFlight = clampFramesInFlight(parseInt(props, "maxFramesInFlight", 2));
            cfg.suppressVanillaWater = parseBoolean(props, "suppressVanillaWater", true);
            cfg.connectedGlass = parseBoolean(props, "connectedGlass", true);
            cfg.dynamicLightsEnabled = parseBoolean(props, "dynamicLightsEnabled", true);
            cfg.interactiveRipplesEnabled = parseBoolean(props, "interactiveRipplesEnabled", true);
            cfg.telemetryHudMode = parseHudMode(props.getProperty("telemetryHudMode"));
            cfg.liveShaderPreviewBackdrop = parseBoolean(props, "liveShaderPreviewBackdrop", true);
        } catch (IOException ex) {
            LOGGER.warn("Failed to load {}: {}", file, ex.getMessage());
        }
        return cfg;
    }

    public synchronized void save() {
        try {
            Path parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties props = new Properties();
            props.setProperty("shadersEnabled", Boolean.toString(shadersEnabled));
            props.setProperty("activeShaderPack", activeShaderPack != null ? activeShaderPack : "");
            props.setProperty("hardwareRayTracing", Boolean.toString(hardwareRayTracing));
            props.setProperty("enableEdr", Boolean.toString(enableEdr));
            props.setProperty("thermalAdaptiveScaling", Boolean.toString(thermalAdaptiveScaling));
            props.setProperty("shaderRenderScale", Float.toString(shaderRenderScale));
            props.setProperty("maxFramesInFlight", Integer.toString(maxFramesInFlight));
            props.setProperty("suppressVanillaWater", Boolean.toString(suppressVanillaWater));
            props.setProperty("connectedGlass", Boolean.toString(connectedGlass));
            props.setProperty("dynamicLightsEnabled", Boolean.toString(dynamicLightsEnabled));
            props.setProperty("interactiveRipplesEnabled", Boolean.toString(interactiveRipplesEnabled));
            props.setProperty("telemetryHudMode", telemetryHudMode.name());
            props.setProperty("liveShaderPreviewBackdrop", Boolean.toString(liveShaderPreviewBackdrop));
            try (OutputStream out = Files.newOutputStream(configFile)) {
                props.store(out, "Metality Apple Silicon Metal Engine & Shader Settings");
            }
        } catch (IOException ex) {
            LOGGER.warn("Failed to save {}: {}", configFile, ex.getMessage());
        }
    }

    public synchronized MetalitySettingsConfig copy() {
        MetalitySettingsConfig clone = new MetalitySettingsConfig(this.configFile);
        clone.applyFrom(this);
        return clone;
    }

    public synchronized void applyFrom(MetalitySettingsConfig other) {
        if (other == null) {
            return;
        }
        this.shadersEnabled = other.shadersEnabled;
        this.activeShaderPack = other.activeShaderPack;
        this.hardwareRayTracing = other.hardwareRayTracing;
        this.enableEdr = other.enableEdr;
        this.thermalAdaptiveScaling = other.thermalAdaptiveScaling;
        this.shaderRenderScale = clampRenderScale(other.shaderRenderScale);
        this.maxFramesInFlight = clampFramesInFlight(other.maxFramesInFlight);
        this.suppressVanillaWater = other.suppressVanillaWater;
        this.connectedGlass = other.connectedGlass;
        this.dynamicLightsEnabled = other.dynamicLightsEnabled;
        this.interactiveRipplesEnabled = other.interactiveRipplesEnabled;
        this.telemetryHudMode = other.telemetryHudMode != null ? other.telemetryHudMode : TelemetryHudMode.OFF;
        this.liveShaderPreviewBackdrop = other.liveShaderPreviewBackdrop;
    }

    public synchronized void resetEngineDefaults() {
        this.hardwareRayTracing = true;
        this.enableEdr = true;
        this.thermalAdaptiveScaling = true;
        this.shaderRenderScale = 1.0f;
        this.maxFramesInFlight = 2;
        this.suppressVanillaWater = true;
        this.connectedGlass = true;
        this.dynamicLightsEnabled = true;
        this.interactiveRipplesEnabled = true;
        this.telemetryHudMode = TelemetryHudMode.OFF;
        this.liveShaderPreviewBackdrop = true;
    }

    public boolean resolveEffectiveEdr() {
        String sys = System.getProperty("metality.enableEdr");
        if (sys != null && !sys.isBlank()) {
            return Boolean.parseBoolean(sys.trim());
        }
        return enableEdr;
    }

    public static Path packOverridesFile(Path gameDir, String packKey) {
        String clean = sanitizePackKey(packKey);
        return gameDir.resolve("shaderpacks").resolve(clean + ".properties");
    }

    public static Map<String, String> loadPackOverrides(Path gameDir, String packKey) {
        if (gameDir == null || packKey == null || packKey.isBlank()) {
            return Map.of();
        }
        Path file = packOverridesFile(gameDir, packKey);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Properties props = new Properties();
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            for (String key : props.stringPropertyNames()) {
                String val = props.getProperty(key);
                if (val != null) {
                    out.put(key, val.trim());
                }
            }
        } catch (IOException ex) {
            LOGGER.warn("Failed to read shader pack overrides {}: {}", file, ex.getMessage());
        }
        return out;
    }

    public static void savePackOverrides(Path gameDir, String packKey, Map<String, String> overrides) {
        if (gameDir == null || packKey == null || packKey.isBlank()) {
            return;
        }
        Path file = packOverridesFile(gameDir, packKey);
        try {
            if (overrides == null || overrides.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Properties props = new Properties();
            for (var entry : overrides.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    props.setProperty(entry.getKey(), entry.getValue());
                }
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Metality Shader Pack Overrides for " + packKey);
            }
        } catch (IOException ex) {
            LOGGER.warn("Failed to write shader pack overrides {}: {}", file, ex.getMessage());
        }
    }

    private static String sanitizePackKey(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public boolean isShadersEnabled() {
        return shadersEnabled;
    }

    public void setShadersEnabled(boolean shadersEnabled) {
        this.shadersEnabled = shadersEnabled;
    }

    public String getActiveShaderPack() {
        return activeShaderPack;
    }

    public void setActiveShaderPack(String activeShaderPack) {
        this.activeShaderPack = activeShaderPack != null ? activeShaderPack.trim() : "";
    }

    public boolean isHardwareRayTracing() {
        return hardwareRayTracing;
    }

    public void setHardwareRayTracing(boolean hardwareRayTracing) {
        this.hardwareRayTracing = hardwareRayTracing;
    }

    public boolean isEnableEdr() {
        return enableEdr;
    }

    public void setEnableEdr(boolean enableEdr) {
        this.enableEdr = enableEdr;
    }

    public boolean isThermalAdaptiveScaling() {
        return thermalAdaptiveScaling;
    }

    public void setThermalAdaptiveScaling(boolean thermalAdaptiveScaling) {
        this.thermalAdaptiveScaling = thermalAdaptiveScaling;
    }

    public float getShaderRenderScale() {
        return shaderRenderScale;
    }

    public void setShaderRenderScale(float shaderRenderScale) {
        this.shaderRenderScale = clampRenderScale(shaderRenderScale);
    }

    public int getMaxFramesInFlight() {
        return maxFramesInFlight;
    }

    public void setMaxFramesInFlight(int maxFramesInFlight) {
        this.maxFramesInFlight = clampFramesInFlight(maxFramesInFlight);
    }

    public boolean isSuppressVanillaWater() {
        return suppressVanillaWater;
    }

    public void setSuppressVanillaWater(boolean suppressVanillaWater) {
        this.suppressVanillaWater = suppressVanillaWater;
    }

    public boolean isConnectedGlass() {
        return connectedGlass;
    }

    public void setConnectedGlass(boolean connectedGlass) {
        this.connectedGlass = connectedGlass;
    }

    public boolean isDynamicLightsEnabled() {
        return dynamicLightsEnabled;
    }

    public void setDynamicLightsEnabled(boolean dynamicLightsEnabled) {
        this.dynamicLightsEnabled = dynamicLightsEnabled;
    }

    public boolean isInteractiveRipplesEnabled() {
        return interactiveRipplesEnabled;
    }

    public void setInteractiveRipplesEnabled(boolean interactiveRipplesEnabled) {
        this.interactiveRipplesEnabled = interactiveRipplesEnabled;
    }

    public TelemetryHudMode getTelemetryHudMode() {
        return telemetryHudMode;
    }

    public void setTelemetryHudMode(TelemetryHudMode telemetryHudMode) {
        this.telemetryHudMode = telemetryHudMode != null ? telemetryHudMode : TelemetryHudMode.OFF;
    }

    public boolean isLiveShaderPreviewBackdrop() {
        return liveShaderPreviewBackdrop;
    }

    public void setLiveShaderPreviewBackdrop(boolean liveShaderPreviewBackdrop) {
        this.liveShaderPreviewBackdrop = liveShaderPreviewBackdrop;
    }

    private static boolean parseBoolean(Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : Boolean.parseBoolean(v.trim());
    }

    private static float parseFloat(Properties p, String key, float fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseInt(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static TelemetryHudMode parseHudMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return TelemetryHudMode.OFF;
        }
        try {
            return TelemetryHudMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TelemetryHudMode.OFF;
        }
    }

    private static float clampRenderScale(float v) {
        if (Float.isNaN(v)) {
            return 1.0f;
        }
        return Math.max(0.50f, Math.min(1.00f, v));
    }

    private static int clampFramesInFlight(int v) {
        return Math.clamp(v, 2, 3);
    }
}

package dev.metality.config;

import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.config.MetalitySettingsConfig.TelemetryHudMode;
import dev.metality.shader.ShaderPackScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetalitySettingsConfigTest {

    @Test
    void configSaveAndLoadRoundtrip(@TempDir Path tempDir) {
        MetalitySettingsConfig cfg = MetalitySettingsConfig.load(tempDir);
        cfg.setShadersEnabled(false);
        cfg.setActiveShaderPack("meteor-shader");
        cfg.setHardwareRayTracing(false);
        cfg.setEnableEdr(false);
        cfg.setThermalAdaptiveScaling(false);
        cfg.setShaderRenderScale(0.67f);
        cfg.setMaxFramesInFlight(2);
        cfg.setSuppressVanillaWater(false);
        cfg.setConnectedGlass(false);
        cfg.setDynamicLightsEnabled(false);
        cfg.setInteractiveRipplesEnabled(false);
        cfg.setTelemetryHudMode(TelemetryHudMode.DETAILED);
        cfg.setLiveShaderPreviewBackdrop(false);
        cfg.save();

        MetalitySettingsConfig reloaded = MetalitySettingsConfig.load(tempDir);
        assertFalse(reloaded.isShadersEnabled());
        assertEquals("meteor-shader", reloaded.getActiveShaderPack());
        assertFalse(reloaded.isHardwareRayTracing());
        assertFalse(reloaded.isEnableEdr());
        assertFalse(reloaded.isThermalAdaptiveScaling());
        assertEquals(0.67f, reloaded.getShaderRenderScale(), 0.001f);
        assertEquals(2, reloaded.getMaxFramesInFlight());
        assertFalse(reloaded.isSuppressVanillaWater());
        assertFalse(reloaded.isConnectedGlass());
        assertFalse(reloaded.isDynamicLightsEnabled());
        assertFalse(reloaded.isInteractiveRipplesEnabled());
        assertEquals(TelemetryHudMode.DETAILED, reloaded.getTelemetryHudMode());
        assertFalse(reloaded.isLiveShaderPreviewBackdrop());
    }

    @Test
    void existingConfigWithoutConnectedGlassDefaultsToTrue(@TempDir Path tempDir) throws IOException {
        Path cfgFile = MetalitySettingsConfig.configFilePath(tempDir);
        Files.createDirectories(cfgFile.getParent());
        Files.writeString(cfgFile, "shadersEnabled=true\nsuppressVanillaWater=true\n", StandardCharsets.UTF_8);

        MetalitySettingsConfig loaded = MetalitySettingsConfig.load(tempDir);
        assertTrue(loaded.isConnectedGlass(), "Missing connectedGlass key must default to true");

        loaded.setConnectedGlass(false);
        loaded.resetEngineDefaults();
        assertTrue(loaded.isConnectedGlass(), "resetEngineDefaults must restore connectedGlass to true");
    }

    @Test
    void packOverridesSaveLoadAndDelete(@TempDir Path tempDir) {
        MetalitySettingsConfig.savePackOverrides(tempDir, "meteor-shader", Map.of(
                "EXPOSURE", "1.10",
                "BLOOM_STRENGTH", "0.5"
        ));
        Map<String, String> loaded = MetalitySettingsConfig.loadPackOverrides(tempDir, "meteor-shader");
        assertEquals("1.10", loaded.get("EXPOSURE"));
        assertEquals("0.5", loaded.get("BLOOM_STRENGTH"));

        MetalitySettingsConfig.savePackOverrides(tempDir, "meteor-shader", Map.of());
        assertFalse(Files.exists(MetalitySettingsConfig.packOverridesFile(tempDir, "meteor-shader")));
    }

    @Test
    void scannerFindsApertureLegacyZipAndExternalDedupe(@TempDir Path tempDir) throws IOException {
        Path packsDir = tempDir.resolve("shaderpacks");
        Path apertureDir = packsDir.resolve("meteor-shader");
        Files.createDirectories(apertureDir);
        Files.writeString(apertureDir.resolve("pack.ts"), "// pack", StandardCharsets.UTF_8);

        Path irisZip = packsDir.resolve("ComplementaryTest.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(irisZip))) {
            zos.putNextEntry(new ZipEntry("shaders/final.fsh"));
            zos.write("void main() {}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Files.writeString(packsDir.resolve("meteor-shader.properties"), "EXPOSURE=1.0\n", StandardCharsets.UTF_8);

        Path externalPack = tempDir.resolve("external-pack");
        Files.createDirectories(externalPack);
        Files.writeString(externalPack.resolve("pack.ts"), "// external", StandardCharsets.UTF_8);

        List<ShaderPackScanner.DiscoveredPack> packs = ShaderPackScanner.scan(packsDir, externalPack);
        assertEquals(3, packs.size());
        assertEquals(ShaderPackFormat.APERTURE_TS, packs.get(0).format());
        assertEquals(ShaderPackFormat.APERTURE_TS, packs.get(1).format());
        assertEquals(ShaderPackFormat.LEGACY_IRIS_GLSL, packs.get(2).format());
        assertTrue(packs.stream().anyMatch(ShaderPackScanner.DiscoveredPack::external));

        // Dedupe when extraActivePackPath points inside shaderpacksDir
        List<ShaderPackScanner.DiscoveredPack> deduped = ShaderPackScanner.scan(packsDir, apertureDir);
        assertEquals(2, deduped.size());
        assertFalse(deduped.get(0).external());

        // Dropped valid pack vs invalid file
        Path dropSource = tempDir.resolve("dropped-iris.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(dropSource))) {
            zos.putNextEntry(new ZipEntry("shaders/composite.fsh"));
            zos.write("void main() {}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path imported = ShaderPackScanner.importDroppedPack(dropSource, packsDir);
        assertTrue(Files.isRegularFile(imported));

        Path bogusFile = tempDir.resolve("notes.txt");
        Files.writeString(bogusFile, "hello");
        assertThrows(IOException.class, () -> ShaderPackScanner.importDroppedPack(bogusFile, packsDir));
    }
}

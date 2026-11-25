package dev.metality.mc263.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.metality.config.MetalitySettingsConfig.TelemetryHudMode;
import dev.metality.mc263.gui.MetalityVideoSettingsScreen.Tab;
import dev.metality.mc263.renderpearl.MetalDeviceBackend;
import dev.metality.mc263.shader.Mc263ShaderRuntime;
import dev.metality.mc263.shader.Mc263ShaderRuntime.PackActionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metality 遊戲內快捷鍵（O 光影包選單 / K 開關光影 / R 熱重載）與 UI 展示驗證掛鉤。
 */
public final class MetalityKeybinds {

    private static final Logger LOGGER = LoggerFactory.getLogger("Metality/Keybinds");
    private static KeyMapping keyShaderPacks;
    private static KeyMapping keyToggleShaders;
    private static KeyMapping keyReloadShaders;

    private static boolean showcaseInitialized = false;
    private static boolean showcaseDone = false;
    private static Path showcaseOutDir = null;
    private static int showcaseStage = 0;
    private static int showcaseTicks = 0;
    private static TelemetryHudMode showcaseSavedHudMode = TelemetryHudMode.OFF;

    private MetalityKeybinds() {}

    public static void register() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("metality", "keybinds")
        );
        keyShaderPacks = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "metality.key.shaderpacks", InputConstants.KEY_O, category
        ));
        keyToggleShaders = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "metality.key.toggle_shaders", InputConstants.KEY_K, category
        ));
        keyReloadShaders = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "metality.key.reload_shaders", InputConstants.KEY_R, category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(MetalityKeybinds::onEndClientTick);
    }

    private static void onEndClientTick(Minecraft mc) {
        if (mc == null) {
            return;
        }
        handleKeybinds(mc);
        handleUiShowcaseAutomation(mc);
    }

    private static void handleKeybinds(Minecraft mc) {
        if (mc.gui.screen() != null) {
            return;
        }
        while (keyShaderPacks != null && keyShaderPacks.consumeClick()) {
            mc.gui.setScreen(new MetalityVideoSettingsScreen(null, mc, mc.options, Tab.SHADERS));
        }
        while (keyToggleShaders != null && keyToggleShaders.consumeClick()) {
            PackActionResult res = Mc263ShaderRuntime.toggleShaders();
            mc.gui.hud.setOverlayMessage(Component.literal(res.message()), false);
        }
        while (keyReloadShaders != null && keyReloadShaders.consumeClick()) {
            PackActionResult res = Mc263ShaderRuntime.reloadActiveShaderPack();
            mc.gui.hud.setOverlayMessage(Component.literal(res.message()), false);
        }
    }

    private static void handleUiShowcaseAutomation(Minecraft mc) {
        if (showcaseDone) {
            return;
        }
        if (!showcaseInitialized) {
            showcaseInitialized = true;
            String dirProp = System.getProperty("metality.uiShowcaseDir");
            if (dirProp == null || dirProp.isBlank()) {
                showcaseDone = true;
                return;
            }
            showcaseOutDir = Path.of(dirProp.trim());
            try {
                Files.createDirectories(showcaseOutDir);
            } catch (IOException ex) {
                LOGGER.error("Failed to create uiShowcaseDir {}", showcaseOutDir, ex);
                showcaseDone = true;
                return;
            }
            LOGGER.info("[Metality/UI-Showcase] Armed automated UI screenshot harness -> {}", showcaseOutDir);
        }

        // Wait until Metal backend and either TitleScreen or World is ready
        if (MetalDeviceBackend.activeInstance().isEmpty() || mc.gameRenderer == null || mc.gameRenderer.mainRenderTarget() == null) {
            return;
        }

        showcaseTicks++;
        switch (showcaseStage) {
            case 0 -> {
                if (showcaseTicks < 35) {
                    return;
                }
                boolean expectsPack = System.getProperty("metality.shaderpack") != null
                        && !System.getProperty("metality.shaderpack").isBlank();
                boolean packReady = MetalDeviceBackend.activeInstance()
                        .map(b -> b.publicEngine().shaders().activePackInfo().isPresent())
                        .orElse(false);
                if (expectsPack && (!packReady || mc.level == null) && showcaseTicks < 360) {
                    return;
                }
                mc.gui.setScreen(new MetalityVideoSettingsScreen(mc.gui.screen(), mc, mc.options, Tab.GENERAL));
                showcaseStage = 1;
                showcaseTicks = 0;
            }
            case 1 -> {
                if (showcaseTicks < 10) return;
                grabPng(mc, "ui_general.png");
                if (mc.gui.screen() instanceof MetalityVideoSettingsScreen scr) {
                    scr.selectTab(Tab.QUALITY);
                }
                showcaseStage = 2;
                showcaseTicks = 0;
            }
            case 2 -> {
                if (showcaseTicks < 8) return;
                grabPng(mc, "ui_quality.png");
                if (mc.gui.screen() instanceof MetalityVideoSettingsScreen scr) {
                    scr.selectTab(Tab.ENGINE);
                }
                showcaseStage = 3;
                showcaseTicks = 0;
            }
            case 3 -> {
                if (showcaseTicks < 8) return;
                grabPng(mc, "ui_engine.png");
                if (mc.gui.screen() instanceof MetalityVideoSettingsScreen scr) {
                    scr.selectTab(Tab.SHADERS);
                }
                showcaseStage = 4;
                showcaseTicks = 0;
            }
            case 4 -> {
                if (showcaseTicks < 8) return;
                grabPng(mc, "ui_shaders.png");
                MetalDeviceBackend backend = MetalDeviceBackend.activeInstance().orElse(null);
                if (backend != null && backend.publicEngine().shaders().activePackInfo().isPresent()) {
                    mc.gui.setScreen(new MetalityShaderPackSettingsScreen(
                            mc.gui.screen(), mc, backend.settingsConfig()));
                    showcaseStage = 5;
                    showcaseTicks = 0;
                } else {
                    showcaseStage = 6;
                    showcaseTicks = 0;
                }
            }
            case 5 -> {
                if (showcaseTicks < 8) return;
                grabPng(mc, "ui_shader_settings.png");
                if (mc.gui.screen() instanceof MetalityShaderPackSettingsScreen sub) {
                    sub.onClose();
                }
                if (mc.gui.screen() instanceof MetalityVideoSettingsScreen mainScr) {
                    mainScr.onClose();
                }
                showcaseStage = 6;
                showcaseTicks = 0;
            }
            case 6 -> {
                if (mc.level != null) {
                    MetalDeviceBackend.activeInstance().ifPresent(b -> {
                        showcaseSavedHudMode = b.settingsConfig().getTelemetryHudMode();
                        b.settingsConfig().setTelemetryHudMode(TelemetryHudMode.DETAILED);
                    });
                    mc.gui.setScreen(null);
                    showcaseStage = 7;
                    showcaseTicks = 0;
                } else {
                    showcaseStage = 8;
                    showcaseTicks = 0;
                }
            }
            case 7 -> {
                if (showcaseTicks < 10) return;
                grabPng(mc, "ui_telemetry_hud.png");
                MetalDeviceBackend.activeInstance().ifPresent(b ->
                        b.settingsConfig().setTelemetryHudMode(showcaseSavedHudMode));
                showcaseStage = 8;
                showcaseTicks = 0;
            }
            case 8 -> {
                if (showcaseTicks < 8) return;
                showcaseDone = true;
                LOGGER.info("[Metality/UI-Showcase] All screenshots captured successfully; stopping Minecraft.");
                mc.stop();
            }
            default -> showcaseDone = true;
        }
    }

    private static void grabPng(Minecraft mc, String fileName) {
        Path out = showcaseOutDir.resolve(fileName);
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> {
            try (img) {
                img.writeToFile(out);
                LOGGER.info("[Metality/UI-Showcase] Wrote {}", out);
            } catch (IOException ex) {
                LOGGER.error("[Metality/UI-Showcase] Failed writing {}", out, ex);
            }
        });
    }
}

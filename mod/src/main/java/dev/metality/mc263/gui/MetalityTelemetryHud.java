package dev.metality.mc263.gui;

import dev.metality.api.MetalityCapabilities;
import dev.metality.api.MetalityTelemetry;
import dev.metality.api.shader.ShaderPackService.ActivePackInfo;
import dev.metality.config.MetalitySettingsConfig.TelemetryHudMode;
import dev.metality.impl.DefaultMetalityEngine;
import dev.metality.mc263.renderpearl.MetalDeviceBackend;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 螢幕左上角之精簡／完整 Apple Silicon Metal 效能與統一記憶體即時遙測覆層。
 */
public final class MetalityTelemetryHud {

    private MetalityTelemetryHud() {}

    public static void extractRenderState(GuiGraphicsExtractor graphics, Minecraft minecraft) {
        if (minecraft == null || minecraft.font == null) {
            return;
        }
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty()) {
            return;
        }
        MetalDeviceBackend backend = backendOpt.get();
        TelemetryHudMode mode = backend.settingsConfig().getTelemetryHudMode();
        if (mode == TelemetryHudMode.OFF) {
            return;
        }

        Font font = minecraft.font;
        DefaultMetalityEngine engine = backend.publicEngine();
        MetalityCapabilities caps = engine.capabilities();
        MetalityTelemetry tel = engine.telemetry();
        int fps = minecraft.getFps();
        double gpuMs = tel.lastGpuFrameTimeMs();

        if (mode == TelemetryHudMode.COMPACT) {
            String text = gpuMs > 0.01
                    ? String.format("Metality  %d FPS · %.1f ms GPU", fps, gpuMs)
                    : String.format("Metality  %d FPS · %s", fps, caps.deviceName());
            int w = font.width(text) + 18;
            int h = 18;
            MetalityUiTheme.fillPanel(graphics, 8, 8, w, h, 0xCC0A0F16, MetalityUiTheme.BORDER_SUBTLE);
            graphics.fill(8, 8, 10, 8 + h, MetalityUiTheme.ACCENT_CYAN);
            graphics.text(font, text, 14, 13, MetalityUiTheme.TEXT_PRIMARY, true);
            return;
        }

        // DETAILED mode
        String gpuFamily = caps.metal4CommandQueue()
                ? "Metal 4 (Apple" + caps.appleGpuFamily() + ")"
                : "Metal 3 (Apple" + caps.appleGpuFamily() + ")";
        String line1 = gpuMs > 0.01
                ? String.format("%s · %s · %d FPS · %.1f ms", caps.deviceName(), gpuFamily, fps, gpuMs)
                : String.format("%s · %s · %d FPS", caps.deviceName(), gpuFamily, fps);

        double umaAllocGb = tel.currentAllocatedUmaBytes() / (1024.0 * 1024.0 * 1024.0);
        double umaMaxGb = caps.recommendedWorkingSetBytes() / (1024.0 * 1024.0 * 1024.0);
        String thermal = thermalLabel(tel.thermalStateLevel());

        Optional<ActivePackInfo> packOpt = engine.shaders().activePackInfo();
        String packPart = packOpt.map(ActivePackInfo::packName)
                .orElseGet(() -> Component.translatable("metality.hud.vanilla").getString());
        String rtPart;
        if (packOpt.isPresent() && packOpt.get().hardwareRayTracingEnabled() && engine.rayTracing().isEnabled()) {
            rtPart = engine.rayTracing().isHardwareAccelerated() ? "HWRT" : "Voxel RT";
        } else {
            rtPart = Component.translatable("metality.common.off").getString();
        }
        String line2 = String.format("UMA %.1f/%.1f GB · %s · RT: %s · %s",
                umaAllocGb, umaMaxGb, thermal, rtPart, packPart);

        int cardW = Math.max(font.width(line1), font.width(line2)) + 20;
        int cardH = 30;
        MetalityUiTheme.fillPanel(graphics, 8, 8, cardW, cardH, 0xD20A0F16, MetalityUiTheme.BORDER_SUBTLE);
        graphics.fill(8, 8, 10, 8 + cardH, MetalityUiTheme.ACCENT_CYAN);
        graphics.text(font, line1, 15, 13, MetalityUiTheme.TEXT_PRIMARY, true);
        graphics.text(font, line2, 15, 24, MetalityUiTheme.TEXT_SECONDARY, true);
    }

    public static String thermalLabel(int level) {
        return switch (level) {
            case 0 -> Component.translatable("metality.thermal.nominal").getString();
            case 1 -> Component.translatable("metality.thermal.fair").getString();
            case 2 -> Component.translatable("metality.thermal.serious").getString();
            case 3 -> Component.translatable("metality.thermal.critical").getString();
            default -> Component.translatable("metality.thermal.nominal").getString();
        };
    }
}

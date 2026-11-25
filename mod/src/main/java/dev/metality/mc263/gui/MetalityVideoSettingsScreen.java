package dev.metality.mc263.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.metality.api.MetalityCapabilities;
import dev.metality.api.MetalityTelemetry;
import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.api.shader.ShaderPackService.ActivePackInfo;
import dev.metality.config.MetalitySettingsConfig;
import dev.metality.config.MetalitySettingsConfig.TelemetryHudMode;
import dev.metality.impl.DefaultMetalityEngine;
import dev.metality.mc263.gui.MetalityOptionRows.ActionOptionRow;
import dev.metality.mc263.gui.MetalityOptionRows.CycleOptionRow;
import dev.metality.mc263.gui.MetalityOptionRows.HardwareCardRow;
import dev.metality.mc263.gui.MetalityOptionRows.Impact;
import dev.metality.mc263.gui.MetalityOptionRows.InspectorInfo;
import dev.metality.mc263.gui.MetalityOptionRows.OptionRow;
import dev.metality.mc263.gui.MetalityOptionRows.SectionHeaderRow;
import dev.metality.mc263.gui.MetalityOptionRows.SliderOptionRow;
import dev.metality.mc263.gui.MetalityOptionRows.ToggleOptionRow;
import dev.metality.mc263.renderpearl.MetalDeviceBackend;
import dev.metality.mc263.shader.Mc263ShaderRuntime;
import dev.metality.mc263.shader.Mc263ShaderRuntime.PackActionResult;
import dev.metality.shader.ShaderPackScanner;
import dev.metality.shader.ShaderPackScanner.DiscoveredPack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

/**
 * Metality 整合版顯示、Apple Silicon 引擎與光影包設定主介面（融合 Sodium 0.6+ / Reese's 與 Iris 1.8+ 設計）。
 */
public final class MetalityVideoSettingsScreen extends Screen {

    public enum Tab {
        GENERAL("metality.tab.general"),
        QUALITY("metality.tab.quality"),
        ENGINE("metality.tab.engine"),
        SHADERS("metality.tab.shaders");

        private final String langKey;

        Tab(String langKey) {
            this.langKey = langKey;
        }

        public Component label() {
            return Component.translatable(langKey);
        }
    }

    private static volatile boolean bypassRedirectOnce = false;

    public static void openVanillaVideoSettings(Minecraft mc, Screen parent, Options options) {
        bypassRedirectOnce = true;
        mc.gui.setScreen(new VideoSettingsScreen(parent, mc, options));
    }

    public static boolean consumeVanillaBypass() {
        if (bypassRedirectOnce) {
            bypassRedirectOnce = false;
            return true;
        }
        return false;
    }

    private final Screen lastScreen;
    private final Options options;
    private Tab activeTab;

    private boolean snapshotCaptured = false;
    private final Map<OptionInstance<?>, Object> vanillaCommitted = new IdentityHashMap<>();
    private final Map<OptionInstance<?>, Object> vanillaStaged = new IdentityHashMap<>();
    private final Map<OptionInstance<?>, Object> vanillaBaseline = new IdentityHashMap<>();
    private MetalitySettingsConfig engineCommitted;
    private MetalitySettingsConfig engineDraft;

    private final List<OptionRow> currentRows = new ArrayList<>();
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private OptionRow draggingRow = null;
    private boolean draggingScrollbar = false;

    // Shaders tab state
    private EditBox shaderSearchBox;
    private String shaderSearchQuery = "";
    private List<DiscoveredPack> discoveredPacks = List.of();
    private List<DiscoveredPack> filteredPacks = List.of();
    private String selectedPackId = "";
    private int packScrollOffset = 0;
    private long lastPackClickTimeMs = 0L;
    private String lastClickedPackId = "";
    private String bannerMessage = "";
    private boolean bannerIsError = false;
    private long bannerExpireMs = 0L;

    // Layout geometry updated every init/render
    private int navX, navY, navW, navH;
    private int listX, listY, listW, listH;
    private int inspectorX, inspectorY, inspectorW, inspectorH;
    private boolean wideInspector = true;

    // Footer & Shaders tab buttons [x, y, w, h]
    private final int[] btnVanillaRect = new int[4];
    private final int[] btnUndoRect = new int[4];
    private final int[] btnApplyRect = new int[4];
    private final int[] btnDoneRect = new int[4];
    private final int[] masterSwitchRect = new int[4];
    private final int[] packOpenFolderRect = new int[4];
    private final int[] packSettingsRect = new int[4];
    private final int[] packPrimaryActionRect = new int[4];
    private final int[] packListRect = new int[4];

    public MetalityVideoSettingsScreen(Screen lastScreen, Minecraft minecraft, Options options, Tab initialTab) {
        super(Component.translatable("metality.screen.title"));
        this.lastScreen = lastScreen;
        this.options = options;
        this.activeTab = initialTab != null ? initialTab : Tab.GENERAL;
    }

    public Tab activeTab() {
        return activeTab;
    }

    public void selectTab(Tab tab) {
        if (tab != null && tab != this.activeTab) {
            this.activeTab = tab;
            this.scrollOffset = 0;
            this.draggingRow = null;
            rebuildTabContent();
        }
    }

    @Override
    protected void init() {
        ensureSnapshotsCaptured();
        computeLayout();
        if (shaderSearchBox == null) {
            shaderSearchBox = new EditBox(this.font, 0, 0, 120, 14, Component.empty());
            shaderSearchBox.setMaxLength(80);
            shaderSearchBox.setBordered(false);
            shaderSearchBox.setTextColor(MetalityUiTheme.TEXT_PRIMARY);
            shaderSearchBox.setHint(Component.translatable("metality.shaders.search_hint"));
            shaderSearchBox.setValue(shaderSearchQuery);
            shaderSearchBox.setResponder(text -> {
                shaderSearchQuery = text != null ? text : "";
                filterDiscoveredPacks();
            });
        }
        addRenderableWidget(shaderSearchBox);
        rebuildTabContent();
    }

    private void ensureSnapshotsCaptured() {
        if (snapshotCaptured) {
            return;
        }
        snapshotCaptured = true;
        captureVanillaOption(options.renderDistance(), 12);
        captureVanillaOption(options.simulationDistance(), 12);
        captureVanillaOption(options.entityDistanceScaling(), 1.0);
        captureVanillaOption(options.chunkSectionFadeInTime(), 0.0);
        captureVanillaOption(options.fullscreen(), false);
        captureVanillaOption(options.exclusiveFullscreen(), false);
        captureVanillaOption(options.macFullscreenMenuVisibility(), true);
        captureVanillaOption(options.enableVsync(), true);
        captureVanillaOption(options.framerateLimit(), 120);
        captureVanillaOption(options.inactivityFpsLimit(), InactivityFpsLimit.AFK);
        captureVanillaOption(options.guiScale(), 0);
        captureVanillaOption(options.gamma(), 0.5);
        captureVanillaOption(options.fov(), 70);
        captureVanillaOption(options.fovEffectScale(), 1.0);
        captureVanillaOption(options.bobView(), true);
        captureVanillaOption(options.attackIndicator(), AttackIndicatorStatus.CROSSHAIR);
        captureVanillaOption(options.showAutosaveIndicator(), true);

        captureVanillaOption(options.graphicsPreset(), GraphicsPreset.FANCY);
        captureVanillaOption(options.ambientOcclusion(), true);
        captureVanillaOption(options.cutoutLeaves(), true);
        captureVanillaOption(options.improvedTransparency(), false);
        captureVanillaOption(options.entityShadows(), true);
        captureVanillaOption(options.vignette(), true);
        captureVanillaOption(options.cloudStatus(), CloudStatus.FANCY);
        captureVanillaOption(options.cloudRange(), 128);
        captureVanillaOption(options.weatherRadius(), 10);
        captureVanillaOption(options.particles(), ParticleStatus.ALL);
        captureVanillaOption(options.biomeBlendRadius(), 2);
        captureVanillaOption(options.mipmapLevels(), 4);
        captureVanillaOption(options.textureFiltering(), TextureFilteringMethod.NONE);
        captureVanillaOption(options.maxAnisotropyBit(), 1);
        captureVanillaOption(options.menuBackgroundBlurriness(), 5);
        captureVanillaOption(options.prioritizeChunkUpdates(), PrioritizeChunkUpdates.NONE);

        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isPresent()) {
            this.engineCommitted = backendOpt.get().settingsConfig().copy();
        } else {
            this.engineCommitted = MetalitySettingsConfig.load(this.minecraft.gameDirectory.toPath());
        }
        this.engineDraft = this.engineCommitted.copy();
    }

    private <T> void captureVanillaOption(OptionInstance<T> opt, T baseline) {
        T current = opt.get();
        vanillaCommitted.put(opt, current);
        vanillaStaged.put(opt, current);
        vanillaBaseline.put(opt, baseline != null ? baseline : current);
    }

    @SuppressWarnings("unchecked")
    private <T> T staged(OptionInstance<T> opt) {
        Object v = vanillaStaged.get(opt);
        return v != null ? (T) v : opt.get();
    }

    @SuppressWarnings("unchecked")
    private <T> T baseline(OptionInstance<T> opt) {
        Object v = vanillaBaseline.get(opt);
        return v != null ? (T) v : opt.get();
    }

    private <T> void stageVanilla(OptionInstance<T> opt, T val) {
        vanillaStaged.put(opt, val);
    }

    private void computeLayout() {
        int topH = 34;
        int footerH = 34;
        int pad = 10;
        int bodyTop = topH + 5;
        int bodyBottom = this.height - footerH - 5;
        int bodyH = Math.max(110, bodyBottom - bodyTop);

        this.navX = pad;
        this.navY = bodyTop;
        this.navW = Math.clamp((int) (this.width * 0.18), 88, 136);
        this.navH = bodyH;

        this.wideInspector = this.width >= 500;
        if (this.wideInspector) {
            this.inspectorW = Math.clamp((int) (this.width * 0.27), 140, 200);
            this.inspectorX = this.width - pad - this.inspectorW;
            this.inspectorY = bodyTop;
            this.inspectorH = bodyH;

            this.listX = this.navX + this.navW + 6;
            this.listY = bodyTop;
            this.listW = Math.max(180, this.inspectorX - this.listX - 6);
            this.listH = bodyH;
        } else {
            this.inspectorH = 44;
            this.inspectorX = this.navX + this.navW + 6;
            this.inspectorW = Math.max(150, this.width - this.inspectorX - pad);
            this.inspectorY = bodyBottom - this.inspectorH;

            this.listX = this.inspectorX;
            this.listY = bodyTop;
            this.listW = this.inspectorW;
            this.listH = Math.max(70, this.inspectorY - 5 - bodyTop);
        }

        // Footer buttons
        int fy = this.height - footerH + 6;
        int fh = 22;
        int vW = Math.min(116, Math.max(88, (this.width - 32) / 5));
        setRect(btnVanillaRect, pad, fy, vW, fh);

        int actW = Math.min(76, Math.max(52, (this.width - pad * 2 - vW - 22) / 3));
        int doneX = this.width - pad - actW;
        int applyX = doneX - 6 - actW;
        int undoX = applyX - 6 - actW;
        setRect(btnDoneRect, doneX, fy, actW, fh);
        setRect(btnApplyRect, applyX, fy, actW, fh);
        setRect(btnUndoRect, undoX, fy, actW, fh);

        // Shaders tab layout sync
        if (shaderSearchBox != null) {
            boolean showSearch = (this.activeTab == Tab.SHADERS) && (this.discoveredPacks.size() >= 8);
            shaderSearchBox.visible = showSearch;
            shaderSearchBox.active = showSearch;
            int swW = Math.min(108, Math.max(82, this.listW / 3));
            int searchX = this.listX + 8 + swW + 6;
            int searchW = Math.max(50, this.listX + this.listW - 8 - searchX);
            shaderSearchBox.setX(searchX + 5);
            shaderSearchBox.setY(this.listY + 13);
            shaderSearchBox.setSize(Math.max(36, searchW - 10), 12);
        }
    }

    private static void setRect(int[] rect, int x, int y, int w, int h) {
        rect[0] = x;
        rect[1] = y;
        rect[2] = w;
        rect[3] = h;
    }

    private static boolean hit(int[] rect, double mx, double my) {
        return mx >= rect[0] && mx < rect[0] + rect[2] && my >= rect[1] && my < rect[1] + rect[3];
    }

    private void rebuildTabContent() {
        currentRows.clear();
        computeLayout();
        switch (activeTab) {
            case GENERAL -> buildGeneralRows();
            case QUALITY -> buildQualityRows();
            case ENGINE -> buildEngineRows();
            case SHADERS -> refreshDiscoveredPacks();
        }
        contentHeight = 0;
        for (OptionRow row : currentRows) {
            contentHeight += row.height();
        }
        clampScroll();
    }

    private void buildGeneralRows() {
        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.world_distance")));
        currentRows.add(vanillaSlider(options.renderDistance(), "metality.opt.render_distance", Impact.HIGH,
                v -> Component.translatable("metality.common.chunks", v).getString()));
        currentRows.add(vanillaSlider(options.simulationDistance(), "metality.opt.simulation_distance", Impact.MEDIUM,
                v -> Component.translatable("metality.common.chunks", v).getString()));
        currentRows.add(vanillaSlider(options.entityDistanceScaling(), "metality.opt.entity_distance", Impact.MEDIUM,
                v -> Math.round(v * 100.0) + "%"));
        currentRows.add(vanillaSlider(options.chunkSectionFadeInTime(), "metality.opt.chunk_fade", Impact.LOW,
                v -> String.format("%.1fs", v)));

        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.display_fps")));
        currentRows.add(vanillaToggle(options.fullscreen(), "metality.opt.fullscreen", Impact.LOW));
        currentRows.add(vanillaToggle(options.exclusiveFullscreen(), "metality.opt.exclusive_fullscreen", Impact.LOW));
        if (Util.getPlatform() == Util.OS.OSX) {
            currentRows.add(vanillaToggle(options.macFullscreenMenuVisibility(), "metality.opt.mac_menu", Impact.NONE));
        }
        currentRows.add(vanillaToggle(options.enableVsync(), "metality.opt.vsync", Impact.VARIES));
        currentRows.add(vanillaSlider(options.framerateLimit(), "metality.opt.max_fps", Impact.VARIES,
                v -> v >= 260 ? Component.translatable("metality.common.unlimited").getString() : v + " FPS"));
        currentRows.add(new CycleOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.inactivity_fps"),
                Component.translatable("metality.opt.inactivity_fps.desc"),
                Impact.LOW,
                List.of(InactivityFpsLimit.values()),
                () -> staged(options.inactivityFpsLimit()),
                v -> stageVanilla(options.inactivityFpsLimit(), v),
                baseline(options.inactivityFpsLimit()),
                v -> v.caption().getString(),
                null
        ));
        currentRows.add(vanillaSlider(options.guiScale(), "metality.opt.gui_scale", Impact.NONE,
                v -> v == 0 ? Component.translatable("metality.common.auto").getString() : v + "x"));
        currentRows.add(vanillaSlider(options.gamma(), "metality.opt.gamma", Impact.NONE,
                v -> Math.round(v * 100.0) + "%"));

        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.camera_pref")));
        currentRows.add(vanillaSlider(options.fov(), "metality.opt.fov", Impact.LOW, v -> v + "°"));
        currentRows.add(vanillaSlider(options.fovEffectScale(), "metality.opt.fov_effects", Impact.NONE,
                v -> Math.round(v * 100.0) + "%"));
        currentRows.add(vanillaToggle(options.bobView(), "metality.opt.bob_view", Impact.NONE));
        currentRows.add(new CycleOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.attack_indicator"),
                Component.translatable("metality.opt.attack_indicator.desc"),
                Impact.NONE,
                List.of(AttackIndicatorStatus.values()),
                () -> staged(options.attackIndicator()),
                v -> stageVanilla(options.attackIndicator(), v),
                baseline(options.attackIndicator()),
                v -> v.caption().getString(),
                null
        ));
        currentRows.add(vanillaToggle(options.showAutosaveIndicator(), "metality.opt.autosave", Impact.NONE));
    }

    private void buildQualityRows() {
        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.lighting_shading")));
        currentRows.add(new CycleOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.graphics_preset"),
                Component.translatable("metality.opt.graphics_preset.desc"),
                Impact.HIGH,
                List.of(GraphicsPreset.FAST, GraphicsPreset.FANCY, GraphicsPreset.FABULOUS),
                () -> staged(options.graphicsPreset()),
                preset -> {
                    stageVanilla(options.graphicsPreset(), preset);
                    applyGraphicsPresetToStaged(preset);
                },
                baseline(options.graphicsPreset()),
                p -> Component.translatable(p.getKey()).getString(),
                null
        ));
        currentRows.add(vanillaToggle(options.ambientOcclusion(), "metality.opt.ao", Impact.LOW));
        currentRows.add(vanillaToggle(options.cutoutLeaves(), "metality.opt.cutout_leaves", Impact.LOW));
        currentRows.add(vanillaToggle(options.improvedTransparency(), "metality.opt.improved_transparency", Impact.MEDIUM));
        currentRows.add(vanillaToggle(options.entityShadows(), "metality.opt.entity_shadows", Impact.LOW));
        currentRows.add(vanillaToggle(options.vignette(), "metality.opt.vignette", Impact.NONE));

        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.clouds_weather")));
        currentRows.add(new CycleOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.clouds"),
                Component.translatable("metality.opt.clouds.desc"),
                Impact.LOW,
                List.of(CloudStatus.values()),
                () -> staged(options.cloudStatus()),
                v -> stageVanilla(options.cloudStatus(), v),
                baseline(options.cloudStatus()),
                v -> v.caption().getString(),
                null
        ));
        currentRows.add(vanillaSlider(options.cloudRange(), "metality.opt.cloud_range", Impact.LOW,
                v -> Component.translatable("metality.common.chunks", v).getString()));
        currentRows.add(vanillaSlider(options.weatherRadius(), "metality.opt.weather_radius", Impact.MEDIUM,
                v -> v + " m"));
        currentRows.add(new CycleOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.particles"),
                Component.translatable("metality.opt.particles.desc"),
                Impact.MEDIUM,
                List.of(ParticleStatus.values()),
                () -> staged(options.particles()),
                v -> stageVanilla(options.particles(), v),
                baseline(options.particles()),
                v -> v.caption().getString(),
                null
        ));

        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.textures_mip")));
        currentRows.add(vanillaSlider(options.biomeBlendRadius(), "metality.opt.biome_blend", Impact.LOW,
                v -> v == 0 ? Component.translatable("metality.common.off").getString()
                        : ((v * 2 + 1) + "×" + (v * 2 + 1))));
        currentRows.add(vanillaSlider(options.mipmapLevels(), "metality.opt.mipmap", Impact.LOW,
                v -> v == 0 ? Component.translatable("metality.common.off").getString() : String.valueOf(v)));
        currentRows.add(new CycleOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.tex_filter"),
                Component.translatable("metality.opt.tex_filter.desc"),
                Impact.LOW,
                List.of(TextureFilteringMethod.values()),
                () -> staged(options.textureFiltering()),
                v -> stageVanilla(options.textureFiltering(), v),
                baseline(options.textureFiltering()),
                v -> v.caption().getString(),
                null
        ));
        currentRows.add(new SliderOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.anisotropy"),
                Component.translatable("metality.opt.anisotropy.desc"),
                Impact.LOW,
                () -> staged(options.maxAnisotropyBit()),
                v -> stageVanilla(options.maxAnisotropyBit(), v),
                baseline(options.maxAnisotropyBit()),
                v -> (double) Math.clamp(v, 0, 4) / 4.0,
                f -> Math.clamp((int) Math.round(f * 4.0), 0, 4),
                v -> v <= 0 ? Component.translatable("metality.common.off").getString() : ((1 << v) + "×"),
                () -> staged(options.textureFiltering()) == TextureFilteringMethod.ANISOTROPIC
        ));
        currentRows.add(vanillaSlider(options.menuBackgroundBlurriness(), "metality.opt.menu_blur", Impact.LOW,
                v -> v == 0 ? Component.translatable("metality.common.off").getString() : String.valueOf(v)));

        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.chunk_scheduling")));
        currentRows.add(new CycleOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.chunk_priority"),
                Component.translatable("metality.opt.chunk_priority.desc"),
                Impact.LOW,
                List.of(PrioritizeChunkUpdates.values()),
                () -> staged(options.prioritizeChunkUpdates()),
                v -> stageVanilla(options.prioritizeChunkUpdates(), v),
                baseline(options.prioritizeChunkUpdates()),
                v -> v.caption().getString(),
                null
        ));
    }

    private void buildEngineRows() {
        currentRows.add(new HardwareCardRow(minecraft));

        boolean hwrtSupported = MetalDeviceBackend.activeInstance()
                .map(b -> b.metalDevice().capabilities().hardwareRayTracing())
                .orElse(true);

        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.rt_hdr")));
        currentRows.add(new ToggleOptionRow(
                minecraft,
                Component.translatable("metality.opt.hwrt"),
                Component.translatable("metality.opt.hwrt.desc"),
                Impact.HIGH,
                () -> hwrtSupported && engineDraft.isHardwareRayTracing(),
                v -> engineDraft.setHardwareRayTracing(v),
                true,
                () -> hwrtSupported,
                Component.translatable("metality.opt.hwrt.unsupported")
        ));
        currentRows.add(new ToggleOptionRow(
                minecraft,
                Component.translatable("metality.opt.edr"),
                Component.translatable("metality.opt.edr.desc"),
                Impact.LOW,
                engineDraft::isEnableEdr,
                engineDraft::setEnableEdr,
                true,
                null,
                null
        ));

        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.resolution_thermal")));
        currentRows.add(SliderOptionRow.discreteList(
                minecraft,
                Component.translatable("metality.opt.render_scale"),
                Component.translatable("metality.opt.render_scale.desc"),
                Impact.HIGH,
                List.of(0.50f, 0.67f, 0.75f, 0.85f, 1.00f),
                engineDraft::getShaderRenderScale,
                engineDraft::setShaderRenderScale,
                1.00f,
                v -> Math.round(v * 100.0f) + "%",
                null
        ));
        currentRows.add(new ToggleOptionRow(
                minecraft,
                Component.translatable("metality.opt.thermal"),
                Component.translatable("metality.opt.thermal.desc"),
                Impact.MEDIUM,
                engineDraft::isThermalAdaptiveScaling,
                engineDraft::setThermalAdaptiveScaling,
                true,
                null,
                null
        ));

        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.metal_pipeline")));
        currentRows.add(new CycleOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.frames_in_flight"),
                Component.translatable("metality.opt.frames_in_flight.desc"),
                Impact.LOW,
                List.of(2, 3),
                engineDraft::getMaxFramesInFlight,
                engineDraft::setMaxFramesInFlight,
                3,
                v -> Component.translatable(v == 2 ? "metality.opt.frames.2" : "metality.opt.frames.3").getString(),
                null
        ));
        currentRows.add(new ToggleOptionRow(
                minecraft,
                Component.translatable("metality.opt.suppress_water"),
                Component.translatable("metality.opt.suppress_water.desc"),
                Impact.LOW,
                engineDraft::isSuppressVanillaWater,
                engineDraft::setSuppressVanillaWater,
                true,
                null,
                null
        ));
        currentRows.add(new ToggleOptionRow(
                minecraft,
                Component.translatable("metality.opt.connected_glass"),
                Component.translatable("metality.opt.connected_glass.desc"),
                Impact.NONE,
                engineDraft::isConnectedGlass,
                engineDraft::setConnectedGlass,
                true,
                null,
                null
        ));
        currentRows.add(new ToggleOptionRow(
                minecraft,
                Component.translatable("metality.opt.dynamic_lights"),
                Component.translatable("metality.opt.dynamic_lights.desc"),
                Impact.MEDIUM,
                engineDraft::isDynamicLightsEnabled,
                engineDraft::setDynamicLightsEnabled,
                true,
                null,
                null
        ));
        currentRows.add(new ToggleOptionRow(
                minecraft,
                Component.translatable("metality.opt.ripples"),
                Component.translatable("metality.opt.ripples.desc"),
                Impact.LOW,
                engineDraft::isInteractiveRipplesEnabled,
                engineDraft::setInteractiveRipplesEnabled,
                true,
                null,
                null
        ));

        currentRows.add(new SectionHeaderRow(Component.translatable("metality.section.telemetry_maintenance")));
        currentRows.add(new CycleOptionRow<>(
                minecraft,
                Component.translatable("metality.opt.telemetry_hud"),
                Component.translatable("metality.opt.telemetry_hud.desc"),
                Impact.NONE,
                List.of(TelemetryHudMode.values()),
                engineDraft::getTelemetryHudMode,
                engineDraft::setTelemetryHudMode,
                TelemetryHudMode.OFF,
                m -> Component.translatable("metality.hud." + m.name().toLowerCase(Locale.ROOT)).getString(),
                null
        ));
        currentRows.add(new ToggleOptionRow(
                minecraft,
                Component.translatable("metality.opt.live_preview"),
                Component.translatable("metality.opt.live_preview.desc"),
                Impact.NONE,
                engineDraft::isLiveShaderPreviewBackdrop,
                engineDraft::setLiveShaderPreviewBackdrop,
                true,
                null,
                null
        ));

        ActionOptionRow flushRow[] = new ActionOptionRow[1];
        flushRow[0] = new ActionOptionRow(
                minecraft,
                Component.translatable("metality.opt.flush_pso"),
                Component.translatable("metality.opt.flush_pso.desc"),
                Component.translatable("metality.opt.flush_pso.btn"),
                () -> {
                    MetalDeviceBackend.activeInstance().ifPresent(b -> b.metalDevice().flushPipelineCache());
                    flushRow[0].setStatusFeedback(Component.translatable("metality.opt.flush_pso.done").getString());
                }
        );
        currentRows.add(flushRow[0]);

        ActionOptionRow resetEngineRow[] = new ActionOptionRow[1];
        resetEngineRow[0] = new ActionOptionRow(
                minecraft,
                Component.translatable("metality.opt.reset_engine"),
                Component.translatable("metality.opt.reset_engine.desc"),
                Component.translatable("metality.opt.reset_engine.btn"),
                () -> {
                    engineDraft.resetEngineDefaults();
                    resetEngineRow[0].setStatusFeedback(Component.translatable("metality.opt.reset_engine.done").getString());
                }
        );
        currentRows.add(resetEngineRow[0]);
    }

    private ToggleOptionRow vanillaToggle(OptionInstance<Boolean> opt, String key, Impact impact) {
        return new ToggleOptionRow(
                minecraft,
                Component.translatable(key),
                Component.translatable(key + ".desc"),
                impact,
                () -> staged(opt),
                v -> stageVanilla(opt, v),
                baseline(opt),
                () -> true,
                null
        );
    }

    private <T> SliderOptionRow<T> vanillaSlider(
            OptionInstance<T> opt,
            String key,
            Impact impact,
            java.util.function.Function<T, String> fmt
    ) {
        @SuppressWarnings("unchecked")
        OptionInstance.SliderableValueSet<T> sliderSet = (OptionInstance.SliderableValueSet<T>) opt.values();
        return new SliderOptionRow<>(
                minecraft,
                Component.translatable(key),
                Component.translatable(key + ".desc"),
                impact,
                () -> staged(opt),
                v -> stageVanilla(opt, v),
                baseline(opt),
                sliderSet::toSliderValue,
                sliderSet::fromSliderValue,
                fmt,
                () -> true
        );
    }

    private void applyGraphicsPresetToStaged(GraphicsPreset preset) {
        switch (preset) {
            case FAST -> {
                stageVanilla(options.biomeBlendRadius(), 1);
                stageVanilla(options.renderDistance(), 8);
                stageVanilla(options.simulationDistance(), 6);
                stageVanilla(options.ambientOcclusion(), false);
                stageVanilla(options.cloudStatus(), CloudStatus.FAST);
                stageVanilla(options.particles(), ParticleStatus.DECREASED);
                stageVanilla(options.cutoutLeaves(), false);
                stageVanilla(options.improvedTransparency(), false);
                stageVanilla(options.entityShadows(), false);
            }
            case FANCY -> {
                stageVanilla(options.biomeBlendRadius(), 2);
                stageVanilla(options.renderDistance(), 12);
                stageVanilla(options.simulationDistance(), 12);
                stageVanilla(options.ambientOcclusion(), true);
                stageVanilla(options.cloudStatus(), CloudStatus.FANCY);
                stageVanilla(options.particles(), ParticleStatus.ALL);
                stageVanilla(options.cutoutLeaves(), true);
                stageVanilla(options.improvedTransparency(), false);
                stageVanilla(options.entityShadows(), true);
            }
            case FABULOUS -> {
                stageVanilla(options.biomeBlendRadius(), 4);
                stageVanilla(options.renderDistance(), 16);
                stageVanilla(options.simulationDistance(), 12);
                stageVanilla(options.ambientOcclusion(), true);
                stageVanilla(options.cloudStatus(), CloudStatus.FANCY);
                stageVanilla(options.particles(), ParticleStatus.ALL);
                stageVanilla(options.cutoutLeaves(), true);
                stageVanilla(options.improvedTransparency(), true);
                stageVanilla(options.entityShadows(), true);
            }
            case CUSTOM -> {}
        }
    }

    private void refreshDiscoveredPacks() {
        Mc263ShaderRuntime.ensureConfiguredPackLoadedForUi();
        Path dir = Mc263ShaderRuntime.shaderPacksDirectory();
        Path extra = null;
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isPresent()) {
            extra = backendOpt.get().publicEngine().shaders().activePackPath()
                    .orElseGet(() -> Mc263ShaderRuntime.resolveConfiguredPackPath(backendOpt.get()));
        }
        discoveredPacks = ShaderPackScanner.scan(dir, extra);
        if (discoveredPacks.size() < 8 && shaderSearchBox != null && !shaderSearchBox.getValue().isEmpty()) {
            shaderSearchQuery = "";
            shaderSearchBox.setValue("");
        }
        if (selectedPackId.isEmpty() && !discoveredPacks.isEmpty()) {
            Path activeOrConf = extra;
            if (activeOrConf != null) {
                for (DiscoveredPack p : discoveredPacks) {
                    if (samePackPath(p.path(), activeOrConf)) {
                        selectedPackId = p.id();
                        break;
                    }
                }
            }
            if (selectedPackId.isEmpty()) {
                selectedPackId = discoveredPacks.get(0).id();
            }
        }
        computeLayout();
        filterDiscoveredPacks();
    }

    private void filterDiscoveredPacks() {
        String q = shaderSearchQuery != null ? shaderSearchQuery.trim().toLowerCase(Locale.ROOT) : "";
        if (q.isEmpty()) {
            filteredPacks = discoveredPacks;
        } else {
            filteredPacks = discoveredPacks.stream()
                    .filter(p -> p.displayName().toLowerCase(Locale.ROOT).contains(q))
                    .toList();
        }
        int maxPackScroll = Math.max(0, filteredPacks.size() * 28 - Math.max(1, packListRect[3]));
        packScrollOffset = Math.clamp(packScrollOffset, 0, maxPackScroll);
    }

    private static boolean samePackPath(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private boolean isDirty() {
        for (var entry : vanillaStaged.entrySet()) {
            if (!Objects.equals(entry.getValue(), vanillaCommitted.get(entry.getKey()))) {
                return true;
            }
        }
        return engineDraft != null && engineCommitted != null
                && (engineDraft.isHardwareRayTracing() != engineCommitted.isHardwareRayTracing()
                || engineDraft.isEnableEdr() != engineCommitted.isEnableEdr()
                || engineDraft.isThermalAdaptiveScaling() != engineCommitted.isThermalAdaptiveScaling()
                || Math.abs(engineDraft.getShaderRenderScale() - engineCommitted.getShaderRenderScale()) > 1e-4f
                || engineDraft.getMaxFramesInFlight() != engineCommitted.getMaxFramesInFlight()
                || engineDraft.isSuppressVanillaWater() != engineCommitted.isSuppressVanillaWater()
                || engineDraft.isConnectedGlass() != engineCommitted.isConnectedGlass()
                || engineDraft.isDynamicLightsEnabled() != engineCommitted.isDynamicLightsEnabled()
                || engineDraft.isInteractiveRipplesEnabled() != engineCommitted.isInteractiveRipplesEnabled()
                || engineDraft.getTelemetryHudMode() != engineCommitted.getTelemetryHudMode()
                || engineDraft.isLiveShaderPreviewBackdrop() != engineCommitted.isLiveShaderPreviewBackdrop());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applyChanges() {
        int oldMipmap = (Integer) vanillaCommitted.getOrDefault(options.mipmapLevels(), options.mipmapLevels().get());
        int oldAniso = (Integer) vanillaCommitted.getOrDefault(options.maxAnisotropyBit(), options.maxAnisotropyBit().get());
        Object oldFilter = vanillaCommitted.getOrDefault(options.textureFiltering(), options.textureFiltering().get());
        boolean oldFullscreen = Boolean.TRUE.equals(vanillaCommitted.get(options.fullscreen()));
        boolean oldExcl = Boolean.TRUE.equals(vanillaCommitted.get(options.exclusiveFullscreen()));
        int oldGuiScale = (Integer) vanillaCommitted.getOrDefault(options.guiScale(), options.guiScale().get());
        boolean oldAo = Boolean.TRUE.equals(vanillaCommitted.get(options.ambientOcclusion()));
        boolean oldLeaves = Boolean.TRUE.equals(vanillaCommitted.get(options.cutoutLeaves()));
        boolean oldTrans = Boolean.TRUE.equals(vanillaCommitted.get(options.improvedTransparency()));
        int oldBiomeBlend = (Integer) vanillaCommitted.getOrDefault(options.biomeBlendRadius(), options.biomeBlendRadius().get());

        GraphicsPreset committedPreset = (GraphicsPreset) vanillaCommitted.get(options.graphicsPreset());
        GraphicsPreset stagedPreset = (GraphicsPreset) vanillaStaged.get(options.graphicsPreset());
        if (stagedPreset != null && stagedPreset != committedPreset) {
            options.graphicsPreset().set(stagedPreset);
        }
        for (var entry : vanillaStaged.entrySet()) {
            OptionInstance rawOpt = entry.getKey();
            if (rawOpt == options.graphicsPreset()) {
                continue;
            }
            Object nextVal = entry.getValue();
            if (!Objects.equals(rawOpt.get(), nextVal)) {
                rawOpt.set(nextVal);
            }
        }

        int newMipmap = options.mipmapLevels().get();
        int newAniso = options.maxAnisotropyBit().get();
        TextureFilteringMethod newFilter = options.textureFiltering().get();
        if (newMipmap != oldMipmap || newAniso != oldAniso || newFilter != oldFilter) {
            minecraft.updateMaxMipLevel(newMipmap);
            minecraft.delayTextureReload();
        }
        if (options.fullscreen().get() != oldFullscreen || options.exclusiveFullscreen().get() != oldExcl) {
            minecraft.getWindow().updateFullscreenIfChanged();
            minecraft.getWindow().changeFullscreenVideoMode();
        }
        if (options.guiScale().get() != oldGuiScale) {
            minecraft.resizeGui();
        }
        boolean rebuildTerrain = options.ambientOcclusion().get() != oldAo
                || options.cutoutLeaves().get() != oldLeaves
                || options.improvedTransparency().get() != oldTrans
                || options.biomeBlendRadius().get() != oldBiomeBlend;
        if (rebuildTerrain && minecraft.levelExtractor != null && minecraft.level != null) {
            minecraft.levelExtractor.allChanged();
        }
        MetalDeviceBackend.activeInstance().ifPresent(
                b -> b.metalDevice().setDisplaySync(options.enableVsync().get())
        );

        options.save();
        for (OptionInstance<?> opt : vanillaStaged.keySet()) {
            vanillaCommitted.put(opt, opt.get());
            vanillaStaged.put(opt, opt.get());
        }

        Mc263ShaderRuntime.applyEngineSettingsLive(engineDraft);
        MetalDeviceBackend.activeInstance().ifPresent(b -> {
            engineDraft.setShadersEnabled(b.settingsConfig().isShadersEnabled());
            engineDraft.setActiveShaderPack(b.settingsConfig().getActiveShaderPack());
        });
        engineCommitted.applyFrom(engineDraft);
    }

    private void undoChanges() {
        vanillaStaged.putAll(vanillaCommitted);
        if (engineCommitted != null && engineDraft != null) {
            engineDraft.applyFrom(engineCommitted);
        }
    }

    @Override
    public void onClose() {
        applyChanges();
        this.minecraft.gui.setScreen(this.lastScreen);
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        Path packsDir = Mc263ShaderRuntime.shaderPacksDirectory();
        int importedCount = 0;
        String lastImportedName = "";
        String errorMsg = null;
        for (Path src : files) {
            try {
                Path target = ShaderPackScanner.importDroppedPack(src, packsDir);
                importedCount++;
                lastImportedName = target.getFileName().toString();
            } catch (Exception ex) {
                errorMsg = ex.getMessage();
            }
        }
        refreshDiscoveredPacks();
        if (importedCount > 0) {
            for (DiscoveredPack dp : discoveredPacks) {
                if (dp.path().getFileName().toString().equals(lastImportedName)) {
                    selectedPackId = dp.id();
                    break;
                }
            }
            this.activeTab = Tab.SHADERS;
            rebuildTabContent();
            showBanner(false, "已匯入 " + importedCount + " 個光影包 (" + lastImportedName + ")");
        } else if (errorMsg != null) {
            showBanner(true, errorMsg);
        }
    }

    private void showBanner(boolean error, String msg) {
        this.bannerIsError = error;
        this.bannerMessage = msg != null ? msg : "";
        this.bannerExpireMs = System.currentTimeMillis() + 4500L;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (this.minecraft.level == null) {
            this.extractPanorama(g, partialTick);
            g.fill(0, 0, this.width, this.height, MetalityUiTheme.BG_SCRIM_MENU);
        } else if (activeTab == Tab.SHADERS) {
            g.fill(0, 0, this.width, this.height, 0x34070B11);
        } else {
            g.fill(0, 0, this.width, this.height, MetalityUiTheme.BG_SCRIM_WORLD);
        }
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        computeLayout();

        // Top Header Bar
        g.fill(0, 0, this.width, 34, MetalityUiTheme.HEADER_BG);
        g.fill(0, 33, this.width, 34, MetalityUiTheme.BORDER_SUBTLE);
        g.text(this.font, this.title, 14, 12, MetalityUiTheme.TEXT_PRIMARY, true);

        renderHeaderStatusPill(g);
        renderSidebar(g, mouseX, mouseY);

        OptionRow hoveredRow = null;
        if (activeTab == Tab.SHADERS) {
            renderShadersTab(g, mouseX, mouseY);
        } else {
            hoveredRow = renderOptionList(g, mouseX, mouseY);
        }

        renderInspector(g, hoveredRow);
        renderFooter(g, mouseX, mouseY);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void renderHeaderStatusPill(GuiGraphicsExtractor g) {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        String pillText;
        if (backendOpt.isPresent()) {
            DefaultMetalityEngine eng = backendOpt.get().publicEngine();
            MetalityCapabilities caps = eng.capabilities();
            MetalityTelemetry tel = eng.telemetry();
            double ms = tel.lastGpuFrameTimeMs();
            pillText = ms > 0.01
                    ? String.format("%s · Metal %s · %d FPS · %.1f ms",
                            caps.deviceName(), caps.metal4CommandQueue() ? "4" : "3", minecraft.getFps(), ms)
                    : String.format("%s · Metal %s · %d FPS",
                            caps.deviceName(), caps.metal4CommandQueue() ? "4" : "3", minecraft.getFps());
        } else {
            pillText = "Metality · " + minecraft.getFps() + " FPS";
        }
        int maxPillW = Math.max(80, this.width - 210);
        String fitted = MetalityUiTheme.fitWithEllipsis(this.font, pillText, maxPillW);
        int pw = this.font.width(fitted) + 16;
        int px = this.width - 12 - pw;
        MetalityUiTheme.fillPanel(g, px, 7, pw, 20, MetalityUiTheme.CARD_BG, MetalityUiTheme.BORDER_SUBTLE);
        g.text(this.font, fitted, px + 8, 13, MetalityUiTheme.ACCENT_CYAN, false);
    }

    private void renderSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int sideBg = (activeTab == Tab.SHADERS && this.minecraft.level != null)
                ? MetalityUiTheme.PANEL_BG_TRANSLUCENT
                : MetalityUiTheme.SIDEBAR_BG;
        MetalityUiTheme.fillPanel(g, navX, navY, navW, navH, sideBg, MetalityUiTheme.BORDER_SUBTLE);

        Tab[] tabs = Tab.values();
        int btnY = navY + 8;
        int btnH = 26;
        boolean shadersOn = isShadersCurrentlyActive();

        for (Tab tab : tabs) {
            boolean active = (tab == activeTab);
            boolean hovered = mouseX >= navX + 6 && mouseX < navX + navW - 6
                    && mouseY >= btnY && mouseY < btnY + btnH;
            int bg = active ? MetalityUiTheme.ROW_SELECTED
                    : (hovered ? MetalityUiTheme.ROW_HOVER : 0x00000000);
            int border = active ? MetalityUiTheme.BORDER_ACCENT
                    : (hovered ? MetalityUiTheme.BORDER_SUBTLE : 0x00000000);
            if ((bg >>> 24) != 0) {
                MetalityUiTheme.fillPanel(g, navX + 6, btnY, navW - 12, btnH, bg, border);
            }
            if (active) {
                g.fill(navX + 6, btnY + 3, navX + 9, btnY + btnH - 3, MetalityUiTheme.ACCENT_CYAN);
            }
            int txtColor = active ? 0xFFFFFFFF : (hovered ? MetalityUiTheme.TEXT_PRIMARY : MetalityUiTheme.TEXT_SECONDARY);
            int maxLblW = navW - (tab == Tab.SHADERS ? 30 : 20);
            MetalityUiTheme.drawEllipsized(g, this.font, tab.label().getString(), navX + 14, btnY + 9, maxLblW, txtColor, false);

            if (tab == Tab.SHADERS) {
                int dotColor = shadersOn ? MetalityUiTheme.SUCCESS_GREEN : MetalityUiTheme.TEXT_MUTED;
                g.fill(navX + navW - 16, btnY + 10, navX + navW - 11, btnY + 15, dotColor);
            }
            btnY += btnH + 4;
        }

        if (navH >= 175) {
            String hint1 = "O 光影選單";
            String hint2 = "K 快速開關";
            String hint3 = "R 重載光影";
            int hy = navY + navH - 40;
            g.fill(navX + 10, hy - 6, navX + navW - 10, hy - 5, MetalityUiTheme.BORDER_SUBTLE);
            g.text(this.font, hint1, navX + 10, hy, MetalityUiTheme.TEXT_MUTED, false);
            g.text(this.font, hint2, navX + 10, hy + 11, MetalityUiTheme.TEXT_MUTED, false);
            g.text(this.font, hint3, navX + 10, hy + 22, MetalityUiTheme.TEXT_MUTED, false);
        }
    }

    private OptionRow renderOptionList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        MetalityUiTheme.fillPanel(g, listX, listY, listW, listH, MetalityUiTheme.PANEL_BG, MetalityUiTheme.BORDER_SUBTLE);

        int innerX = listX + 6;
        int innerY = listY + 6;
        int innerH = listH - 12;
        int maxScroll = Math.max(0, contentHeight - innerH);
        scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
        boolean hasBar = maxScroll > 0;
        int innerW = listW - 12 - (hasBar ? 7 : 0);

        g.enableScissor(innerX, innerY, innerX + innerW + 2, innerY + innerH);
        int curY = innerY - scrollOffset;
        OptionRow hoveredRow = null;
        for (OptionRow row : currentRows) {
            int rh = row.height();
            if (curY + rh > innerY && curY < innerY + innerH) {
                boolean hover = mouseX >= innerX && mouseX < innerX + innerW
                        && mouseY >= Math.max(innerY, curY) && mouseY < Math.min(innerY + innerH, curY + rh);
                row.render(g, this.font, innerX, curY, innerW, mouseX, mouseY, hover);
                if (hover && row.inspectorInfo() != null) {
                    hoveredRow = row;
                }
            }
            curY += rh;
        }
        g.disableScissor();

        if (hasBar) {
            int barX = listX + listW - 9;
            boolean barHover = mouseX >= barX - 2 && mouseX <= barX + 6 && mouseY >= innerY && mouseY < innerY + innerH;
            MetalityUiTheme.drawScrollbar(g, barX, innerY, innerH, scrollOffset, maxScroll, contentHeight, barHover || draggingScrollbar);
        }
        return hoveredRow;
    }

    private void renderShadersTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int panelBg = (this.minecraft.level != null) ? MetalityUiTheme.PANEL_BG_TRANSLUCENT : MetalityUiTheme.PANEL_BG;
        MetalityUiTheme.fillPanel(g, listX, listY, listW, listH, panelBg, MetalityUiTheme.BORDER_SUBTLE);

        int pad = 8;
        int topY = listY + pad;
        boolean shadersOn = isShadersCurrentlyActive();
        Optional<ActivePackInfo> activeOpt = MetalDeviceBackend.activeInstance()
                .flatMap(b -> b.publicEngine().shaders().activePackInfo());

        // 1. Clean Top Bar: Master Toggle + Search Box (only if >=8 packs) or Active Pack Name
        int swW = Math.min(108, Math.max(82, listW / 3));
        setRect(masterSwitchRect, listX + pad, topY, swW, 22);
        boolean swHover = hit(masterSwitchRect, mouseX, mouseY);
        int swBg = shadersOn ? 0x2E10B981 : MetalityUiTheme.BTN_NEUTRAL;
        int swBorder = shadersOn ? MetalityUiTheme.SUCCESS_GREEN : (swHover ? MetalityUiTheme.BORDER_ACCENT : MetalityUiTheme.BORDER_STRONG);
        MetalityUiTheme.fillPanel(g, masterSwitchRect[0], masterSwitchRect[1], masterSwitchRect[2], masterSwitchRect[3], swBg, swBorder);
        String swTxt = Component.translatable("metality.shaders.master").getString() + ": "
                + (shadersOn ? Component.translatable("metality.common.on").getString()
                             : Component.translatable("metality.common.off").getString());
        g.text(this.font, MetalityUiTheme.fitWithEllipsis(this.font, swTxt, swW - 12),
                masterSwitchRect[0] + 6, topY + 7,
                shadersOn ? MetalityUiTheme.SUCCESS_GREEN : MetalityUiTheme.TEXT_SECONDARY, false);

        int rightX = masterSwitchRect[0] + masterSwitchRect[2] + 6;
        int rightW = Math.max(50, listX + listW - pad - rightX);
        if (discoveredPacks.size() >= 8) {
            boolean searchFocused = shaderSearchBox != null && shaderSearchBox.isFocused();
            MetalityUiTheme.fillPanel(g, rightX, topY, rightW, 22, MetalityUiTheme.INPUT_BG,
                    searchFocused ? MetalityUiTheme.BORDER_ACCENT : MetalityUiTheme.BORDER_STRONG);
        } else {
            MetalityUiTheme.fillPanel(g, rightX, topY, rightW, 22, MetalityUiTheme.CARD_BG, MetalityUiTheme.BORDER_SUBTLE);
            String statusTxt = activeOpt.isPresent()
                    ? Component.translatable("metality.shaders.badge.active").getString() + ": " + activeOpt.get().packName()
                    : Component.translatable("metality.shaders.none_active").getString();
            MetalityUiTheme.drawEllipsized(g, this.font, statusTxt, rightX + 8, topY + 7, rightW - 16,
                    activeOpt.isPresent() ? MetalityUiTheme.TEXT_PRIMARY : MetalityUiTheme.TEXT_MUTED, false);
        }

        // 2. Bottom 3-Button Toolbar
        int actionBarH = 22;
        int actionY = listY + listH - pad - actionBarH;
        boolean hasTempBanner = System.currentTimeMillis() < bannerExpireMs && !bannerMessage.isEmpty();
        boolean showHint = hasTempBanner || listH >= 195;
        int bannerH = showHint ? 13 : 0;
        int bannerY = actionY - (showHint ? (bannerH + 4) : 0);

        // 3. Generous Shader Pack List Box
        int pListY = topY + 27;
        int pListBottom = (showHint ? bannerY : actionY) - 6;
        int pListH = Math.max(36, pListBottom - pListY);
        setRect(packListRect, listX + pad, pListY, listW - pad * 2, pListH);
        MetalityUiTheme.fillPanel(g, packListRect[0], packListRect[1], packListRect[2], packListRect[3],
                0xA6080D14, MetalityUiTheme.BORDER_SUBTLE);

        if (filteredPacks.isEmpty()) {
            String emptyTxt = Component.translatable("metality.shaders.empty").getString();
            List<FormattedCharSequence> wrapped = this.font.split(Component.literal(emptyTxt), Math.max(60, packListRect[2] - 20));
            int ey = packListRect[1] + Math.max(8, (packListRect[3] - wrapped.size() * 11) / 2);
            for (FormattedCharSequence line : wrapped) {
                if (ey + 11 > packListRect[1] + packListRect[3] - 4) break;
                int lw = this.font.width(line);
                g.text(this.font, line, packListRect[0] + (packListRect[2] - lw) / 2, ey, MetalityUiTheme.TEXT_MUTED, false);
                ey += 11;
            }
        } else {
            int rowH = 26;
            int innerX = packListRect[0] + 4;
            int innerY = packListRect[1] + 4;
            int innerH = Math.max(12, packListRect[3] - 8);
            int contentH = filteredPacks.size() * (rowH + 2);
            int maxS = Math.max(0, contentH - innerH);
            packScrollOffset = Math.clamp(packScrollOffset, 0, maxS);
            int innerW = packListRect[2] - 8 - (maxS > 0 ? 7 : 0);

            Path activePath = MetalDeviceBackend.activeInstance()
                    .flatMap(b -> b.publicEngine().shaders().activePackPath())
                    .orElse(null);

            g.enableScissor(innerX, innerY, innerX + innerW + 2, innerY + innerH);
            int curY = innerY - packScrollOffset;
            for (DiscoveredPack dp : filteredPacks) {
                if (curY + rowH > innerY && curY < innerY + innerH) {
                    boolean selected = dp.id().equals(selectedPackId);
                    boolean activePack = samePackPath(dp.path(), activePath);
                    boolean hover = mouseX >= innerX && mouseX < innerX + innerW
                            && mouseY >= Math.max(innerY, curY) && mouseY < Math.min(innerY + innerH, curY + rowH);
                    int bg = selected ? MetalityUiTheme.ROW_SELECTED
                            : (hover ? MetalityUiTheme.ROW_HOVER : MetalityUiTheme.ROW_BG);
                    int border = selected ? MetalityUiTheme.BORDER_ACCENT
                            : (activePack ? MetalityUiTheme.SUCCESS_GREEN : MetalityUiTheme.BORDER_SUBTLE);
                    MetalityUiTheme.fillPanel(g, innerX, curY, innerW, rowH, bg, border);

                    int tx = innerX + 8;
                    if (activePack) {
                        g.fill(innerX + 2, curY + 4, innerX + 4, curY + rowH - 4, MetalityUiTheme.SUCCESS_GREEN);
                        tx += 2;
                    }

                    int rightCursor = innerX + innerW - 6;
                    boolean aperture = dp.format() == ShaderPackFormat.APERTURE_TS;
                    String fmtTxt = aperture ? "RT" : "Iris";
                    int fmtW = this.font.width(fmtTxt) + 10;
                    rightCursor -= fmtW;
                    MetalityUiTheme.drawBadge(g, this.font, fmtTxt, rightCursor, curY + 6,
                            aperture ? MetalityUiTheme.ACCENT_CYAN_SOFT : MetalityUiTheme.MODIFIED_BG,
                            aperture ? MetalityUiTheme.ACCENT_CYAN : MetalityUiTheme.MODIFIED_AMBER,
                            aperture ? MetalityUiTheme.ACCENT_CYAN : MetalityUiTheme.MODIFIED_AMBER);

                    if (activePack) {
                        String actTxt = Component.translatable("metality.shaders.badge.active").getString();
                        int actW = this.font.width(actTxt) + 10;
                        if (rightCursor - actW - tx > 50) {
                            rightCursor -= (actW + 4);
                            MetalityUiTheme.drawBadge(g, this.font, actTxt, rightCursor, curY + 6,
                                    MetalityUiTheme.SUCCESS_BG, MetalityUiTheme.SUCCESS_GREEN, MetalityUiTheme.SUCCESS_GREEN);
                        }
                    }

                    int nameMaxW = Math.max(30, rightCursor - tx - 6);
                    MetalityUiTheme.drawEllipsized(g, this.font, dp.displayName(), tx, curY + 9, nameMaxW,
                            activePack ? 0xFFFFFFFF : MetalityUiTheme.TEXT_PRIMARY, false);
                }
                curY += rowH + 2;
            }
            g.disableScissor();

            if (maxS > 0) {
                MetalityUiTheme.drawScrollbar(g, packListRect[0] + packListRect[2] - 6, innerY, innerH,
                        packScrollOffset, maxS, contentH, false);
            }
        }

        // 4. Status Banner or Drag-Drop hint
        if (showHint) {
            if (hasTempBanner) {
                MetalityUiTheme.drawEllipsized(g, this.font, bannerMessage, listX + pad + 2, bannerY + 2,
                        listW - pad * 2 - 4,
                        bannerIsError ? MetalityUiTheme.DANGER_RED : MetalityUiTheme.SUCCESS_GREEN, false);
            } else {
                MetalityUiTheme.drawEllipsized(g, this.font,
                        Component.translatable("metality.shaders.drop_hint").getString(),
                        listX + pad + 2, bannerY + 2, listW - pad * 2 - 4, MetalityUiTheme.TEXT_MUTED, false);
            }
        }

        // 5. Clean 3-Button Bar: Open Folder | Pack Settings... | Apply / Reload
        int totalBtnArea = listW - pad * 2;
        int gap = 6;
        int bw = Math.max(48, (totalBtnArea - gap * 2) / 3);
        int bx = listX + pad;
        setRect(packOpenFolderRect, bx, actionY, bw, actionBarH);
        setRect(packSettingsRect, bx + bw + gap, actionY, bw, actionBarH);
        setRect(packPrimaryActionRect, listX + listW - pad - bw, actionY, bw, actionBarH);

        boolean hasActive = activeOpt.isPresent();
        DiscoveredPack selectedPack = findSelectedPack();
        Path activePath = MetalDeviceBackend.activeInstance()
                .flatMap(b -> b.publicEngine().shaders().activePackPath())
                .orElse(null);
        boolean selectedIsActive = hasActive && selectedPack != null && samePackPath(selectedPack.path(), activePath);
        boolean canPrimary = selectedPack != null || hasActive;
        Component primaryLabel = Component.translatable(
                selectedIsActive ? "metality.shaders.reload" : "metality.shaders.apply_selected"
        );

        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.shaders.open_folder"),
                packOpenFolderRect[0], packOpenFolderRect[1], packOpenFolderRect[2], packOpenFolderRect[3],
                hit(packOpenFolderRect, mouseX, mouseY), false, true);
        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.shaders.settings"),
                packSettingsRect[0], packSettingsRect[1], packSettingsRect[2], packSettingsRect[3],
                hit(packSettingsRect, mouseX, mouseY), hasActive, hasActive);
        MetalityUiTheme.drawButton(g, this.font, primaryLabel,
                packPrimaryActionRect[0], packPrimaryActionRect[1], packPrimaryActionRect[2], packPrimaryActionRect[3],
                hit(packPrimaryActionRect, mouseX, mouseY), !selectedIsActive && selectedPack != null, canPrimary);
    }

    private void renderInspector(GuiGraphicsExtractor g, OptionRow hoveredRow) {
        int inspBg = (activeTab == Tab.SHADERS && this.minecraft.level != null)
                ? MetalityUiTheme.PANEL_BG_TRANSLUCENT
                : MetalityUiTheme.PANEL_BG;
        MetalityUiTheme.fillPanel(g, inspectorX, inspectorY, inspectorW, inspectorH,
                inspBg, MetalityUiTheme.BORDER_SUBTLE);

        InspectorInfo info = hoveredRow != null ? hoveredRow.inspectorInfo() : null;
        if (info == null) {
            Component title = activeTab.label();
            String overview = switch (activeTab) {
                case GENERAL -> "調整視距、全螢幕與 Vertical Sync (DisplaySync)。所有原生視窗設定均套用 Apple Silicon Metal 呈現。";
                case QUALITY -> "精細調整環境光遮蔽、雲層、紋理 Mipmap 與各向異性過濾倍率。";
                case ENGINE -> "釋放 Apple M3–M5 硬體光追 (BVH)、EDR 延伸動態範圍與溫控自適應管線效能。";
                case SHADERS -> "點擊光影包項目可選取，雙擊可直接啟用；拖曳 .zip 或光影資料夾至視窗即可匯入。";
            };
            if (wideInspector) {
                g.text(this.font, title, inspectorX + 10, inspectorY + 12, MetalityUiTheme.ACCENT_CYAN, false);
                g.fill(inspectorX + 10, inspectorY + 25, inspectorX + inspectorW - 10, inspectorY + 26, MetalityUiTheme.BORDER_SUBTLE);
                List<FormattedCharSequence> lines = this.font.split(Component.literal(overview), inspectorW - 20);
                int ly = inspectorY + 32;
                for (FormattedCharSequence line : lines) {
                    if (ly + 11 > inspectorY + inspectorH - 22) break;
                    g.text(this.font, line, inspectorX + 10, ly, MetalityUiTheme.TEXT_SECONDARY, false);
                    ly += 11;
                }
                MetalityUiTheme.drawEllipsized(g, this.font,
                        Component.translatable("metality.common.right_click_reset").getString(),
                        inspectorX + 10, inspectorY + inspectorH - 16, inspectorW - 20, MetalityUiTheme.TEXT_MUTED, false);
            } else {
                MetalityUiTheme.drawEllipsized(g, this.font, title.getString() + " — " + overview,
                        inspectorX + 8, inspectorY + 10, inspectorW - 16, MetalityUiTheme.TEXT_SECONDARY, false);
            }
            return;
        }

        if (!wideInspector) {
            MetalityUiTheme.drawEllipsized(g, this.font, info.title().getString(),
                    inspectorX + 8, inspectorY + 7, inspectorW - 90, MetalityUiTheme.TEXT_PRIMARY, false);
            if (info.impact() != Impact.NONE) {
                String imp = info.impact().label().getString();
                int bw = this.font.width(imp) + 10;
                MetalityUiTheme.drawBadge(g, this.font, imp, inspectorX + inspectorW - bw - 8, inspectorY + 5,
                        info.impact().bgColor(), info.impact().textColor(), info.impact().textColor());
            }
            MetalityUiTheme.drawEllipsized(g, this.font, info.description().getString(),
                    inspectorX + 8, inspectorY + 24, inspectorW - 16, MetalityUiTheme.TEXT_SECONDARY, false);
            return;
        }

        int ix = inspectorX + 10;
        int iy = inspectorY + 12;
        int maxW = inspectorW - 20;
        List<FormattedCharSequence> titleLines = this.font.split(info.title(), maxW);
        for (FormattedCharSequence tl : titleLines) {
            g.text(this.font, tl, ix, iy, info.modified() ? MetalityUiTheme.MODIFIED_AMBER : MetalityUiTheme.TEXT_PRIMARY, true);
            iy += 11;
        }
        iy += 3;

        if (info.impact() != Impact.NONE) {
            MetalityUiTheme.drawBadge(g, this.font, info.impact().label().getString(), ix, iy,
                    info.impact().bgColor(), info.impact().textColor(), info.impact().textColor());
            iy += 20;
        } else {
            iy += 2;
        }

        g.fill(ix, iy, inspectorX + inspectorW - 10, iy + 1, MetalityUiTheme.BORDER_SUBTLE);
        iy += 8;

        List<FormattedCharSequence> descLines = this.font.split(info.description(), maxW);
        for (FormattedCharSequence dl : descLines) {
            if (iy + 11 > inspectorY + inspectorH - 36) break;
            g.text(this.font, dl, ix, iy, MetalityUiTheme.TEXT_SECONDARY, false);
            iy += 11;
        }

        int bottomY = inspectorY + inspectorH - 28;
        if (info.resettable()) {
            String defLine = Component.translatable("metality.common.default_prefix").getString() + info.defaultText().getString();
            MetalityUiTheme.drawEllipsized(g, this.font, defLine, ix, bottomY, maxW, MetalityUiTheme.ACCENT_CYAN, false);
            MetalityUiTheme.drawEllipsized(g, this.font,
                    Component.translatable("metality.common.right_click_reset").getString(),
                    ix, bottomY + 12, maxW, MetalityUiTheme.TEXT_MUTED, false);
        }
    }

    private void renderFooter(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int fy = this.height - 36;
        g.fill(0, fy, this.width, this.height, MetalityUiTheme.HEADER_BG);
        g.fill(0, fy, this.width, fy + 1, MetalityUiTheme.BORDER_SUBTLE);

        boolean dirty = isDirty();
        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.common.vanilla_settings"),
                btnVanillaRect[0], btnVanillaRect[1], btnVanillaRect[2], btnVanillaRect[3],
                hit(btnVanillaRect, mouseX, mouseY), false, true);
        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.common.undo"),
                btnUndoRect[0], btnUndoRect[1], btnUndoRect[2], btnUndoRect[3],
                hit(btnUndoRect, mouseX, mouseY), false, dirty);
        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.common.apply"),
                btnApplyRect[0], btnApplyRect[1], btnApplyRect[2], btnApplyRect[3],
                hit(btnApplyRect, mouseX, mouseY), dirty, dirty);
        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.common.done"),
                btnDoneRect[0], btnDoneRect[1], btnDoneRect[2], btnDoneRect[3],
                hit(btnDoneRect, mouseX, mouseY), !dirty, true);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();

        if (activeTab == Tab.SHADERS && shaderSearchBox != null && shaderSearchBox.visible) {
            int searchX = masterSwitchRect[0] + masterSwitchRect[2] + 6;
            int searchW = Math.max(60, listX + listW - 8 - searchX);
            if (mx >= searchX && mx < searchX + searchW && my >= listY + 8 && my < listY + 32) {
                setFocused(shaderSearchBox);
                shaderSearchBox.setFocused(true);
                return super.mouseClicked(event, doubleClick);
            } else {
                shaderSearchBox.setFocused(false);
            }
        }

        // Sidebar tabs
        if (button == InputConstants.MOUSE_BUTTON_LEFT && mx >= navX + 6 && mx < navX + navW - 6) {
            Tab[] tabs = Tab.values();
            int btnY = navY + 8;
            for (Tab tab : tabs) {
                if (my >= btnY && my < btnY + 26) {
                    MetalityUiTheme.playClick(this.minecraft);
                    selectTab(tab);
                    return true;
                }
                btnY += 30;
            }
        }

        // Footer buttons
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            if (hit(btnVanillaRect, mx, my)) {
                MetalityUiTheme.playClick(this.minecraft);
                applyChanges();
                openVanillaVideoSettings(this.minecraft, this.lastScreen, this.options);
                return true;
            }
            if (hit(btnUndoRect, mx, my) && isDirty()) {
                MetalityUiTheme.playClick(this.minecraft);
                undoChanges();
                return true;
            }
            if (hit(btnApplyRect, mx, my) && isDirty()) {
                MetalityUiTheme.playClick(this.minecraft);
                applyChanges();
                return true;
            }
            if (hit(btnDoneRect, mx, my)) {
                MetalityUiTheme.playClick(this.minecraft);
                onClose();
                return true;
            }
        }

        if (activeTab == Tab.SHADERS) {
            if (handleShadersTabClick(mx, my, button)) {
                return true;
            }
        } else {
            int innerX = listX + 6;
            int innerY = listY + 6;
            int innerH = listH - 12;
            int maxScroll = Math.max(0, contentHeight - innerH);
            boolean hasBar = maxScroll > 0;
            int barX = listX + listW - 9;
            if (hasBar && button == InputConstants.MOUSE_BUTTON_LEFT
                    && mx >= barX - 3 && mx <= barX + 7 && my >= innerY && my < innerY + innerH) {
                draggingScrollbar = true;
                updateScrollFromMouse(my, innerY, innerH, maxScroll);
                return true;
            }

            int innerW = listW - 12 - (hasBar ? 7 : 0);
            if (mx >= innerX && mx < innerX + innerW && my >= innerY && my < innerY + innerH) {
                int curY = innerY - scrollOffset;
                for (OptionRow row : currentRows) {
                    int rh = row.height();
                    if (my >= curY && my < curY + rh) {
                        if (row.mouseClicked(mx - innerX, my - curY, button, innerW, event.modifiers())) {
                            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                                draggingRow = row;
                            }
                            return true;
                        }
                        break;
                    }
                    curY += rh;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    private boolean handleShadersTabClick(double mx, double my, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (hit(masterSwitchRect, mx, my)) {
            MetalityUiTheme.playClick(this.minecraft);
            PackActionResult res = Mc263ShaderRuntime.toggleShaders();
            syncShaderConfigSnapshot();
            refreshDiscoveredPacks();
            showBanner(!res.success(), res.message());
            return true;
        }
        if (hit(packOpenFolderRect, mx, my)) {
            MetalityUiTheme.playClick(this.minecraft);
            boolean ok = MetalityUiTheme.openDirectoryInOs(Mc263ShaderRuntime.shaderPacksDirectory());
            showBanner(!ok, ok ? "已在 Finder 開啟 shaderpacks 資料夾" : "無法開啟 shaderpacks 資料夾");
            return true;
        }
        Optional<ActivePackInfo> activeOpt = MetalDeviceBackend.activeInstance()
                .flatMap(b -> b.publicEngine().shaders().activePackInfo());
        if (hit(packSettingsRect, mx, my) && activeOpt.isPresent()) {
            MetalityUiTheme.playClick(this.minecraft);
            this.minecraft.gui.setScreen(new MetalityShaderPackSettingsScreen(this, this.minecraft, this.engineDraft));
            return true;
        }
        if (hit(packPrimaryActionRect, mx, my)) {
            DiscoveredPack dp = findSelectedPack();
            Path activePath = MetalDeviceBackend.activeInstance()
                    .flatMap(b -> b.publicEngine().shaders().activePackPath())
                    .orElse(null);
            boolean selectedIsActive = activeOpt.isPresent() && dp != null && samePackPath(dp.path(), activePath);
            if (selectedIsActive) {
                MetalityUiTheme.playClick(this.minecraft);
                PackActionResult res = Mc263ShaderRuntime.reloadActiveShaderPack();
                showBanner(!res.success(), res.message());
                return true;
            }
            if (dp != null) {
                MetalityUiTheme.playClick(this.minecraft);
                PackActionResult res = Mc263ShaderRuntime.applyShaderPackSelection(true, dp.path());
                syncShaderConfigSnapshot();
                refreshDiscoveredPacks();
                showBanner(!res.success(), res.message());
                return true;
            }
            if (activeOpt.isPresent()) {
                MetalityUiTheme.playClick(this.minecraft);
                PackActionResult res = Mc263ShaderRuntime.reloadActiveShaderPack();
                showBanner(!res.success(), res.message());
                return true;
            }
        }
        if (hit(packListRect, mx, my) && !filteredPacks.isEmpty()) {
            int rowH = 26;
            int innerY = packListRect[1] + 4;
            int curY = innerY - packScrollOffset;
            for (DiscoveredPack dp : filteredPacks) {
                if (my >= curY && my < curY + rowH) {
                    MetalityUiTheme.playClick(this.minecraft);
                    long now = System.currentTimeMillis();
                    boolean isSecondClick = dp.id().equals(lastClickedPackId) && (now - lastPackClickTimeMs) < 380L;
                    selectedPackId = dp.id();
                    lastClickedPackId = dp.id();
                    lastPackClickTimeMs = now;
                    if (isSecondClick) {
                        PackActionResult res = Mc263ShaderRuntime.applyShaderPackSelection(true, dp.path());
                        syncShaderConfigSnapshot();
                        refreshDiscoveredPacks();
                        showBanner(!res.success(), res.message());
                    }
                    return true;
                }
                curY += rowH + 2;
            }
        }
        return false;
    }

    private DiscoveredPack findSelectedPack() {
        for (DiscoveredPack dp : filteredPacks) {
            if (dp.id().equals(selectedPackId)) {
                return dp;
            }
        }
        for (DiscoveredPack dp : discoveredPacks) {
            if (dp.id().equals(selectedPackId)) {
                return dp;
            }
        }
        return null;
    }

    private void syncShaderConfigSnapshot() {
        MetalDeviceBackend.activeInstance().ifPresent(b -> {
            MetalitySettingsConfig live = b.settingsConfig();
            if (engineDraft != null) {
                engineDraft.setShadersEnabled(live.isShadersEnabled());
                engineDraft.setActiveShaderPack(live.getActiveShaderPack());
            }
            if (engineCommitted != null) {
                engineCommitted.setShadersEnabled(live.isShadersEnabled());
                engineCommitted.setActiveShaderPack(live.getActiveShaderPack());
            }
        });
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScrollbar && activeTab != Tab.SHADERS) {
            int innerY = listY + 6;
            int innerH = listH - 12;
            int maxScroll = Math.max(0, contentHeight - innerH);
            updateScrollFromMouse(event.y(), innerY, innerH, maxScroll);
            return true;
        }
        if (draggingRow != null) {
            int innerX = listX + 6;
            int innerH = listH - 12;
            boolean hasBar = contentHeight > innerH;
            int innerW = listW - 12 - (hasBar ? 7 : 0);
            if (draggingRow.mouseDragged(event.x() - innerX, 0.0, event.button(), innerW)) {
                return true;
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingRow != null) {
            draggingRow.mouseReleased();
            draggingRow = null;
        }
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (activeTab == Tab.SHADERS) {
            if (hit(packListRect, mx, my)) {
                int innerH = Math.max(1, packListRect[3] - 8);
                int contentH = filteredPacks.size() * 28;
                int maxS = Math.max(0, contentH - innerH);
                packScrollOffset = Math.clamp((int) Math.round(packScrollOffset - scrollY * 22.0), 0, maxS);
                return true;
            }
        } else if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            scrollOffset = (int) Math.round(scrollOffset - scrollY * 22.0);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (activeTab == Tab.SHADERS && shaderSearchBox != null && shaderSearchBox.visible && shaderSearchBox.isFocused()) {
            if (event.key() == InputConstants.KEY_ESCAPE) {
                if (!shaderSearchBox.getValue().isEmpty()) {
                    shaderSearchBox.setValue("");
                } else {
                    shaderSearchBox.setFocused(false);
                }
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return super.charTyped(event);
    }

    private void updateScrollFromMouse(double my, int innerY, int innerH, int maxScroll) {
        double f = Math.max(0.0, Math.min(1.0, (my - innerY) / Math.max(1.0, innerH)));
        scrollOffset = (int) Math.round(f * maxScroll);
        clampScroll();
    }

    private void clampScroll() {
        int innerH = Math.max(1, listH - 12);
        int maxScroll = Math.max(0, contentHeight - innerH);
        scrollOffset = Math.clamp(scrollOffset, 0, maxScroll);
    }

    private boolean isShadersCurrentlyActive() {
        return MetalDeviceBackend.activeInstance()
                .map(b -> b.publicEngine().shaders().activePackInfo().isPresent())
                .orElse(false);
    }
}

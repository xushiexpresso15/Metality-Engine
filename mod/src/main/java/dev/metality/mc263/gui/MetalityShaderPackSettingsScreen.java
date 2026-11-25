package dev.metality.mc263.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.api.shader.ShaderPackOption;
import dev.metality.api.shader.ShaderPackProfile;
import dev.metality.api.shader.ShaderPackService.ActivePackInfo;
import dev.metality.config.MetalitySettingsConfig;
import dev.metality.impl.DefaultMetalityEngine;
import dev.metality.mc263.gui.MetalityOptionRows.CycleOptionRow;
import dev.metality.mc263.gui.MetalityOptionRows.Impact;
import dev.metality.mc263.gui.MetalityOptionRows.InspectorInfo;
import dev.metality.mc263.gui.MetalityOptionRows.OptionRow;
import dev.metality.mc263.gui.MetalityOptionRows.SectionHeaderRow;
import dev.metality.mc263.gui.MetalityOptionRows.SliderOptionRow;
import dev.metality.mc263.gui.MetalityOptionRows.ToggleOptionRow;
import dev.metality.mc263.renderpearl.MetalDeviceBackend;
import dev.metality.mc263.shader.Mc263ShaderRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 光影細部設定頁面（參考 Iris Shaders 並支援 Meteor 16 項零重編譯即時調參 + 半透明世界即時預覽）。
 */
public final class MetalityShaderPackSettingsScreen extends Screen {

    private final Screen parentScreen;
    private final MetalitySettingsConfig settingsConfig;

    private boolean snapshotTaken = false;
    private final Map<String, String> initialValues = new LinkedHashMap<>();
    private final Map<String, String> draftValues = new LinkedHashMap<>();
    private final Map<String, String> defaultValues = new LinkedHashMap<>();

    private final List<String> categories = new ArrayList<>();
    private String selectedCategory = "ALL";
    private EditBox searchBox;
    private String searchQuery = "";
    private boolean livePreviewDimming = true;

    private final List<OptionRow> rows = new ArrayList<>();
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private OptionRow draggingRow = null;
    private boolean draggingScrollbar = false;

    // Geometry
    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private final int[] btnBackRect = new int[4];
    private final int[] btnPreviewRect = new int[4];
    private final int[] btnResetAllRect = new int[4];
    private final int[] btnUndoRect = new int[4];
    private final int[] btnApplyDoneRect = new int[4];
    private final List<int[]> profileBtnRects = new ArrayList<>();
    private final List<String> profileBtnNames = new ArrayList<>();
    private final List<int[]> categoryBtnRects = new ArrayList<>();
    private final List<String> categoryBtnKeys = new ArrayList<>();

    public MetalityShaderPackSettingsScreen(Screen parentScreen, Minecraft minecraft, MetalitySettingsConfig config) {
        super(Component.translatable("metality.pack_settings.title"));
        this.parentScreen = parentScreen;
        this.settingsConfig = config;
        this.livePreviewDimming = config != null ? config.isLiveShaderPreviewBackdrop() : true;
    }

    @Override
    protected void init() {
        ensureSnapshotsCaptured();
        computeGeometry();

        if (searchBox == null) {
            searchBox = new EditBox(this.font, 0, 0, 120, 14, Component.empty());
            searchBox.setMaxLength(64);
            searchBox.setBordered(false);
            searchBox.setTextColor(MetalityUiTheme.TEXT_PRIMARY);
            searchBox.setHint(Component.translatable("metality.pack_settings.search_hint"));
            searchBox.setValue(searchQuery);
            searchBox.setResponder(text -> {
                searchQuery = text != null ? text : "";
                rebuildOptionRows();
            });
        }
        addRenderableWidget(searchBox);
        positionSearchBox();
        rebuildOptionRows();
    }

    private void ensureSnapshotsCaptured() {
        if (snapshotTaken) {
            return;
        }
        snapshotTaken = true;
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty()) {
            return;
        }
        List<ShaderPackOption> opts = backendOpt.get().publicEngine().shaders().activePackOptions();
        Set<String> cats = new LinkedHashSet<>();
        for (ShaderPackOption opt : opts) {
            initialValues.put(opt.name(), opt.currentValue());
            draftValues.put(opt.name(), opt.currentValue());
            defaultValues.put(opt.name(), opt.defaultValue());
            if (opt.screenGroup() != null && !opt.screenGroup().isBlank()) {
                cats.add(opt.screenGroup());
            }
        }
        categories.clear();
        categories.add("ALL");
        categories.addAll(cats);
    }

    private void computeGeometry() {
        boolean inWorldLive = this.minecraft.level != null && livePreviewDimming;
        if (inWorldLive && this.width >= 480) {
            this.panelW = Math.clamp((int) (this.width * 0.62), 308, 480);
            this.panelX = 12;
            this.panelY = 8;
            this.panelH = Math.max(190, this.height - 16);
        } else {
            this.panelW = Math.min(720, this.width - 24);
            this.panelX = (this.width - this.panelW) / 2;
            this.panelY = 8;
            this.panelH = Math.max(190, this.height - 16);
        }
    }

    private void positionSearchBox() {
        if (searchBox == null) {
            return;
        }
        int sx = panelX + 8;
        int sw = Math.max(84, Math.min(164, panelW / 3));
        int sy = panelY + 28;
        searchBox.setX(sx + 5);
        searchBox.setY(sy + 4);
        searchBox.setSize(Math.max(40, sw - 10), 11);
    }

    private void rebuildOptionRows() {
        rows.clear();
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty()) {
            contentHeight = 0;
            return;
        }
        DefaultMetalityEngine engine = backendOpt.get().publicEngine();
        boolean instantUniformPreview = engine.shaders().activePackInfo()
                .map(i -> i.format() == ShaderPackFormat.APERTURE_TS)
                .orElse(false);

        String q = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.ROOT) : "";
        String lastCat = null;

        for (ShaderPackOption opt : engine.shaders().activePackOptions()) {
            String cat = opt.screenGroup() != null ? opt.screenGroup() : "MAIN";
            if (!"ALL".equals(selectedCategory) && !cat.equals(selectedCategory)) {
                continue;
            }
            Component displayTitle = optionDisplayTitle(opt.name());
            if (!q.isEmpty()) {
                boolean matchKey = opt.name().toLowerCase(Locale.ROOT).contains(q);
                boolean matchDisp = displayTitle.getString().toLowerCase(Locale.ROOT).contains(q);
                if (!matchKey && !matchDisp) {
                    continue;
                }
            }
            if ("ALL".equals(selectedCategory) && !cat.equals(lastCat)) {
                rows.add(new SectionHeaderRow(categoryDisplayTitle(cat)));
                lastCat = cat;
            }

            Component desc = optionDescription(opt.name());
            List<String> allowed = opt.allowedValues();
            boolean isBooleanPair = !opt.slider() && allowed.size() == 2
                    && ((allowed.contains("true") && allowed.contains("false"))
                    || (allowed.contains("1") && allowed.contains("0")));

            if (isBooleanPair) {
                String trueTok = allowed.contains("true") ? "true" : "1";
                String falseTok = allowed.contains("false") ? "false" : "0";
                boolean defBool = trueTok.equals(opt.defaultValue());
                rows.add(new ToggleOptionRow(
                        minecraft,
                        displayTitle,
                        desc,
                        Impact.NONE,
                        () -> trueTok.equals(draftValues.getOrDefault(opt.name(), opt.defaultValue())),
                        val -> setDraftOption(engine, opt.name(), val ? trueTok : falseTok, instantUniformPreview),
                        defBool,
                        null,
                        null
                ));
            } else if (opt.slider() && allowed.size() > 1) {
                rows.add(SliderOptionRow.discreteList(
                        minecraft,
                        displayTitle,
                        desc,
                        Impact.NONE,
                        allowed,
                        () -> draftValues.getOrDefault(opt.name(), opt.defaultValue()),
                        val -> setDraftOption(engine, opt.name(), val, instantUniformPreview),
                        opt.defaultValue(),
                        v -> formatOptionValue(opt.name(), v),
                        null
                ));
            } else {
                rows.add(new CycleOptionRow<>(
                        minecraft,
                        displayTitle,
                        desc,
                        Impact.NONE,
                        allowed,
                        () -> draftValues.getOrDefault(opt.name(), opt.defaultValue()),
                        val -> setDraftOption(engine, opt.name(), val, instantUniformPreview),
                        opt.defaultValue(),
                        v -> formatOptionValue(opt.name(), v),
                        null
                ));
            }
        }

        contentHeight = 0;
        for (OptionRow r : rows) {
            contentHeight += r.height();
        }
        clampScroll();
    }

    private void setDraftOption(DefaultMetalityEngine engine, String key, String value, boolean livePush) {
        draftValues.put(key, value);
        if (livePush) {
            engine.shaders().updateOptions(Map.of(key, value));
        }
    }

    private static Component optionDisplayTitle(String name) {
        return Component.translatableWithFallback("metality.shader_opt." + name, prettifyName(name));
    }

    private static Component optionDescription(String name) {
        return Component.translatableWithFallback("metality.shader_opt." + name + ".desc", "Shader Pack 選項 `" + name + "`");
    }

    private static Component categoryDisplayTitle(String cat) {
        if ("ALL".equals(cat)) {
            return Component.translatable("metality.pack_settings.all_categories");
        }
        return Component.translatableWithFallback("metality.cat." + cat, prettifyName(cat));
    }

    private static String prettifyName(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.isEmpty() ? raw : sb.toString();
    }

    private static String formatOptionValue(String name, String raw) {
        if (raw == null) {
            return "";
        }
        try {
            double d = Double.parseDouble(raw);
            if (name.contains("THRESHOLD") || name.contains("EXPOSURE") || name.contains("CONTRAST")
                    || name.contains("SATURATION") || name.contains("CLARITY")
                    || name.contains("HEIGHT") || name.contains("STRENGTH")
                    || name.contains("INTENSITY") || name.contains("BOOST")
                    || name.contains("DEPTH") || name.contains("HAZE")
                    || name.contains("QUALITY")) {
                return String.format(Locale.ROOT, "%.2f", d);
            }
        } catch (NumberFormatException ignored) {
            // Keep string token as-is
        }
        return raw;
    }

    private static boolean valuesEquivalent(String a, String b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        try {
            return Math.abs(Double.parseDouble(a.trim()) - Double.parseDouble(b.trim())) < 1e-4;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String detectActiveProfileName(List<ShaderPackProfile> profiles) {
        for (ShaderPackProfile profile : profiles) {
            boolean allMatch = true;
            for (var entry : draftValues.entrySet()) {
                String expected = profile.optionOverrides().getOrDefault(
                        entry.getKey(),
                        defaultValues.getOrDefault(entry.getKey(), entry.getValue())
                );
                if (!valuesEquivalent(expected, entry.getValue())) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return profile.name();
            }
        }
        return null;
    }

    private void applyProfileChoice(ShaderPackProfile profile) {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty()) {
            return;
        }
        DefaultMetalityEngine engine = backendOpt.get().publicEngine();
        for (ShaderPackOption opt : engine.shaders().activePackOptions()) {
            String nextVal = profile.optionOverrides().getOrDefault(opt.name(), opt.defaultValue());
            draftValues.put(opt.name(), nextVal);
        }
        boolean instantUniformPreview = engine.shaders().activePackInfo()
                .map(i -> i.format() == ShaderPackFormat.APERTURE_TS)
                .orElse(false);
        if (instantUniformPreview) {
            engine.shaders().applyProfile(profile.name());
        }
    }

    private void resetAllToDefaults() {
        draftValues.putAll(defaultValues);
        MetalDeviceBackend.activeInstance().ifPresent(b -> {
            DefaultMetalityEngine engine = b.publicEngine();
            boolean instantUniform = engine.shaders().activePackInfo()
                    .map(i -> i.format() == ShaderPackFormat.APERTURE_TS)
                    .orElse(false);
            if (instantUniform) {
                engine.shaders().resetOptions();
            }
        });
    }

    private void undoToInitial() {
        draftValues.putAll(initialValues);
        MetalDeviceBackend.activeInstance().ifPresent(b -> {
            DefaultMetalityEngine engine = b.publicEngine();
            boolean instantUniform = engine.shaders().activePackInfo()
                    .map(i -> i.format() == ShaderPackFormat.APERTURE_TS)
                    .orElse(false);
            if (instantUniform) {
                engine.shaders().updateOptions(draftValues);
            }
        });
    }

    private void commitAndSave() {
        MetalDeviceBackend.activeInstance().ifPresent(b -> {
            DefaultMetalityEngine engine = b.publicEngine();
            if (!Objects.equals(draftValues, initialValues)
                    || engine.shaders().activePackOptions().stream()
                    .anyMatch(o -> !Objects.equals(o.currentValue(), draftValues.get(o.name())))) {
                engine.shaders().updateOptions(draftValues);
            }
            Mc263ShaderRuntime.saveActivePackOverrides(engine);
            Mc263ShaderRuntime.resetTemporalHistory();
        });
        if (settingsConfig != null) {
            settingsConfig.setLiveShaderPreviewBackdrop(livePreviewDimming);
            settingsConfig.save();
        }
        initialValues.putAll(draftValues);
    }

    @Override
    public void onClose() {
        undoToInitial();
        this.minecraft.gui.setScreen(this.parentScreen);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (this.minecraft.level == null) {
            this.extractPanorama(g, partialTick);
            g.fill(0, 0, this.width, this.height, MetalityUiTheme.BG_SCRIM_MENU);
        } else {
            g.fill(0, 0, this.width, this.height,
                    livePreviewDimming ? MetalityUiTheme.BG_SCRIM_LIVE_PREVIEW : MetalityUiTheme.BG_SCRIM_WORLD);
        }
        this.minecraft.gui.hud.extractDeferredSubtitles();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        computeGeometry();
        positionSearchBox();

        int panelBg = (this.minecraft.level != null && livePreviewDimming)
                ? MetalityUiTheme.PANEL_BG_TRANSLUCENT
                : MetalityUiTheme.PANEL_BG;
        MetalityUiTheme.fillPanel(g, panelX, panelY, panelW, panelH, panelBg, MetalityUiTheme.BORDER_STRONG);

        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        Optional<ActivePackInfo> packInfoOpt = backendOpt.flatMap(b -> b.publicEngine().shaders().activePackInfo());
        List<ShaderPackProfile> profiles = backendOpt
                .map(b -> b.publicEngine().shaders().activePackProfiles())
                .orElse(List.of());

        // 1. Header Bar
        int hdrY = panelY + 6;
        setRect(btnBackRect, panelX + 8, hdrY, 44, 18);
        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.common.back"),
                btnBackRect[0], btnBackRect[1], btnBackRect[2], btnBackRect[3],
                hit(btnBackRect, mouseX, mouseY), false, true);

        String pvTxt = Component.translatable("metality.pack_settings.live_preview").getString()
                + ": " + (livePreviewDimming ? "開" : "關");
        int pvW = Math.max(66, this.font.width(pvTxt) + 14);
        setRect(btnPreviewRect, panelX + panelW - 8 - pvW, hdrY, pvW, 18);
        boolean pvHover = hit(btnPreviewRect, mouseX, mouseY);
        int pvBg = livePreviewDimming ? MetalityUiTheme.ACCENT_CYAN_SOFT : MetalityUiTheme.BTN_NEUTRAL;
        int pvBorder = livePreviewDimming ? MetalityUiTheme.BORDER_ACCENT : MetalityUiTheme.BORDER_STRONG;
        MetalityUiTheme.fillPanel(g, btnPreviewRect[0], btnPreviewRect[1], btnPreviewRect[2], btnPreviewRect[3], pvBg, pvBorder);
        g.text(this.font, pvTxt, btnPreviewRect[0] + (pvW - this.font.width(pvTxt)) / 2, hdrY + 5,
                livePreviewDimming ? MetalityUiTheme.ACCENT_CYAN : (pvHover ? 0xFFFFFFFF : MetalityUiTheme.TEXT_SECONDARY), false);

        String packName = packInfoOpt.map(ActivePackInfo::packName).orElse("Shader Pack");
        String titleLine = Component.translatable("metality.pack_settings.title").getString() + " · " + packName;
        MetalityUiTheme.drawEllipsized(g, this.font, titleLine,
                btnBackRect[0] + btnBackRect[2] + 8, hdrY + 5,
                Math.max(50, btnPreviewRect[0] - (btnBackRect[0] + btnBackRect[2] + 14)),
                MetalityUiTheme.TEXT_PRIMARY, true);

        // 2. Search + Profiles Row
        int row2Y = panelY + 28;
        int searchW = Math.max(84, Math.min(164, panelW / 3));
        MetalityUiTheme.fillPanel(g, panelX + 8, row2Y, searchW, 18, MetalityUiTheme.INPUT_BG,
                searchBox != null && searchBox.isFocused() ? MetalityUiTheme.BORDER_ACCENT : MetalityUiTheme.BORDER_STRONG);

        profileBtnRects.clear();
        profileBtnNames.clear();
        int px = panelX + 8 + searchW + 6;
        String activeProfile = detectActiveProfileName(profiles);
        if (!profiles.isEmpty()) {
            for (ShaderPackProfile prof : profiles) {
                int bw = Math.min(56, this.font.width(prof.name()) + 10);
                if (px + bw > panelX + panelW - 8) break;
                int[] rect = new int[]{px, row2Y, bw, 18};
                profileBtnRects.add(rect);
                profileBtnNames.add(prof.name());
                boolean isCur = prof.name().equalsIgnoreCase(activeProfile);
                boolean hov = hit(rect, mouseX, mouseY);
                MetalityUiTheme.fillPanel(g, rect[0], rect[1], rect[2], rect[3],
                        isCur ? MetalityUiTheme.ROW_SELECTED : (hov ? MetalityUiTheme.ROW_HOVER : MetalityUiTheme.BTN_NEUTRAL),
                        isCur ? MetalityUiTheme.BORDER_ACCENT : MetalityUiTheme.BORDER_SUBTLE);
                String lbl = MetalityUiTheme.fitWithEllipsis(this.font, prof.name(), bw - 6);
                g.text(this.font, lbl, rect[0] + (bw - this.font.width(lbl)) / 2, rect[1] + 5,
                        isCur ? MetalityUiTheme.ACCENT_CYAN : MetalityUiTheme.TEXT_SECONDARY, false);
                px += bw + 3;
            }
            if (activeProfile == null && px + 50 <= panelX + panelW - 8) {
                MetalityUiTheme.drawBadge(g, this.font, "CUSTOM", px + 2, row2Y + 2,
                        MetalityUiTheme.MODIFIED_BG, MetalityUiTheme.MODIFIED_AMBER, MetalityUiTheme.MODIFIED_AMBER);
            }
        }

        // 3. Category Pills Row
        int catY = row2Y + 22;
        categoryBtnRects.clear();
        categoryBtnKeys.clear();
        int cx = panelX + 8;
        int cy = catY;
        for (String cat : categories) {
            String catTxt = categoryDisplayTitle(cat).getString();
            int cw = this.font.width(catTxt) + 10;
            if (cx + cw > panelX + panelW - 8 && cx > panelX + 8) {
                cx = panelX + 8;
                cy += 17;
            }
            int[] rect = new int[]{cx, cy, cw, 15};
            categoryBtnRects.add(rect);
            categoryBtnKeys.add(cat);
            boolean sel = cat.equals(selectedCategory);
            boolean hov = hit(rect, mouseX, mouseY);
            MetalityUiTheme.fillPanel(g, rect[0], rect[1], rect[2], rect[3],
                    sel ? MetalityUiTheme.ACCENT_CYAN_SOFT : (hov ? MetalityUiTheme.ROW_HOVER : MetalityUiTheme.ROW_BG),
                    sel ? MetalityUiTheme.BORDER_ACCENT : MetalityUiTheme.BORDER_SUBTLE);
            g.text(this.font, catTxt, rect[0] + 5, rect[1] + 4,
                    sel ? MetalityUiTheme.ACCENT_CYAN : MetalityUiTheme.TEXT_SECONDARY, false);
            cx += cw + 3;
        }

        // 4. Footer & Description Strip
        int footerH = 26;
        int descH = 24;
        int footerY = panelY + panelH - footerH - 4;
        int descY = footerY - descH - 4;

        // 5. Scrollable Options List
        listX = panelX + 8;
        listY = cy + 19;
        listW = panelW - 16;
        listH = Math.max(60, descY - 4 - listY);
        MetalityUiTheme.fillPanel(g, listX, listY, listW, listH, 0x99080D14, MetalityUiTheme.BORDER_SUBTLE);

        OptionRow hoveredRow = renderOptionList(g, mouseX, mouseY);

        // Description Strip
        MetalityUiTheme.fillPanel(g, panelX + 8, descY, panelW - 16, descH,
                MetalityUiTheme.CARD_BG, MetalityUiTheme.BORDER_SUBTLE);
        InspectorInfo info = hoveredRow != null ? hoveredRow.inspectorInfo() : null;
        if (info != null) {
            String line = info.title().getString() + " (" + info.defaultText().getString() + "): "
                    + info.description().getString();
            MetalityUiTheme.drawEllipsized(g, this.font, line, panelX + 14, descY + 8, panelW - 28,
                    info.modified() ? MetalityUiTheme.MODIFIED_AMBER : MetalityUiTheme.TEXT_SECONDARY, false);
        } else {
            MetalityUiTheme.drawEllipsized(g, this.font,
                    Component.translatable("metality.common.right_click_reset").getString(),
                    panelX + 14, descY + 8, panelW - 28, MetalityUiTheme.TEXT_MUTED, false);
        }

        // Footer Buttons
        int fy = footerY + 2;
        int bw = Math.clamp((panelW - 36) / 3, 62, 112);
        setRect(btnResetAllRect, panelX + 8, fy, bw, 20);
        setRect(btnUndoRect, panelX + 14 + bw, fy, Math.max(50, bw - 10), 20);
        int saveW = Math.max(82, bw + 10);
        setRect(btnApplyDoneRect, panelX + panelW - 8 - saveW, fy, saveW, 20);

        boolean canReset = !Objects.equals(draftValues, defaultValues);
        boolean canUndo = !Objects.equals(draftValues, initialValues);
        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.common.reset_all"),
                btnResetAllRect[0], btnResetAllRect[1], btnResetAllRect[2], btnResetAllRect[3],
                hit(btnResetAllRect, mouseX, mouseY), false, canReset);
        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.common.undo"),
                btnUndoRect[0], btnUndoRect[1], btnUndoRect[2], btnUndoRect[3],
                hit(btnUndoRect, mouseX, mouseY), false, canUndo);
        MetalityUiTheme.drawButton(g, this.font, Component.translatable("metality.pack_settings.save_apply"),
                btnApplyDoneRect[0], btnApplyDoneRect[1], btnApplyDoneRect[2], btnApplyDoneRect[3],
                hit(btnApplyDoneRect, mouseX, mouseY), true, true);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private OptionRow renderOptionList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int innerX = listX + 4;
        int innerY = listY + 4;
        int innerH = listH - 8;
        int maxS = Math.max(0, contentHeight - innerH);
        scrollOffset = Math.clamp(scrollOffset, 0, maxS);
        boolean hasBar = maxS > 0;
        int innerW = listW - 8 - (hasBar ? 7 : 0);

        g.enableScissor(innerX, innerY, innerX + innerW + 2, innerY + innerH);
        int curY = innerY - scrollOffset;
        OptionRow hoveredRow = null;
        for (OptionRow row : rows) {
            int rh = row.height();
            if (curY + rh > innerY && curY < innerY + innerH) {
                boolean hov = mouseX >= innerX && mouseX < innerX + innerW
                        && mouseY >= Math.max(innerY, curY) && mouseY < Math.min(innerY + innerH, curY + rh);
                row.render(g, this.font, innerX, curY, innerW, mouseX, mouseY, hov);
                if (hov && row.inspectorInfo() != null) {
                    hoveredRow = row;
                }
            }
            curY += rh;
        }
        g.disableScissor();

        if (hasBar) {
            MetalityUiTheme.drawScrollbar(g, listX + listW - 7, innerY, innerH,
                    scrollOffset, maxS, contentHeight, draggingScrollbar);
        }
        return hoveredRow;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();

        if (searchBox != null) {
            int sw = Math.max(84, Math.min(164, panelW / 3));
            if (mx >= panelX + 8 && mx < panelX + 8 + sw && my >= panelY + 28 && my < panelY + 46) {
                setFocused(searchBox);
                searchBox.setFocused(true);
                return super.mouseClicked(event, doubleClick);
            } else {
                searchBox.setFocused(false);
            }
        }

        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            if (hit(btnBackRect, mx, my)) {
                MetalityUiTheme.playClick(this.minecraft);
                onClose();
                return true;
            }
            if (hit(btnPreviewRect, mx, my)) {
                MetalityUiTheme.playClick(this.minecraft);
                livePreviewDimming = !livePreviewDimming;
                return true;
            }
            if (hit(btnResetAllRect, mx, my)) {
                MetalityUiTheme.playClick(this.minecraft);
                resetAllToDefaults();
                return true;
            }
            if (hit(btnUndoRect, mx, my)) {
                MetalityUiTheme.playClick(this.minecraft);
                undoToInitial();
                return true;
            }
            if (hit(btnApplyDoneRect, mx, my)) {
                MetalityUiTheme.playClick(this.minecraft);
                commitAndSave();
                this.minecraft.gui.setScreen(this.parentScreen);
                return true;
            }
            for (int i = 0; i < profileBtnRects.size(); ++i) {
                if (hit(profileBtnRects.get(i), mx, my)) {
                    String name = profileBtnNames.get(i);
                    MetalDeviceBackend.activeInstance().ifPresent(b -> b.publicEngine().shaders().activePackProfiles().stream()
                            .filter(p -> p.name().equalsIgnoreCase(name))
                            .findFirst()
                            .ifPresent(this::applyProfileChoice));
                    MetalityUiTheme.playClick(this.minecraft);
                    return true;
                }
            }
            for (int i = 0; i < categoryBtnRects.size(); ++i) {
                if (hit(categoryBtnRects.get(i), mx, my)) {
                    selectedCategory = categoryBtnKeys.get(i);
                    scrollOffset = 0;
                    rebuildOptionRows();
                    MetalityUiTheme.playClick(this.minecraft);
                    return true;
                }
            }
        }

        int innerX = listX + 4;
        int innerY = listY + 4;
        int innerH = listH - 8;
        int maxS = Math.max(0, contentHeight - innerH);
        boolean hasBar = maxS > 0;
        int barX = listX + listW - 7;
        if (hasBar && button == InputConstants.MOUSE_BUTTON_LEFT
                && mx >= barX - 3 && mx <= barX + 6 && my >= innerY && my < innerY + innerH) {
            draggingScrollbar = true;
            double f = Math.max(0.0, Math.min(1.0, (my - innerY) / Math.max(1.0, innerH)));
            scrollOffset = (int) Math.round(f * maxS);
            return true;
        }
        int innerW = listW - 8 - (hasBar ? 7 : 0);
        if (mx >= innerX && mx < innerX + innerW && my >= innerY && my < innerY + innerH) {
            int curY = innerY - scrollOffset;
            for (OptionRow row : rows) {
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

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScrollbar) {
            int innerY = listY + 4;
            int innerH = listH - 8;
            int maxS = Math.max(0, contentHeight - innerH);
            double f = Math.max(0.0, Math.min(1.0, (event.y() - innerY) / Math.max(1.0, innerH)));
            scrollOffset = (int) Math.round(f * maxS);
            return true;
        }
        if (draggingRow != null) {
            int innerX = listX + 4;
            int innerH = listH - 8;
            int innerW = listW - 8 - (contentHeight > innerH ? 7 : 0);
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
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            scrollOffset = (int) Math.round(scrollOffset - scrollY * 22.0);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (searchBox != null && searchBox.isFocused() && event.key() == InputConstants.KEY_ESCAPE) {
            if (!searchBox.getValue().isEmpty()) {
                searchBox.setValue("");
            } else {
                searchBox.setFocused(false);
            }
            return true;
        }
        return super.keyPressed(event);
    }

    private void clampScroll() {
        int innerH = Math.max(1, listH - 8);
        int maxS = Math.max(0, contentHeight - innerH);
        scrollOffset = Math.clamp(scrollOffset, 0, maxS);
    }

    private static void setRect(int[] r, int x, int y, int w, int h) {
        r[0] = x;
        r[1] = y;
        r[2] = w;
        r[3] = h;
    }

    private static boolean hit(int[] r, double mx, double my) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }
}

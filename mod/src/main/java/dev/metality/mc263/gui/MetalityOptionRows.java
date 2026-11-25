package dev.metality.mc263.gui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.metality.api.MetalityCapabilities;
import dev.metality.api.MetalityTelemetry;
import dev.metality.impl.DefaultMetalityEngine;
import dev.metality.mc263.renderpearl.MetalDeviceBackend;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * 可捲動選項列元件集合（區段標題、Apple Silicon 硬體遙測卡、開關、滑桿、循環選擇與動作按鈕）。
 */
public final class MetalityOptionRows {

    private MetalityOptionRows() {}

    public enum Impact {
        NONE("metality.impact.none", MetalityUiTheme.TEXT_MUTED, 0x2264748B),
        LOW("metality.impact.low", MetalityUiTheme.IMPACT_LOW, MetalityUiTheme.SUCCESS_BG),
        MEDIUM("metality.impact.medium", MetalityUiTheme.IMPACT_MED, MetalityUiTheme.MODIFIED_BG),
        HIGH("metality.impact.high", MetalityUiTheme.IMPACT_HIGH, MetalityUiTheme.DANGER_BG),
        VARIES("metality.impact.varies", MetalityUiTheme.IMPACT_VARIES, MetalityUiTheme.INFO_BG);

        private final String langKey;
        private final int textColor;
        private final int bgColor;

        Impact(String langKey, int textColor, int bgColor) {
            this.langKey = langKey;
            this.textColor = textColor;
            this.bgColor = bgColor;
        }

        public Component label() {
            return Component.translatable(langKey);
        }

        public int textColor() {
            return textColor;
        }

        public int bgColor() {
            return bgColor;
        }
    }

    public record InspectorInfo(
            Component title,
            Component description,
            Impact impact,
            Component defaultText,
            boolean modified,
            boolean resettable
    ) {}

    public interface OptionRow {
        int height();

        void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int mouseX, int mouseY, boolean isHovered);

        default boolean mouseClicked(double relX, double relY, int button, int width, int modifiers) {
            return false;
        }

        default boolean mouseDragged(double relX, double relY, int button, int width) {
            return false;
        }

        default void mouseReleased() {}

        default InspectorInfo inspectorInfo() {
            return null;
        }
    }

    public static final class SectionHeaderRow implements OptionRow {
        private final Component title;

        public SectionHeaderRow(Component title) {
            this.title = title;
        }

        @Override
        public int height() {
            return 22;
        }

        @Override
        public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int mouseX, int mouseY, boolean isHovered) {
            g.fill(x + 2, y + 8, x + 5, y + 15, MetalityUiTheme.ACCENT_CYAN);
            g.text(font, title, x + 10, y + 8, MetalityUiTheme.ACCENT_CYAN, false);
            int lineStart = x + 16 + font.width(title);
            if (lineStart < x + w - 4) {
                g.fill(lineStart, y + 12, x + w - 4, y + 13, MetalityUiTheme.BORDER_SUBTLE);
            }
        }
    }

    public static final class HardwareCardRow implements OptionRow {
        private final Minecraft minecraft;

        public HardwareCardRow(Minecraft minecraft) {
            this.minecraft = minecraft;
        }

        @Override
        public int height() {
            return 84;
        }

        @Override
        public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int mouseX, int mouseY, boolean isHovered) {
            MetalityUiTheme.fillPanel(g, x, y, w, height() - 4, MetalityUiTheme.CARD_BG, MetalityUiTheme.BORDER_STRONG);
            g.fill(x, y, x + 3, y + height() - 4, MetalityUiTheme.ACCENT_CYAN);

            Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
            if (backendOpt.isEmpty()) {
                g.text(font, "Apple Silicon Metal Backend", x + 12, y + 12, MetalityUiTheme.TEXT_PRIMARY, true);
                return;
            }

            DefaultMetalityEngine engine = backendOpt.get().publicEngine();
            MetalityCapabilities caps = engine.capabilities();
            MetalityTelemetry tel = engine.telemetry();

            String title = caps.deviceName() + "  ·  "
                    + (caps.metal4CommandQueue() ? "Metal 4" : "Metal 3")
                    + " (Apple" + caps.appleGpuFamily() + ")";
            MetalityUiTheme.drawEllipsized(g, font, title, x + 12, y + 9, w - 110, MetalityUiTheme.TEXT_PRIMARY, true);

            double gpuMs = tel.lastGpuFrameTimeMs();
            String fpsStr = gpuMs > 0.01
                    ? String.format("%d FPS · %.1f ms", minecraft.getFps(), gpuMs)
                    : String.format("%d FPS", minecraft.getFps());
            int fpsW = font.width(fpsStr);
            g.text(font, fpsStr, x + w - fpsW - 10, y + 9, MetalityUiTheme.ACCENT_CYAN, false);

            // UMA bar
            double allocGb = tel.currentAllocatedUmaBytes() / (1024.0 * 1024.0 * 1024.0);
            double maxGb = Math.max(1.0, caps.recommendedWorkingSetBytes() / (1024.0 * 1024.0 * 1024.0));
            double util = Math.max(0.0, Math.min(1.0, tel.umaWorkingSetUtilization()));
            String umaLabel = String.format("UMA %.2f / %.1f GB  ·  %s",
                    allocGb, maxGb, MetalityTelemetryHud.thermalLabel(tel.thermalStateLevel()));
            g.text(font, umaLabel, x + 12, y + 25, MetalityUiTheme.TEXT_SECONDARY, false);

            int barX = x + 12;
            int barY = y + 38;
            int barW = Math.max(40, w - 24);
            MetalityUiTheme.fillPanel(g, barX, barY, barW, 6, 0xFF090D13, MetalityUiTheme.BORDER_SUBTLE);
            int fillW = Math.max(2, (int) Math.round(util * (barW - 2)));
            int barColor = util > 0.85 ? MetalityUiTheme.DANGER_RED
                    : (util > 0.65 ? MetalityUiTheme.MODIFIED_AMBER : MetalityUiTheme.SUCCESS_GREEN);
            g.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + 5, barColor);

            // Capability pills
            int bx = x + 12;
            int by = y + 52;
            if (caps.hardwareRayTracing()) {
                bx += MetalityUiTheme.drawBadge(g, font, "Hardware BVH", bx, by,
                        MetalityUiTheme.SUCCESS_BG, MetalityUiTheme.SUCCESS_GREEN, MetalityUiTheme.SUCCESS_GREEN) + 6;
            } else {
                bx += MetalityUiTheme.drawBadge(g, font, "Voxel DDA RT", bx, by,
                        MetalityUiTheme.INFO_BG, MetalityUiTheme.INFO_BLUE, MetalityUiTheme.INFO_BLUE) + 6;
            }
            if (caps.metalFXTemporal()) {
                bx += MetalityUiTheme.drawBadge(g, font, "MetalFX", bx, by,
                        MetalityUiTheme.ACCENT_CYAN_SOFT, MetalityUiTheme.ACCENT_CYAN, MetalityUiTheme.ACCENT_CYAN) + 6;
            }
            if (caps.unifiedMemory() && bx + 64 < x + w - 8) {
                MetalityUiTheme.drawBadge(g, font, "UMA Zero-Copy", bx, by,
                        MetalityUiTheme.INFO_BG, MetalityUiTheme.BORDER_STRONG, MetalityUiTheme.TEXT_SECONDARY);
            }
        }
    }

    public static final class ToggleOptionRow implements OptionRow {
        private final Component title;
        private final Component description;
        private final Impact impact;
        private final BooleanSupplier getter;
        private final Consumer<Boolean> setter;
        private final boolean defaultValue;
        private final BooleanSupplier interactiveSupplier;
        private final Component disabledReason;
        private final Minecraft minecraft;

        public ToggleOptionRow(
                Minecraft minecraft,
                Component title,
                Component description,
                Impact impact,
                BooleanSupplier getter,
                Consumer<Boolean> setter,
                boolean defaultValue,
                BooleanSupplier interactiveSupplier,
                Component disabledReason
        ) {
            this.minecraft = minecraft;
            this.title = title;
            this.description = description;
            this.impact = impact;
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
            this.interactiveSupplier = interactiveSupplier != null ? interactiveSupplier : () -> true;
            this.disabledReason = disabledReason;
        }

        public static ToggleOptionRow fromVanilla(
                Minecraft mc,
                Component title,
                Component description,
                Impact impact,
                OptionInstance<Boolean> opt,
                boolean baselineValue
        ) {
            return new ToggleOptionRow(mc, title, description, impact, opt::get, opt::set, baselineValue, () -> true, null);
        }

        @Override
        public int height() {
            return 28;
        }

        private boolean isModified() {
            return getter.getAsBoolean() != defaultValue;
        }

        @Override
        public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int mouseX, int mouseY, boolean isHovered) {
            boolean interactive = interactiveSupplier.getAsBoolean();
            boolean val = getter.getAsBoolean();
            boolean modified = interactive && isModified();
            int bg = (isHovered && interactive) ? MetalityUiTheme.ROW_HOVER : MetalityUiTheme.ROW_BG;
            int border = modified ? MetalityUiTheme.BORDER_MODIFIED
                    : ((isHovered && interactive) ? MetalityUiTheme.BORDER_STRONG : MetalityUiTheme.BORDER_SUBTLE);
            MetalityUiTheme.fillPanel(g, x, y, w, height() - 2, bg, border);

            int textX = x + 10;
            if (modified) {
                g.fill(x + 4, y + 10, x + 7, y + 15, MetalityUiTheme.MODIFIED_AMBER);
            }
            int labelColor = !interactive ? MetalityUiTheme.TEXT_DISABLED
                    : (modified ? MetalityUiTheme.MODIFIED_AMBER : MetalityUiTheme.TEXT_PRIMARY);
            MetalityUiTheme.drawEllipsized(g, font, title.getString(), textX, y + 9, Math.max(40, w - 95), labelColor, false);

            String stateTxt = val
                    ? Component.translatable("metality.common.on").getString()
                    : Component.translatable("metality.common.off").getString();
            int swW = 34;
            int swH = 16;
            int swX = x + w - swW - 8;
            int swY = y + 5;
            int txtColor = !interactive ? MetalityUiTheme.TEXT_DISABLED
                    : (val ? MetalityUiTheme.ACCENT_CYAN : MetalityUiTheme.TEXT_MUTED);
            g.text(font, stateTxt, swX - font.width(stateTxt) - 8, y + 9, txtColor, false);
            MetalityUiTheme.drawToggleSwitch(g, swX, swY, swW, swH, val, isHovered, interactive);
        }

        @Override
        public boolean mouseClicked(double relX, double relY, int button, int width, int modifiers) {
            if (!interactiveSupplier.getAsBoolean()) {
                return false;
            }
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                if (getter.getAsBoolean() != defaultValue) {
                    setter.accept(defaultValue);
                    MetalityUiTheme.playClick(minecraft);
                }
                return true;
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                setter.accept(!getter.getAsBoolean());
                MetalityUiTheme.playClick(minecraft);
                return true;
            }
            return false;
        }

        @Override
        public InspectorInfo inspectorInfo() {
            Component desc = (!interactiveSupplier.getAsBoolean() && disabledReason != null)
                    ? disabledReason : description;
            Component defText = defaultValue
                    ? Component.translatable("metality.common.on")
                    : Component.translatable("metality.common.off");
            return new InspectorInfo(title, desc, impact, defText, isModified(), interactiveSupplier.getAsBoolean());
        }
    }

    public static final class SliderOptionRow<T> implements OptionRow {
        private final Minecraft minecraft;
        private final Component title;
        private final Component description;
        private final Impact impact;
        private final Supplier<T> getter;
        private final Consumer<T> setter;
        private final T defaultValue;
        private final Function<T, Double> toFraction;
        private final Function<Double, T> fromFraction;
        private final Function<T, String> formatter;
        private final BooleanSupplier interactiveSupplier;

        public SliderOptionRow(
                Minecraft minecraft,
                Component title,
                Component description,
                Impact impact,
                Supplier<T> getter,
                Consumer<T> setter,
                T defaultValue,
                Function<T, Double> toFraction,
                Function<Double, T> fromFraction,
                Function<T, String> formatter,
                BooleanSupplier interactiveSupplier
        ) {
            this.minecraft = minecraft;
            this.title = title;
            this.description = description;
            this.impact = impact;
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
            this.toFraction = toFraction;
            this.fromFraction = fromFraction;
            this.formatter = formatter;
            this.interactiveSupplier = interactiveSupplier != null ? interactiveSupplier : () -> true;
        }

        @SuppressWarnings("unchecked")
        public static <T> SliderOptionRow<T> fromVanilla(
                Minecraft mc,
                Component title,
                Component description,
                Impact impact,
                OptionInstance<T> opt,
                T baselineValue,
                Function<T, String> formatter,
                BooleanSupplier interactive
        ) {
            if (!(opt.values() instanceof OptionInstance.SliderableValueSet<T> sliderSet)) {
                throw new IllegalArgumentException("Option does not have a SliderableValueSet: " + opt);
            }
            return new SliderOptionRow<>(
                    mc,
                    title,
                    description,
                    impact,
                    opt::get,
                    opt::set,
                    baselineValue,
                    sliderSet::toSliderValue,
                    sliderSet::fromSliderValue,
                    formatter,
                    interactive
            );
        }

        public static <T> SliderOptionRow<T> discreteList(
                Minecraft mc,
                Component title,
                Component description,
                Impact impact,
                List<T> choices,
                Supplier<T> getter,
                Consumer<T> setter,
                T defaultValue,
                Function<T, String> formatter,
                BooleanSupplier interactive
        ) {
            return new SliderOptionRow<>(
                    mc,
                    title,
                    description,
                    impact,
                    getter,
                    setter,
                    defaultValue,
                    v -> {
                        if (choices.size() <= 1) {
                            return 0.0;
                        }
                        int idx = choices.indexOf(v);
                        if (idx < 0) {
                            idx = 0;
                        }
                        return (double) idx / (double) (choices.size() - 1);
                    },
                    frac -> {
                        if (choices.isEmpty()) {
                            return getter.get();
                        }
                        int idx = (int) Math.round(Math.max(0.0, Math.min(1.0, frac)) * (choices.size() - 1));
                        return choices.get(Math.clamp(idx, 0, choices.size() - 1));
                    },
                    formatter,
                    interactive
            );
        }

        @Override
        public int height() {
            return 28;
        }

        private boolean isModified() {
            return !Objects.equals(getter.get(), defaultValue);
        }

        @Override
        public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int mouseX, int mouseY, boolean isHovered) {
            boolean interactive = interactiveSupplier.getAsBoolean();
            T current = getter.get();
            boolean modified = interactive && isModified();
            int bg = (isHovered && interactive) ? MetalityUiTheme.ROW_HOVER : MetalityUiTheme.ROW_BG;
            int border = modified ? MetalityUiTheme.BORDER_MODIFIED
                    : ((isHovered && interactive) ? MetalityUiTheme.BORDER_STRONG : MetalityUiTheme.BORDER_SUBTLE);
            MetalityUiTheme.fillPanel(g, x, y, w, height() - 2, bg, border);

            if (modified) {
                g.fill(x + 4, y + 10, x + 7, y + 15, MetalityUiTheme.MODIFIED_AMBER);
            }
            int sliderW = Math.clamp((int) (w * 0.34), 76, 132);
            int sliderX = x + w - sliderW - 8;
            String valStr = formatter.apply(current);
            int valColor = !interactive ? MetalityUiTheme.TEXT_DISABLED
                    : (modified ? MetalityUiTheme.MODIFIED_AMBER : MetalityUiTheme.ACCENT_CYAN);
            int valW = font.width(valStr);
            int valX = sliderX - valW - 8;
            g.text(font, valStr, valX, y + 9, valColor, false);

            int maxLabelW = Math.max(36, valX - (x + 16));
            int labelColor = !interactive ? MetalityUiTheme.TEXT_DISABLED : MetalityUiTheme.TEXT_PRIMARY;
            MetalityUiTheme.drawEllipsized(g, font, title.getString(), x + 10, y + 9, maxLabelW, labelColor, false);

            double frac = toFraction.apply(current);
            boolean sliderHover = isHovered && mouseX >= sliderX - 4;
            MetalityUiTheme.drawSlider(g, sliderX, y + 6, sliderW, 14, frac, sliderHover, modified, interactive);
        }

        @Override
        public boolean mouseClicked(double relX, double relY, int button, int width, int modifiers) {
            if (!interactiveSupplier.getAsBoolean()) {
                return false;
            }
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                setter.accept(defaultValue);
                MetalityUiTheme.playClick(minecraft);
                return true;
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                int sliderW = Math.clamp((int) (width * 0.34), 76, 132);
                int sliderX = width - sliderW - 8;
                if (relX >= sliderX - 10 && relX <= width - 2) {
                    applyFractionFromRelX(relX, sliderX, sliderW);
                    MetalityUiTheme.playClick(minecraft);
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean mouseDragged(double relX, double relY, int button, int width) {
            if (!interactiveSupplier.getAsBoolean() || button != InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }
            int sliderW = Math.clamp((int) (width * 0.34), 76, 132);
            int sliderX = width - sliderW - 8;
            applyFractionFromRelX(relX, sliderX, sliderW);
            return true;
        }

        private void applyFractionFromRelX(double relX, int sliderX, int sliderW) {
            double f = Math.max(0.0, Math.min(1.0, (relX - sliderX) / Math.max(1.0, sliderW)));
            T next = fromFraction.apply(f);
            if (!Objects.equals(next, getter.get())) {
                setter.accept(next);
            }
        }

        @Override
        public InspectorInfo inspectorInfo() {
            return new InspectorInfo(
                    title,
                    description,
                    impact,
                    Component.literal(formatter.apply(defaultValue)),
                    isModified(),
                    interactiveSupplier.getAsBoolean()
            );
        }
    }

    public static final class CycleOptionRow<T> implements OptionRow {
        private final Minecraft minecraft;
        private final Component title;
        private final Component description;
        private final Impact impact;
        private final List<T> choices;
        private final Supplier<T> getter;
        private final Consumer<T> setter;
        private final T defaultValue;
        private final Function<T, String> formatter;
        private final BooleanSupplier interactiveSupplier;

        public CycleOptionRow(
                Minecraft minecraft,
                Component title,
                Component description,
                Impact impact,
                List<T> choices,
                Supplier<T> getter,
                Consumer<T> setter,
                T defaultValue,
                Function<T, String> formatter,
                BooleanSupplier interactiveSupplier
        ) {
            this.minecraft = minecraft;
            this.title = title;
            this.description = description;
            this.impact = impact;
            this.choices = List.copyOf(choices);
            this.getter = getter;
            this.setter = setter;
            this.defaultValue = defaultValue;
            this.formatter = formatter;
            this.interactiveSupplier = interactiveSupplier != null ? interactiveSupplier : () -> true;
        }

        @Override
        public int height() {
            return 28;
        }

        private boolean isModified() {
            return !Objects.equals(getter.get(), defaultValue);
        }

        @Override
        public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int mouseX, int mouseY, boolean isHovered) {
            boolean interactive = interactiveSupplier.getAsBoolean();
            T current = getter.get();
            boolean modified = interactive && isModified();
            int bg = (isHovered && interactive) ? MetalityUiTheme.ROW_HOVER : MetalityUiTheme.ROW_BG;
            int border = modified ? MetalityUiTheme.BORDER_MODIFIED
                    : ((isHovered && interactive) ? MetalityUiTheme.BORDER_STRONG : MetalityUiTheme.BORDER_SUBTLE);
            MetalityUiTheme.fillPanel(g, x, y, w, height() - 2, bg, border);

            if (modified) {
                g.fill(x + 4, y + 10, x + 7, y + 15, MetalityUiTheme.MODIFIED_AMBER);
            }
            int pillW = Math.clamp((int) (w * 0.34), 76, 136);
            int pillX = x + w - pillW - 8;
            int pillY = y + 4;
            int pillH = 18;
            int pillBg = isHovered && interactive ? MetalityUiTheme.BTN_NEUTRAL_HOVER : MetalityUiTheme.BTN_NEUTRAL;
            int pillBorder = modified ? MetalityUiTheme.BORDER_MODIFIED : MetalityUiTheme.BORDER_STRONG;
            MetalityUiTheme.fillPanel(g, pillX, pillY, pillW, pillH, pillBg, pillBorder);

            int maxLabelW = Math.max(40, pillX - (x + 16));
            int labelColor = !interactive ? MetalityUiTheme.TEXT_DISABLED : MetalityUiTheme.TEXT_PRIMARY;
            MetalityUiTheme.drawEllipsized(g, font, title.getString(), x + 10, y + 9, maxLabelW, labelColor, false);

            String valTxt = MetalityUiTheme.fitWithEllipsis(font, formatter.apply(current), pillW - 14);
            int valColor = !interactive ? MetalityUiTheme.TEXT_DISABLED
                    : (modified ? MetalityUiTheme.MODIFIED_AMBER : MetalityUiTheme.ACCENT_CYAN);
            g.text(font, valTxt, pillX + (pillW - font.width(valTxt)) / 2, pillY + 5, valColor, false);
        }

        @Override
        public boolean mouseClicked(double relX, double relY, int button, int width, int modifiers) {
            if (!interactiveSupplier.getAsBoolean() || choices.isEmpty()) {
                return false;
            }
            if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
                setter.accept(defaultValue);
                MetalityUiTheme.playClick(minecraft);
                return true;
            }
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                T cur = getter.get();
                int idx = choices.indexOf(cur);
                int nextIdx = (idx < 0) ? 0 : (idx + 1) % choices.size();
                setter.accept(choices.get(nextIdx));
                MetalityUiTheme.playClick(minecraft);
                return true;
            }
            return false;
        }

        @Override
        public InspectorInfo inspectorInfo() {
            return new InspectorInfo(
                    title,
                    description,
                    impact,
                    Component.literal(formatter.apply(defaultValue)),
                    isModified(),
                    interactiveSupplier.getAsBoolean()
            );
        }
    }

    public static final class ActionOptionRow implements OptionRow {
        private final Minecraft minecraft;
        private final Component title;
        private final Component description;
        private final Component buttonLabel;
        private final Runnable action;
        private String statusFeedback = "";

        public ActionOptionRow(
                Minecraft minecraft,
                Component title,
                Component description,
                Component buttonLabel,
                Runnable action
        ) {
            this.minecraft = minecraft;
            this.title = title;
            this.description = description;
            this.buttonLabel = buttonLabel;
            this.action = action;
        }

        public void setStatusFeedback(String statusFeedback) {
            this.statusFeedback = statusFeedback != null ? statusFeedback : "";
        }

        @Override
        public int height() {
            return 28;
        }

        @Override
        public void render(GuiGraphicsExtractor g, Font font, int x, int y, int w, int mouseX, int mouseY, boolean isHovered) {
            int bg = isHovered ? MetalityUiTheme.ROW_HOVER : MetalityUiTheme.ROW_BG;
            MetalityUiTheme.fillPanel(g, x, y, w, height() - 2, bg, MetalityUiTheme.BORDER_SUBTLE);

            int btnW = Math.clamp((int) (w * 0.32), 78, 136);
            int btnX = x + w - btnW - 8;
            int btnY = y + 4;
            int btnH = 18;
            boolean btnHover = isHovered && mouseX >= btnX && mouseX <= btnX + btnW;

            int textMax = Math.max(40, btnX - (x + 14));
            String combined = statusFeedback.isEmpty()
                    ? title.getString()
                    : title.getString() + "  · " + statusFeedback;
            MetalityUiTheme.drawEllipsized(g, font, combined, x + 10, y + 9, textMax,
                    statusFeedback.isEmpty() ? MetalityUiTheme.TEXT_PRIMARY : MetalityUiTheme.SUCCESS_GREEN, false);
            MetalityUiTheme.drawButton(g, font, buttonLabel, btnX, btnY, btnW, btnH, btnHover, false, true);
        }

        @Override
        public boolean mouseClicked(double relX, double relY, int button, int width, int modifiers) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT && relX >= 0 && relX <= width) {
                MetalityUiTheme.playClick(minecraft);
                action.run();
                return true;
            }
            return false;
        }

        @Override
        public InspectorInfo inspectorInfo() {
            return new InspectorInfo(title, description, Impact.NONE, Component.literal("—"), false, false);
        }
    }
}

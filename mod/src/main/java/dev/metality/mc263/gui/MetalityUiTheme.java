package dev.metality.mc263.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

/**
 * Metality 設定介面統一深色霧面玻璃視覺主題與繪製輔助工具（Apple Silicon × Sodium 0.6 × Iris 融合風格）。
 */
public final class MetalityUiTheme {

    public static final int BG_SCRIM_MENU = 0xD8070B11;
    public static final int BG_SCRIM_WORLD = 0x9C070B11;
    public static final int BG_SCRIM_LIVE_PREVIEW = 0x20070B11;

    public static final int PANEL_BG = 0xEB0D131C;
    public static final int PANEL_BG_TRANSLUCENT = 0xCC0D131C;
    public static final int SIDEBAR_BG = 0xF00A0F16;
    public static final int CARD_BG = 0xDC131B26;
    public static final int ROW_BG = 0xB8141D29;
    public static final int ROW_HOVER = 0xE61D293B;
    public static final int ROW_SELECTED = 0xED17324D;
    public static final int INPUT_BG = 0xF0080C12;
    public static final int HEADER_BG = 0xE60B1018;

    public static final int BORDER_SUBTLE = 0x3494A3B8;
    public static final int BORDER_STRONG = 0x5C94A3B8;
    public static final int BORDER_ACCENT = 0xFF38BDF8;
    public static final int BORDER_MODIFIED = 0xFFFBBF24;

    public static final int ACCENT_CYAN = 0xFF38BDF8;
    public static final int ACCENT_CYAN_SOFT = 0x3038BDF8;
    public static final int ACCENT_BTN = 0xFF0369A1;
    public static final int ACCENT_BTN_HOVER = 0xFF0284C7;
    public static final int BTN_NEUTRAL = 0xD81B2533;
    public static final int BTN_NEUTRAL_HOVER = 0xEE263448;
    public static final int BTN_DISABLED = 0x88131A24;

    public static final int SUCCESS_GREEN = 0xFF34D399;
    public static final int SUCCESS_BG = 0x2B10B981;
    public static final int MODIFIED_AMBER = 0xFFFBBF24;
    public static final int MODIFIED_BG = 0x2BF59E0B;
    public static final int DANGER_RED = 0xFFFB7185;
    public static final int DANGER_BG = 0x2BF43F5E;
    public static final int INFO_BLUE = 0xFF60A5FA;
    public static final int INFO_BG = 0x2B3B82F6;

    public static final int IMPACT_LOW = 0xFF34D399;
    public static final int IMPACT_MED = 0xFFFBBF24;
    public static final int IMPACT_HIGH = 0xFFFB7185;
    public static final int IMPACT_VARIES = 0xFF60A5FA;

    public static final int TEXT_PRIMARY = 0xFFF1F5F9;
    public static final int TEXT_SECONDARY = 0xFFCBD5E1;
    public static final int TEXT_MUTED = 0xFF7C8B9E;
    public static final int TEXT_DISABLED = 0xFF475569;

    private MetalityUiTheme() {}

    public static void fillPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int bgColor, int borderColor) {
        if (w <= 2 || h <= 2) {
            return;
        }
        g.fill(x, y, x + w, y + h, bgColor);
        drawBorder(g, x, y, w, h, borderColor);
    }

    public static void drawBorder(GuiGraphicsExtractor g, int x, int y, int w, int h, int borderColor) {
        if (w <= 1 || h <= 1 || (borderColor >>> 24) == 0) {
            return;
        }
        g.fill(x, y, x + w, y + 1, borderColor);
        g.fill(x, y + h - 1, x + w, y + h, borderColor);
        g.fill(x, y + 1, x + 1, y + h - 1, borderColor);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, borderColor);
    }

    public static int drawBadge(
            GuiGraphicsExtractor g,
            Font font,
            String text,
            int x,
            int y,
            int bgColor,
            int borderColor,
            int textColor
    ) {
        int textW = font.width(text);
        int w = textW + 10;
        int h = 14;
        fillPanel(g, x, y, w, h, bgColor, borderColor);
        g.text(font, text, x + 5, y + 3, textColor, false);
        return w;
    }

    public static void drawToggleSwitch(
            GuiGraphicsExtractor g,
            int x,
            int y,
            int w,
            int h,
            boolean enabled,
            boolean hovered,
            boolean interactive
    ) {
        int trackBg;
        int trackBorder;
        if (!interactive) {
            trackBg = enabled ? 0x55164E63 : 0x66111827;
            trackBorder = 0x44475569;
        } else if (enabled) {
            trackBg = hovered ? 0xFF0EA5E9 : 0xFF0284C7;
            trackBorder = ACCENT_CYAN;
        } else {
            trackBg = hovered ? 0xFF283548 : 0xFF1A2433;
            trackBorder = hovered ? BORDER_STRONG : BORDER_SUBTLE;
        }
        fillPanel(g, x, y, w, h, trackBg, trackBorder);

        int knobSize = Math.max(6, h - 6);
        int knobX = enabled ? (x + w - knobSize - 3) : (x + 3);
        int knobY = y + 3;
        int knobColor = !interactive ? 0xFF64748B : (enabled ? 0xFFFFFFFF : 0xFFCBD5E1);
        g.fill(knobX, knobY, knobX + knobSize, knobY + knobSize, knobColor);
    }

    public static void drawSlider(
            GuiGraphicsExtractor g,
            int x,
            int y,
            int w,
            int h,
            double fraction,
            boolean hovered,
            boolean modified,
            boolean interactive
    ) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        int trackY = y + (h - 4) / 2;
        int trackBg = interactive ? 0xFF0F172A : 0xAA0F172A;
        fillPanel(g, x, trackY, w, 4, trackBg, BORDER_SUBTLE);

        int fillW = (int) Math.round(f * Math.max(0, w - 2));
        if (fillW > 0) {
            int fillColor = !interactive
                    ? 0xFF475569
                    : (modified ? MODIFIED_AMBER : (hovered ? 0xFF7DD3FC : ACCENT_CYAN));
            g.fill(x + 1, trackY + 1, x + 1 + fillW, trackY + 3, fillColor);
        }

        int knobW = 6;
        int knobH = Math.min(14, h);
        int travel = Math.max(0, w - knobW);
        int knobX = x + (int) Math.round(f * travel);
        int knobY = y + (h - knobH) / 2;
        int knobBg = !interactive ? 0xFF64748B : 0xFFF8FAFC;
        int knobBorder = !interactive ? BORDER_SUBTLE : (modified ? MODIFIED_AMBER : ACCENT_CYAN);
        fillPanel(g, knobX, knobY, knobW, knobH, knobBg, knobBorder);
    }

    public static void drawButton(
            GuiGraphicsExtractor g,
            Font font,
            Component label,
            int x,
            int y,
            int w,
            int h,
            boolean hovered,
            boolean primary,
            boolean enabled
    ) {
        int bg;
        int border;
        int txt;
        if (!enabled) {
            bg = BTN_DISABLED;
            border = BORDER_SUBTLE;
            txt = TEXT_DISABLED;
        } else if (primary) {
            bg = hovered ? ACCENT_BTN_HOVER : ACCENT_BTN;
            border = ACCENT_CYAN;
            txt = 0xFFFFFFFF;
        } else {
            bg = hovered ? BTN_NEUTRAL_HOVER : BTN_NEUTRAL;
            border = hovered ? BORDER_ACCENT : BORDER_STRONG;
            txt = hovered ? 0xFFFFFFFF : TEXT_PRIMARY;
        }
        fillPanel(g, x, y, w, h, bg, border);
        String s = fitWithEllipsis(font, label.getString(), Math.max(12, w - 10));
        int textX = x + (w - font.width(s)) / 2;
        int textY = y + (h - font.lineHeight) / 2 + 1;
        g.text(font, s, textX, textY, txt, enabled);
    }

    public static void drawEllipsized(
            GuiGraphicsExtractor g,
            Font font,
            String text,
            int x,
            int y,
            int maxWidth,
            int color,
            boolean dropShadow
    ) {
        String fitted = fitWithEllipsis(font, text, maxWidth);
        g.text(font, fitted, x, y, color, dropShadow);
    }

    public static String fitWithEllipsis(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int ew = font.width(ellipsis);
        if (maxWidth <= ew) {
            return font.plainSubstrByWidth(text, maxWidth);
        }
        return font.plainSubstrByWidth(text, maxWidth - ew) + ellipsis;
    }

    public static void drawScrollbar(
            GuiGraphicsExtractor g,
            int x,
            int y,
            int h,
            int scrollOffset,
            int maxScroll,
            int contentHeight,
            boolean hovered
    ) {
        if (maxScroll <= 0 || contentHeight <= 0 || h <= 16) {
            return;
        }
        g.fill(x, y, x + 4, y + h, 0x331E293B);
        int thumbH = Math.max(20, (int) ((double) h * h / contentHeight));
        thumbH = Math.min(h, thumbH);
        double frac = (double) scrollOffset / (double) maxScroll;
        int thumbY = y + (int) Math.round(frac * (h - thumbH));
        int thumbColor = hovered ? ACCENT_CYAN : 0x9994A3B8;
        g.fill(x, thumbY, x + 4, thumbY + thumbH, thumbColor);
    }

    public static void playClick(Minecraft minecraft) {
        if (minecraft != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    public static boolean openDirectoryInOs(Path dir) {
        try {
            Files.createDirectories(dir);
            if (Util.getPlatform() == Util.OS.OSX) {
                new ProcessBuilder("open", dir.toAbsolutePath().toString()).start();
                return true;
            }
            new ProcessBuilder("xdg-open", dir.toAbsolutePath().toString()).start();
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}

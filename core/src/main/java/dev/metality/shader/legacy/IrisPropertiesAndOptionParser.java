package dev.metality.shader.legacy;

import dev.metality.api.shader.ShaderPackOption;
import dev.metality.api.shader.ShaderPackProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Legacy Iris / OptiFine 之 {@code shaders.properties} 與 GLSL {@code #define} / {@code const} 選項。
 * 完整支援 {@code profile.*}、{@code sliders=}、{@code screen.*}、{@code alphaTest.*} 與行尾續行（\）。
 */
public final class IrisPropertiesAndOptionParser {

    private static final Pattern DEFINE_OPTION_PATTERN = Pattern.compile(
            "^\\s*#define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([^/\\r\\n]+?)\\s*//\\s*\\[(.*?)\\]\\s*$"
    );
    private static final Pattern DEFINE_TOGGLE_PATTERN = Pattern.compile(
            "^\\s*(?://\\s*)?#define\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?://.*)?$"
    );
    private static final Pattern CONST_OPTION_PATTERN = Pattern.compile(
            "^\\s*const\\s+(?:int|float|bool)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^;]+);\\s*//\\s*\\[(.*?)\\]\\s*$"
    );

    public record ParsedMetadata(
            List<ShaderPackOption> options,
            List<ShaderPackProfile> profiles,
            Set<String> sliderNames,
            Map<String, String> alphaTestRules,
            Map<String, String> effectiveDefines
    ) {}

    private IrisPropertiesAndOptionParser() {}

    public static ParsedMetadata parse(ShaderPackArchive archive, Map<String, String> overrides) {
        Map<String, String> safeOverrides = overrides != null ? overrides : Map.of();

        Set<String> sliders = new LinkedHashSet<>();
        Map<String, String> optionScreenGroup = new LinkedHashMap<>();
        Map<String, Map<String, String>> rawProfiles = new LinkedHashMap<>();
        Map<String, String> alphaTestRules = new LinkedHashMap<>();

        String propsText = archive.readString("shaders/shaders.properties").orElse("");
        for (String line : unfoldPropertyLines(propsText)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String val = trimmed.substring(eq + 1).trim();

            if (key.startsWith("profile.")) {
                String profName = key.substring("profile.".length()).trim();
                Map<String, String> profMap = rawProfiles.computeIfAbsent(profName, _ -> new LinkedHashMap<>());
                for (String tok : val.split("\\s+")) {
                    if (tok.isEmpty() || tok.startsWith("profile.")) {
                        continue;
                    }
                    int innerEq = tok.indexOf('=');
                    if (innerEq > 0) {
                        profMap.put(tok.substring(0, innerEq).trim(), tok.substring(innerEq + 1).trim());
                    } else if (tok.startsWith("!") && tok.length() > 1) {
                        profMap.put(tok.substring(1), "0");
                    } else {
                        profMap.put(tok, "1");
                    }
                }
            } else if ("sliders".equals(key)) {
                sliders.addAll(Arrays.stream(val.split("\\s+")).filter(s -> !s.isBlank()).toList());
            } else if (key.startsWith("screen.")) {
                String group = key.substring("screen.".length()).trim();
                for (String tok : val.split("\\s+")) {
                    if (!tok.isBlank() && !tok.startsWith("[") && !"<empty>".equals(tok) && !"*".equals(tok)) {
                        optionScreenGroup.putIfAbsent(tok, group);
                    }
                }
            } else if (key.startsWith("alphaTest.")) {
                alphaTestRules.put(key.substring("alphaTest.".length()).trim(), val);
            }
        }

        Map<String, OptionDraft> discovered = new LinkedHashMap<>();
        for (String path : archive.listFilesUnder("shaders")) {
            if (!isShaderSource(path)) {
                continue;
            }
            String text = archive.readString(path).orElse("");
            String[] lines = text.split("\\R");
            for (int i = 0; i < lines.length; ++i) {
                String ln = lines[i];
                Matcher m = DEFINE_OPTION_PATTERN.matcher(ln);
                if (m.matches()) {
                    String name = m.group(1);
                    String def = m.group(2).trim();
                    List<String> allowed = parseAllowedValues(m.group(3), def);
                    discovered.putIfAbsent(name, new OptionDraft(name, def, allowed));
                    continue;
                }
                Matcher cm = CONST_OPTION_PATTERN.matcher(ln);
                if (cm.matches()) {
                    String name = cm.group(1);
                    String def = cm.group(2).trim();
                    List<String> allowed = parseAllowedValues(cm.group(3), def);
                    discovered.putIfAbsent(name, new OptionDraft(name, def, allowed));
                    continue;
                }
                // Check Iris //#define OPTION_NAME style annotated in preceding/same comment
                if (ln.contains("//#define ") || (ln.trim().startsWith("#define ") && i > 0 && lines[i - 1].contains("["))) {
                    Matcher tm = DEFINE_TOGGLE_PATTERN.matcher(ln);
                    if (tm.matches()) {
                        String name = tm.group(1);
                        boolean commentedOut = ln.trim().startsWith("//");
                        String def = commentedOut ? "0" : "1";
                        discovered.putIfAbsent(name, new OptionDraft(name, def, List.of("0", "1")));
                    }
                }
            }
        }

        // Also register keys referenced in profiles/sliders that may live inside conditional blocks
        for (Map<String, String> profMap : rawProfiles.values()) {
            for (var e : profMap.entrySet()) {
                discovered.putIfAbsent(e.getKey(), new OptionDraft(e.getKey(), e.getValue(), List.of("0", "1", e.getValue())));
            }
        }
        for (String slider : sliders) {
            discovered.putIfAbsent(slider, new OptionDraft(slider, "1", List.of("0", "1")));
        }

        List<ShaderPackOption> finalOptions = new ArrayList<>(discovered.size());
        Map<String, String> effectiveDefines = new LinkedHashMap<>();
        for (OptionDraft draft : discovered.values()) {
            String cur = safeOverrides.getOrDefault(draft.name, draft.defaultValue);
            String group = optionScreenGroup.getOrDefault(draft.name, "MAIN");
            boolean isSlider = sliders.contains(draft.name) || draft.allowedValues.size() > 2;
            finalOptions.add(new ShaderPackOption(
                    draft.name,
                    cur,
                    draft.defaultValue,
                    draft.allowedValues,
                    isSlider,
                    group
            ));
            effectiveDefines.put(draft.name, cur);
        }
        for (var e : safeOverrides.entrySet()) {
            effectiveDefines.put(e.getKey(), e.getValue());
        }

        List<ShaderPackProfile> finalProfiles = new ArrayList<>(rawProfiles.size());
        for (var e : rawProfiles.entrySet()) {
            finalProfiles.add(new ShaderPackProfile(e.getKey(), e.getValue()));
        }

        return new ParsedMetadata(
                Collections.unmodifiableList(finalOptions),
                Collections.unmodifiableList(finalProfiles),
                Collections.unmodifiableSet(sliders),
                Collections.unmodifiableMap(alphaTestRules),
                Collections.unmodifiableMap(effectiveDefines)
        );
    }

    private static List<String> unfoldPropertyLines(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder acc = new StringBuilder();
        for (String raw : text.split("\\R")) {
            String trimmedEnd = raw.stripTrailing();
            if (trimmedEnd.endsWith("\\")) {
                acc.append(trimmedEnd, 0, trimmedEnd.length() - 1).append(' ');
            } else {
                acc.append(raw);
                result.add(acc.toString());
                acc.setLength(0);
            }
        }
        if (!acc.isEmpty()) {
            result.add(acc.toString());
        }
        return result;
    }

    private static List<String> parseAllowedValues(String rawList, String def) {
        List<String> vals = new ArrayList<>();
        for (String s : rawList.trim().split("\\s+")) {
            if (!s.isBlank()) {
                vals.add(s.trim());
            }
        }
        if (vals.isEmpty()) {
            vals.add(def);
        } else if (!vals.contains(def)) {
            vals.add(0, def);
        }
        return vals.stream().distinct().toList();
    }

    private static boolean isShaderSource(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".glsl")
                || lower.endsWith(".vsh")
                || lower.endsWith(".fsh")
                || lower.endsWith(".csh")
                || lower.endsWith(".gsh");
    }

    private record OptionDraft(String name, String defaultValue, List<String> allowedValues) {}
}

package dev.metality.api.shader;

import java.util.List;
import java.util.Objects;

/**
 * 光影包可調選項定義（解析自 Legacy {@code #define} 與 {@code shaders.properties} 或 Aperture {@code pack.ts}）。
 */
public record ShaderPackOption(
        String name,
        String currentValue,
        String defaultValue,
        List<String> allowedValues,
        boolean slider,
        String screenGroup
) {
    public ShaderPackOption {
        Objects.requireNonNull(name, "name");
        currentValue = Objects.requireNonNullElse(currentValue, defaultValue != null ? defaultValue : "1");
        defaultValue = Objects.requireNonNullElse(defaultValue, currentValue);
        allowedValues = allowedValues != null ? List.copyOf(allowedValues) : List.of(currentValue);
        screenGroup = Objects.requireNonNullElse(screenGroup, "MAIN");
    }
}

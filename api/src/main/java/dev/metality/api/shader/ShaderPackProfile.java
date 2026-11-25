package dev.metality.api.shader;

import java.util.Map;
import java.util.Objects;

/**
 * 光影包預設畫質設定檔（如 LOW / MEDIUM / HIGH / ULTRA）。
 */
public record ShaderPackProfile(
        String name,
        Map<String, String> optionOverrides
) {
    public ShaderPackProfile {
        Objects.requireNonNull(name, "name");
        optionOverrides = optionOverrides != null ? Map.copyOf(optionOverrides) : Map.of();
    }
}

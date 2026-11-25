package dev.metality.api.shader;

/**
 * 光影包格式分類（雙軌制：原生 Next-Gen Aperture/Slang 或 Legacy Iris/OptiFine 相容橋接）。
 */
public enum ShaderPackFormat {
    APERTURE_TS("Metality Aperture (TypeScript + Slang)"),
    LEGACY_IRIS_GLSL("Legacy Iris / OptiFine (GLSL Compatibility Bridge)"),
    UNKNOWN("Unknown");

    private final String displayName;

    ShaderPackFormat(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

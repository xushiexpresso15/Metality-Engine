package dev.metality.api;

import java.util.Objects;

/**
 * Metality 對外穩定單例入口（跨 Minecraft 版本維持二進位與原始碼相容）。
 * 外部光影工具、延伸模組（如遠景 LOD、動態機械渲染、物理材質模組）應一律透過本介面與引擎互動，
 * 無需直接耦合 Minecraft 內部類別或底層 Metal / FFM 實作。
 */
public final class MetalityApi {

    public static final String API_VERSION = "1.0.0";

    private static volatile MetalityEngine activeEngine;

    private MetalityApi() {}

    public static boolean isAvailable() {
        return activeEngine != null;
    }

    public static MetalityEngine get() {
        MetalityEngine engine = activeEngine;
        if (engine == null) {
            throw new IllegalStateException("Metality engine has not been initialized yet.");
        }
        return engine;
    }

    /**
     * 僅供 Metality 核心模組於啟動階段註冊引擎實作。
     */
    public static final class Bootstrap {
        private Bootstrap() {}

        public static void register(MetalityEngine engine) {
            activeEngine = Objects.requireNonNull(engine, "engine");
        }

        public static void clear() {
            activeEngine = null;
        }
    }
}

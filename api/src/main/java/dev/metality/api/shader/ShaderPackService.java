package dev.metality.api.shader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 對外光影包管理服務（統一支援 Aperture TS+Slang 與 Legacy Iris/OptiFine GLSL 雙軌光影包）。
 */
public interface ShaderPackService {

    record ActivePackInfo(
            String packName,
            int totalPasses,
            int fusedGroups,
            int memorylessTextures,
            int declaredTextures,
            int compiledPsos,
            long savedDramBytesPerFrame,
            boolean hardwareRayTracingEnabled,
            boolean edrEnabled,
            ShaderPackFormat format
    ) {
        public ActivePackInfo {
            format = format != null ? format : ShaderPackFormat.UNKNOWN;
        }

        public ActivePackInfo(
                String packName,
                int totalPasses,
                int fusedGroups,
                int memorylessTextures,
                int declaredTextures,
                int compiledPsos,
                long savedDramBytesPerFrame,
                boolean hardwareRayTracingEnabled,
                boolean edrEnabled
        ) {
            this(
                    packName,
                    totalPasses,
                    fusedGroups,
                    memorylessTextures,
                    declaredTextures,
                    compiledPsos,
                    savedDramBytesPerFrame,
                    hardwareRayTracingEnabled,
                    edrEnabled,
                    ShaderPackFormat.APERTURE_TS
            );
        }
    }

    ActivePackInfo loadPack(
            Path packRootDir,
            int screenWidth,
            int screenHeight,
            float internalScale
    );

    default ActivePackInfo loadPack(
            Path packRootOrZip,
            int screenWidth,
            int screenHeight,
            float internalScale,
            Map<String, String> optionOverrides
    ) {
        return loadPack(packRootOrZip, screenWidth, screenHeight, internalScale);
    }

    ActivePackInfo reloadActivePack();

    void unloadPack();

    Optional<ActivePackInfo> activePackInfo();

    default Optional<Path> activePackPath() {
        return Optional.empty();
    }

    default List<ShaderPackOption> activePackOptions() {
        return List.of();
    }

    default List<ShaderPackProfile> activePackProfiles() {
        return List.of();
    }

    default ActivePackInfo applyProfile(String profileName) {
        throw new UnsupportedOperationException("Profiles are not supported by this ShaderPackService");
    }

    default ActivePackInfo updateOptions(Map<String, String> optionOverrides) {
        throw new UnsupportedOperationException("Dynamic option updates are not supported by this ShaderPackService");
    }

    default ActivePackInfo resetOptions() {
        throw new UnsupportedOperationException("Resetting options is not supported by this ShaderPackService");
    }
}

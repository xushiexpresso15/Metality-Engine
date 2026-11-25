package dev.metality.shader.legacy;

import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.api.shader.ShaderPackOption;
import dev.metality.api.shader.ShaderPackProfile;
import dev.metality.api.shader.ShaderPackService.ActivePackInfo;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.nativebridge.MetalityFFM;
import dev.metality.shader.LiveShaderComposer.ComposerParams;
import dev.metality.spi.GlslSpirvTranspilerSpi;
import dev.metality.spi.GlslSpirvTranspilerSpi.ShaderStage;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Legacy Iris / OptiFine 光影封裝載入器：
 * 解析選項/畫質檔、前處理相容層、透過 {@link GlslSpirvTranspilerSpi} 轉譯至 Apple Silicon FP16 MSL、
 * 建立原生 Metal TBDR RenderGraph 計畫，並設定 {@link dev.metality.shader.LiveShaderComposer}。
 */
public final class LegacyIrisPackLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("Metality/LegacyIrisLoader");

    private static final List<String> ORDERED_PROGRAMS = List.of(
            "shadow",
            "gbuffers_skybasic",
            "gbuffers_skytextured",
            "gbuffers_clouds",
            "gbuffers_terrain",
            "gbuffers_terrain_solid",
            "gbuffers_terrain_cutout",
            "gbuffers_textured",
            "gbuffers_textured_lit",
            "gbuffers_entities",
            "gbuffers_block",
            "gbuffers_hand",
            "gbuffers_water",
            "deferred", "deferred1", "deferred2", "deferred3", "deferred4", "deferred5",
            "composite", "composite1", "composite2", "composite3", "composite4", "composite5",
            "composite6", "composite7", "composite8", "composite9", "composite10",
            "composite11", "composite12", "composite13", "composite14", "composite15",
            "final"
    );

    public record LoadedLegacyPack(
            ActivePackInfo info,
            List<ShaderPackOption> options,
            List<ShaderPackProfile> profiles,
            ComposerParams composerParams,
            List<Long> compiledLibraryHandles
    ) {}

    private LegacyIrisPackLoader() {}

    public static LoadedLegacyPack load(
            MetalGpuDevice gpuDevice,
            Path packRootOrZip,
            int width,
            int height,
            float internalScale,
            Map<String, String> optionOverrides
    ) {
        try (ShaderPackArchive archive = ShaderPackArchive.open(packRootOrZip)) {
            IrisPropertiesAndOptionParser.ParsedMetadata meta =
                    IrisPropertiesAndOptionParser.parse(archive, optionOverrides);

            int renderW = Math.max(1, Math.round(width * internalScale));
            int renderH = Math.max(1, Math.round(height * internalScale));

            List<ProgramPassSpec> discoveredPasses = new ArrayList<>();
            for (String prog : ORDERED_PROGRAMS) {
                Optional<String> fsh = findShaderFile(archive, prog + ".fsh");
                Optional<String> vsh = findShaderFile(archive, prog + ".vsh");
                if (fsh.isPresent()) {
                    discoveredPasses.add(new ProgramPassSpec(prog, vsh.orElse(null), fsh.get()));
                }
            }

            if (discoveredPasses.isEmpty()) {
                // Fallback scan for packs whose entrypoints use custom program names directly under shaders/
                for (String f : archive.listFilesUnder("shaders")) {
                    if (f.endsWith(".fsh") && discoveredPasses.size() < 12) {
                        String base = f.substring("shaders/".length(), f.length() - 4);
                        if (!base.contains("/")) {
                            Optional<String> vsh = archive.exists("shaders/" + base + ".vsh")
                                    ? Optional.of("shaders/" + base + ".vsh")
                                    : Optional.empty();
                            discoveredPasses.add(new ProgramPassSpec(base, vsh.orElse(null), f));
                        }
                    }
                }
            }

            if (discoveredPasses.isEmpty()) {
                throw new IllegalArgumentException("No Iris fragment shaders found in archive: " + packRootOrZip);
            }

            MetalityFFM ffm = gpuDevice.ffm();
            MemorySegment graph = ffm.graphCreate();
            List<Long> compiledHandles = new ArrayList<>();
            int compiledPsos = 0;

            try {
                Map<Integer, Integer> colortexIds = new LinkedHashMap<>();
                for (int rt = 0; rt <= 3; ++rt) {
                    int fmt = (rt == 0 || rt == 3)
                            ? MetalityFFM.FORMAT_RGBA16_FLOAT
                            : MetalityFFM.FORMAT_RGBA8_UNORM;
                    colortexIds.put(rt, ffm.graphDeclareTexture(
                            graph, "colortex" + rt, fmt, renderW, renderH, rt == 0
                    ));
                }
                int depthtexId = ffm.graphDeclareTexture(
                        graph, "depthtex0", MetalityFFM.FORMAT_DEPTH32_FLOAT, renderW, renderH, false
                );
                int shadowtexId = ffm.graphDeclareTexture(
                        graph, "shadowtex0", MetalityFFM.FORMAT_DEPTH32_FLOAT, 2048, 2048, true
                );

                Optional<GlslSpirvTranspilerSpi> spiOpt = gpuDevice.glslTranspilerSpi();
                boolean hasDeferredPass = discoveredPasses.stream().anyMatch(p -> p.name.startsWith("deferred"));
                Set<Integer> writtenColortexes = new LinkedHashSet<>();

                for (ProgramPassSpec pass : discoveredPasses) {
                    var varyings = new LegacyGlslPreprocessor.VaryingLocationTable();
                    boolean isShadowPass = "shadow".equals(pass.name);
                    boolean isTerrain = pass.name.equals("gbuffers_terrain");
                    boolean isGbufferPass = pass.name.startsWith("gbuffers_");
                    boolean isCompositeOrFinal = pass.name.startsWith("composite") || pass.name.equals("final");

                    var fsSolid = LegacyGlslPreprocessor.preprocess(
                            archive, pass.fragmentPath, ShaderStage.FRAGMENT,
                            meta.effectiveDefines(), true, varyings
                    );
                    LegacyGlslPreprocessor.PreprocessResult vsPre = null;
                    if (pass.vertexPath != null) {
                        vsPre = LegacyGlslPreprocessor.preprocess(
                                archive, pass.vertexPath, ShaderStage.VERTEX,
                                meta.effectiveDefines(), false, varyings
                        );
                    }

                    // Register pass node in native TBDR RenderGraph using actual colortex R/W sets
                    Set<Integer> reads = new LinkedHashSet<>();
                    if (!isShadowPass && !isGbufferPass) {
                        for (int rc : fsSolid.readColortexIndices()) {
                            if (writtenColortexes.contains(rc) && colortexIds.containsKey(rc)) {
                                reads.add(colortexIds.get(rc));
                            }
                        }
                        if (reads.isEmpty()) {
                            reads.add(colortexIds.get(0));
                        }
                        if (fsSolid.readsDepth()) {
                            reads.add(depthtexId);
                        }
                        if (pass.name.startsWith("deferred")) {
                            if (writtenColortexes.contains(1) && colortexIds.containsKey(1)) reads.add(colortexIds.get(1));
                            if (writtenColortexes.contains(2) && colortexIds.containsKey(2)) reads.add(colortexIds.get(2));
                        } else if (pass.name.equals("final")) {
                            if (writtenColortexes.contains(3) && colortexIds.containsKey(3)) reads.add(colortexIds.get(3));
                        }
                    }

                    int[] writeIds;
                    if (isShadowPass) {
                        writeIds = new int[]{shadowtexId};
                    } else {
                        int[] colorRts = fsSolid.renderTargets();
                        if (hasDeferredPass && colorRts.length == 1 && (pass.name.equals("gbuffers_terrain")
                                || pass.name.equals("gbuffers_textured")
                                || pass.name.equals("gbuffers_entities"))) {
                            colorRts = new int[]{0, 1, 2};
                        }
                        List<Integer> writeList = new ArrayList<>(colorRts.length + 1);
                        for (int rt : colorRts) {
                            writtenColortexes.add(rt);
                            writeList.add(colortexIds.computeIfAbsent(rt, id -> ffm.graphDeclareTexture(
                                    graph, "colortex" + id, MetalityFFM.FORMAT_RGBA16_FLOAT, renderW, renderH, false
                            )));
                        }
                        if (isGbufferPass) {
                            writeList.add(depthtexId);
                        }
                        writeIds = writeList.stream().mapToInt(Integer::intValue).toArray();
                    }

                    boolean samplesNeighbors = !isGbufferPass && !isShadowPass
                            && ("final".equals(pass.name) || fsSolid.samplesNeighbors());
                    ffm.graphAddPass(
                            graph,
                            pass.name,
                            reads.stream().mapToInt(Integer::intValue).toArray(),
                            samplesNeighbors,
                            writeIds
                    );

                    // Transpile & compile on real Apple Silicon GPU when Shaderc/Spvc SPI is registered (:mod / tests)
                    if (spiOpt.isPresent()) {
                        GlslSpirvTranspilerSpi spi = spiOpt.get();
                        boolean compiledHere = tryCompilePassPair(
                                gpuDevice, spi, pass.name + "_solid",
                                vsPre != null ? vsPre.preprocessedGlsl() : null,
                                fsSolid.preprocessedGlsl(),
                                true,
                                compiledHandles
                        );
                        if (compiledHere) {
                            ++compiledPsos;
                        }
                        if (isTerrain && fsSolid.usesDiscard()) {
                            var cutoutVars = new LegacyGlslPreprocessor.VaryingLocationTable();
                            var fsCutout = LegacyGlslPreprocessor.preprocess(
                                    archive, pass.fragmentPath, ShaderStage.FRAGMENT,
                                    meta.effectiveDefines(), false, cutoutVars
                            );
                            var vsCutout = pass.vertexPath != null
                                    ? LegacyGlslPreprocessor.preprocess(
                                    archive, pass.vertexPath, ShaderStage.VERTEX,
                                    meta.effectiveDefines(), false, cutoutVars)
                                    : null;
                            if (tryCompilePassPair(
                                    gpuDevice, spi, pass.name + "_cutout",
                                    vsCutout != null ? vsCutout.preprocessedGlsl() : null,
                                    fsCutout.preprocessedGlsl(),
                                    false,
                                    compiledHandles
                            )) {
                                ++compiledPsos;
                            }
                        }
                    }
                }

                MetalityFFM.GraphPlanStats stats = ffm.graphCompileTbdr(graph);
                ComposerParams composerParams = deriveComposerParams(meta.effectiveDefines());
                boolean shadowEnabled = !"0".equals(meta.effectiveDefines().getOrDefault("SHADOW_QUALITY", "1"))
                        && !"false".equalsIgnoreCase(meta.effectiveDefines().getOrDefault("SHADOW", "true"));

                ActivePackInfo info = new ActivePackInfo(
                        archive.packDisplayName(),
                        stats.totalPasses(),
                        stats.fusedEncoderGroups(),
                        stats.memorylessTextures(),
                        stats.totalTextures(),
                        compiledPsos,
                        stats.savedDramBytesPerFrame(),
                        gpuDevice.publicCapabilities().hardwareRayTracing() && shadowEnabled,
                        false,
                        ShaderPackFormat.LEGACY_IRIS_GLSL
                );
                return new LoadedLegacyPack(info, meta.options(), meta.profiles(), composerParams, compiledHandles);
            } finally {
                ffm.graphDestroy(graph);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load legacy Iris shader pack: " + packRootOrZip, e);
        }
    }

    private static boolean tryCompilePassPair(
            MetalGpuDevice gpuDevice,
            GlslSpirvTranspilerSpi spi,
            String label,
            String vsGlsl,
            String fsGlsl,
            boolean solidHsr,
            List<Long> outLibs
    ) {
        try {
            var fragMsl = spi.transpileLegacyGlslToMsl(label + "_fs", ShaderStage.FRAGMENT, fsGlsl, solidHsr);
            long fHandle = gpuDevice.compileMslLibrary(label + "_fs", fragMsl.mslSource(), true);
            outLibs.add(fHandle);
            if (vsGlsl != null) {
                var vertMsl = spi.transpileLegacyGlslToMsl(label + "_vs", ShaderStage.VERTEX, vsGlsl, false);
                long vHandle = gpuDevice.compileMslLibrary(label + "_vs", vertMsl.mslSource(), true);
                outLibs.add(vHandle);
            }
            return true;
        } catch (Throwable ex) {
            LOGGER.debug("Skipping direct GLSL->Metal compilation for pass {} (handled by Metality Composer fallback): {}",
                    label, ex.getMessage());
            return false;
        }
    }

    private static ComposerParams deriveComposerParams(Map<String, String> defs) {
        float bloomEnabled = parseFloatOrBool(defs.getOrDefault("BLOOM_ENABLED", defs.getOrDefault("BLOOM", "1")), 1.0f);
        float bloomStrength = parseFloatOrBool(defs.getOrDefault("BLOOM_STRENGTH", "0.42"), 0.42f) * bloomEnabled;
        float ssaoSamples = parseFloatOrBool(defs.getOrDefault("SSAO_SAMPLES", "8"), 8.0f);
        float ssaoStrength = Math.min(0.85f, ssaoSamples <= 0.0f ? 0.0f : 0.45f + ssaoSamples * 0.025f);
        float godrayQuality = parseFloatOrBool(defs.getOrDefault("GODRAY_QUALITY", defs.getOrDefault("LIGHTSHAFT_QUALI", "1")), 1.0f);
        float godrayStrength = godrayQuality <= 0.0f ? 0.0f : Math.min(0.95f, 0.55f + godrayQuality * 0.15f);
        float exposure = parseFloatOrBool(defs.getOrDefault("TONEMAP_EXPOSURE", "1.12"), 1.12f);
        return new ComposerParams(true, bloomStrength, ssaoStrength, godrayStrength, exposure, 0.0f, 0);
    }

    private static float parseFloatOrBool(String val, float fallback) {
        if (val == null) return fallback;
        String t = val.trim();
        if ("true".equalsIgnoreCase(t) || "on".equalsIgnoreCase(t)) return 1.0f;
        if ("false".equalsIgnoreCase(t) || "off".equalsIgnoreCase(t)) return 0.0f;
        try {
            return Float.parseFloat(t);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Optional<String> findShaderFile(ShaderPackArchive archive, String fileName) {
        String rootPath = "shaders/" + fileName;
        if (archive.exists(rootPath)) {
            return Optional.of(rootPath);
        }
        String world0Path = "shaders/world0/" + fileName;
        if (archive.exists(world0Path)) {
            return Optional.of(world0Path);
        }
        return Optional.empty();
    }

    private record ProgramPassSpec(String name, String vertexPath, String fragmentPath) {}
}

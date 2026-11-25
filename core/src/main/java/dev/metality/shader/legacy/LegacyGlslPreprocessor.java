package dev.metality.shader.legacy;

import dev.metality.spi.GlslSpirvTranspilerSpi.ShaderStage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legacy Iris / OptiFine GLSL 預處理器與相容層生成器：
 * <ol>
 *   <li>遞迴展開 {@code #include "/..."} 與相對路徑含入，並防止循環。</li>
 *   <li>萃取 {@code RENDERTARGETS} / {@code DRAWBUFFERS} 寫入目標與鄰域取樣相依（供 Metal TBDR 決定融合與 Memoryless）。</li>
 *   <li>將 GLSL 120–150 傳統語法（{@code attribute}/{@code varying}/{@code texture2D}/{@code gl_Vertex}/{@code gl_FragData[]}）轉換為可由 Vulkan/SPIR-V Shaderc 編譯的乾淨 GLSL 450。</li>
 *   <li>為 Solid HSR 管線安全消除 {@code discard;} 以維持 Apple GPU Hidden Surface Removal 最高效能。</li>
 * </ol>
 */
public final class LegacyGlslPreprocessor {

    private static final int MAX_INCLUDE_DEPTH = 32;
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*(?://.*)?$");
    private static final Pattern RENDERTARGETS_PATTERN = Pattern.compile("/\\*\\s*RENDERTARGETS\\s*:\\s*([0-9,\\s]+)\\*/");
    private static final Pattern DRAWBUFFERS_PATTERN = Pattern.compile("/\\*\\s*DRAWBUFFERS\\s*:\\s*([0-9]+)\\s*\\*/");
    private static final Pattern FRAG_DATA_PATTERN = Pattern.compile("\\bgl_FragData\\s*\\[\\s*([0-9]+)\\s*\\]");
    private static final Pattern DEFINE_DIRECTIVE_PATTERN = Pattern.compile(
            "^\\s*#define\\s+([A-Za-z_][A-Za-z0-9_]*)\\b.*$"
    );
    private static final Pattern LEGACY_GL_BUILTINS_PATTERN = Pattern.compile(
            "\\bgl_(ModelViewProjectionMatrix|ModelViewMatrix|ProjectionMatrix|ProjectionMatrixInverse|ModelViewMatrixInverse|TextureMatrix|NormalMatrix|Vertex|Color|Normal|MultiTexCoord0|MultiTexCoord1|FragColor)\\b"
    );
    private static final Pattern COLORTEX_REF_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*colortex([0-9]+)\\s*[,)]");
    private static final Pattern DECL_PATTERN = Pattern.compile(
            "^(\\s*)(?:(?:flat|smooth|noperspective|centroid|sample)\\s+)?(attribute|varying)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\[[^\\]]*\\])?\\s*;"
    );
    private static final Pattern LOOSE_UNIFORM_PATTERN = Pattern.compile(
            "^\\s*uniform\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*(\\[\\s*[0-9]+\\s*\\]))?\\s*(?:=\\s*[^;]+)?\\s*;\\s*(?://.*)?$"
    );
    private static final Set<String> OPAQUE_TYPES = Set.of(
            "sampler2D", "sampler3D", "samplerCube", "sampler2DShadow", "sampler2DArray",
            "sampler2DArrayShadow", "samplerBuffer", "isampler2D", "usampler2D", "isampler3D", "usampler3D"
    );

    public record PreprocessResult(
            String preprocessedGlsl,
            int[] renderTargets,
            int[] readColortexIndices,
            boolean readsDepth,
            boolean usesDiscard,
            boolean samplesNeighbors
    ) {}

    /**
     * 確保同一個 Shader Program 的 Vertex out 與 Fragment in 使用完全一致的 {@code layout(location = N)}。
     */
    public static final class VaryingLocationTable {
        private final Map<String, Integer> locations = new LinkedHashMap<>();
        private int nextLoc = 0;

        public int locationFor(String varyingName) {
            return locations.computeIfAbsent(varyingName, _ -> nextLoc++);
        }
    }

    private LegacyGlslPreprocessor() {}

    public static PreprocessResult preprocess(
            ShaderPackArchive archive,
            String entryFilePath,
            ShaderStage stage,
            Map<String, String> activeDefines,
            boolean stripDiscardForSolidHsr,
            VaryingLocationTable sharedVaryings
    ) {
        String raw = archive.readString(entryFilePath)
                .orElseThrow(() -> new IllegalArgumentException("Missing shader entry: " + entryFilePath));

        Set<String> includeGuard = new HashSet<>();
        String expanded = resolveIncludes(archive, entryFilePath, raw, 0, includeGuard);

        int[] renderTargets = extractRenderTargets(expanded);
        int[] readColortex = extractColortexReads(expanded);
        boolean readsDepth = expanded.contains("depthtex");
        boolean usesDiscard = containsDiscard(expanded);
        boolean samplesNeighbors = detectNeighborSampling(expanded);

        if (stripDiscardForSolidHsr) {
            expanded = expanded.replaceAll("\\bdiscard\\s*;", "{}");
        }

        String normalized = normalizeLegacyGlslForSpirv(expanded, stage, renderTargets, activeDefines, sharedVaryings);
        return new PreprocessResult(normalized, renderTargets, readColortex, readsDepth, usesDiscard, samplesNeighbors);
    }

    private static int[] extractColortexReads(String source) {
        Set<Integer> ids = new LinkedHashSet<>();
        Matcher m = COLORTEX_REF_PATTERN.matcher(source);
        while (m.find()) {
            try {
                ids.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException ignored) {}
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String resolveIncludes(
            ShaderPackArchive archive,
            String currentFile,
            String source,
            int depth,
            Set<String> stack
    ) {
        if (depth > MAX_INCLUDE_DEPTH) {
            return source;
        }
        String dir = parentDir(currentFile);
        StringBuilder out = new StringBuilder(source.length() + 1024);
        for (String line : source.split("\\R", -1)) {
            Matcher m = INCLUDE_PATTERN.matcher(line);
            if (m.matches()) {
                String inc = m.group(1).trim().replace('\\', '/');
                String targetPath;
                if (inc.startsWith("/")) {
                    targetPath = "shaders" + inc;
                } else {
                    targetPath = normalizeJoin(dir, inc);
                }
                if (!stack.contains(targetPath) && archive.exists(targetPath)) {
                    stack.add(targetPath);
                    String incContent = archive.readString(targetPath).orElse("");
                    out.append("// --- begin include ").append(targetPath).append(" ---\n");
                    out.append(resolveIncludes(archive, targetPath, incContent, depth + 1, stack));
                    out.append("\n// --- end include ").append(targetPath).append(" ---\n");
                    stack.remove(targetPath);
                } else {
                    out.append("// skipped missing or cyclic include: ").append(targetPath).append('\n');
                }
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static String normalizeLegacyGlslForSpirv(
            String expanded,
            ShaderStage stage,
            int[] renderTargets,
            Map<String, String> activeDefines,
            VaryingLocationTable sharedVaryings
    ) {
        StringBuilder body = new StringBuilder(expanded.length() + 512);
        Set<Integer> fragDataIndices = new LinkedHashSet<>();
        List<String> looseScalarUniformMembers = new ArrayList<>();
        Set<String> seenUniformNames = new HashSet<>();
        int nextSamplerBinding = 2;
        int braceDepth = 0;

        for (String line : expanded.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#version")) {
                continue;
            }
            String rewritten = line;
            Matcher defMatch = DEFINE_DIRECTIVE_PATTERN.matcher(trimmed);
            if (defMatch.matches() && activeDefines.containsKey(defMatch.group(1))) {
                rewritten = "// overridden by Metality option: " + defMatch.group(1);
            } else if (braceDepth == 0 && !trimmed.contains("layout(")) {
                Matcher um = LOOSE_UNIFORM_PATTERN.matcher(trimmed);
                if (um.matches()) {
                    String uType = um.group(1);
                    String uName = um.group(2);
                    String arraySuffix = um.group(3) != null ? um.group(3) : "";
                    if (OPAQUE_TYPES.contains(uType)) {
                        rewritten = "layout(binding = " + (nextSamplerBinding++) + ") uniform " + uType + " " + uName + arraySuffix + ";";
                    } else if (seenUniformNames.add(uName)) {
                        looseScalarUniformMembers.add("    " + uType + " " + uName + arraySuffix + ";");
                        rewritten = "// packed into MetalityLooseUniforms: " + uName;
                    } else {
                        rewritten = "// duplicate loose uniform: " + uName;
                    }
                }
            }

            if (stage != ShaderStage.COMPUTE) {
                Matcher dm = DECL_PATTERN.matcher(rewritten);
                if (dm.find()) {
                    String indent = dm.group(1);
                    String qual = dm.group(2);
                    String type = dm.group(3);
                    String varName = dm.group(4);
                    String newQual;
                    int loc;
                    if ("attribute".equals(qual)) {
                        newQual = "in";
                        loc = 20 + Math.abs(varName.hashCode() % 12);
                    } else {
                        newQual = (stage == ShaderStage.VERTEX) ? "out" : "in";
                        loc = sharedVaryings.locationFor(varName);
                    }
                    rewritten = indent + "layout(location = " + loc + ") " + newQual + " " + type + " " + varName + ";";
                }
            }
            if (stage == ShaderStage.FRAGMENT) {
                Matcher fm = FRAG_DATA_PATTERN.matcher(rewritten);
                if (fm.find()) {
                    StringBuilder fb = new StringBuilder();
                    do {
                        int idx = Integer.parseInt(fm.group(1));
                        fragDataIndices.add(idx);
                        fm.appendReplacement(fb, "_metality_fragData_" + idx);
                    } while (fm.find());
                    fm.appendTail(fb);
                    rewritten = fb.toString();
                }
            }
            body.append(rewritten).append('\n');

            for (int i = 0; i < line.length(); ++i) {
                char c = line.charAt(i);
                if (c == '{') ++braceDepth;
                else if (c == '}' && braceDepth > 0) --braceDepth;
            }
        }

        String finalBody = LEGACY_GL_BUILTINS_PATTERN.matcher(body.toString()).replaceAll("_metality_$1");
        StringBuilder header = new StringBuilder(2048);
        header.append("#version 450\n");
        header.append("#define MC_VERSION 12104\n");
        header.append("#define MC_GL_VERSION 410\n");
        header.append("#define MC_GLSL_VERSION 410\n");
        header.append("#define MC_OS_MAC 1\n");
        header.append("#define MC_VENDOR_APPLE 1\n");
        header.append("#define MC_RENDER_QUALITY 1.0\n");
        header.append("#define MC_SHADOW_QUALITY 1.0\n");
        header.append("#define METALITY_APPLE_SILICON 1\n");
        for (var e : activeDefines.entrySet()) {
            String v = e.getValue();
            if ("false".equalsIgnoreCase(v) || "0".equals(v)) {
                header.append("// option disabled: ").append(e.getKey()).append('\n');
                header.append("#define ").append(e.getKey()).append(" 0\n");
            } else if ("true".equalsIgnoreCase(v)) {
                header.append("#define ").append(e.getKey()).append(" 1\n");
            } else {
                header.append("#define ").append(e.getKey()).append(' ').append(v).append('\n');
            }
        }

        header.append("#ifndef METALITY_COMPAT_MACROS\n");
        header.append("#define METALITY_COMPAT_MACROS 1\n");
        header.append("#define texture2D texture\n");
        header.append("#define texture2DLod textureLod\n");
        header.append("#define texture2DProj textureProj\n");
        header.append("#define texture3D texture\n");
        header.append("#define shadow2D texture\n");
        header.append("#define shadow2DLod textureLod\n");
        header.append("#endif\n");

        boolean needsLegacyMatrices = finalBody.contains("_metality_ModelViewProjectionMatrix")
                || finalBody.contains("_metality_ModelViewMatrix")
                || finalBody.contains("_metality_ProjectionMatrix")
                || finalBody.contains("_metality_TextureMatrix")
                || finalBody.contains("_metality_NormalMatrix")
                || finalBody.contains("ftransform");
        if (needsLegacyMatrices) {
            header.append("layout(std140, binding = 0) uniform MetalityLegacyMatrices {\n");
            header.append("    mat4 _metality_ModelViewProjectionMatrix;\n");
            header.append("    mat4 _metality_ModelViewMatrix;\n");
            header.append("    mat4 _metality_ProjectionMatrix;\n");
            header.append("    mat4 _metality_ProjectionMatrixInverse;\n");
            header.append("    mat4 _metality_ModelViewMatrixInverse;\n");
            header.append("    mat4 _metality_TextureMatrix[8];\n");
            header.append("    mat3 _metality_NormalMatrix;\n");
            header.append("};\n");
        }
        if (!looseScalarUniformMembers.isEmpty()) {
            header.append("layout(std140, binding = 1) uniform MetalityLooseUniforms {\n");
            for (String m : looseScalarUniformMembers) {
                header.append(m).append('\n');
            }
            header.append("};\n");
        }

        if (stage == ShaderStage.VERTEX) {
            if (finalBody.contains("_metality_Vertex") || finalBody.contains("ftransform")) {
                header.append("layout(location = 10) in vec4 _metality_Vertex;\n");
            }
            if (finalBody.contains("_metality_Color")) {
                header.append("layout(location = 11) in vec4 _metality_Color;\n");
            }
            if (finalBody.contains("_metality_Normal")) {
                header.append("layout(location = 12) in vec3 _metality_Normal;\n");
            }
            if (finalBody.contains("_metality_MultiTexCoord0")) {
                header.append("layout(location = 13) in vec4 _metality_MultiTexCoord0;\n");
            }
            if (finalBody.contains("_metality_MultiTexCoord1")) {
                header.append("layout(location = 14) in vec4 _metality_MultiTexCoord1;\n");
            }
            if (finalBody.contains("ftransform")) {
                header.append("vec4 ftransform() { return _metality_ModelViewProjectionMatrix * _metality_Vertex; }\n");
            }
        } else if (stage == ShaderStage.FRAGMENT) {
            Map<Integer, Integer> rtLoc = new HashMap<>();
            for (int i = 0; i < renderTargets.length; ++i) {
                rtLoc.put(i, i);
            }
            for (int idx : fragDataIndices) {
                int outLoc = rtLoc.getOrDefault(idx, idx);
                header.append("layout(location = ").append(outLoc).append(") out vec4 _metality_fragData_").append(idx).append(";\n");
            }
            if (finalBody.contains("_metality_FragColor")) {
                header.append("layout(location = 0) out vec4 _metality_FragColor;\n");
            }
        }

        header.append(finalBody);
        return header.toString();
    }

    private static int[] extractRenderTargets(String source) {
        Matcher rtm = RENDERTARGETS_PATTERN.matcher(source);
        if (rtm.find()) {
            List<Integer> list = new ArrayList<>();
            for (String tok : rtm.group(1).split(",")) {
                String t = tok.trim();
                if (!t.isEmpty()) {
                    try {
                        list.add(Integer.parseInt(t));
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (!list.isEmpty()) {
                return list.stream().mapToInt(Integer::intValue).toArray();
            }
        }
        Matcher dbm = DRAWBUFFERS_PATTERN.matcher(source);
        if (dbm.find()) {
            String digits = dbm.group(1).trim();
            int[] arr = new int[digits.length()];
            for (int i = 0; i < digits.length(); ++i) {
                arr[i] = Character.digit(digits.charAt(i), 10);
            }
            if (arr.length > 0) {
                return arr;
            }
        }
        return new int[]{0};
    }

    private static boolean containsDiscard(String source) {
        return Pattern.compile("\\bdiscard\\s*;").matcher(source).find();
    }

    private static boolean detectNeighborSampling(String source) {
        return source.contains("textureOffset")
                || (source.contains("texcoord") && (source.contains("+ off") || source.contains("+ vec2") || source.contains("* texel")));
    }

    private static String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : "";
    }

    private static String normalizeJoin(String dir, String rel) {
        List<String> parts = new ArrayList<>();
        if (!dir.isEmpty()) {
            parts.addAll(Arrays.asList(dir.split("/")));
        }
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else {
                parts.add(seg);
            }
        }
        return String.join("/", parts);
    }
}

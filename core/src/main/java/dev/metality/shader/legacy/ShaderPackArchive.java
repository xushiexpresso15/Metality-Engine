package dev.metality.shader.legacy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 統一讀取目錄或 .zip 格式之 Iris / OptiFine 光影封裝（唯讀，支援根目錄 shaders/ 與單層子目錄封裝）。
 */
public final class ShaderPackArchive implements AutoCloseable {

    private final Path sourcePath;
    private final String packDisplayName;
    private final ZipFile zipFile;
    private final String zipPrefix;
    private final Path dirShaderParent;
    private final Map<String, ZipEntry> zipEntriesByNormalizedPath;

    private ShaderPackArchive(
            Path sourcePath,
            String packDisplayName,
            ZipFile zipFile,
            String zipPrefix,
            Path dirShaderParent,
            Map<String, ZipEntry> zipEntriesByNormalizedPath
    ) {
        this.sourcePath = sourcePath;
        this.packDisplayName = packDisplayName;
        this.zipFile = zipFile;
        this.zipPrefix = zipPrefix;
        this.dirShaderParent = dirShaderParent;
        this.zipEntriesByNormalizedPath = zipEntriesByNormalizedPath;
    }

    public static ShaderPackArchive open(Path packRootOrZip) throws IOException {
        if (!Files.exists(packRootOrZip)) {
            throw new IOException("Shader pack path does not exist: " + packRootOrZip);
        }
        String fileName = packRootOrZip.getFileName() != null
                ? packRootOrZip.getFileName().toString()
                : packRootOrZip.toString();

        if (Files.isRegularFile(packRootOrZip) && fileName.toLowerCase().endsWith(".zip")) {
            ZipFile zf = new ZipFile(packRootOrZip.toFile(), StandardCharsets.UTF_8);
            try {
                String detectedPrefix = "";
                int bestDepth = Integer.MAX_VALUE;
                Enumeration<? extends ZipEntry> entries = zf.entries();
                List<ZipEntry> all = new ArrayList<>();
                while (entries.hasMoreElements()) {
                    ZipEntry ze = entries.nextElement();
                    all.add(ze);
                    String name = normalizeSlash(ze.getName());
                    if (name.startsWith("shaders/")) {
                        detectedPrefix = "";
                        bestDepth = 0;
                    } else {
                        int idx = name.indexOf("/shaders/");
                        if (idx >= 0) {
                            String cand = name.substring(0, idx + 1);
                            int depth = (int) cand.chars().filter(c -> c == '/').count();
                            if (depth < bestDepth) {
                                bestDepth = depth;
                                detectedPrefix = cand;
                            }
                        }
                    }
                }
                Map<String, ZipEntry> map = new LinkedHashMap<>();
                for (ZipEntry ze : all) {
                    if (ze.isDirectory()) {
                        continue;
                    }
                    String name = normalizeSlash(ze.getName());
                    if (!detectedPrefix.isEmpty()) {
                        if (!name.startsWith(detectedPrefix)) {
                            continue;
                        }
                        name = name.substring(detectedPrefix.length());
                    }
                    if (!name.isEmpty()) {
                        map.put(name, ze);
                    }
                }
                String display = fileName.endsWith(".zip")
                        ? fileName.substring(0, fileName.length() - 4)
                        : fileName;
                return new ShaderPackArchive(packRootOrZip, display, zf, detectedPrefix, null, map);
            } catch (Throwable t) {
                zf.close();
                throw t;
            }
        }

        if (!Files.isDirectory(packRootOrZip)) {
            throw new IOException("Unsupported shader pack path (expected directory or .zip): " + packRootOrZip);
        }
        Path base = packRootOrZip;
        if (!Files.isDirectory(base.resolve("shaders"))) {
            try (Stream<Path> children = Files.list(base)) {
                Optional<Path> nested = children
                        .filter(Files::isDirectory)
                        .filter(p -> Files.isDirectory(p.resolve("shaders")))
                        .findFirst();
                if (nested.isPresent()) {
                    base = nested.get();
                }
            }
        }
        return new ShaderPackArchive(packRootOrZip, fileName, null, "", base, Map.of());
    }

    public static boolean looksLikeLegacyIrisPack(Path packRootOrZip) {
        if (Files.isDirectory(packRootOrZip)) {
            return Files.isDirectory(packRootOrZip.resolve("shaders"))
                    && !Files.exists(packRootOrZip.resolve("pack.ts"));
        }
        String name = packRootOrZip.getFileName() != null ? packRootOrZip.getFileName().toString().toLowerCase() : "";
        return Files.isRegularFile(packRootOrZip) && name.endsWith(".zip");
    }

    private static String normalizeSlash(String p) {
        String s = p.replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    public boolean exists(String relativePath) {
        String norm = normalizeSlash(relativePath);
        if (zipFile != null) {
            return zipEntriesByNormalizedPath.containsKey(norm);
        }
        return Files.isRegularFile(dirShaderParent.resolve(norm));
    }

    public Optional<String> readString(String relativePath) {
        String norm = normalizeSlash(relativePath);
        try {
            byte[] bytes;
            if (zipFile != null) {
                ZipEntry ze = zipEntriesByNormalizedPath.get(norm);
                if (ze == null) {
                    return Optional.empty();
                }
                try (InputStream in = zipFile.getInputStream(ze)) {
                    bytes = in.readAllBytes();
                }
            } else {
                Path p = dirShaderParent.resolve(norm);
                if (!Files.isRegularFile(p)) {
                    return Optional.empty();
                }
                bytes = Files.readAllBytes(p);
            }
            try {
                var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return Optional.of(decoder.decode(ByteBuffer.wrap(bytes)).toString());
            } catch (CharacterCodingException e) {
                return Optional.of(new String(bytes, StandardCharsets.ISO_8859_1));
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public List<String> listFilesUnder(String dirPrefix) {
        String normPrefix = normalizeSlash(dirPrefix);
        if (!normPrefix.isEmpty() && !normPrefix.endsWith("/")) {
            normPrefix += "/";
        }
        if (zipFile != null) {
            final String pfx = normPrefix;
            return zipEntriesByNormalizedPath.keySet().stream()
                    .filter(k -> k.startsWith(pfx))
                    .sorted()
                    .toList();
        }
        Path root = normPrefix.isEmpty() ? dirShaderParent : dirShaderParent.resolve(normPrefix);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> dirShaderParent.relativize(p).toString().replace('\\', '/'))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public String packDisplayName() {
        return packDisplayName;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    @Override
    public void close() throws IOException {
        if (zipFile != null) {
            zipFile.close();
        }
    }
}

package dev.metality.shader;

import dev.metality.api.shader.ShaderPackFormat;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 掃描 {@code shaderpacks/} 目錄內與外部指派的 Aperture ({@code pack.ts}) 及 Legacy Iris/OptiFine 光影包。
 */
public final class ShaderPackScanner {

    public record DiscoveredPack(
            String id,
            String displayName,
            Path path,
            ShaderPackFormat format,
            boolean external
    ) {}

    private ShaderPackScanner() {}

    public static List<DiscoveredPack> scan(Path shaderpacksDir, Path extraActivePackPath) {
        List<DiscoveredPack> discovered = new ArrayList<>();
        Set<Path> seenNormalized = new LinkedHashSet<>();

        if (shaderpacksDir != null) {
            try {
                Files.createDirectories(shaderpacksDir);
                try (Stream<Path> entries = Files.list(shaderpacksDir)) {
                    for (Path entry : entries.toList()) {
                        String name = entry.getFileName() != null ? entry.getFileName().toString() : "";
                        if (name.isEmpty() || name.startsWith(".")) {
                            continue;
                        }
                        if (Files.isRegularFile(entry)) {
                            String lower = name.toLowerCase();
                            if (lower.endsWith(".properties") || lower.endsWith(".txt") || !lower.endsWith(".zip")) {
                                continue;
                            }
                        }
                        ShaderPackFormat fmt = detectFormat(entry);
                        if (fmt != ShaderPackFormat.UNKNOWN) {
                            Path canonical = canonicalize(entry);
                            if (seenNormalized.add(canonical)) {
                                String display = stripZipExtension(name);
                                discovered.add(new DiscoveredPack(name, display, entry, fmt, false));
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
                // Directory inaccessible: return what we have
            }
        }

        if (extraActivePackPath != null && Files.exists(extraActivePackPath)) {
            Path canonicalExtra = canonicalize(extraActivePackPath);
            if (!seenNormalized.contains(canonicalExtra)) {
                ShaderPackFormat fmt = detectFormat(extraActivePackPath);
                if (fmt != ShaderPackFormat.UNKNOWN) {
                    String name = extraActivePackPath.getFileName() != null
                            ? extraActivePackPath.getFileName().toString()
                            : extraActivePackPath.toString();
                    discovered.add(new DiscoveredPack(
                            extraActivePackPath.toAbsolutePath().toString(),
                            stripZipExtension(name),
                            extraActivePackPath,
                            fmt,
                            true
                    ));
                }
            }
        }

        discovered.sort(Comparator
                .comparingInt((DiscoveredPack p) -> p.format() == ShaderPackFormat.APERTURE_TS ? 0 : 1)
                .thenComparing(DiscoveredPack::displayName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(discovered);
    }

    public static ShaderPackFormat detectFormat(Path candidate) {
        if (candidate == null || !Files.exists(candidate)) {
            return ShaderPackFormat.UNKNOWN;
        }
        if (Files.isDirectory(candidate)) {
            if (Files.isRegularFile(candidate.resolve("pack.ts"))) {
                return ShaderPackFormat.APERTURE_TS;
            }
            if (Files.isDirectory(candidate.resolve("shaders"))) {
                return ShaderPackFormat.LEGACY_IRIS_GLSL;
            }
            try (Stream<Path> children = Files.list(candidate)) {
                boolean hasNested = children.filter(Files::isDirectory)
                        .anyMatch(sub -> Files.isDirectory(sub.resolve("shaders")));
                if (hasNested) {
                    return ShaderPackFormat.LEGACY_IRIS_GLSL;
                }
            } catch (IOException ignored) {
                // ignore
            }
            return ShaderPackFormat.UNKNOWN;
        }
        String fileName = candidate.getFileName() != null ? candidate.getFileName().toString().toLowerCase() : "";
        if (Files.isRegularFile(candidate) && fileName.endsWith(".zip") && zipLooksLikeShaderPack(candidate)) {
            return ShaderPackFormat.LEGACY_IRIS_GLSL;
        }
        return ShaderPackFormat.UNKNOWN;
    }

    public static Path importDroppedPack(Path source, Path shaderpacksDir) throws IOException {
        if (source == null || !Files.exists(source)) {
            throw new IOException("Source shader pack does not exist: " + source);
        }
        if (detectFormat(source) == ShaderPackFormat.UNKNOWN) {
            throw new IOException("Not a valid Metality Aperture or Iris/OptiFine shader pack: " + source.getFileName());
        }
        Files.createDirectories(shaderpacksDir);
        Path target = shaderpacksDir.resolve(source.getFileName().toString());
        if (canonicalize(source).equals(canonicalize(target))) {
            return target;
        }
        if (Files.isRegularFile(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
        copyDirectory(source, target);
        return target;
    }

    private static boolean zipLooksLikeShaderPack(Path zipPath) {
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName().replace('\\', '/');
                if (n.startsWith("shaders/") || n.contains("/shaders/")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static void copyDirectory(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(dir);
                Files.createDirectories(dst.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(file);
                Files.copy(file, dst.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String stripZipExtension(String name) {
        return name.toLowerCase().endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    private static Path canonicalize(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException ignored) {
            return p.toAbsolutePath().normalize();
        }
    }
}

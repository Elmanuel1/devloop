package com.tosspaper.aiengine.vfs;

import com.tosspaper.aiengine.properties.VfsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Implementation of VirtualFilesystemService with security protections.
 * Implements path traversal protection to prevent access outside VFS root.
 *
 * <p>Path structure: {root}/companies/{companyId}/po/{poNumber}/{documentType}/{documentId}.json
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(VfsProperties.class)
public class VirtualFilesystemServiceImpl implements VirtualFilesystemService {

    private final VfsProperties properties;

    private Path root;

    @PostConstruct
    void init() {
        this.root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        log.info("VFS initialized with root: {}", this.root);
    }

    @Override
    public Path save(VfsDocumentContext context) {
        validatePathComponents(context.poNumber(), context.documentId(), context.documentType().getFilePrefix());

        Path filePath = buildPath(context);
        return writeFileInternal(filePath, context.content());
    }

    @Override
    public Path getPath(VfsDocumentContext context) {
        validatePathComponents(context.poNumber(), context.documentId(), context.documentType().getFilePrefix());
        return buildPath(context);
    }

    @Override
    public Path getWorkingDirectory(long companyId, String poNumber) {
        validatePathComponents(poNumber);
        return root
            .resolve("companies")
            .resolve(String.valueOf(companyId))
            .resolve("po")
            .resolve(sanitize(poNumber));
    }

    @Override
    public Path getAuditDirectory(long companyId, String poNumber) {
        return getWorkingDirectory(companyId, poNumber).resolve("audits");
    }

    @Override
    @SneakyThrows
    public String readFile(Path path) {
        validatePathWithinRoot(path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Override
    @SneakyThrows
    public ReadChunkResult readChunk(Path path, long offset, int limit) {
        validatePathWithinRoot(path);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + path);
        }

        byte[] bytes = Files.readAllBytes(path);
        long totalSize = bytes.length;

        if (offset >= totalSize) {
            return new ReadChunkResult("", offset, 0, totalSize, false);
        }

        int start = (int) Math.min(offset, totalSize);
        int end = (int) Math.min(start + limit, totalSize);
        int length = end - start;

        String content = new String(bytes, start, length, StandardCharsets.UTF_8);
        boolean hasMore = end < totalSize;

        return new ReadChunkResult(content, offset, length, totalSize, hasMore);
    }

    @Override
    public Path writeFile(Path path, String content) {
        validatePathWithinRoot(path);
        return writeFileInternal(path, content);
    }

    @Override
    @SneakyThrows
    public List<FileInfo> listDirectory(Path path) {
        validatePathWithinRoot(path);

        if (!Files.exists(path)) {
            return List.of();
        }

        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Not a directory: " + path);
        }

        List<FileInfo> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(path)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    result.add(FileInfo.directory(name));
                } else {
                    try {
                        result.add(FileInfo.file(name, Files.size(p)));
                    } catch (IOException e) {
                        result.add(FileInfo.file(name, 0));
                    }
                }
            });
        }
        return result;
    }

    @Override
    @SneakyThrows
    public List<GrepResult> grep(Path path, String pattern, int beforeContext, int afterContext) {
        validatePathWithinRoot(path);

        List<GrepResult> results = new ArrayList<>();
        Pattern regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);

        if (Files.isDirectory(path)) {
            // Search all files in directory recursively
            try (Stream<Path> stream = Files.walk(path)) {
                stream.filter(Files::isRegularFile)
                      .forEach(file -> results.addAll(grepFile(file, path, regex, beforeContext, afterContext)));
            }
        } else if (Files.isRegularFile(path)) {
            results.addAll(grepFile(path, path.getParent(), regex, beforeContext, afterContext));
        }

        return results;
    }

    @SneakyThrows
    private List<GrepResult> grepFile(Path file, Path basePath, Pattern pattern, int beforeContext, int afterContext) {
        List<GrepResult> results = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher matcher = pattern.matcher(line);

            if (matcher.find()) {
                List<String> context = new ArrayList<>();

                // Add before context
                for (int j = Math.max(0, i - beforeContext); j < i; j++) {
                    context.add(lines.get(j));
                }

                // Add after context
                for (int j = i + 1; j <= Math.min(lines.size() - 1, i + afterContext); j++) {
                    context.add(lines.get(j));
                }

                String relativePath = basePath.relativize(file).toString();
                results.add(GrepResult.withContext(relativePath, i + 1, line, context));
            }
        }

        return results;
    }

    @Override
    public boolean exists(Path path) {
        validatePathWithinRoot(path);
        return Files.exists(path);
    }

    @Override
    @SneakyThrows
    public boolean delete(Path path) {
        validatePathWithinRoot(path);
        return Files.deleteIfExists(path);
    }

    @Override
    public Path getRoot() {
        return root;
    }

    /**
     * Build path for a document based on its context.
     * Structure: {root}/companies/{companyId}/po/{poNumber}/{documentType}/{documentId}.json
     * For PO itself: {root}/companies/{companyId}/po/{poNumber}/po.json
     */
    private Path buildPath(VfsDocumentContext context) {
        Path basePath = root
            .resolve("companies")
            .resolve(String.valueOf(context.companyId()))
            .resolve("po")
            .resolve(sanitize(context.poNumber()));

        if (context.documentType() == com.tosspaper.models.domain.DocumentType.PURCHASE_ORDER) {
            // PO goes directly in the PO folder as po.json
            return basePath.resolve("po.json");
        } else {
            // Other docs go in docType subfolder
            return basePath
                .resolve(sanitize(context.documentType().getFilePrefix()))
                .resolve(sanitize(context.documentId()) + ".json");
        }
    }

    /**
     * Write content to file, creating parent directories if needed.
     */
    @SneakyThrows
    private Path writeFileInternal(Path path, String content) {
        validatePathWithinRoot(path);
        if (properties.isAutoCreateDirectories()) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        log.debug("Wrote file: {}", path);
        return path;
    }

    /**
     * Validate that a path is within the VFS root (prevent path traversal).
     */
    private void validatePathWithinRoot(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(root)) {
            log.error("Path traversal attempt detected: {} is outside root {}", path, root);
            throw new SecurityException("Path traversal attempt: path is outside VFS root");
        }
    }

    /**
     * Validate path components for security issues.
     */
    private void validatePathComponents(String... components) {
        for (String component : components) {
            if (component == null || component.isBlank()) {
                throw new IllegalArgumentException("Path component cannot be null or blank");
            }
            if (containsPathTraversal(component)) {
                log.error("Path traversal attempt detected in component: {}", component);
                throw new SecurityException("Path traversal attempt in component: " + component);
            }
        }
    }

    /**
     * Check if a string contains path traversal patterns.
     */
    private boolean containsPathTraversal(String path) {
        return path.contains("..") ||
               path.contains("~") ||
               path.startsWith("/") ||
               path.contains("\\");
    }

    /**
     * Sanitize a path component by removing unsafe characters.
     */
    private String sanitize(String component) {
        if (component == null) {
            return null;
        }
        // Replace unsafe characters with underscores
        return component
            .replace("..", "_")
            .replace("~", "_")
            .replace("/", "_")
            .replace("\\", "_")
            .replace(":", "_")
            .replace("*", "_")
            .replace("?", "_")
            .replace("\"", "_")
            .replace("<", "_")
            .replace(">", "_")
            .replace("|", "_");
    }
}

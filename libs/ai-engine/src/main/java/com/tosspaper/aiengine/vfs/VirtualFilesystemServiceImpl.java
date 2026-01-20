package com.tosspaper.aiengine.vfs;

import com.tosspaper.aiengine.properties.VfsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        return writeFile(filePath, context.content());
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
    private Path writeFile(Path path, String content) {
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

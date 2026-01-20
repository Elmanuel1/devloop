package com.tosspaper.aiengine.factory;

import com.tosspaper.models.domain.DocumentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for loading document schemas and prompts from file system.
 * Reads from configurable schema-prompts path.
 * Caches loaded schemas for performance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentResourceFactory {

    private final Map<DocumentType, String> schemaCache = new ConcurrentHashMap<>();

    @Value("${schema.prompts.path:./schema-prompts}")
    private String schemasBasePath;

    /**
     * Load JSON schema for the given document type.
     * Schemas are cached after first load.
     */
    public String loadSchema(DocumentType type) {
        return schemaCache.computeIfAbsent(type, this::loadSchemaFile);
    }

    /**
     * Load generation prompt template for the given document type.
     */
    public String loadGenerationPrompt(DocumentType type) {
        String filename = type.getFilePrefix() + ".prompt";
        return loadFile("prompts", filename);
    }

    /**
     * Load evaluation prompt template for the given document type.
     */
    public String loadEvaluationPrompt(DocumentType type) {
        String filename = type.getFilePrefix() + ".evaluation.prompt";
        return loadFile("prompts", filename);
    }

    /**
     * Load PO match evaluation prompt for document matching.
     */
    public String loadPoMatchEvaluationPrompt() {
        return loadFile("prompts", "po-match.evaluation.prompt");
    }

    private String loadSchemaFile(DocumentType type) {
        String filename = type.getFilePrefix() + ".schema.json";
        return loadFile("schemas", filename);
    }

    private String loadFile(String subdir, String filename) {
        Path filePath = Paths.get(schemasBasePath).resolve(subdir).resolve(filename);
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            log.debug("Successfully loaded {} from: {}", filename, filePath);
            return content;
        } catch (Exception e) {
            log.error("Failed to load {}/{}: {}", subdir, filename, filePath, e);
            throw new RuntimeException("Failed to load " + filename + " from " + filePath, e);
        }
    }
}

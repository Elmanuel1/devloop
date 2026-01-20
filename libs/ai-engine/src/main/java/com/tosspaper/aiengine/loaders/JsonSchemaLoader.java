package com.tosspaper.aiengine.loaders;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utility class for loading JSON schemas from file system.
 * Reads from configurable schema path.
 * Supports loading named schemas (e.g., "extraction", "invoice", "po").
 * Caches schemas after first load to avoid repeated file system access.
 * Uses synchronization to ensure thread-safe initialization on first load.
 */
@Slf4j
@Component
public class JsonSchemaLoader {

    private static final String DEFAULT_SCHEMA_FILENAME = "extraction.json";

    @Value("${schema.prompts.path:./schema-prompts}")
    private String schemasBasePath;

    private final AtomicReference<String> cachedSchema = new AtomicReference<>();
    private final Map<String, String> namedSchemaCache = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    
    /**
     * Load the default schema (Reducto).
     * Schema is cached after first successful load.
     * Thread-safe: uses synchronization to prevent race conditions on first load.
     * 
     * @return JSON schema as string
     * @throws RuntimeException if the schema file cannot be read
     */
    public String loadSchema() {
        // Quick check without synchronization (optimistic)
        String cached = cachedSchema.get();
        if (cached != null) {
            log.debug("Returning cached schema");
            return cached;
        }
        
        // Synchronize on first load to prevent race conditions
        synchronized (lock) {
            // Double-check pattern: another thread may have loaded it while we were waiting
            cached = cachedSchema.get();
            if (cached != null) {
                log.debug("Schema already cached by another thread");
                return cached;
            }
            
            Path schemaPath = Paths.get(schemasBasePath).resolve("schemas").resolve(DEFAULT_SCHEMA_FILENAME);
            log.debug("Loading schema from: {}", schemaPath);
            
            try {
                String schema = Files.readString(schemaPath, StandardCharsets.UTF_8);
                log.info("Successfully loaded schema: {} ({} characters)", 
                        schemaPath, schema.length());
                
                // Cache the schema
                cachedSchema.set(schema);
                return schema;
            } catch (Exception e) {
                log.error("Failed to load schema from: {}", schemaPath, e);
                throw new RuntimeException("Failed to load schema from " + schemaPath, e);
            }
        }
    }

    /**
     * Load a named schema from schema-prompts/schemas/{schemaName}.json
     * Schema is cached after first successful load.
     * Thread-safe: uses ConcurrentHashMap for caching.
     *
     * Examples:
     * - loadSchema("extraction") -> loads "schema-prompts/schemas/extraction.json"
     * - loadSchema("invoice") -> loads "schema-prompts/schemas/invoice.json"
     * - loadSchema("po") -> loads "schema-prompts/schemas/po.json"
     *
     * @param schemaName Name of the schema (without .json extension)
     * @return JSON schema as string
     * @throws RuntimeException if the schema file cannot be read
     */
    public String loadSchema(String schemaName) {
        // Check cache first
        String cached = namedSchemaCache.get(schemaName);
        if (cached != null) {
            log.debug("Returning cached schema for: {}", schemaName);
            return cached;
        }

        Path schemaPath = getSchemaPath(schemaName);

        log.debug("Loading schema '{}' from: {}", schemaName, schemaPath);

        try {
            String schema = Files.readString(schemaPath, StandardCharsets.UTF_8);
            log.info("Successfully loaded schema '{}': {} ({} characters)",
                    schemaName, schemaPath, schema.length());

            // Cache the schema
            namedSchemaCache.put(schemaName, schema);
            return schema;
        } catch (Exception e) {
            log.error("Failed to load schema '{}' from: {}", schemaName, schemaPath, e);
            throw new RuntimeException("Failed to load schema '" + schemaName + "' from " + schemaPath, e);
        }
    }

    /**
     * Get the filesystem path for a named schema.
     * Used by agents that need to reference the schema file directly.
     *
     * @param schemaName Name of the schema (without .json extension)
     * @return Path to the schema file
     */
    public Path getSchemaPath(String schemaName) {
        return Paths.get(schemasBasePath)
            .resolve("schemas")
            .resolve(schemaName + ".json");
    }
}

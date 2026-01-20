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
 * Utility class for loading prompts from file system.
 * Reads from configurable prompts path.
 * Supports loading named prompts (e.g., "extraction", "invoice", "po").
 * Caches prompts after first load to avoid repeated file system access.
 * Uses synchronization to ensure thread-safe initialization on first load.
 */
@Slf4j
@Component
public class PromptLoader {

    private static final String DEFAULT_PROMPT_FILENAME = "extraction.prompt";

    @Value("${schema.prompts.path:./schema-prompts}")
    private String schemasBasePath;

    private final AtomicReference<String> cachedPrompt = new AtomicReference<>();
    private final Map<String, String> namedPromptCache = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    /**
     * Load the default prompt (Reducto).
     * Prompt is cached after first successful load.
     * Thread-safe: uses synchronization to prevent race conditions on first load.
     *
     * @return String content of the prompt
     * @throws RuntimeException if the prompt file cannot be read
     */
    public String loadPrompt() {
        // Quick check without synchronization (optimistic)
        String cached = cachedPrompt.get();
        if (cached != null) {
            log.debug("Returning cached prompt");
            return cached;
        }

        // Synchronize on first load to prevent race conditions
        synchronized (lock) {
            // Double-check pattern: another thread may have loaded it while we were waiting
            cached = cachedPrompt.get();
            if (cached != null) {
                log.debug("Prompt already cached by another thread");
                return cached;
            }

            Path promptPath = Paths.get(schemasBasePath).resolve("prompts").resolve(DEFAULT_PROMPT_FILENAME);
            log.debug("Loading prompt from: {}", promptPath);

            try {
                String prompt = Files.readString(promptPath, StandardCharsets.UTF_8);
                log.info("Successfully loaded prompt: {} ({} characters)", 
                        promptPath, prompt.length());

                // Cache the prompt
                cachedPrompt.set(prompt);
                return prompt;
            } catch (Exception e) {
                log.error("Failed to load prompt from: {}", promptPath, e);
                throw new RuntimeException("Failed to load prompt from " + promptPath, e);
            }
        }
    }

    /**
     * Load a named prompt from schema-prompts/prompts/{promptName}.prompt
     * Prompt is cached after first successful load.
     * Thread-safe: uses ConcurrentHashMap for caching.
     *
     * Examples:
     * - loadPrompt("extraction") -> loads "schema-prompts/prompts/extraction.prompt"
     * - loadPrompt("invoice") -> loads "schema-prompts/prompts/invoice.prompt"
     * - loadPrompt("po") -> loads "schema-prompts/prompts/po.prompt"
     *
     * @param promptName Name of the prompt (without .prompt extension)
     * @return String content of the prompt
     * @throws RuntimeException if the prompt file cannot be read
     */
    public String loadPrompt(String promptName) {
        // Check cache first
        String cached = namedPromptCache.get(promptName);
        if (cached != null) {
            log.debug("Returning cached prompt for: {}", promptName);
            return cached;
        }

        Path promptPath = Paths.get(schemasBasePath)
            .resolve("prompts")
            .resolve(promptName + ".prompt");

        log.debug("Loading prompt '{}' from: {}", promptName, promptPath);

        try {
            String prompt = Files.readString(promptPath, StandardCharsets.UTF_8);
            log.info("Successfully loaded prompt '{}': {} ({} characters)",
                    promptName, promptPath, prompt.length());

            // Cache the prompt
            namedPromptCache.put(promptName, prompt);
            return prompt;
        } catch (Exception e) {
            log.error("Failed to load prompt '{}' from: {}", promptName, promptPath, e);
            throw new RuntimeException("Failed to load prompt '" + promptName + "' from " + promptPath, e);
        }
    }
}

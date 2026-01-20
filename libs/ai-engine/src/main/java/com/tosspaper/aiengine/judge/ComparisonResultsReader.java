package com.tosspaper.aiengine.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and caches comparison results from file.
 * Ensures file is only read once across all judges.
 *
 * <p>Expects wrapper object format:
 * <pre>
 * {
 *   "documentId": "...",
 *   "poId": "...",
 *   "results": [...]
 * }
 * </pre>
 */
@RequiredArgsConstructor
public class ComparisonResultsReader {

    public static final String RESULTS_KEY = "parsedResults";

    @Getter
    private final Path filePath;
    private final ObjectMapper objectMapper;

    private JsonNode cachedRoot;
    private JsonNode cachedResults;
    private String readError;
    private boolean fileExists;
    private boolean isValidJson;
    private boolean isObject;
    private boolean hasResults;

    /**
     * Read and parse the results file. Caches the result for subsequent calls.
     *
     * @return the parsed JSON root node, or null if file doesn't exist or is invalid
     */
    public JsonNode read() {
        if (cachedRoot != null || readError != null) {
            return cachedRoot;
        }

        try {
            if (!Files.exists(filePath)) {
                fileExists = false;
                readError = "File not found: " + filePath;
                return null;
            }
            fileExists = true;

            String content = Files.readString(filePath);
            if (content.isBlank()) {
                readError = "File is empty";
                return null;
            }

            cachedRoot = objectMapper.readTree(content);
            isValidJson = true;
            isObject = cachedRoot.isObject();

            // Extract results array from wrapper object
            if (isObject && cachedRoot.has("results")) {
                JsonNode results = cachedRoot.get("results");
                if (results.isArray()) {
                    cachedResults = results;
                    hasResults = true;
                }
            }

            return cachedRoot;

        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            isValidJson = false;
            readError = "Invalid JSON: " + e.getOriginalMessage();
            return null;
        } catch (IOException e) {
            readError = "Error reading file: " + e.getMessage();
            return null;
        }
    }

    /**
     * Get the results array from the wrapper object.
     *
     * @return the results array, or null if not available
     */
    public JsonNode getResults() {
        if (cachedRoot == null && readError == null) {
            read();
        }
        return cachedResults;
    }

    /**
     * Get the documentId from the wrapper object.
     */
    public String getDocumentId() {
        if (cachedRoot == null && readError == null) {
            read();
        }
        if (cachedRoot != null && cachedRoot.has("documentId")) {
            return cachedRoot.get("documentId").asText();
        }
        return null;
    }

    /**
     * Get the poId from the wrapper object.
     */
    public String getPoId() {
        if (cachedRoot == null && readError == null) {
            read();
        }
        if (cachedRoot != null && cachedRoot.has("poId")) {
            return cachedRoot.get("poId").asText();
        }
        return null;
    }

    public boolean fileExists() {
        if (cachedRoot == null && readError == null) {
            read();
        }
        return fileExists;
    }

    public boolean isValidJson() {
        if (cachedRoot == null && readError == null) {
            read();
        }
        return isValidJson;
    }

    public boolean isObject() {
        if (cachedRoot == null && readError == null) {
            read();
        }
        return isObject;
    }

    public boolean hasResults() {
        if (cachedRoot == null && readError == null) {
            read();
        }
        return hasResults;
    }

    public String getError() {
        return readError;
    }

}

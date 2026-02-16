package com.tosspaper.aiengine.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.vfs.FileInfo;
import com.tosspaper.aiengine.vfs.GrepResult;
import com.tosspaper.aiengine.vfs.ReadChunkResult;
import com.tosspaper.aiengine.vfs.VirtualFilesystemService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Embedded file tools for AI document comparison.
 * Wraps VirtualFilesystemService with Spring AI @Tool annotations.
 *
 * <p>These tools are injected into the ChatClient and allow the AI to:
 * <ul>
 *   <li>Read files (whole or in chunks for large files)</li>
 *   <li>Write analysis results to files</li>
 *   <li>List directory contents</li>
 *   <li>Search for patterns in files</li>
 * </ul>
 *
 * <p>All paths are relative to the working directory set per comparison request.
 * Security is enforced by VirtualFilesystemService (path traversal prevention).
 */
@Slf4j
public class FileTools {

    private static final String TRACKER_FILE = "_po_matches.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VirtualFilesystemService vfs;
    private final org.springframework.ai.chat.client.ChatClient validationChatClient;

    public FileTools(VirtualFilesystemService vfs, org.springframework.ai.chat.client.ChatClient validationChatClient) {
        this.vfs = vfs;
        this.validationChatClient = validationChatClient;
    }

    /**
     * The working directory for the current comparison.
     * ThreadLocal to support concurrent comparisons on different threads.
     * Must be set before tools are used.
     */
    private final ThreadLocal<Path> workingDirectory = new ThreadLocal<>();

    /**
     * The number of items in the PO (for index validation).
     * ThreadLocal to support concurrent comparisons.
     * Set during comparison setup to validate poIndex bounds.
     */
    private final ThreadLocal<Integer> poItemCount = ThreadLocal.withInitial(() -> -1);

    /**
     * Set the working directory for file operations.
     * Called before each comparison to scope file access.
     * Uses ThreadLocal for concurrent comparison safety.
     *
     * @param path Absolute path to the working directory
     */
    public void setWorkingDirectory(Path path) {
        this.workingDirectory.set(path);
        this.poItemCount.set(-1); // Reset on new comparison
        log.debug("FileTools working directory set to: {} (thread: {})", path, Thread.currentThread().getName());
    }

    /**
     * Get the current working directory for this thread.
     */
    public Path getWorkingDirectory() {
        return workingDirectory.get();
    }

    /**
     * Clear ThreadLocal state after comparison completes.
     * Should be called in a finally block to prevent memory leaks.
     */
    public void clearThreadLocalState() {
        workingDirectory.remove();
        poItemCount.remove();
        log.debug("FileTools ThreadLocal state cleared (thread: {})", Thread.currentThread().getName());
    }

    /**
     * Set the number of PO items for index validation.
     * Called during comparison setup after reading po.json.
     *
     * @param count The number of items in the PO
     */
    public void setPoItemCount(int count) {
        this.poItemCount.set(count);
        log.debug("PO item count set to: {} (valid indices: 0 to {})", count, count - 1);
    }

    @Tool(description = """
        Read the entire contents of a file. Use this for small files.
        For large files (>10KB), use read_file_chunk instead to avoid context bloat.
        Returns the file content as a string.
        """)
    public String readFile(
            @ToolParam(description = "Relative path to the file from the working directory") String path) {
        Path resolved = resolve(path);
        log.debug("Reading file: {}", resolved);
        return vfs.readFile(resolved);
    }

    @Tool(description = """
        Read a chunk of a file. Use this for large files to avoid loading too much into context.
        Returns the chunk content plus metadata (offset, length, totalSize, hasMore).
        Start with offset=0, then use the returned offset+length for the next chunk if hasMore=true.
        """)
    public ReadChunkResult readFileChunk(
            @ToolParam(description = "Relative path to the file from the working directory") String path,
            @ToolParam(description = "Byte offset to start reading from (0 = beginning)") long offset,
            @ToolParam(description = "Maximum number of bytes/characters to read (default 10000)") int limit) {
        Path resolved = resolve(path);
        log.debug("Reading chunk from file: {} offset={} limit={}", resolved, offset, limit);

        // Default limit if not specified or too small
        if (limit <= 0) {
            limit = 10000;
        }

        return vfs.readChunk(resolved, offset, limit);
    }

    @Tool(description = """
        Write content to a file. Creates the file if it doesn't exist, overwrites if it does.
        Parent directories are created automatically.
        Use this to save analysis results, intermediate data, or the final comparison output.
        Returns confirmation message with the file path.
        """)
    public String writeFile(
            @ToolParam(description = "Relative path to the file from the working directory") String path,
            @ToolParam(description = "Content to write to the file") String content) {
        Path resolved = resolve(path);
        log.debug("Writing file: {} ({} chars)", resolved, content.length());
        vfs.writeFile(resolved, content);
        return "File written: " + path + " (" + content.length() + " characters)";
    }

    @Tool(description = """
        List files and directories in a directory.
        Returns list of {name, type, size} for each entry.
        Type is "file" or "directory".
        Use empty string or "." for the current working directory.
        """)
    public List<FileInfo> listDirectory(
            @ToolParam(description = "Relative path to the directory (empty or '.' for current)") String path) {
        Path resolved = (path == null || path.isBlank() || ".".equals(path))
                ? workingDirectory.get()
                : resolve(path);
        log.debug("Listing directory: {}", resolved);
        return vfs.listDirectory(resolved);
    }

    @Tool(description = """
        Search for a text pattern in files (like grep).
        Pattern is a regular expression.
        Can search a single file or all files in a directory recursively.
        Returns list of matches with file path, line number, matching line, and context.
        """)
    public List<GrepResult> grep(
            @ToolParam(description = "Regular expression pattern to search for") String pattern,
            @ToolParam(description = "File or directory path to search (relative to working directory)") String path,
            @ToolParam(description = "Number of lines of context to show before each match (0-5)") int beforeContext,
            @ToolParam(description = "Number of lines of context to show after each match (0-5)") int afterContext) {
        Path resolved = resolve(path);
        log.debug("Grep pattern='{}' in path={}", pattern, resolved);

        // Limit context to reasonable bounds
        beforeContext = Math.max(0, Math.min(5, beforeContext));
        afterContext = Math.max(0, Math.min(5, afterContext));

        return vfs.grep(resolved, pattern, beforeContext, afterContext);
    }

    // ===== VALIDATION RESULT RECORD =====
    // Used for programmatic validation in post-hoc validation flow

    /**
     * Result of validating a line item match.
     * Used by LineItemValidator for post-hoc validation.
     */
    public record ValidationResult(
            boolean valid,
            int docIndex,
            int poIndex,
            String itemCode,
            String description,
            String actualItemCode,
            String actualDescription
    ) {}

    // ===== PO MATCH TRACKING TOOLS =====
    // These tools help the AI track which PO line items have been matched to prevent duplicates.

    @Tool(description = """
        Check if a PO line index is available for matching (not yet matched to a document line).
        Call this BEFORE matching a document line to a PO line to ensure no duplicates.
        Returns JSON with poIndex and available (true/false).
        """)
    public String checkPoIndexAvailable(
            @ToolParam(description = "The PO line index (0-based, matching JSON array) to check") int poIndex) {
        PoMatchTracker tracker = loadTracker();
        boolean available = !tracker.getUsedPoIndices().contains(poIndex);
        log.debug("Check PO index {} available: {}", poIndex, available);
        return String.format("{\"poIndex\": %d, \"available\": %s}", poIndex, available);
    }

    @Tool(description = """
        List all PO matches recorded so far.
        Returns JSON with usedPoIndices array and matches array.
        Use this at the end to verify all matches and as source of truth for extractedIndex/poIndex in results.
        """)
    public String listPoMatches() {
        PoMatchTracker tracker = loadTracker();
        log.debug("Listing PO matches: {} used indices, {} matches",
                tracker.getUsedPoIndices().size(), tracker.getMatches().size());
        try {
            return MAPPER.writeValueAsString(tracker);
        } catch (Exception e) {
            log.error("Failed to serialize tracker", e);
            return "{\"error\": \"Failed to serialize tracker\"}";
        }
    }

    /**
     * Validate that a PO index contains the expected item code and description.
     * Public method for programmatic validation (used by LineItemValidator).
     * Does NOT write to tracker - validation only.
     *
     * @param poIndex The PO line index (0-based)
     * @param expectedItemCode The item code from the document
     * @param expectedDescription The item description from the document
     * @return ValidationResult with valid=true if match, or actual values if mismatch
     */
    public ValidationResult validateLineItemMatch(int poIndex, String expectedItemCode, String expectedDescription) {
        log.debug("=== VALIDATING LINE ITEM === poIndex={}, itemCode='{}', desc='{}'",
                poIndex, expectedItemCode, truncate(expectedDescription, 40));

        if (poIndex < 0) {
            log.warn("VALIDATION FAILED: Invalid poIndex {} (must be >= 0)", poIndex);
            return new ValidationResult(false, -1, poIndex, expectedItemCode, expectedDescription,
                    "INVALID_INDEX", "poIndex must be >= 0");
        }

        try {
            Path poPath = workingDirectory.get().resolve("po.json");
            String poJson = vfs.readFile(poPath);
            var poNode = MAPPER.readTree(poJson);
            var items = poNode.get("items");

            if (items == null || !items.isArray()) {
                return new ValidationResult(false, -1, poIndex, expectedItemCode, expectedDescription,
                        "ERROR", "Cannot read PO items");
            }

            if (poIndex >= items.size()) {
                log.warn("VALIDATION FAILED: poIndex {} out of bounds (max: {})", poIndex, items.size() - 1);
                return new ValidationResult(false, -1, poIndex, expectedItemCode, expectedDescription,
                        "OUT_OF_BOUNDS", "poIndex " + poIndex + " out of bounds (max: " + (items.size() - 1) + ")");
            }

            // Get item at poIndex
            var item = items.get(poIndex);
            String actualItemCode = item.has("unitCode") ? item.get("unitCode").asText() : "";
            String actualDescription = item.has("name") ? item.get("name").asText() : "";

            log.debug("VALIDATING: po.items[{}] -> code='{}', desc='{}'",
                    poIndex, actualItemCode, truncate(actualDescription, 40));

            // Use validation model to compare
            boolean isMatch = compareProductWithValidationModel(expectedItemCode, expectedDescription,
                    actualItemCode, actualDescription);

            if (isMatch) {
                log.debug("VALIDATION SUCCESS ✓ poIndex={}", poIndex);
                return new ValidationResult(true, -1, poIndex, expectedItemCode, expectedDescription,
                        actualItemCode, actualDescription);
            } else {
                log.debug("VALIDATION FAILED ✗ poIndex={} | expected: code='{}' desc='{}' | actual: code='{}' desc='{}'",
                        poIndex, expectedItemCode, truncate(expectedDescription, 30),
                        actualItemCode, truncate(actualDescription, 30));
                return new ValidationResult(false, -1, poIndex, expectedItemCode, expectedDescription,
                        actualItemCode, actualDescription);
            }

        } catch (Exception e) {
            log.error("Failed to validate PO index {}", poIndex, e);
            return new ValidationResult(false, -1, poIndex, expectedItemCode, expectedDescription,
                    "ERROR", e.getMessage());
        }
    }

    /**
     * Truncate a string for logging.
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Get all PO items as a list for batch correction prompts.
     * Returns index, itemCode, and description for each item.
     */
    public List<PoItemInfo> getPoItemsList() {
        List<PoItemInfo> items = new ArrayList<>();
        try {
            Path poPath = workingDirectory.get().resolve("po.json");
            String poJson = vfs.readFile(poPath);
            var poNode = MAPPER.readTree(poJson);
            var itemsNode = poNode.get("items");

            if (itemsNode == null || !itemsNode.isArray()) {
                log.warn("Cannot read PO items for list");
                return items;
            }

            for (int i = 0; i < itemsNode.size(); i++) {
                var item = itemsNode.get(i);
                String itemCode = item.has("unitCode") ? item.get("unitCode").asText() : "";
                String description = item.has("name") ? item.get("name").asText() : "";
                items.add(new PoItemInfo(i, itemCode, description));
            }
        } catch (Exception e) {
            log.error("Failed to get PO items list", e);
        }
        return items;
    }

    /**
     * Get the set of already-matched PO indices from the tracker.
     */
    public Set<Integer> getUsedPoIndices() {
        return new HashSet<>(loadTracker().getUsedPoIndices());
    }

    /**
     * PO item info for batch correction.
     */
    public record PoItemInfo(int index, String itemCode, String description) {}

    /**
     * Clear specific PO indices from the tracker.
     * Used by LineItemValidator to reset invalid matches before correction.
     *
     * @param indicesToClear Set of PO indices to remove from tracker
     */
    public void clearPoIndicesFromTracker(Set<Integer> indicesToClear) {
        PoMatchTracker tracker = loadTracker();
        tracker.getUsedPoIndices().removeAll(indicesToClear);
        tracker.getMatches().removeIf(match -> indicesToClear.contains(match.getPoIndex()));
        saveTracker(tracker);
        log.info("Cleared {} PO indices from tracker", indicesToClear.size());
    }

    /**
     * Write a validated match to the tracker.
     * Used by LineItemValidator after successful correction.
     *
     * @param docIndex The document line index
     * @param poIndex The PO line index
     * @return true if written, false if poIndex already used
     */
    public boolean writeValidatedMatch(int docIndex, int poIndex) {
        PoMatchTracker tracker = loadTracker();
        if (tracker.getUsedPoIndices().contains(poIndex)) {
            log.warn("Cannot write match: poIndex {} already used", poIndex);
            return false;
        }
        tracker.getUsedPoIndices().add(poIndex);
        tracker.getMatches().add(new PoMatch(docIndex, poIndex, "post-hoc-validated"));
        saveTracker(tracker);
        log.info("MATCH WRITTEN ✓ docIndex={} → poIndex={}", docIndex, poIndex);
        return true;
    }

    /**
     * Validate and write a line item match.
     * NOT exposed as @Tool - validation happens programmatically in post-hoc flow.
     *
     * @deprecated Use {@link #validateLineItemMatch} for validation and {@link #writeValidatedMatch} for writing.
     */
    @Deprecated
    public String validateAndWriteLineItem(
            @ToolParam(description = "The document line index (0-based)") int docIndex,
            @ToolParam(description = "The PO line index (0-based) you want to match") int poIndex,
            @ToolParam(description = "The item code from the document") String expectedItemCode,
            @ToolParam(description = "The item description/name from the document") String expectedDescription) {

        log.info("=== VALIDATE AND WRITE === docIndex={}, poIndex={}, itemCode='{}', desc='{}'",
                docIndex, poIndex, expectedItemCode, expectedDescription);

        if (poIndex < 0) {
            log.warn("WRITE REJECTED: Invalid poIndex {} (must be >= 0)", poIndex);
            return String.format("{\"written\": false, \"error\": \"poIndex %d is invalid: must be >= 0\"}", poIndex);
        }

        try {
            Path poPath = workingDirectory.get().resolve("po.json");
            String poJson = vfs.readFile(poPath);
            var poNode = MAPPER.readTree(poJson);
            var items = poNode.get("items");

            if (items == null || !items.isArray()) {
                return "{\"written\": false, \"error\": \"Cannot read PO items\"}";
            }

            if (poIndex >= items.size()) {
                log.warn("WRITE REJECTED: poIndex {} out of bounds (max: {})", poIndex, items.size() - 1);
                return String.format("{\"written\": false, \"error\": \"poIndex %d out of bounds (max: %d)\"}",
                        poIndex, items.size() - 1);
            }

            // Check if already matched
            PoMatchTracker tracker = loadTracker();
            if (tracker.getUsedPoIndices().contains(poIndex)) {
                log.warn("WRITE REJECTED: PO index {} already matched", poIndex);
                return String.format("{\"written\": false, \"error\": \"PO index %d already matched to another document line\"}", poIndex);
            }

            // Get item at poIndex
            var item = items.get(poIndex);
            String actualItemCode = item.has("unitCode") ? item.get("unitCode").asText() : "";
            String actualDescription = item.has("name") ? item.get("name").asText() : "";

            log.info("VALIDATING: po.items[{}] -> code='{}', desc='{}'",
                    poIndex, actualItemCode, actualDescription);

            // Use smaller model to compare BOTH item code AND description
            boolean isMatch = compareProductWithValidationModel(expectedItemCode, expectedDescription, actualItemCode, actualDescription);

            if (!isMatch) {
                // Wrong index - nothing written, AI must retry
                log.warn("WRITE REJECTED ✗ poIndex={} | expected: code='{}' desc='{}' | actual: code='{}' desc='{}'",
                        poIndex, expectedItemCode, expectedDescription, actualItemCode, actualDescription);

                return String.format(
                        "{\"written\": false, \"poIndex\": %d, \"actualItemCode\": \"%s\", \"actualDescription\": \"%s\"}",
                        poIndex, escapeJson(actualItemCode), escapeJson(actualDescription));
            }

            // Valid match - write to tracker
            tracker.getUsedPoIndices().add(poIndex);
            tracker.getMatches().add(new PoMatch(docIndex, poIndex, "validated"));
            saveTracker(tracker);

            log.info("WRITE ACCEPTED ✓ docIndex={} → poIndex={} written to _po_matches.json", docIndex, poIndex);
            return String.format("{\"written\": true, \"docIndex\": %d, \"poIndex\": %d}", docIndex, poIndex);

        } catch (Exception e) {
            log.error("Failed to validate and write PO index {}", poIndex, e);
            return String.format("{\"written\": false, \"error\": \"%s\"}", escapeJson(e.getMessage()));
        }
    }

    /**
     * Escape special characters for JSON string values.
     */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    /**
     * Validate that the item at PO index matches the expected item code and description.
     * This is INDEX VALIDATION - checking if the AI got the correct array index.
     * Uses the validation model to handle minor text variations (Ø vs 0, spacing, case).
     */
    private boolean compareProductWithValidationModel(
            String expectedCode, String expectedDesc,
            String actualCode, String actualDesc) {

        // Quick check: if both match exactly (case-insensitive), index is correct
        if (expectedCode.equalsIgnoreCase(actualCode) && expectedDesc.equalsIgnoreCase(actualDesc)) {
            return true;
        }

        // Item codes MUST match for the index to be correct
        if (!expectedCode.equalsIgnoreCase(actualCode)) {
            log.debug("Index validation FAILED: item codes differ ('{}' vs '{}')", expectedCode, actualCode);
            return false;
        }

        // Same item code - use validation model to check if descriptions match
        // (handles minor variations like Ø vs 0, extra spaces, punctuation)
        log.info("INDEX VALIDATION: Calling validation model (same code '{}', checking descriptions)", expectedCode);
        try {
            String prompt = String.format("""
                Do these two descriptions refer to the SAME LINE ITEM?
                (Same item code: %s)

                This is INDEX VALIDATION - checking if the AI got the correct array index.
                Allow minor text variations (Ø vs 0, spacing, case, punctuation).
                But different products = false (e.g., "MONOBASE" vs "RISER" = false).

                Answer ONLY "true" or "false".

                Expected: %s
                Actual: %s

                Same item (true/false):""",
                    expectedCode, expectedDesc, actualDesc);

            String response = validationChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content()
                    .trim()
                    .toLowerCase();

            boolean result = response.contains("true");
            log.info("INDEX VALIDATION: Model response='{}' -> result={}", response, result);
            return result;
        } catch (Exception e) {
            log.warn("Validation model call failed, falling back to exact match", e);
            return expectedDesc.equalsIgnoreCase(actualDesc);
        }
    }

    /**
     * Load the PO match tracker from the working directory.
     */
    private PoMatchTracker loadTracker() {
        Path trackerPath = workingDirectory.get().resolve(TRACKER_FILE);
        try {
            if (vfs.exists(trackerPath)) {
                String json = vfs.readFile(trackerPath);
                return MAPPER.readValue(json, PoMatchTracker.class);
            }
        } catch (Exception e) {
            log.warn("Could not load tracker, starting fresh", e);
        }
        return new PoMatchTracker();
    }

    /**
     * Save the PO match tracker to the working directory.
     */
    private void saveTracker(PoMatchTracker tracker) {
        Path trackerPath = workingDirectory.get().resolve(TRACKER_FILE);
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tracker);
            vfs.writeFile(trackerPath, json);
        } catch (Exception e) {
            log.error("Failed to save tracker", e);
        }
    }

    /**
     * Tracker state for PO line matching.
     */
    @Data
    public static class PoMatchTracker {
        private List<Integer> usedPoIndices = new ArrayList<>();
        private List<PoMatch> matches = new ArrayList<>();
    }

    /**
     * A single PO match record.
     */
    @Data
    public static class PoMatch {
        private int docIndex;
        private int poIndex;
        private String matchedBy;

        public PoMatch() {}

        public PoMatch(int docIndex, int poIndex, String matchedBy) {
            this.docIndex = docIndex;
            this.poIndex = poIndex;
            this.matchedBy = matchedBy;
        }
    }

    /**
     * Resolve a relative path against the working directory.
     *
     * @param relativePath The relative path
     * @return Absolute path within the working directory
     * @throws IllegalStateException if working directory is not set
     */
    private Path resolve(String relativePath) {
        if (workingDirectory.get() == null) {
            throw new IllegalStateException("Working directory not set. Call setWorkingDirectory() first.");
        }
        return workingDirectory.get().resolve(relativePath).normalize();
    }
}

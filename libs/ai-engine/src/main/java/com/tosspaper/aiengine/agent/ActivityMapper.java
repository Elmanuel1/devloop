package com.tosspaper.aiengine.agent;

import com.tosspaper.models.domain.ComparisonContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps internal tool calls to user-friendly activity messages.
 * Hides file operations and shows natural analysis flow to the user.
 *
 * <p>Example mappings:
 * <ul>
 *   <li>readFile("po.json") -> "Reviewing purchase order PO-2024-001..."</li>
 *   <li>readFile("invoice/xxx.json") -> "Analyzing invoice..."</li>
 *   <li>writeFile("_results.json", ...) -> "Saving analysis results..."</li>
 *   <li>grep(...) -> "Searching for..."</li>
 * </ul>
 */
@Slf4j
@Component
public class ActivityMapper {

    /**
     * Map a tool call to a user-friendly activity message.
     *
     * @param toolName   Name of the tool being called
     * @param arguments  Tool arguments as a map
     * @param context    Current comparison context (for extracting PO number, etc.)
     * @return Activity event for SSE streaming
     */
    public ComparisonEvent.Activity map(String toolName, Map<String, Object> arguments, ComparisonContext context) {
        return switch (toolName) {
            case "readFile", "read_file" -> mapReadFile(arguments, context);
            case "readFileChunk", "read_file_chunk" -> mapReadFileChunk(arguments, context);
            case "writeFile", "write_file" -> mapWriteFile(arguments, context);
            case "listDirectory", "list_directory" -> mapListDirectory(arguments);
            case "grep" -> mapGrep(arguments);
            default -> {
                log.debug("Unknown tool: {}", toolName);
                yield ComparisonEvent.Activity.processing();
            }
        };
    }

    private ComparisonEvent.Activity mapReadFile(Map<String, Object> args, ComparisonContext context) {
        String path = getStringArg(args, "path", "");

        if (path.contains("po.json") || path.endsWith("/po.json")) {
            String poNumber = context != null && context.extractionTask() != null
                    ? context.extractionTask().getPoNumber()
                    : "...";
            return ComparisonEvent.Activity.reviewing("purchase order", poNumber);
        }

        if (path.contains("invoice/") || path.contains("invoice\\")) {
            return ComparisonEvent.Activity.analyzing("invoice");
        }

        if (path.contains("delivery_slip/") || path.contains("delivery_slip\\")) {
            return ComparisonEvent.Activity.analyzing("delivery slip");
        }

        if (path.contains("delivery_note/") || path.contains("delivery_note\\")) {
            return ComparisonEvent.Activity.analyzing("delivery note");
        }

        if (path.contains("_results.json")) {
            return new ComparisonEvent.Activity("📊", "Reading analysis results...");
        }

        if (path.contains("schema") || path.endsWith(".schema.json")) {
            return new ComparisonEvent.Activity("📋", "Loading comparison schema...");
        }

        // Generic file read
        String fileName = extractFileName(path);
        return new ComparisonEvent.Activity("📄", String.format("Reading %s...", fileName));
    }

    private ComparisonEvent.Activity mapReadFileChunk(Map<String, Object> args, ComparisonContext context) {
        String path = getStringArg(args, "path", "");
        long offset = getLongArg(args, "offset", 0);

        // For chunk reads, indicate we're continuing to read
        if (offset > 0) {
            return new ComparisonEvent.Activity("📄", "Reading more content...");
        }

        // First chunk - same as full read
        return mapReadFile(args, context);
    }

    private ComparisonEvent.Activity mapWriteFile(Map<String, Object> args, ComparisonContext context) {
        String path = getStringArg(args, "path", "");

        if (path.contains("_results.json")) {
            return ComparisonEvent.Activity.saving("comparison results");
        }

        if (path.contains("_vendor_analysis")) {
            return ComparisonEvent.Activity.saving("vendor analysis");
        }

        if (path.contains("_line_items")) {
            return ComparisonEvent.Activity.saving("line item matches");
        }

        if (path.contains("_analysis") || path.contains("analysis")) {
            return ComparisonEvent.Activity.saving("analysis");
        }

        // Generic write
        String fileName = extractFileName(path);
        return new ComparisonEvent.Activity("💾", String.format("Writing %s...", fileName));
    }

    private ComparisonEvent.Activity mapListDirectory(Map<String, Object> args) {
        String path = getStringArg(args, "path", "");

        if (path.isBlank() || ".".equals(path)) {
            return new ComparisonEvent.Activity("📁", "Listing working directory...");
        }

        return new ComparisonEvent.Activity("📁", String.format("Listing %s...", path));
    }

    private ComparisonEvent.Activity mapGrep(Map<String, Object> args) {
        String pattern = getStringArg(args, "pattern", "");
        String path = getStringArg(args, "path", "");

        if (pattern.isBlank()) {
            return ComparisonEvent.Activity.searching("documents");
        }

        // Truncate long patterns
        if (pattern.length() > 30) {
            pattern = pattern.substring(0, 27) + "...";
        }

        return new ComparisonEvent.Activity("🔍", String.format("Searching for '%s'...", pattern));
    }

    /**
     * Extract just the filename from a path.
     */
    private String extractFileName(String path) {
        if (path == null || path.isBlank()) {
            return "file";
        }

        // Handle both forward and back slashes
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    private String getStringArg(Map<String, Object> args, String key, String defaultValue) {
        if (args == null) return defaultValue;
        Object value = args.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private long getLongArg(Map<String, Object> args, String key, long defaultValue) {
        if (args == null) return defaultValue;
        Object value = args.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}

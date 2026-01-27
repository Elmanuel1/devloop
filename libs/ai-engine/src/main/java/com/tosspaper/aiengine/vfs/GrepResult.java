package com.tosspaper.aiengine.vfs;

import java.util.List;

/**
 * Result of a grep (text search) operation.
 *
 * @param file         Relative path to the file containing the match
 * @param lineNumber   Line number where the match was found (1-based)
 * @param matchContent The line containing the match
 * @param context      Context lines around the match (before and after)
 */
public record GrepResult(
    String file,
    int lineNumber,
    String matchContent,
    List<String> context
) {
    /**
     * Create a GrepResult without context.
     */
    public static GrepResult of(String file, int lineNumber, String matchContent) {
        return new GrepResult(file, lineNumber, matchContent, List.of());
    }

    /**
     * Create a GrepResult with context.
     */
    public static GrepResult withContext(String file, int lineNumber, String matchContent, List<String> context) {
        return new GrepResult(file, lineNumber, matchContent, context);
    }
}

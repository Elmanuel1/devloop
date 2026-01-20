package com.tosspaper.models.utils;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Utility class for PostgreSQL full-text search operations.
 * Provides methods for building tsquery expressions that work with tsvector columns.
 */
public final class PostgresSearchUtils {

    private PostgresSearchUtils() {
        // Prevent instantiation
    }

    /**
     * Build a PostgreSQL tsquery prefix query string from a search term.
     * 
     * <p>This method:
     * <ul>
     *   <li>Splits the search term by whitespace into individual words</li>
     *   <li>Escapes PostgreSQL tsquery special characters: & | ! ( ) : < ></li>
     *   <li>Preserves hyphens, underscores, and other common characters</li>
     *   <li>Adds :* suffix for prefix matching (e.g., "app" matches "application")</li>
     *   <li>Filters out words shorter than 3 characters (to avoid ":*" only)</li>
     *   <li>Joins words with " & " for AND logic</li>
     * </ul>
     * 
     * <p>Example:
     * <pre>
     * buildPrefixQuery("ABC-123 test")  // returns "ABC-123:* & test:*"
     * buildPrefixQuery("hello world")   // returns "hello:* & world:*"
     * buildPrefixQuery("a b")           // returns "" (words too short)
     * </pre>
     * 
     * <p>Usage with JOOQ:
     * <pre>
     * String query = PostgresSearchUtils.buildPrefixQuery(searchTerm);
     * if (!query.isEmpty()) {
     *     conditions.add(DSL.condition(
     *         "search_vector @@ to_tsquery('english', ?)",
     *         query
     *     ));
     * }
     * </pre>
     * 
     * @param searchTerm the raw search term from user input
     * @return the formatted tsquery string, or empty string if no valid words
     */
    public static String buildPrefixQuery(String searchTerm) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return "";
        }
        
        return Arrays.stream(searchTerm.trim().split("\\s+"))
            .map(word -> {
                // Escape PostgreSQL tsquery special characters: & | ! ( ) : < >
                // Preserve hyphens, underscores, numbers, letters, and other common chars
                String escaped = word.replaceAll("([&|!()::<>])", "\\\\$1");
                return escaped + ":*";
            })
            .filter(word -> word.length() > 2) // Filter out ":*" only (words must be at least 1 char + ":*")
            .collect(Collectors.joining(" & "));
    }
}


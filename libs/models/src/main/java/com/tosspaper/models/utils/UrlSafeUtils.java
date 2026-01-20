package com.tosspaper.models.utils;

/**
 * Utility class for making strings URL-safe.
 */
public class UrlSafeUtils {

    private UrlSafeUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Makes a string URL-safe by replacing problematic characters.
     * Preserves forward slashes for path structure, @ symbols in email addresses,
     * and dots in filenames/emails. Replaces hyphens and dashes with underscores
     * for S3 signature compatibility.
     * 
     * @param input the input string
     * @return URL-safe string
     */
    public static String makeUrlSafe(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        
        return input
            .replace(" ", "_")
            .replace("-", "_")  // Regular hyphen
            .replace("\"", "_")
            .replace("<", "_")
            .replace(">", "_")
            .replace("|", "_")
            .replace("\\", "_")
            .replace(":", "_")
            .replace("*", "_")
            .replace("?", "_")
            .replace("..", "_")
            // Normalize Unicode minus signs to underscores (for S3 signature compatibility)
            .replace("\u2212", "_")  // MINUS SIGN (U+2212)
            .replace("\u2010", "_")  // HYPHEN (U+2010)
            .replace("\u2011", "_")  // NON-BREAKING HYPHEN (U+2011)
            .replace("\u2012", "_")  // FIGURE DASH (U+2012)
            .replace("\u2013", "_")  // EN DASH (U+2013)
            .replace("\u2014", "_")  // EM DASH (U+2014)
            .replace("\u2015", "_")  // HORIZONTAL BAR (U+2015)
            .trim();
    }
}


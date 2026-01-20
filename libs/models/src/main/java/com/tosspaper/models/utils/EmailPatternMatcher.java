package com.tosspaper.models.utils;

import java.util.regex.Pattern;

/**
 * Utility for testing email addresses against sender approval/block patterns
 */
public class EmailPatternMatcher {

    /**
     * Test if an email address matches a regex pattern
     *
     * @param email   The email address to test
     * @param pattern The regex pattern (e.g., "^.*@acme\\.com$")
     * @return true if the email matches the pattern
     */
    public static boolean matches(String email, String pattern) {
        if (email == null || pattern == null) {
            return false;
        }
        return Pattern.matches(pattern, email);
    }

    /**
     * Generate regex pattern for exact email match
     *
     * @param email The email address
     * @return Regex pattern that matches only this exact email
     */
    public static String createEmailPattern(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email cannot be null");
        }
        return "^" + email.replace(".", "\\.") + "$";
    }

    /**
     * Generate regex pattern for domain match
     *
     * @param domain The domain (e.g., "acme.com")
     * @return Regex pattern that matches any email from this domain
     */
    public static String createDomainPattern(String domain) {
        if (domain == null) {
            throw new IllegalArgumentException("Domain cannot be null");
        }
        return "^.*@" + domain.replace(".", "\\.") + "$";
    }

    /**
     * Extract domain from email address
     *
     * @param email The email address
     * @return The domain part (everything after @)
     */
    public static String extractDomain(String email) {
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("Invalid email format");
        }
        return email.substring(email.indexOf('@') + 1);
    }
}


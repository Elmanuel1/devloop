package com.tosspaper.models.utils;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import java.util.Set;

/**
 * Utility class for email address operations.
 */
public class EmailUtils {
    
    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    
    // Wrapper class for email validation
    private static class EmailWrapper {
        @Email(message = "Invalid email format")
        private final String email;
        
        EmailWrapper(String email) {
            this.email = email;
        }
    }

    private EmailUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Cleans an email address by extracting it from a string that may contain a display name.
     * Handles formats like:
     * - "Name" <email@domain.com> -> email@domain.com
     * - Name <email@domain.com> -> email@domain.com
     * - <email@domain.com> -> email@domain.com
     * - email@domain.com -> email@domain.com
     * 
     * @param emailString the email string (may include display name)
     * @return the cleaned email address
     */
    public static String cleanEmailAddress(String emailString) {
        if (emailString == null || emailString.isBlank()) {
            return emailString;
        }
        
        String trimmed = emailString.trim();
        
        // Find the last occurrence of < and > (in case there are multiple)
        int startBracket = trimmed.lastIndexOf('<');
        int endBracket = trimmed.indexOf('>', startBracket);
        
        if (startBracket != -1 && endBracket != -1 && startBracket < endBracket) {
            // Extract email between < and >
            String extracted = trimmed.substring(startBracket + 1, endBracket).trim();
            // Return extracted email if it's not empty, otherwise fall back to original
            return extracted.isEmpty() ? trimmed : extracted;
        }
        
        // No angle brackets, return as-is (already just an email)
        return trimmed;
    }
    
    /**
     * Checks if a string is a valid email address using Jakarta Validation.
     * 
     * @param email the string to check
     * @return true if it's a valid email format, false otherwise
     */
    public static boolean isValidEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        
        EmailWrapper wrapper = new EmailWrapper(email.trim());
        Set<ConstraintViolation<EmailWrapper>> violations = validator.validate(wrapper);
        return violations.isEmpty();
    }
    
    /**
     * Checks if a string is a valid domain format (e.g., "example.com").
     * Must contain at least one dot and no @ symbol.
     * 
     * @param domain the string to check
     * @return true if it's a valid domain format, false otherwise
     */
    public static boolean isValidDomain(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        
        String trimmed = domain.trim();
        
        // Must not contain @
        if (trimmed.contains("@")) {
            return false;
        }
        
        // Must contain at least one dot
        if (!trimmed.contains(".")) {
            return false;
        }
        
        // Must have text before and after the dot
        int dotIndex = trimmed.indexOf('.');
        if (dotIndex == 0 || dotIndex == trimmed.length() - 1) {
            return false;
        }
        
        // Basic check: no spaces, no special chars except dot and hyphen
        return trimmed.matches("^[a-zA-Z0-9.-]+$");
    }
}


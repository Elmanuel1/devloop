package com.tosspaper.models.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the result of file validation with a list of violations.
 */
public class ValidationResult {
    
    private final List<String> violations;
    private final boolean valid;
    
    private ValidationResult(List<String> violations) {
        this.violations = violations != null ? new ArrayList<>(violations) : new ArrayList<>();
        this.valid = this.violations.isEmpty();
    }
    
    /**
     * Creates a valid validation result (no violations).
     */
    public static ValidationResult valid() {
        return new ValidationResult(Collections.emptyList());
    }
    
    /**
     * Creates an invalid validation result with violations.
     */
    public static ValidationResult invalid(String... violations) {
        return new ValidationResult(List.of(violations));
    }
    
    /**
     * Creates an invalid validation result with a list of violations.
     */
    public static ValidationResult invalid(List<String> violations) {
        return new ValidationResult(violations);
    }
    
    /**
     * Checks if the validation passed (no violations).
     */
    public boolean isValid() {
        return valid;
    }
    
    /**
     * Checks if the validation failed (has violations).
     */
    public boolean isInvalid() {
        return !valid;
    }
    
    /**
     * Gets the list of violations.
     */
    public List<String> getViolations() {
        return Collections.unmodifiableList(violations);
    }
    
    /**
     * Gets the combined violation message.
     */
    public String getViolationMessage() {
        if (violations.isEmpty()) {
            return "";
        }
        return String.join("; ", violations);
    }
    
    /**
     * Adds a violation to this result.
     */
    public ValidationResult addViolation(String violation) {
        List<String> newViolations = new ArrayList<>(this.violations);
        newViolations.add(violation);
        return new ValidationResult(newViolations);
    }
    
    /**
     * Combines this result with another validation result.
     */
    public ValidationResult combine(ValidationResult other) {
        List<String> combinedViolations = new ArrayList<>(this.violations);
        combinedViolations.addAll(other.violations);
        return new ValidationResult(combinedViolations);
    }
}

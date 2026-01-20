package com.tosspaper.models.validation;

import com.tosspaper.models.domain.FileObject;

/**
 * Interface for file validation in the Chain of Responsibility pattern.
 */
public interface FileValidation {
    
    /**
     * Validates a file object.
     * 
     * @param fileObject the file to validate
     * @return ValidationResult with violations if any, or valid result if validation passes
     */
    ValidationResult validate(FileObject fileObject);
}

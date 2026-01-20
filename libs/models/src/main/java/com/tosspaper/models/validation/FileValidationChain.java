package com.tosspaper.models.validation;

import com.tosspaper.models.domain.FileObject;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chain of Responsibility implementation for file validation.
 * Executes all validations and collects all violations.
 */
@Component
@RequiredArgsConstructor
public class FileValidationChain {
    
    private final List<FileValidation> validations;
    
    /**
     * Validates a file object using all registered validations.
     * 
     * @param fileObject the file to validate
     * @return ValidationResult with all violations collected from all validations
     */
    public ValidationResult validate(FileObject fileObject) {
        ValidationResult combinedResult = ValidationResult.valid();
        
        for (FileValidation validation : validations) {
            ValidationResult result = validation.validate(fileObject);
            combinedResult = combinedResult.combine(result);
        }
        
        return combinedResult;
    }
}

package com.tosspaper.models.validation;

import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.properties.FileProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates file name for length and character restrictions.
 */
@Component
@RequiredArgsConstructor
public class FileNameValidation implements FileValidation {
    
    private final FileProperties fileProperties;
    
    @Override
    public ValidationResult validate(FileObject fileObject) {
        String fileName = fileObject.getFileName();
        List<String> violations = new ArrayList<>();
        
        if (fileName == null || fileName.isBlank()) {
            violations.add("File name is required");
            return ValidationResult.invalid(violations);
        }
        
        // Check length
        if (fileName.length() > this.fileProperties.getMaxFilenameLength()) {
            violations.add(String.format("File name length %d exceeds maximum allowed length %d", 
                fileName.length(), this.fileProperties.getMaxFilenameLength()));
        }
        
        return violations.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(violations);
    }
}

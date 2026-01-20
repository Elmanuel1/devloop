package com.tosspaper.models.validation;

import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.properties.FileProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates file type against allowed content types.
 */
@Component
@RequiredArgsConstructor
public class FileTypeValidation implements FileValidation {
    
    private final FileProperties fileProperties;
    
    @Override
    public ValidationResult validate(FileObject fileObject) {
        String contentType = fileObject.getContentType();
        
        if (contentType == null || contentType.isBlank()) {
            return ValidationResult.invalid("File content type is required");
        }
        
        if (!fileProperties.getAllowedContentTypes().contains(contentType.toLowerCase())) {
            return ValidationResult.invalid(
                String.format("File type '%s' is not allowed. Allowed types: %s", 
                    contentType, String.join(", ", fileProperties.getAllowedContentTypes())));
        }
        
        return ValidationResult.valid();
    }
}

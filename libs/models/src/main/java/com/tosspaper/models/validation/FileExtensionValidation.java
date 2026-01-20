package com.tosspaper.models.validation;

import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.properties.FileProperties;
import com.tosspaper.models.utils.FileExtensionUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Validates file extension against allowed extensions.
 */
@Component
@RequiredArgsConstructor
public class FileExtensionValidation implements FileValidation {
    
    private final FileProperties fileProperties;
    
    @Override
    public ValidationResult validate(FileObject fileObject) {
        String fileName = fileObject.getFileName();
        
        if (fileName == null || fileName.isBlank()) {
            return ValidationResult.valid(); // Let FileNameValidation handle this
        }
        
        String fileExtension = FileExtensionUtils.getFileExtension(fileName);
        if (fileExtension.isEmpty()) {
            return ValidationResult.invalid("File must have an extension");
        }
        
        Set<String> allowedExtensions = fileProperties.getAllowedFileExtensions();
        if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(fileExtension)) {
            return ValidationResult.invalid(
                String.format("File extension '%s' is not allowed. Allowed extensions: %s", 
                    fileExtension, String.join(", ", allowedExtensions)));
        }
        
        return ValidationResult.valid();
    }
}

package com.tosspaper.models.validation;

import com.tosspaper.models.domain.FileObject;
import org.springframework.stereotype.Component;

/**
 * Validates file magic bytes against the declared content type.
 * Uses the first bytes of the file content to verify the file format matches
 * what was declared. Skips validation if the file has no content (e.g., metadata-only).
 */
@Component
public class MagicByteValidation implements FileValidation {

    @Override
    public ValidationResult validate(FileObject fileObject) {
        byte[] content = fileObject.getContent();
        String contentType = fileObject.getContentType();

        if (content == null || content.length == 0) {
            return ValidationResult.valid();
        }

        if (contentType == null || contentType.isBlank()) {
            return ValidationResult.valid();
        }

        boolean valid = MagicByteValidator.validate(content, contentType);
        if (!valid) {
            return ValidationResult.invalid(
                    "File magic bytes do not match declared content type '%s'".formatted(contentType));
        }

        return ValidationResult.valid();
    }
}

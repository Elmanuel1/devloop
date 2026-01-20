package com.tosspaper.models.validation;

import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.properties.FileProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates file size against minimum and maximum allowed sizes.
 * Filters out signature icons (too small) and oversized files.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileSizeValidation implements FileValidation {

    private final FileProperties fileProperties;

    @Override
    public ValidationResult validate(FileObject fileObject) {
        long maxSize = fileProperties.getMaxFileSizeBytes();
        long minSize = fileProperties.getMinFileSizeBytes();
        long actualSize = fileObject.getContent().length;

        if (actualSize < minSize) {
            String violation = String.format(
                "File size %d bytes is below minimum %d bytes - likely a signature icon",
                actualSize, minSize);
            log.debug("File validation failed for '{}': {}", fileObject.getFileName(), violation);
            return ValidationResult.invalid(violation);
        }

        if (actualSize > maxSize) {
            String violation = String.format(
                "File size %d bytes exceeds maximum allowed size %d bytes",
                actualSize, maxSize);
            log.debug("File validation failed for '{}': {}", fileObject.getFileName(), violation);
            return ValidationResult.invalid(violation);
        }

        log.trace("File size validation passed for '{}': {} bytes (min: {}, max: {})",
            fileObject.getFileName(), actualSize, minSize, maxSize);
        return ValidationResult.valid();
    }
}

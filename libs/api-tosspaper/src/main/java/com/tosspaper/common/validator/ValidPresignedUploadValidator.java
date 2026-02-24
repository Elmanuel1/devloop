package com.tosspaper.common.validator;

import com.tosspaper.precon.TenderFileProperties;
import com.tosspaper.precon.generated.model.ContentType;
import com.tosspaper.precon.generated.model.PresignedUrlRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

import java.util.Set;

@RequiredArgsConstructor
public class ValidPresignedUploadValidator implements ConstraintValidator<ValidPresignedUpload, PresignedUrlRequest> {

    private final TenderFileProperties fileProperties;

    @Override
    public boolean isValid(PresignedUrlRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }

        boolean valid = true;
        context.disableDefaultConstraintViolation();

        // Allowed content type
        ContentType contentType = request.getContentType();
        if (contentType != null && !fileProperties.getAllowedContentTypes().contains(contentType)) {
            context.buildConstraintViolationWithTemplate(
                    "Content type '" + contentType.getValue() + "' is not allowed")
                    .addPropertyNode("contentType")
                    .addConstraintViolation();
            valid = false;
        }

        // Max file size
        Integer fileSize = request.getFileSize();
        if (fileSize != null && fileSize > fileProperties.getMaxFileSizeBytes()) {
            long maxMb = fileProperties.getMaxFileSizeBytes() / (1024 * 1024);
            context.buildConstraintViolationWithTemplate(
                    "File size must not exceed " + maxMb + " MB")
                    .addPropertyNode("fileSize")
                    .addConstraintViolation();
            valid = false;
        }

        // Extension-to-content-type match
        String fileName = request.getFileName();
        if (fileName != null && contentType != null) {
            if (!fileName.contains(".") || fileName.endsWith(".")) {
                context.buildConstraintViolationWithTemplate(
                        "File name must have a valid extension")
                        .addPropertyNode("fileName")
                        .addConstraintViolation();
                valid = false;
            } else {
                String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
                Set<String> allowedExtensions = fileProperties.getContentTypeExtensions().get(contentType);
                if (allowedExtensions == null || !allowedExtensions.contains(extension)) {
                    context.buildConstraintViolationWithTemplate(
                            "File extension '." + extension + "' does not match content type '" + contentType.getValue() + "'")
                            .addPropertyNode("fileName")
                            .addConstraintViolation();
                    valid = false;
                }
            }
        }

        return valid;
    }
}

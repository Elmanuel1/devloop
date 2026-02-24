package com.tosspaper.common.validator;

import com.tosspaper.models.properties.FileProperties;
import com.tosspaper.precon.generated.model.ContentType;
import com.tosspaper.precon.generated.model.PresignedUrlRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ValidPresignedUploadValidator implements ConstraintValidator<ValidPresignedUpload, PresignedUrlRequest> {

    private final FileProperties fileProperties;

    @Override
    public boolean isValid(PresignedUrlRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }

        boolean valid = true;
        context.disableDefaultConstraintViolation();

        // Allowed content type
        ContentType contentType = request.getContentType();
        if (contentType != null && !fileProperties.getAllowedContentTypes().contains(contentType.getValue())) {
            context.buildConstraintViolationWithTemplate(
                    "Content type '" + contentType.getValue() + "' is not allowed")
                    .addPropertyNode("contentType")
                    .addConstraintViolation();
            valid = false;
        }

        // File extension
        String fileName = request.getFileName();
        if (fileName != null) {
            if (!fileName.contains(".") || fileName.endsWith(".")) {
                context.buildConstraintViolationWithTemplate(
                        "File name must have a valid extension")
                        .addPropertyNode("fileName")
                        .addConstraintViolation();
                valid = false;
            } else {
                String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
                if (!fileProperties.getAllowedFileExtensions().contains(extension)) {
                    context.buildConstraintViolationWithTemplate(
                            "File extension '." + extension + "' is not allowed")
                            .addPropertyNode("fileName")
                            .addConstraintViolation();
                    valid = false;
                }
            }
        }

        return valid;
    }
}

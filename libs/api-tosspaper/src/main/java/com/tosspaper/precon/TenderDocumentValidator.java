package com.tosspaper.precon;

import com.tosspaper.common.BadRequestException;
import com.tosspaper.precon.generated.model.ContentType;
import com.tosspaper.precon.generated.model.PresignedUrlRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Custom validations for tender document uploads that cannot be expressed
 * as Jakarta Bean Validation annotations on the DTO.
 *
 * <p>Standard checks (null, blank, size range, content type enum) are handled
 * by Jakarta annotations on {@link PresignedUrlRequest}.
 */
@Component
@RequiredArgsConstructor
public class TenderDocumentValidator {

    private final TenderFileProperties fileProperties;

    /**
     * Validates custom business rules for a presigned URL request.
     *
     * @param request the presigned URL request to validate
     * @throws BadRequestException if validation fails
     */
    public void validate(PresignedUrlRequest request) {
        validateAllowedContentType(request.getContentType());
        validateMaxFileSize(request.getFileSize());
        validateFileExtension(request.getFileName(), request.getContentType());
    }

    private void validateAllowedContentType(ContentType contentType) {
        if (!fileProperties.getAllowedContentTypes().contains(contentType)) {
            throw new BadRequestException("api.tenderDocument.contentTypeNotAllowed",
                    "Content type '" + contentType.getValue() + "' is not allowed. Allowed types: " +
                            fileProperties.getAllowedContentTypes());
        }
    }

    private void validateMaxFileSize(Integer fileSize) {
        if (fileSize > fileProperties.getMaxFileSizeBytes()) {
            long maxMb = fileProperties.getMaxFileSizeBytes() / (1024 * 1024);
            throw new BadRequestException("api.tenderDocument.fileSizeTooLarge",
                    "File size must not exceed " + maxMb + " MB");
        }
    }

    private void validateFileExtension(String fileName, ContentType contentType) {
        if (!fileName.contains(".") || fileName.endsWith(".")) {
            throw new BadRequestException("api.tenderDocument.fileExtensionRequired",
                    "File name must have a valid extension");
        }

        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        Set<String> allowedExtensions = fileProperties.getContentTypeExtensions().get(contentType);

        if (allowedExtensions == null || !allowedExtensions.contains(extension)) {
            throw new BadRequestException("api.tenderDocument.fileExtensionMismatch",
                    "File extension '." + extension + "' does not match content type '" + contentType.getValue() + "'");
        }
    }
}

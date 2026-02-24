package com.tosspaper.precon;

import com.tosspaper.common.BadRequestException;
import com.tosspaper.precon.generated.model.PresignedUrlRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Validates tender document upload requests against configured file properties.
 */
@Component
@RequiredArgsConstructor
public class TenderDocumentValidator {

    private final TenderFileProperties fileProperties;

    /**
     * Validates a presigned URL request for file upload.
     *
     * @param request the presigned URL request to validate
     * @throws BadRequestException if validation fails
     */
    public void validate(PresignedUrlRequest request) {
        validateFileName(request.getFileName());
        validateContentType(request.getContentType().getValue());
        validateFileSize(request.getFileSize());
        validateFileExtension(request.getFileName(), request.getContentType().getValue());
    }

    private void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BadRequestException("api.tenderDocument.fileNameRequired",
                    "File name is required");
        }
        if (fileName.length() > fileProperties.getMaxFilenameLength()) {
            throw new BadRequestException("api.tenderDocument.fileNameTooLong",
                    "File name must not exceed " + fileProperties.getMaxFilenameLength() + " characters");
        }
    }

    private void validateContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new BadRequestException("api.tenderDocument.contentTypeRequired",
                    "Content type is required");
        }
        if (!fileProperties.getAllowedContentTypes().contains(contentType)) {
            throw new BadRequestException("api.tenderDocument.contentTypeNotAllowed",
                    "Content type '" + contentType + "' is not allowed. Allowed types: " +
                            String.join(", ", fileProperties.getAllowedContentTypes()));
        }
    }

    private void validateFileSize(Integer fileSize) {
        if (fileSize == null || fileSize <= 0) {
            throw new BadRequestException("api.tenderDocument.fileSizeInvalid",
                    "File size must be a positive number");
        }
        if (fileSize > fileProperties.getMaxFileSizeBytes()) {
            long maxMb = fileProperties.getMaxFileSizeBytes() / (1024 * 1024);
            throw new BadRequestException("api.tenderDocument.fileSizeTooLarge",
                    "File size must not exceed " + maxMb + " MB");
        }
    }

    private void validateFileExtension(String fileName, String contentType) {
        if (!fileName.contains(".") || fileName.endsWith(".")) {
            throw new BadRequestException("api.tenderDocument.fileExtensionRequired",
                    "File name must have a valid extension");
        }

        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        Set<String> allowedExtensions = fileProperties.getContentTypeExtensions().get(contentType);

        if (allowedExtensions == null || !allowedExtensions.contains(extension)) {
            throw new BadRequestException("api.tenderDocument.fileExtensionMismatch",
                    "File extension '." + extension + "' does not match content type '" + contentType + "'");
        }
    }
}

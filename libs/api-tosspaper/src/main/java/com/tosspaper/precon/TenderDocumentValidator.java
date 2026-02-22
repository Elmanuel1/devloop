package com.tosspaper.precon;

import com.tosspaper.common.BadRequestException;
import com.tosspaper.generated.model.PresignedUrlRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TenderDocumentValidator {

    private final TenderFileProperties fileProperties;

    /**
     * Validates the presigned URL request metadata.
     * Throws BadRequestException with appropriate error code on first violation.
     */
    public void validate(PresignedUrlRequest request) {
        validateFileName(request.getFileName());
        validateContentType(request.getContentType() != null ? request.getContentType().getValue() : null);
        validateFileSize(request.getFileSize());
        validateDoubleExtension(request.getFileName());
        validateExtensionMatch(request.getFileName(), request.getContentType() != null ? request.getContentType().getValue() : null);
    }

    private void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BadRequestException("api.validation.fileNameRequired", "File name is required");
        }
        if (fileName.length() > fileProperties.getMaxFileNameLength()) {
            throw new BadRequestException("api.validation.invalidFileName",
                    "File name must not exceed " + fileProperties.getMaxFileNameLength() + " characters");
        }
    }

    private void validateContentType(String contentType) {
        if (contentType == null || !fileProperties.getAllowedContentTypes().contains(contentType)) {
            throw new BadRequestException("api.validation.invalidContentType",
                    "Content type must be one of: " + String.join(", ", fileProperties.getAllowedContentTypes()));
        }
    }

    private void validateFileSize(Long fileSize) {
        if (fileSize == null || fileSize <= 0) {
            throw new BadRequestException("api.validation.invalidFileSize", "File size must be greater than 0");
        }
        if (fileSize > fileProperties.getMaxFileSize()) {
            throw new BadRequestException("api.validation.fileTooLarge",
                    "File size must not exceed " + fileProperties.getMaxFileSize() + " bytes (200MB)");
        }
    }

    private void validateDoubleExtension(String fileName) {
        if (fileName == null) return;
        String name = fileName.trim();
        // Count dots (excluding leading dots for hidden files)
        String relevantPart = name.startsWith(".") ? name.substring(1) : name;
        long dotCount = relevantPart.chars().filter(c -> c == '.').count();
        if (dotCount > 1) {
            throw new BadRequestException("api.validation.invalidFileName",
                    "File name must not contain double extensions (e.g., .pdf.exe)");
        }
    }

    private void validateExtensionMatch(String fileName, String contentType) {
        if (fileName == null || contentType == null) return;

        String extension = getFileExtension(fileName);
        if (extension.isEmpty()) {
            throw new BadRequestException("api.validation.fileNameMismatch",
                    "File extension does not match declared content type");
        }

        List<String> allowedExtensions = fileProperties.getContentTypeExtensions().get(contentType);
        if (allowedExtensions == null || !allowedExtensions.contains(extension.toLowerCase())) {
            throw new BadRequestException("api.validation.fileNameMismatch",
                    "File extension '" + extension + "' does not match content type '" + contentType + "'");
        }
    }

    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex <= 0 || lastDotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDotIndex + 1);
    }
}

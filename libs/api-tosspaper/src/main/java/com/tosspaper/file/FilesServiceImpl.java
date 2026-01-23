package com.tosspaper.file;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.file.exception.FileDeleteException;
import com.tosspaper.file.exception.FileUploadException;
import com.tosspaper.models.exception.ForbiddenException;
import com.tosspaper.generated.model.CreatePresignedUrlRequest;
import com.tosspaper.generated.model.DeletePresignedUrlRequest;
import com.tosspaper.models.properties.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.DeleteObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FilesServiceImpl implements FilesService {

    private final AwsProperties awsProperties;
    private final S3Presigner s3Presigner;
    private final com.tosspaper.models.service.ReceivedMessageService receivedMessageService;

    // Maximum file size: 3MB in bytes
    private static final long MAX_FILE_SIZE_BYTES = 3 * 1024 * 1024; // 3MB

    @Override
    public com.tosspaper.generated.model.PreSignedUrl createPresignedUploadUrl(Long companyId, String userId, CreatePresignedUrlRequest request) {

        // Validate file size (3MB limit)
        validateFileSize(request.getSize());

        // Validate file extension exists
        validateFileExtension(request.getKey());

        // Validate content type matches file extension
        validateContentType(request.getKey(), request.getContentType().getValue());

        // Use the key directly from the request
        String key = request.getKey();

        // Validate key belongs to company
        validateKeyOwnership(companyId, key);

        // Generate presigned URL with 2 minute duration
        com.tosspaper.file.model.PreSignedUrl internalUrl = generatePresignedUploadUrlInternal(
            userId, key, request.getContentType().getValue(), request.getSize());

        // Convert back to generated model
        return convertToGeneratedModel(internalUrl);
    }

    @Override
    public com.tosspaper.generated.model.PreSignedUrl createPresignedDeleteUrl(Long companyId, String userId, DeletePresignedUrlRequest request) {
        try {
            // Validate the key format (basic validation)
            validateDeleteKey(request.getKey());

            // Validate key belongs to company
            validateKeyOwnership(companyId, request.getKey());

            // Generate presigned URL using the provided key with 5 minute duration
            com.tosspaper.file.model.PreSignedUrl internalUrl = generatePresignedDeleteUrlInternal(
                userId, request.getKey());
            
            // Convert back to generated model
            return convertToGeneratedModel(internalUrl);
        } catch (Exception e) {
            log.error("Error generating presigned delete URL for user: {}, key: {}", userId, request.getKey(), e);
            throw new FileDeleteException("Failed to generate presigned delete URL", e);
        }
    }

    @Override
    public com.tosspaper.generated.model.PreSignedUrl createPresignedDownloadUrl(Long companyId, String key) {
        // Validate key belongs to company
        validateKeyOwnership(companyId, key);

        // Generate presigned URL using the provided key with 2 minute duration
        com.tosspaper.file.model.PreSignedUrl internalUrl = generatePresignedDownloadUrlInternal(key);

        // Convert back to generated model
        return convertToGeneratedModel(internalUrl);
    }

    private void validateKeyOwnership(Long companyId, String key) {
        if (key == null) {
            throw new ForbiddenException("ACCESS_DENIED", "You do not have permission to access this file");
        }
        receivedMessageService.getAttachmentByStorageKey(key, companyId)
            .orElseThrow(() -> new ForbiddenException("ACCESS_DENIED", "You do not have permission to access this file"));
    }

    private void validateFileSize(long fileSizeBytes) {
        if (fileSizeBytes <= 0) {
            log.error("Invalid file size: {} bytes", fileSizeBytes);
            throw new FileUploadException(ApiErrorMessages.FILE_SIZE_INVALID);
        }
        
        if (fileSizeBytes > MAX_FILE_SIZE_BYTES) {
            log.error("File size exceeds maximum limit: {} bytes (max: {} bytes)", fileSizeBytes, MAX_FILE_SIZE_BYTES);
            throw new FileUploadException(ApiErrorMessages.FILE_SIZE_TOO_LARGE.formatted(MAX_FILE_SIZE_BYTES / (1024 * 1024))); // Convert to MB for error message
        }
    }

    private void validateDeleteKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new FileDeleteException("Key cannot be null or empty");
        }
        
        // Basic validation: key should contain a filename
        if (key.endsWith("/")) {
            throw new FileDeleteException("Invalid key format");
        }
    }

    private com.tosspaper.generated.model.PreSignedUrl convertToGeneratedModel(com.tosspaper.file.model.PreSignedUrl internalModel) {
        com.tosspaper.generated.model.PreSignedUrl generatedModel = new com.tosspaper.generated.model.PreSignedUrl();
        generatedModel.setUrl(internalModel.url());
        generatedModel.setExpiration(internalModel.expiration());
        return generatedModel;
    }

    private com.tosspaper.file.model.PreSignedUrl generatePresignedUploadUrlInternal(
            String userId, String key, String contentType, Long size) {
        
        // Fixed 2 minute duration for all uploads
        Duration duration = Duration.ofMinutes(2);
        log.info("Generating presigned upload URL for key: {}, contentType: {}, size: {}, duration: {}, userId: {}", key, contentType, size, duration, userId);

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(duration)
                .putObjectRequest(c -> c.bucket(awsProperties.getBucket().getName())
                        .key(key)
                        .contentType(contentType)
                        .contentLength(size)
                        .metadata(Map.of("x-amz-meta-fileSize", String.valueOf(size),
                                "x-amz-meta-userId", userId))
                        .build())
                .build();

        var presignedRequest = s3Presigner.presignPutObject(presignRequest);
        OffsetDateTime expiresAt = presignedRequest.expiration().atOffset(java.time.ZoneOffset.UTC);

        return new com.tosspaper.file.model.PreSignedUrl(presignedRequest.url().toString(), expiresAt);
    }

    private com.tosspaper.file.model.PreSignedUrl generatePresignedDeleteUrlInternal(
            String userId, String key) {
        
        // Fixed 5 minute duration for all deletes
        Duration duration = Duration.ofMinutes(5);

        DeleteObjectPresignRequest presignRequest = DeleteObjectPresignRequest.builder()
                .signatureDuration(duration)
                .deleteObjectRequest(c -> c.bucket(awsProperties.getBucket().getName())
                        .key(key)
                        .build())
                .build();

        var presignedRequest = s3Presigner.presignDeleteObject(presignRequest);
        OffsetDateTime expiresAt = presignedRequest.expiration().atOffset(java.time.ZoneOffset.UTC);

        return new com.tosspaper.file.model.PreSignedUrl(presignedRequest.url().toString(), expiresAt);
    }

    private com.tosspaper.file.model.PreSignedUrl generatePresignedDownloadUrlInternal(String key) {

        // Fixed 2 minute duration for all downloads
        Duration duration = Duration.ofMinutes(2);
        log.info("Generating presigned download URL for key: {}, duration: {}", key, duration);

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(c -> c.bucket(awsProperties.getBucket().getName())
                        .key(key)
                        .build())
                .build();

        var presignedRequest = s3Presigner.presignGetObject(presignRequest);
        OffsetDateTime expiresAt = presignedRequest.expiration().atOffset(java.time.ZoneOffset.UTC);

        return new com.tosspaper.file.model.PreSignedUrl(presignedRequest.url().toString(), expiresAt);
    }

    private void validateFileExtension(String key) {
        if (key == null || key.isEmpty()) {
            throw new FileUploadException(ApiErrorMessages.FILE_EXTENSION_REQUIRED);
        }

        // Get filename from key (last part after /)
        String fileName = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;

        // Check if filename has an extension
        if (!fileName.contains(".") || fileName.endsWith(".")) {
            throw new FileUploadException(ApiErrorMessages.FILE_EXTENSION_REQUIRED);
        }
    }

    private void validateContentType(String key, String contentType) {
        // Get file extension from key
        String extension = key.substring(key.lastIndexOf('.') + 1).toLowerCase();

        // Map content types to expected extensions
        Map<String, String> contentTypeToExtension = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "application/pdf", "pdf"
        );

        String expectedExtension = contentTypeToExtension.get(contentType);
        if (expectedExtension == null) {
            throw new FileUploadException(ApiErrorMessages.CONTENT_TYPE_UNSUPPORTED);
        }
        if (!extension.equals(expectedExtension) &&
            !(contentType.equals("image/jpeg") && extension.equals("jpeg"))) {
            throw new FileUploadException(ApiErrorMessages.FILE_EXTENSION_MISMATCH);
        }
    }
} 
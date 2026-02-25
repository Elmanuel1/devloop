package com.tosspaper.precon;

import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import com.tosspaper.models.validation.MagicByteValidation;
import com.tosspaper.models.validation.ValidationResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Processes document uploads triggered by S3 ObjectCreated events.
 * Validates uploaded files by checking magic bytes against the declared content type,
 * then updates the document status accordingly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadProcessor {

    private static final int MAGIC_BYTE_LENGTH = 8;

    private final TenderDocumentRepository documentRepository;
    private final S3Client s3Client;
    private final MagicByteValidation magicByteValidation;

    /**
     * Processes an uploaded file by validating its magic bytes.
     *
     * @param bucket the S3 bucket name
     * @param key    the S3 object key
     * @param size   the file size in bytes
     */
    public void processUpload(String bucket, String key, long size) {
        log.info("Processing upload - bucket: {}, key: {}, size: {}", bucket, key, size);

        S3KeyMetadata metadata = parseS3Key(key);
        if (metadata == null) {
            log.warn("Could not parse S3 key: {}", key);
            return;
        }

        String documentId = metadata.getDocumentId();

        TenderDocumentsRecord document = documentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.warn("Document record not found - id: {}, may have been deleted", documentId);
            return;
        }
        documentRepository.updateStatusToProcessing(documentId);

        byte[] header;
        try {
            header = downloadHeader(bucket, key);
        } catch (NoSuchKeyException e) {
            documentRepository.updateStatusToFailed(documentId, "S3 object not found: " + key);
            log.warn("S3 object not found - bucket: {}, key: {}", bucket, key);
            return;
        }
        String contentType = document.getContentType();

        FileObject fileObject = FileObject.builder()
                .fileName(document.getFileName())
                .contentType(contentType)
                .content(header)
                .build();

        ValidationResult result = magicByteValidation.validate(fileObject);

        if (result.isInvalid()) {
            documentRepository.updateStatusToFailed(documentId, result.getViolationMessage());
            log.warn("Document validation failed - id: {}, reason: {}", documentId, result.getViolationMessage());
            return;
        }

        documentRepository.updateStatusToReady(documentId);
        log.info("Document validated successfully - id: {}, contentType: {}", documentId, contentType);
    }

    /**
     * Downloads the first few bytes of an S3 object using a range request.
     */
    @SneakyThrows
    byte[] downloadHeader(String bucket, String key) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .range("bytes=0-" + (MAGIC_BYTE_LENGTH - 1))
                .build();

        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request)) {
            return response.readAllBytes();
        }
    }

    /**
     * Parses an S3 key in the format: tenders/{companyId}/{tenderId}/{documentId}/{fileName}
     *
     * @param key the S3 object key
     * @return parsed metadata, or null if the key format is invalid
     */
    S3KeyMetadata parseS3Key(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        String[] parts = key.split("/");
        if (parts.length < 5 || !"tenders".equals(parts[0])) {
            log.warn("Invalid S3 key format: {}", key);
            return null;
        }

        S3KeyMetadata metadata = new S3KeyMetadata();
        metadata.setCompanyId(parts[1]);
        metadata.setTenderId(parts[2]);
        metadata.setDocumentId(parts[3]);
        metadata.setFileName(parts[4]);
        return metadata;
    }

    @Data
    public static class S3KeyMetadata {
        private String companyId;
        private String tenderId;
        private String documentId;
        private String fileName;
    }
}

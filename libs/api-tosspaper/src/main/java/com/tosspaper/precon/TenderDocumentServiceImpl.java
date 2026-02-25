package com.tosspaper.precon;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.exception.DocumentNotReadyException;
import com.tosspaper.precon.generated.model.DownloadUrlResponse;
import com.tosspaper.precon.generated.model.PresignedUrlRequest;
import com.tosspaper.precon.generated.model.PresignedUrlResponse;
import com.tosspaper.precon.generated.model.TenderDocument;
import com.tosspaper.precon.generated.model.Pagination;
import com.tosspaper.precon.generated.model.TenderDocumentListResponse;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import com.tosspaper.models.properties.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenderDocumentServiceImpl implements TenderDocumentService {

    private final TenderRepository tenderRepository;
    private final TenderDocumentRepository documentRepository;
    private final TenderDocumentMapper documentMapper;
    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final AwsProperties awsProperties;

    private static final Duration PRESIGNED_URL_EXPIRY = Duration.ofMinutes(5);

    @Override
    public PresignedUrlResponse getUploadPresignedUrl(Long companyId, String tenderId, PresignedUrlRequest request) {
        String companyIdStr = companyId.toString();

        // Verify tender exists and belongs to company
        verifyTenderOwnership(tenderId, companyIdStr);

        // Generate document ID and S3 key
        String documentId = UUID.randomUUID().toString();
        String sanitizedFileName = sanitizeFileName(request.getFileName());
        String s3Key = String.format("tender-uploads/%s/%s/%s/%s",
                companyIdStr, tenderId, documentId, sanitizedFileName);

        // Generate presigned PUT URL FIRST - if fails, no DB record created
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(awsProperties.getBucket().getName())
                .key(s3Key)
                .contentType(request.getContentType().getValue())
                .contentLength(request.getFileSize().longValue())
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_URL_EXPIRY)
                .putObjectRequest(putObjectRequest)
                .build();

        var presignedPutObject = s3Presigner.presignPutObject(presignRequest);
        URI presignedUrl = URI.create(presignedPutObject.url().toString());
        OffsetDateTime expiration = OffsetDateTime.now(ZoneOffset.UTC).plus(PRESIGNED_URL_EXPIRY);

        // Create document record SECOND - if fails, URL expires harmlessly
        documentRepository.insert(
                documentId, tenderId, companyIdStr,
                request.getFileName(),
                request.getContentType().getValue(),
                request.getFileSize(),
                s3Key, "uploading"
        );

        log.info("Created document record and presigned URL - documentId: {}, tenderId: {}", documentId, tenderId);

        PresignedUrlResponse response = new PresignedUrlResponse();
        response.setPresignedUrl(presignedUrl);
        response.setDocumentId(UUID.fromString(documentId));
        response.setExpiration(expiration);

        return response;
    }

    @Override
    public TenderDocumentListResponse listDocuments(Long companyId, String tenderId, String status,
                                                     int limit, String cursorCreatedAt, String cursorId) {
        String companyIdStr = companyId.toString();

        // Verify tender exists and belongs to company
        verifyTenderOwnership(tenderId, companyIdStr);

        List<TenderDocumentsRecord> records = documentRepository.findByTenderId(
                tenderId, status, limit, cursorCreatedAt, cursorId);

        // Determine if there are more results
        boolean hasMore = records.size() > limit;
        if (hasMore) {
            records = records.subList(0, limit);
        }

        List<TenderDocument> documents = documentMapper.toDtoList(records);

        // Build pagination
        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            TenderDocumentsRecord lastRecord = records.get(records.size() - 1);
            nextCursor = CursorUtils.encodeCursor(lastRecord.getCreatedAt(), lastRecord.getId());
        }

        Pagination pagination = new Pagination();
        pagination.setCursor(nextCursor);

        TenderDocumentListResponse response = new TenderDocumentListResponse();
        response.setData(documents);
        response.setPagination(pagination);

        return response;
    }

    @Override
    public void deleteDocument(Long companyId, String tenderId, String documentId) {
        String companyIdStr = companyId.toString();

        // Verify tender exists and belongs to company
        verifyTenderOwnership(tenderId, companyIdStr);

        // Verify document exists and belongs to tender
        TenderDocumentsRecord document = documentRepository.findById(documentId);

        if (!document.getTenderId().equals(tenderId)) {
            throw new NotFoundException(ApiErrorMessages.DOCUMENT_NOT_FOUND_CODE, ApiErrorMessages.DOCUMENT_NOT_FOUND);
        }

        // Soft-delete document record
        int deleted = documentRepository.softDelete(documentId);
        if (deleted == 0) {
            throw new NotFoundException(ApiErrorMessages.DOCUMENT_NOT_FOUND_CODE, ApiErrorMessages.DOCUMENT_NOT_FOUND);
        }

        // Delete S3 object synchronously
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(awsProperties.getBucket().getName())
                    .key(document.getS3Key())
                    .build());
            log.info("Deleted S3 object - key: {}", document.getS3Key());
        } catch (Exception e) {
            log.error("Failed to delete S3 object - key: {}", document.getS3Key(), e);
            // Don't rethrow - document is already soft-deleted
        }
    }

    @Override
    public DownloadUrlResponse getDownloadPresignedUrl(Long companyId, String tenderId, String documentId) {
        String companyIdStr = companyId.toString();

        // Verify tender exists and belongs to company
        verifyTenderOwnership(tenderId, companyIdStr);

        // Verify document exists and belongs to tender
        TenderDocumentsRecord document = documentRepository.findById(documentId);

        if (!document.getTenderId().equals(tenderId)) {
            throw new NotFoundException(ApiErrorMessages.DOCUMENT_NOT_FOUND_CODE, ApiErrorMessages.DOCUMENT_NOT_FOUND);
        }

        // Verify document status is "ready"
        if (!"ready".equals(document.getStatus())) {
            throw new DocumentNotReadyException(ApiErrorMessages.DOCUMENT_NOT_READY_CODE,
                    ApiErrorMessages.DOCUMENT_NOT_READY.formatted(document.getStatus()));
        }

        // Generate presigned GET URL
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(awsProperties.getBucket().getName())
                .key(document.getS3Key())
                .build();

        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGNED_URL_EXPIRY)
                .getObjectRequest(getObjectRequest)
                .build();

        var presignedGetObject = s3Presigner.presignGetObject(presignRequest);

        DownloadUrlResponse response = new DownloadUrlResponse();
        response.setUrl(URI.create(presignedGetObject.url().toString()));
        response.setExpiration(OffsetDateTime.now(ZoneOffset.UTC).plus(PRESIGNED_URL_EXPIRY));

        return response;
    }

    private void verifyTenderOwnership(String tenderId, String companyIdStr) {
        TendersRecord tender = tenderRepository.findById(tenderId);
        if (!tender.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException(ApiErrorMessages.TENDER_NOT_FOUND_CODE, ApiErrorMessages.TENDER_NOT_FOUND);
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "file";
        // Replace spaces and special chars with underscores, keep alphanumeric, dots, hyphens
        return fileName.replaceAll("[^a-zA-Z0-9.\\-_]", "_");
    }
}

package com.tosspaper.precon;

import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.exception.DocumentNotReadyException;
import com.tosspaper.precon.generated.model.DownloadUrlResponse;
import com.tosspaper.precon.generated.model.Pagination;
import com.tosspaper.precon.generated.model.PresignedUrlRequest;
import com.tosspaper.precon.generated.model.PresignedUrlResponse;
import com.tosspaper.precon.generated.model.TenderDocument;
import com.tosspaper.precon.generated.model.TenderDocumentListResponse;
import com.tosspaper.precon.generated.model.TenderDocumentStatus;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
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
    private final TenderDocumentRepository tenderDocumentRepository;
    private final TenderDocumentMapper tenderDocumentMapper;
    private final TenderDocumentValidator tenderDocumentValidator;
    private final TenderFileProperties fileProperties;
    private final S3Presigner s3Presigner;
    private final S3Client s3Client;

    private static final Duration UPLOAD_URL_DURATION = Duration.ofMinutes(10);
    private static final Duration DOWNLOAD_URL_DURATION = Duration.ofMinutes(5);

    @Override
    public PresignedUrlResponse getUploadPresignedUrl(Long companyId, String tenderId, PresignedUrlRequest request) {
        String companyIdStr = companyId.toString();

        // Validate tender ownership
        TendersRecord tender = tenderRepository.findById(tenderId);
        if (!tender.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException("api.tender.notFound", "Tender not found");
        }

        // Validate the file request
        tenderDocumentValidator.validate(request);

        // Generate document ID and S3 key
        String documentId = UUID.randomUUID().toString();
        String s3Key = buildS3Key(companyIdStr, tenderId, documentId, request.getFileName());

        // Create document record
        TenderDocumentsRecord record = new TenderDocumentsRecord();
        record.setId(documentId);
        record.setTenderId(tenderId);
        record.setCompanyId(companyIdStr);
        record.setFileName(request.getFileName());
        record.setContentType(request.getContentType().getValue());
        record.setFileSize(request.getFileSize().longValue());
        record.setS3Key(s3Key);
        record.setStatus("uploading");

        tenderDocumentRepository.insert(record);

        // Generate presigned upload URL
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(UPLOAD_URL_DURATION)
                .putObjectRequest(c -> c.bucket(fileProperties.getUploadBucket())
                        .key(s3Key)
                        .contentType(request.getContentType().getValue())
                        .contentLength(request.getFileSize().longValue())
                        .build())
                .build();

        var presignedRequest = s3Presigner.presignPutObject(presignRequest);
        OffsetDateTime expiration = presignedRequest.expiration().atOffset(ZoneOffset.UTC);

        PresignedUrlResponse response = new PresignedUrlResponse();
        response.setPresignedUrl(URI.create(presignedRequest.url().toString()));
        response.setDocumentId(UUID.fromString(documentId));
        response.setExpiration(expiration);

        return response;
    }

    @Override
    public TenderDocumentListResponse listDocuments(Long companyId, String tenderId, Integer limit,
                                                     String cursor, TenderDocumentStatus status) {
        String companyIdStr = companyId.toString();

        // Validate tender ownership
        TendersRecord tender = tenderRepository.findById(tenderId);
        if (!tender.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException("api.tender.notFound", "Tender not found");
        }

        // Clamp limit to valid range
        int effectiveLimit = limit != null ? limit : 20;
        if (effectiveLimit < 1 || effectiveLimit > 100) {
            effectiveLimit = 20;
        }

        // Decode cursor
        CursorUtils.CursorPair cursorPair = CursorUtils.parseCursor(cursor);

        String statusValue = status != null ? status.getValue() : null;
        OffsetDateTime cursorCreatedAt = cursorPair != null ? cursorPair.createdAt() : null;
        String cursorId = cursorPair != null ? cursorPair.id() : null;

        List<TenderDocumentsRecord> records = tenderDocumentRepository.findByTenderId(
                tenderId, statusValue, effectiveLimit, cursorCreatedAt, cursorId);

        // Determine if there are more results
        boolean hasMore = records.size() > effectiveLimit;
        if (hasMore) {
            records = records.subList(0, effectiveLimit);
        }

        List<TenderDocument> documents = tenderDocumentMapper.toDtoList(records);

        // Build pagination -- cursor is null when no more results
        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            TenderDocumentsRecord lastRecord = records.getLast();
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

        // Validate tender ownership
        TendersRecord tender = tenderRepository.findById(tenderId);
        if (!tender.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException("api.tender.notFound", "Tender not found");
        }

        // Find the document (throws NotFoundException if not found)
        TenderDocumentsRecord document = tenderDocumentRepository.findById(documentId);

        // Verify the document belongs to the tender
        if (!document.getTenderId().equals(tenderId)) {
            throw new NotFoundException("api.tenderDocument.notFound", "Tender document not found");
        }

        // Soft-delete the record first
        tenderDocumentRepository.softDelete(documentId);

        // Then delete the S3 object (log error if it fails, don't rethrow)
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(fileProperties.getUploadBucket())
                    .key(document.getS3Key())
                    .build();
            s3Client.deleteObject(deleteRequest);
            log.info("Deleted S3 object for document {} - key: {}", documentId, document.getS3Key());
        } catch (Exception e) {
            log.error("Failed to delete S3 object for document {} - key: {}. Record already soft-deleted.",
                    documentId, document.getS3Key(), e);
        }
    }

    @Override
    public DownloadUrlResponse getDownloadPresignedUrl(Long companyId, String tenderId, String documentId) {
        String companyIdStr = companyId.toString();

        // Validate tender ownership
        TendersRecord tender = tenderRepository.findById(tenderId);
        if (!tender.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException("api.tender.notFound", "Tender not found");
        }

        // Find the document (throws NotFoundException if not found)
        TenderDocumentsRecord document = tenderDocumentRepository.findById(documentId);

        // Verify the document belongs to the tender
        if (!document.getTenderId().equals(tenderId)) {
            throw new NotFoundException("api.tenderDocument.notFound", "Tender document not found");
        }

        // Check status is ready
        if (!"ready".equals(document.getStatus())) {
            throw new DocumentNotReadyException("api.tenderDocument.notReady",
                    "Document is not ready for download. Current status: " + document.getStatus());
        }

        // Generate presigned download URL
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(DOWNLOAD_URL_DURATION)
                .getObjectRequest(c -> c.bucket(fileProperties.getUploadBucket())
                        .key(document.getS3Key())
                        .build())
                .build();

        var presignedRequest = s3Presigner.presignGetObject(presignRequest);
        OffsetDateTime expiration = presignedRequest.expiration().atOffset(ZoneOffset.UTC);

        DownloadUrlResponse response = new DownloadUrlResponse();
        response.setUrl(URI.create(presignedRequest.url().toString()));
        response.setExpiration(expiration);

        return response;
    }

    private String buildS3Key(String companyId, String tenderId, String documentId, String fileName) {
        return "tenders/" + companyId + "/" + tenderId + "/" + documentId + "/" + fileName;
    }
}

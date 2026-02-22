package com.tosspaper.precon;

import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.TenderDocumentsApi;
import com.tosspaper.generated.model.DownloadUrlResponse;
import com.tosspaper.generated.model.PresignedUrlRequest;
import com.tosspaper.generated.model.PresignedUrlResponse;
import com.tosspaper.generated.model.TenderDocumentListResponse;
import com.tosspaper.generated.model.TenderDocumentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class TenderDocumentController implements TenderDocumentsApi {

    private final TenderDocumentService tenderDocumentService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<PresignedUrlResponse> getUploadPresignedUrl(
            String xContextId, UUID tenderId, PresignedUrlRequest presignedUrlRequest) {
        log.debug("POST /v1/tenders/{}/documents/presigned-urls - xContextId={}", tenderId, xContextId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        PresignedUrlResponse response = tenderDocumentService.getUploadPresignedUrl(
                companyId, tenderId.toString(), presignedUrlRequest);

        return ResponseEntity
                .created(URI.create("/v1/tenders/" + tenderId + "/documents/" + response.getDocumentId()))
                .body(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<TenderDocumentListResponse> listTenderDocuments(
            String xContextId, UUID tenderId, Integer limit, String cursor, TenderDocumentStatus status) {
        log.debug("GET /v1/tenders/{}/documents - xContextId={}, limit={}, cursor={}, status={}",
                tenderId, xContextId, limit, cursor, status);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        // Validate limit
        int effectiveLimit = limit != null ? limit : 20;
        if (effectiveLimit < 1 || effectiveLimit > 100) {
            throw new BadRequestException("api.validation.invalidLimit", "Limit must be between 1 and 100");
        }

        // Decode cursor
        String cursorCreatedAt = null;
        String cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                CursorUtils.CursorPair cursorPair = CursorUtils.decodeCursor(cursor);
                cursorCreatedAt = cursorPair.createdAt().toString();
                cursorId = cursorPair.id();
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("api.validation.invalidCursor", "Invalid cursor format");
            }
        }

        String statusValue = status != null ? status.getValue() : null;

        TenderDocumentListResponse response = tenderDocumentService.listDocuments(
                companyId, tenderId.toString(), statusValue, effectiveLimit, cursorCreatedAt, cursorId);

        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<Void> deleteTenderDocument(String xContextId, UUID tenderId, UUID documentId) {
        log.debug("DELETE /v1/tenders/{}/documents/{} - xContextId={}", tenderId, documentId, xContextId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        tenderDocumentService.deleteDocument(companyId, tenderId.toString(), documentId.toString());

        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<DownloadUrlResponse> getDownloadPresignedUrl(
            String xContextId, UUID tenderId, UUID documentId) {
        log.debug("GET /v1/tenders/{}/documents/{}/presigned-urls - xContextId={}", tenderId, documentId, xContextId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        DownloadUrlResponse response = tenderDocumentService.getDownloadPresignedUrl(
                companyId, tenderId.toString(), documentId.toString());

        return ResponseEntity.ok(response);
    }
}

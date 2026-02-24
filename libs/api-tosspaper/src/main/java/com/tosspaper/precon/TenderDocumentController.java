package com.tosspaper.precon;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.precon.generated.api.TenderDocumentsApi;
import com.tosspaper.precon.generated.model.DownloadUrlResponse;
import com.tosspaper.precon.generated.model.PresignedUrlRequest;
import com.tosspaper.precon.generated.model.PresignedUrlResponse;
import com.tosspaper.precon.generated.model.TenderDocumentListResponse;
import com.tosspaper.precon.generated.model.TenderDocumentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

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
            String xContextId,
            UUID tenderId,
            PresignedUrlRequest presignedUrlRequest) {
        log.debug("POST /v1/tenders/{}/documents/upload - xContextId={}", tenderId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        PresignedUrlResponse response = tenderDocumentService.getUploadPresignedUrl(
                companyId, tenderId.toString(), presignedUrlRequest);
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<TenderDocumentListResponse> listTenderDocuments(
            String xContextId,
            UUID tenderId,
            Integer limit,
            String cursor,
            TenderDocumentStatus status) {
        log.debug("GET /v1/tenders/{}/documents - xContextId={}", tenderId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        TenderDocumentListResponse response = tenderDocumentService.listDocuments(
                companyId, tenderId.toString(), limit, cursor, status);
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<Void> deleteTenderDocument(
            String xContextId,
            UUID tenderId,
            UUID documentId) {
        log.debug("DELETE /v1/tenders/{}/documents/{} - xContextId={}", tenderId, documentId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        tenderDocumentService.deleteDocument(companyId, tenderId.toString(), documentId.toString());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<DownloadUrlResponse> getDownloadPresignedUrl(
            String xContextId,
            UUID tenderId,
            UUID documentId) {
        log.debug("GET /v1/tenders/{}/documents/{}/download - xContextId={}", tenderId, documentId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        DownloadUrlResponse response = tenderDocumentService.getDownloadPresignedUrl(
                companyId, tenderId.toString(), documentId.toString());
        return ResponseEntity.ok(response);
    }
}

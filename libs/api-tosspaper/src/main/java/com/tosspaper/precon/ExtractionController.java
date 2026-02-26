package com.tosspaper.precon;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.precon.generated.api.ExtractionsApi;
import com.tosspaper.precon.generated.model.Application;
import com.tosspaper.precon.generated.model.ApplicationCreateRequest;
import com.tosspaper.precon.generated.model.Extraction;
import com.tosspaper.precon.generated.model.ExtractionCreateRequest;
import com.tosspaper.precon.generated.model.ExtractionCreateResponse;
import com.tosspaper.precon.generated.model.ExtractionFieldBulkUpdateRequest;
import com.tosspaper.precon.generated.model.ExtractionFieldBulkUpdateResponse;
import com.tosspaper.precon.generated.model.ExtractionFieldListResponse;
import com.tosspaper.precon.generated.model.ExtractionListResponse;
import com.tosspaper.precon.generated.model.ExtractionStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class ExtractionController implements ExtractionsApi {

    private final ExtractionService extractionService;
    private final ExtractionFieldService extractionFieldService;
    private final ExtractionApplicationService extractionApplicationService;
    private final HttpServletRequest request;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<ExtractionCreateResponse> createExtraction(
            String xContextId,
            ExtractionCreateRequest extractionCreateRequest,
            UUID idempotencyKey) {
        log.debug("POST /v1/extractions - xContextId={}", xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        ExtractionResult result = extractionService.createExtraction(companyId, extractionCreateRequest);
        ExtractionCreateResponse response = new ExtractionCreateResponse();
        response.setId(result.extraction().getId());
        return ResponseEntity
                .created(URI.create("/v1/extractions/" + result.extraction().getId()))
                .eTag(HeaderUtils.formatETag(result.version()))
                .body(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<ExtractionListResponse> listExtractions(
            String xContextId,
            UUID entityId,
            ExtractionStatus status,
            Integer limit,
            String cursor) {
        log.debug("GET /v1/extractions - xContextId={}, entityId={}", xContextId, entityId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        ExtractionListResponse response = extractionService.listExtractions(
                companyId, entityId, status, limit, cursor);
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<Extraction> getExtraction(
            String xContextId,
            UUID extractionId) {
        log.debug("GET /v1/extractions/{} - xContextId={}", extractionId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        ExtractionResult result = extractionService.getExtraction(companyId, extractionId.toString());
        String currentETag = HeaderUtils.formatETag(result.version());

        if (HeaderUtils.isNotModified(request, currentETag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(CacheControl.noCache())
                    .eTag(currentETag)
                    .build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .eTag(currentETag)
                .body(result.extraction());
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<Void> cancelExtraction(
            String xContextId,
            UUID extractionId) {
        log.debug("DELETE /v1/extractions/{} - xContextId={}", extractionId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        extractionService.cancelExtraction(companyId, extractionId.toString());
        return ResponseEntity.accepted().build();
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<ExtractionFieldListResponse> listExtractionFields(
            String xContextId,
            UUID extractionId,
            String fieldName,
            UUID documentId,
            Integer limit,
            String cursor) {
        log.debug("GET /v1/extractions/{}/fields - xContextId={}", extractionId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        ExtractionFieldListResponse response = extractionFieldService.listExtractionFields(
                companyId, extractionId.toString(), fieldName, documentId, limit, cursor);
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<ExtractionFieldBulkUpdateResponse> bulkUpdateExtractionFields(
            String xContextId,
            String ifMatch,
            UUID extractionId,
            ExtractionFieldBulkUpdateRequest extractionFieldBulkUpdateRequest) {
        log.debug("PATCH /v1/extractions/{}/fields - xContextId={}", extractionId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        ExtractionFieldBulkUpdateResponse response = extractionFieldService.bulkUpdateFields(
                companyId, extractionId.toString(), ifMatch, extractionFieldBulkUpdateRequest);
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<Application> applyExtraction(
            String xContextId,
            UUID extractionId,
            ApplicationCreateRequest applicationCreateRequest,
            UUID idempotencyKey) {
        log.debug("POST /v1/extractions/{}/applications - xContextId={}", extractionId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        Application application = extractionApplicationService.apply(
                companyId, extractionId.toString(), applicationCreateRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(application);
    }
}

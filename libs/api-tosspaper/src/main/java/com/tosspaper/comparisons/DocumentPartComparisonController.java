package com.tosspaper.comparisons;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.ComparisonsApi;
import com.tosspaper.generated.model.DocumentComparisonResult;
import com.tosspaper.models.exception.BadRequestException;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.service.DocumentPartComparisonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for document part comparison operations.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class DocumentPartComparisonController implements ComparisonsApi {

    private final DocumentPartComparisonService service;
    private final DocumentComparisonMapper mapper;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<DocumentComparisonResult> getComparisons(String xContextId, String assignedId) {
        log.debug("GET /v1/comparisons?assignedId={}", assignedId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        return service.getComparisonByAssignedId(assignedId, companyId)
                .map(comparison -> {
                    log.debug("Found comparison for assignedId: {}, resultCount: {}",
                            assignedId, comparison.getResults() != null ? comparison.getResults().size() : 0);
                    return ResponseEntity.ok(mapper.toDto(comparison));
                })
                .orElseGet(() -> {
                    log.debug("No comparison found for assignedId: {}", assignedId);
                    return ResponseEntity.notFound().build();
                });
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:edit')")
    public ResponseEntity<Void> runComparison(String xContextId, String assignedId) {
        log.debug("POST /v1/comparisons/{}/", assignedId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        try {
            service.manuallyTriggerComparison(assignedId, companyId);
            return ResponseEntity.ok().build();

        } catch (BadRequestException e) {
            log.warn("Invalid comparison request for assignedId: {} - code: {}, message: {}",
                assignedId, e.getCode(), e.getMessage());

            if ("EXTRACTION_NOT_FOUND".equals(e.getCode())) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().build();

        } catch (Exception e) {
            log.error("Failed to run comparison for assignedId: {}", assignedId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}


package com.tosspaper.aiengine.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.repository.DocumentPartComparisonRepository;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.exception.BadRequestException;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.service.DocumentPartComparisonService;
import com.tosspaper.models.service.PurchaseOrderLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;

import java.util.Optional;

/**
 * Abstract base class for DocumentPartComparisonService implementations.
 * Provides common functionality for comparison result persistence and task validation.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractDocumentPartComparisonService implements DocumentPartComparisonService {

    protected final ObjectMapper objectMapper;
    protected final DocumentPartComparisonRepository repository;
    protected final ExtractionTaskRepository extractionTaskRepository;
    protected final DSLContext dslContext;
    protected final PurchaseOrderLookupService poLookupService;

    @Override
    public Optional<Comparison> getComparisonByAssignedId(String assignedId, Long companyId) {
        log.debug("Getting comparison for assignedId: {}, companyId: {}", assignedId, companyId);

        Optional<Comparison> result = repository.findByAssignedId(assignedId, companyId);

        log.debug("Found comparison for assignedId: {}: {}", assignedId, result.isPresent());

        return result;
    }

    @Override
    public void manuallyTriggerComparison(String assignedId, Long companyId) {
        log.info("Manually triggering comparison for assignedId: {}, companyId: {}", assignedId, companyId);

        // 1. Fetch and validate extraction task
        ExtractionTask task = extractionTaskRepository.findByAssignedId(assignedId);

        if (task == null) {
            log.warn("Extraction task not found for assignedId: {}", assignedId);
            throw new BadRequestException("EXTRACTION_NOT_FOUND", "Extraction task not found: " + assignedId);
        }

        if (!task.getCompanyId().equals(companyId)) {
            log.warn("Company ID mismatch for assignedId: {} (expected: {}, actual: {})",
                    assignedId, companyId, task.getCompanyId());
            throw new BadRequestException("COMPANY_MISMATCH", "Company ID mismatch for extraction task");
        }

        if (task.getPurchaseOrderId() == null || task.getPurchaseOrderId().isBlank()) {
            log.warn("No PO linked for assignedId: {}", assignedId);
            throw new BadRequestException("NO_PO_LINKED", "No purchase order linked to this document");
        }

        if (task.getConformedJson() == null || task.getConformedJson().isBlank()) {
            log.warn("No conformed JSON available for assignedId: {}", assignedId);
            throw new BadRequestException("NOT_CONFORMED", "Document has not been conformed yet");
        }

        try {
            var po = poLookupService.getPoWithItemsByPoNumber(task.getCompanyId(), task.getPoNumber()).orElseThrow();

            // 2. Run comparison (outside transaction)
            log.info("Running comparison for assignedId: {} with PO: {}", assignedId, task.getPurchaseOrderId());

            Comparison comparison = compareDocumentParts(
                    new ComparisonContext(po, task)
            );

            // 3. Save result with upsert (handles retry case automatically)
            dslContext.transaction(ctx -> {
                repository.upsert(ctx.dsl(), assignedId, comparison);
                log.debug("Upserted comparison result for assignedId: {}", assignedId);
            });

        } catch (Exception e) {
            log.error("Failed to run comparison for assignedId: {}", assignedId, e);
            throw new RuntimeException("Failed to run comparison: " + e.getMessage(), e);
        }
    }
}

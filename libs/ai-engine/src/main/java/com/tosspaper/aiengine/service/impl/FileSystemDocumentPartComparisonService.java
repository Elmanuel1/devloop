package com.tosspaper.aiengine.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.agent.FileSystemComparisonAgent;
import com.tosspaper.aiengine.repository.DocumentPartComparisonRepository;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.extraction.dto.Extraction;
import com.tosspaper.models.service.PurchaseOrderLookupService;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Filesystem-based implementation of DocumentPartComparisonService.
 * Uses Claude agent with VFS access for document comparison.
 *
 * <p>This implementation:
 * <ul>
 *   <li>Syncs PO data to VFS as JSON</li>
 *   <li>Saves document extraction to VFS</li>
 *   <li>Runs Claude agent to compare files</li>
 *   <li>Reads comparison results from VFS</li>
 * </ul>
 *
 * <p>Marked @Primary to replace the vector-store implementation without modifying existing code.
 */
@Slf4j
@Service
@Primary
public class FileSystemDocumentPartComparisonService extends AbstractDocumentPartComparisonService {

    private final FileSystemComparisonAgent comparisonAgent;

    public FileSystemDocumentPartComparisonService(
            ObjectMapper objectMapper,
            DocumentPartComparisonRepository repository,
            ExtractionTaskRepository extractionTaskRepository,
            DSLContext dslContext,
            FileSystemComparisonAgent comparisonAgent,
            PurchaseOrderLookupService poService) {
        super(objectMapper, repository, extractionTaskRepository, dslContext, poService);
        this.comparisonAgent = comparisonAgent;
    }

    @Override
    @Observed(
            name = "document.part.comparison.filesystem",
            contextualName = "Compare Document Parts (Filesystem)",
            lowCardinalityKeyValues = {"service", "comparison", "operation", "compare-parts-filesystem"}
    )
    public Comparison compareDocumentParts(ComparisonContext context) {
        ExtractionTask task = context.extractionTask();
        String extractionId = task.getAssignedId();

        log.info("Starting filesystem comparison: companyId={}, extractionId={}, poNumber={}, documentType={}",
                task.getCompanyId(), extractionId, task.getPoNumber(), task.getDocumentType());

        // Execute comparison via agent (uses task.getConformedJson() for document data)
        Comparison comparison = comparisonAgent.executeComparison(context);

        int resultCount = comparison.getResults() != null ? comparison.getResults().size() : 0;

        log.info("Filesystem comparison complete: extractionId={}, overallStatus={}, resultCount={}",
                extractionId, comparison.getOverallStatus(), resultCount);

        return comparison;
    }

    /**
     * Determine document type from extraction metadata or content.
     */
    private DocumentType determineDocumentType(Extraction extraction) {
        if (extraction.getDocumentType() != null) {
            String docType = extraction.getDocumentType().value().toLowerCase();
            return switch (docType) {
                case "invoice" -> DocumentType.INVOICE;
                case "delivery_slip", "delivery slip", "packing slip" -> DocumentType.DELIVERY_SLIP;
                case "delivery_note", "delivery note" -> DocumentType.DELIVERY_NOTE;
                default -> DocumentType.UNKNOWN;
            };
        }
        return DocumentType.UNKNOWN;
    }
}

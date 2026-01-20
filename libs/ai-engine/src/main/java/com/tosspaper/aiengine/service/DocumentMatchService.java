package com.tosspaper.aiengine.service;

import com.tosspaper.aiengine.repository.DocumentMatchRepository;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.MatchType;
import com.tosspaper.models.exception.ForbiddenException;
import com.tosspaper.models.exception.NotFoundException;
import com.tosspaper.models.service.PurchaseOrderLookupService;
import com.tosspaper.models.messaging.MessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.InvalidParameterException;
import java.util.Map;

/**
 * Service for managing document matching operations.
 * Handles business logic for initiating matches and publishing to Redis streams.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentMatchService {

    private final DocumentMatchRepository documentMatchRepository;
    private final ExtractionTaskRepository extractionTaskRepository;
    private final MessagePublisher messagePublisher;
    private final PurchaseOrderLookupService purchaseOrderLookupService;

    /**
     * Initiates manual PO linking for a document.
     * Sets match_type='in_progress' and publishes to Redis stream for async AI comparison.
     *
     * @param assignedId the extraction task assigned ID
     * @param poNumber the purchase order number to link
     */
    public void initiateManualLink(Long xContextId, String assignedId, String poNumber) {
        log.info("Initiating manual link for document {} to PO {}", assignedId, poNumber);

        var extractionTask = extractionTaskRepository.findByAssignedId(assignedId);
        if (!extractionTask.getCompanyId().equals(xContextId)) {
            throw new ForbiddenException("You do not have permission to perform this operation");
        }

        var po = purchaseOrderLookupService.findByCompanyIdAndDisplayId(xContextId, poNumber)
                .orElseThrow(() -> new NotFoundException("Purchase Order Not Found"));

        var extraction = extractionTask.toBuilder()
                            .projectId(po.projectId())
                            .purchaseOrderId(po.id())
                            .poNumber(po.poNumber())
                            .build();

        // 1. Update match_type to IN_PROGRESS (shows UI feedback)
        extractionTaskRepository.updateManualPoInformation(extraction);
    }

    /**
     * Initiates automatic PO matching for a document.
     * Sets match_type='in_progress' and publishes to Redis stream for async AI matching.
     *
     * @param assignedId the extraction task assigned ID
     */
    public void initiateAutoMatch(String assignedId) {
        log.info("Initiating auto match for document {}", assignedId);

        // Fetch extraction task to get document type
        var extractionTask = extractionTaskRepository.findByAssignedId(assignedId);
        DocumentType documentType = extractionTask.getDocumentType();

        // 1. Update match_type to IN_PROGRESS (shows UI feedback)
        documentMatchRepository.updateToInProgress(assignedId, documentType);

        // 2. Publish to Redis stream for async AI matching
        Map<String, String> payload = Map.of(
            "assignedId", assignedId,
            "documentType", documentType.getFilePrefix(),
            "matchType", "auto"
        );

        messagePublisher.publish("po-match-requests", payload);
        log.info("Published auto match request to Redis stream: assignedId={}", assignedId);
    }

    /**
     * Resets a document to PENDING state for rematch.
     * Clears all match information.
     *
     * @param assignedId the extraction task assigned ID
     */
    public void resetToPending(String assignedId) {
        log.info("Resetting document {} to PENDING for rematch", assignedId);

        // Fetch extraction task to get document type
        var extractionTask = extractionTaskRepository.findByAssignedId(assignedId);
        DocumentType documentType = extractionTask.getDocumentType();

        documentMatchRepository.updateToPending(assignedId, documentType);
        log.info("Reset document {} to PENDING", assignedId);
    }
}
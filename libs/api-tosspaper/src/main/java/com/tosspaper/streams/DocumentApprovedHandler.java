package com.tosspaper.streams;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.messaging.MessageHandler;
import com.tosspaper.document_approval.DocumentApprovalEmailProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Redis Stream listener for processing document approval events.
 * Delegates to DocumentApprovalProcessingService for business logic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentApprovedHandler implements MessageHandler<Map<String, String>> {

    private static final String QUEUE_NAME = "document-approved-events";

    private final DocumentApprovalEmailProcessingService processingService;

    @Override
    public String getQueueName() {
        return QUEUE_NAME;
    }

    @Override
    public void handle(Map<String, String> message) {
        processApproval(message);
    }

    private void processApproval(Map<String, String> data) {
        String assignedId = data.get("assignedId");

        try {
            processingService.processDocumentApproval(assignedId);
            log.info("Sent email for approved document with assignedId {}", assignedId);
        } catch (Exception e) {
            log.error("Failed to process document approval: {}", assignedId, e);
            throw new RuntimeException("Failed to process document approval event", e);
        }
    }
}


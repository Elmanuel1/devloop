package com.tosspaper.aiengine.service.impl;

import com.tosspaper.aiengine.api.dto.ReductoWebhookPayload;
import com.tosspaper.aiengine.service.ExtractionService;
import com.tosspaper.aiengine.service.ReductoWebhookService;
import com.tosspaper.models.domain.ExtractionStatus;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.messaging.MessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of ReductoWebhookService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReductoWebhookServiceImpl implements ReductoWebhookService {

    private final ExtractionService extractionService;
    private final MessagePublisher streamPublisher;

    @Override
    public void processWebhook(ReductoWebhookPayload payload) {
        log.info("Processing Reducto webhook: jobId={}, status={}", 
                payload.getJobId(), payload.getStatus());

        // Map Reducto status to ExtractionStatus
        ExtractionStatus reductoStatus = mapReductoStatus(payload.getStatus());

        if(!Set.of(ExtractionStatus.FAILED, ExtractionStatus.COMPLETED, ExtractionStatus.CANCELLED).contains(reductoStatus)) {
            log.info("Webhook only honoring final notifications for now");
            return;
        }
        
        var record = extractionService.findByTaskId(payload.getJobId());
        if(record.isEmpty()) {
           log.info("Task {} has no extraction record", payload.getJobId());
           return;
        }

        ExtractionTask task = record.get();
        if(Set.of(ExtractionStatus.FAILED, ExtractionStatus.COMPLETED, ExtractionStatus.CANCELLED).contains(task.getStatus())) {
            log.info("Task {} has a final extraction status {}, webhook status {}", payload.getJobId(),  task.getStatus(), reductoStatus);
            return;
        }

        Map<String, String> aiProcessMessage = new HashMap<>();
        aiProcessMessage.put("assignedId", task.getAssignedId());
        aiProcessMessage.put("storageUrl", task.getStorageKey());
        streamPublisher.publish("ai-process", aiProcessMessage);
        log.info("Sent final status update to redis stream {}", payload.getStatus());
    }
    
    private ExtractionStatus mapReductoStatus(String reductoStatus) {
        return switch (reductoStatus.toLowerCase()) {
            case "completed", "succeeded" -> ExtractionStatus.COMPLETED;
            case "failed", "error" -> ExtractionStatus.FAILED;
            case "cancelled" -> ExtractionStatus.CANCELLED;
            default -> ExtractionStatus.PENDING;
        };
    }
}

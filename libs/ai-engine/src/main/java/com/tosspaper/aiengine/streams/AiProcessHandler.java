package com.tosspaper.aiengine.streams;

import com.tosspaper.aiengine.service.ExtractionService;
import com.tosspaper.models.messaging.MessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Map;

/**
 * Listener for AI processing stream events.
 * Processes messages from the ai-process stream to handle document extraction.
 */
@Slf4j
@Component("aiProcessStreamListener")
@RequiredArgsConstructor
public class AiProcessHandler implements MessageHandler<Map<String, String>> {

    private static final String QUEUE_NAME = "ai-process";

    private final ExtractionService extractionService;

    @Override
    public String getQueueName() {
        return QUEUE_NAME;
    }

    @Override
    public void handle(Map<String, String> message) {
        processAiTask(message);
    }

    private void processAiTask(Map<String, String> messageData) {
        String assignedId = messageData.get("assignedId");
        String storageKey = messageData.get("storageUrl");
        Assert.notNull(assignedId, "Assigned ID is null");
        Assert.notNull(storageKey, "Storage key is null");
        log.info("Processing AI task for attachment: {} from storage key: {}", assignedId, storageKey);
        
        try {
            extractionService.extract(assignedId, storageKey);
        } catch (Exception e) {
            log.error("Failed to process AI task for attachment: {}", assignedId, e);
        }
    }
}

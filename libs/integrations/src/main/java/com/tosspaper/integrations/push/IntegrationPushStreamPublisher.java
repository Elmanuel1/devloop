package com.tosspaper.integrations.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.messaging.MessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Publisher for integration push events.
 * Serializes events and publishes to Redis stream for async processing.
 * Provider-agnostic - works with any integration provider.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntegrationPushStreamPublisher {

    private static final String STREAM_NAME = "integration-push-events";

    private final MessagePublisher messagePublisher;
    private final ObjectMapper objectMapper;

    /**
     * Publish a push event to the Redis stream.
     *
     * @param event the push event to publish
     */
    public void publish(IntegrationPushEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            Map<String, String> streamMessage = new HashMap<>();
            streamMessage.put("message", payload);

            messagePublisher.publish(STREAM_NAME, streamMessage);

            log.info("Published push event to stream: {} - provider={}, companyId={}, payloadSize={}",
                    STREAM_NAME,
                    event.provider(),
                    event.companyId(),
                    payload.length());

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize push event", e);
            throw new RuntimeException("Failed to serialize push event", e);
        }
    }
}

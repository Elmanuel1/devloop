package com.tosspaper.models.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.messaging.MessagePublisher;
import com.tosspaper.models.properties.SqsProperties;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.baggage.Baggage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQS implementation of MessagePublisher for publishing messages to AWS SQS queues.
 * Automatically propagates W3C trace context (traceparent) and W3C Baggage from HTTP requests
 * to SQS message attributes so that message processing spans join the originating HTTP trace.
 *
 * This implementation is active when messaging.provider=sqs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "messaging.provider", havingValue = "sqs")
public class SqsMessagePublisherImpl implements MessagePublisher {

    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;
    private final Tracer tracer;
    private final ObjectMapper objectMapper;

    // Cache queue URLs to avoid repeated lookups
    private final Map<String, String> queueUrlCache = new ConcurrentHashMap<>();

    @Override
    public void publish(String queueName, Map<String, String> message) {
        try {
            String queueUrl = getQueueUrl(queueName);

            // Build message attributes for trace context
            Map<String, MessageAttributeValue> attributes = new HashMap<>();

            // W3C traceparent
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                String traceId = currentSpan.context().traceId();
                String spanId = currentSpan.context().spanId();

                if (traceId != null && !traceId.isEmpty() && spanId != null && !spanId.isEmpty()) {
                    String traceparent = String.format("00-%s-%s-01",
                            padHex(traceId, 32), padHex(spanId, 16));
                    attributes.put("traceparent", MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(traceparent)
                            .build());
                }
            }

            // Baggage propagation
            try {
                Baggage currentBaggage = Baggage.current();
                if (currentBaggage != null && !currentBaggage.isEmpty()) {
                    currentBaggage.forEach((key, entry) -> {
                        String value = entry.getValue();
                        if (key != null && value != null) {
                            attributes.put("baggage." + key, MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(value)
                                    .build());
                        }
                    });
                }
            } catch (Exception e) {
                log.debug("Failed to extract baggage for queue {}: {}", queueName, e.getMessage());
            }

            // Send message
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(toJson(message))
                    .messageAttributes(attributes)
                    .build());

            log.debug("Published message to SQS queue: {}", queueName);

        } catch (Exception e) {
            log.error("Failed to publish message to SQS queue: {}", queueName, e);
            throw new RuntimeException("Failed to publish to SQS queue: " + queueName, e);
        }
    }

    private String getQueueUrl(String queueName) {
        return queueUrlCache.computeIfAbsent(queueName, name -> {
            String fullName = sqsProperties.getQueuePrefix() + "-" + name;
            String url = sqsClient.getQueueUrl(r -> r.queueName(fullName)).queueUrl();
            log.debug("Resolved queue URL for {}: {}", fullName, url);
            return url;
        });
    }

    private String toJson(Map<String, String> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize message to JSON", e);
        }
    }

    /**
     * Pad hex string to required length (left-pad with zeros).
     * W3C traceparent requires: trace-id (32 hex chars), parent-id (16 hex chars).
     */
    private String padHex(String hex, int length) {
        if (hex == null) {
            return "0".repeat(length);
        }
        hex = hex.replaceAll("^0[xX]", "").replaceAll("-", "");
        if (hex.length() >= length) {
            return hex.substring(0, length);
        }
        return "0".repeat(length - hex.length()) + hex;
    }
}

package com.tosspaper.models.service.impl;

import com.tosspaper.models.messaging.MessagePublisher;
import com.tosspaper.models.service.RedisStreamPublisher;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.baggage.Baggage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis implementation of MessagePublisher for publishing messages to Redis streams.
 * Automatically propagates W3C trace context (traceparent) and W3C Baggage from HTTP requests
 * to Redis stream messages so that stream processing spans join the originating HTTP trace.
 *
 * This implementation is active when messaging.provider=redis (default).
 *
 * The vector store writes happen inside the Redis stream processing, and trace context
 * is automatically propagated via the observation scope created in RedisStreamManager.
 * Spring AI VectorStore operations (pgvector → PostgreSQL) are automatically instrumented
 * by Spring Boot Observation when JDBC observation is enabled.
 *
 * W3C Baggage (user-id, tenant, etc.) is automatically extracted and propagated,
 * allowing custom context to be available during asynchronous stream processing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "messaging.provider", havingValue = "redis", matchIfMissing = true)
public class RedisStreamPublisherImpl implements RedisStreamPublisher, MessagePublisher {

    private final StringRedisTemplate redisTemplate;
    private final Tracer tracer;

    @Override
    public void publish(String streamName, Map<String, String> message) {
        try {
            // Create a mutable copy of the message map to avoid UnsupportedOperationException
            Map<String, String> mutableMessage = new HashMap<>(message);
            
            // Extract W3C trace context and baggage using Micrometer Tracing Tracer API
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                String traceId = currentSpan.context().traceId();
                String spanId = currentSpan.context().spanId();

                if (!traceId.isEmpty() && !spanId.isEmpty()) {

                    // Format as W3C traceparent: version-trace-id-parent-id-trace-flags
                    // Format: 00-{32-char-trace-id}-{16-char-parent-id}-01
                    // Where: 00 = version, 01 = sampled flag
                    String traceparent = String.format("00-%s-%s-01",
                            padHex(traceId, 32), padHex(spanId, 16));

                    // Add W3C traceparent to message (standard W3C Trace Context format)
                    mutableMessage.put("traceparent", traceparent);
                }
            }
            
            // Extract and propagate W3C Baggage (user-id, tenant, etc.)
            try {
                Baggage currentBaggage = Baggage.current();
                if (currentBaggage != null && !currentBaggage.isEmpty()) {
                    currentBaggage.forEach((key, entry) -> {
                        String value = entry.getValue();
                        if (key != null && value != null) {
                            // Use W3C baggage key format (can be accessed later)
                            mutableMessage.put("baggage." + key, value);
                        }
                    });
                }
            } catch (Exception e) {
                // Baggage extraction failed - log but don't fail the publish
                log.debug("Failed to extract baggage for stream {}: {}", streamName, e.getMessage());
            }
            
            redisTemplate.opsForStream().add(streamName, mutableMessage);
        } catch (Exception e) {
            log.error("Failed to publish message to stream: {}", streamName, e);
            throw new RuntimeException("Failed to publish to Redis stream: " + streamName, e);
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
        // Remove any prefix like "0x" or "-"
        hex = hex.replaceAll("^0[xX]", "").replaceAll("-", "");
        if (hex.length() >= length) {
            return hex.substring(0, length);
        }
        return "0".repeat(length - hex.length()) + hex;
    }
}

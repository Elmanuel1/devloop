package com.tosspaper.everything.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@Slf4j
public class RedisStreamManager {

    private final RedisConnectionFactory connectionFactory;
    private final ObservationRegistry observationRegistry;
    private final Map<String, StreamMessageListenerContainer<String, MapRecord<String, String, String>>> containers = new ConcurrentHashMap<>();
    private final ExecutorService virtualThreadExecutor;

    @Value("${spring.data.redis.stream.poll-timeout:2000}")
    private long pollTimeoutMs;

    @Value("${spring.data.redis.stream.batch-size:10}")
    private int batchSize;

    public RedisStreamManager(
            RedisConnectionFactory connectionFactory,
            ObservationRegistry observationRegistry) {
        this.connectionFactory = connectionFactory;
        this.observationRegistry = observationRegistry;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void registerListener(
            String streamKey,
            String groupName,
            String consumerName,
            org.springframework.data.redis.stream.StreamListener<String, MapRecord<String, String, String>> listener
    ) {
        String containerKey = streamKey + ":" + groupName;

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .executor(virtualThreadExecutor)
                        .pollTimeout(Duration.ofMillis(pollTimeoutMs))
                        .batchSize(batchSize)
                        .errorHandler(t -> log.warn("Error while registering listener", t))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                containers.computeIfAbsent(containerKey, key -> {
                    ensureConsumerGroupExists(streamKey, groupName);
                    log.info("Creating container for stream: {}, group: {}", streamKey, groupName);
                    return StreamMessageListenerContainer.create(connectionFactory, options);
                });

        // Receive messages without auto-ack
        // Use lastConsumed() which reads pending messages first, then new messages
        // This follows Redis best practices and prevents reprocessing acknowledged messages
        container.receive(
                Consumer.from(groupName, consumerName),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                createWrappedListener(streamKey, groupName, consumerName, listener)
        );

        if(!container.isRunning()) {
            container.start();
        }

        log.debug("Registered listener for stream={}, group={}, consumer={}", streamKey, groupName, consumerName);
    }

    
    /**
     * Creates a StreamListener wrapper that handles trace context, baggage, and error handling.
     * On error, messages are acknowledged to prevent blocking, allowing processing to continue.
     */
    private org.springframework.data.redis.stream.StreamListener<String, MapRecord<String, String, String>>
            createWrappedListener(
                    String streamKey, 
                    String groupName, 
                    String consumerName,
                    org.springframework.data.redis.stream.StreamListener<String, MapRecord<String, String, String>> listener) {
        return new org.springframework.data.redis.stream.StreamListener<String, MapRecord<String, String, String>>() {
            @Override
            public void onMessage(MapRecord<String, String, String> record) {
            log.debug("Received message from stream: {}, group: {}, consumer: {}, messageId: {}", 
                    streamKey, groupName, consumerName, record.getId());
            
            // Extract W3C trace context and baggage from message if present (propagated from HTTP request)
            Map<String, String> messageValue = record.getValue();
            String traceparent = messageValue.get("traceparent");
            
            // Parse W3C traceparent format: version-trace-id-parent-id-trace-flags
            // Format: 00-{32-char-trace-id}-{16-char-parent-id}-01
            String traceId = null;
            String spanId = null;
            if (traceparent != null && traceparent.length() >= 55) { // Minimum length for valid traceparent
                String[] parts = traceparent.split("-");
                if (parts.length == 4) {
                    traceId = parts[1]; // 32-char trace ID
                    spanId = parts[2];  // 16-char parent/span ID
                } else {
                    log.warn("Invalid traceparent format (expected 4 parts): {}", traceparent);
                }
            }
            
            // Extract and restore W3C Baggage from message
            io.opentelemetry.api.baggage.BaggageBuilder baggageBuilder = Baggage.builder();
            for (Map.Entry<String, String> entry : messageValue.entrySet()) {
                String key = entry.getKey();
                if (key != null && key.startsWith("baggage.")) {
                    String baggageKey = key.substring(8); // Remove "baggage." prefix
                    String baggageValue = entry.getValue();
                    if (baggageValue != null) {
                        baggageBuilder.put(baggageKey, baggageValue);
                    }
                }
            }
            
            Baggage baggage = baggageBuilder.build();
            Context contextWithBaggage = !baggage.isEmpty() 
                ? baggage.storeInContext(Context.current())
                : Context.current();
            
            // If we have trace context, set it in OpenTelemetry Context so Observation can join the trace
            Context finalContext = contextWithBaggage;
            if (traceId != null && spanId != null) {
                // Normalize hex strings (pad/truncate to correct length for OpenTelemetry)
                String normalizedTraceId = normalizeHex(traceId, 32); // 32 hex chars = 128 bits
                String normalizedSpanId = normalizeHex(spanId, 16);  // 16 hex chars = 64 bits
                
                // Create SpanContext from the extracted trace and span IDs
                // OpenTelemetry expects hex strings, not byte arrays
                SpanContext parentSpanContext = SpanContext.createFromRemoteParent(
                        normalizedTraceId,
                        normalizedSpanId,
                        TraceFlags.getSampled(), // 01 flag means sampled
                        TraceState.getDefault()
                );
                
                // Set the SpanContext in the OpenTelemetry Context
                // This makes Observation create a child span that joins the parent trace
                finalContext = finalContext.with(Span.wrap(parentSpanContext));
            }
            
            // Create observation that will automatically join the parent trace if context is set
            Observation observation = Observation.createNotStarted("redis.stream.process", observationRegistry)
                .contextualName("Redis Stream: " + streamKey)
                .lowCardinalityKeyValue("stream", streamKey)
                .lowCardinalityKeyValue("group", groupName)
                .lowCardinalityKeyValue("consumer", consumerName)
                .highCardinalityKeyValue("message.id", record.getId().getValue());
            
            // Use observe() with trace context and baggage - it automatically starts, scopes, and stops the observation
            // The OpenTelemetry Context contains both the parent SpanContext (to join traces) and Baggage
            try (Scope scope = finalContext.makeCurrent()) {
                observation.observe(() -> {
                    try {
                        listener.onMessage(record); // process message
                        
                        // Acknowledge and delete message after successful processing
                        var connection = connectionFactory.getConnection();
                        var streamCommands = connection.streamCommands();
                        
                        // Acknowledge to remove from pending list
                        streamCommands.xAck(streamKey.getBytes(), groupName, record.getId());
                        
                        // Delete message from stream after successful processing
                        streamCommands.xDel(streamKey.getBytes(), record.getId());
                        
                        log.debug("Processed, acknowledged, and deleted Redis stream message: stream={}, id={}", 
                                streamKey, record.getId());
                    } catch (Exception e) {
                        log.error("Error processing message {} from stream {}: {}", 
                                record.getId(), streamKey, e.getMessage(), e);
                    }
                });
            }
            }
        };
    }
    
    /**
     * Ensures the consumer group exists, creating it only if necessary.
     * Checks if group exists before attempting creation to avoid unnecessary Redis commands.
     */
    private void ensureConsumerGroupExists(String streamKey, String groupName) {
        // Check if group already exists
        try {
            var groups = connectionFactory.getConnection().streamCommands().xInfoGroups(streamKey.getBytes());
            boolean groupExists = groups.stream()
                    .anyMatch(group -> groupName.equals(group.groupName()));
            if (groupExists) {
                return; // Group already exists, nothing to do
            }
        } catch (Exception e) {
            // Stream doesn't exist yet, will create it with mkstream flag
            log.debug("Stream {} does not exist yet, will create group", streamKey);
        }
        
        // Create group if it doesn't exist
        try {
            connectionFactory.getConnection().streamCommands().xGroupCreate(
                    streamKey.getBytes(),
                    groupName,
                    ReadOffset.from("0-0"),
                    true  // mkstream - creates stream if it doesn't exist
            );
            log.debug("Created consumer group: {} for stream: {}", groupName, streamKey);
        } catch (Exception e) {
            log.debug("Failed to create consumer group: {} for stream: {} - {}", 
                    groupName, streamKey, e.getMessage());
        }
    }
    
    /**
     * Normalize hex string to expected length (pad or truncate).
     * 
     * @param hex hex string (may have leading zeros or be too long)
     * @param expectedLength expected number of hex characters
     * @return normalized hex string
     */
    private String normalizeHex(String hex, int expectedLength) {
        if (hex == null || hex.isEmpty()) {
            return "0".repeat(expectedLength);
        }
        
        // Remove any dashes or 0x prefix
        hex = hex.replaceAll("-", "").replaceAll("^0[xX]", "");
        
        // Pad or truncate to expected length
        if (hex.length() < expectedLength) {
            hex = "0".repeat(expectedLength - hex.length()) + hex;
        } else if (hex.length() > expectedLength) {
            hex = hex.substring(hex.length() - expectedLength);
        }
        
        return hex;
    }

    @PreDestroy
    public void stopAll() {
        containers.values().forEach(StreamMessageListenerContainer::stop);
        virtualThreadExecutor.close();
    }
}



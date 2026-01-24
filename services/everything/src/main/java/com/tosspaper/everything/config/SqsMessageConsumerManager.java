package com.tosspaper.everything.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.messaging.MessageConsumerManager;
import com.tosspaper.models.messaging.MessageHandler;
import com.tosspaper.models.properties.SqsProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SQS implementation of MessageConsumerManager.
 * Auto-discovers MessageHandler beans and registers them with SQS queues.
 * This implementation is active when messaging.provider=sqs.
 * Uses SmartLifecycle for proper startup/shutdown ordering.
 */
@Component
@ConditionalOnProperty(name = "messaging.provider", havingValue = "sqs")
@Slf4j
public class SqsMessageConsumerManager implements MessageConsumerManager {

    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;
    private final ObservationRegistry observationRegistry;
    private final ObjectMapper objectMapper;
    private final List<MessageHandler<?>> handlers;

    private final ExecutorService messageExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService pollExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, String> queueUrlCache = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public SqsMessageConsumerManager(
            SqsClient sqsClient,
            SqsProperties sqsProperties,
            ObservationRegistry observationRegistry,
            ObjectMapper objectMapper,
            List<MessageHandler<?>> handlers) {
        this.sqsClient = sqsClient;
        this.sqsProperties = sqsProperties;
        this.observationRegistry = observationRegistry;
        this.objectMapper = objectMapper;
        this.handlers = handlers;
    }

    @Override
    public void start() {
        running = true;
        log.debug("Starting SQS consumers for {} handlers", handlers.size());

        handlers.forEach(handler -> {
            String queueName = handler.getQueueName();
            var config = sqsProperties.getQueues().get(queueName);

            if (config != null && config.isEnabled()) {
                String queueUrl = getQueueUrl(queueName);
                pollExecutor.submit(() -> pollLoop(queueName, queueUrl, handler, config));
                log.debug("Started SQS consumer for queue: {}", queueName);
            } else if (config == null) {
                log.warn("No SQS configuration found for queue: {}, skipping", queueName);
            } else {
                log.info("Queue {} is disabled, skipping", queueName);
            }
        });
    }

    private void pollLoop(String queueName, String queueUrl, MessageHandler<?> handler, SqsProperties.QueueConfig config) {
        log.info("Starting poll loop for queue: {}", queueName);

        while (running) {
            try {
                var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(config.getMaxMessages())
                        .waitTimeSeconds(config.getPollDelaySeconds())
                        .messageAttributeNames("All")
                        .build());

                for (var message : response.messages()) {
                    messageExecutor.submit(() -> processMessage(queueName, queueUrl, message, handler));
                }

            } catch (SqsException e) {
                log.error("SQS error polling queue {}: {}", queueName, e.getMessage());
            } catch (Exception e) {
                log.error("Error polling queue {}", queueName, e);
            }
        }

        log.info("Poll loop stopped for queue: {}", queueName);
    }

    @SuppressWarnings({"try", "unchecked"}) // Scope is used by try-with-resources to keep trace context active
    private void processMessage(String queueName, String queueUrl, Message message, MessageHandler<?> handler) {
        Context traceContext = extractTraceContext(message.messageAttributes());

        try (Scope scope = traceContext.makeCurrent()) {
            Map<String, String> body = parseMessageBody(message.body());

            Observation.createNotStarted("sqs.message.process", observationRegistry)
                    .contextualName("SQS Queue: " + queueName)
                    .lowCardinalityKeyValue("queue", queueName)
                    .highCardinalityKeyValue("message.id", message.messageId())
                    .observe(() -> ((MessageHandler<Map<String, String>>) handler).handle(body));

            // Delete on success
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());

            log.debug("Processed message {} from queue {}", message.messageId(), queueName);

        } catch (Exception e) {
            log.error("Failed to process message {} from queue {}, will retry",
                    message.messageId(), queueName, e);
            // Don't delete - message becomes visible after visibilityTimeout
        }
    }

    private Context extractTraceContext(Map<String, MessageAttributeValue> attrs) {
        Context context = Context.current();

        // Extract traceparent
        if (attrs.containsKey("traceparent")) {
            String traceparent = attrs.get("traceparent").stringValue();
            if (traceparent != null && traceparent.length() >= 55) {
                String[] parts = traceparent.split("-");
                if (parts.length == 4) {
                    String traceId = normalizeHex(parts[1], 32);
                    String spanId = normalizeHex(parts[2], 16);
                    SpanContext parentSpanContext = SpanContext.createFromRemoteParent(
                            traceId,
                            spanId,
                            TraceFlags.getSampled(),
                            TraceState.getDefault()
                    );
                    context = context.with(Span.wrap(parentSpanContext));
                }
            }
        }

        // Extract baggage
        BaggageBuilder baggageBuilder = Baggage.builder();
        attrs.forEach((key, value) -> {
            if (key.startsWith("baggage.")) {
                baggageBuilder.put(key.substring(8), value.stringValue());
            }
        });

        Baggage baggage = baggageBuilder.build();
        if (!baggage.isEmpty()) {
            context = baggage.storeInContext(context);
        }

        return context;
    }

    private String normalizeHex(String hex, int expectedLength) {
        if (hex == null || hex.isEmpty()) {
            return "0".repeat(expectedLength);
        }
        hex = hex.replaceAll("-", "").replaceAll("^0[xX]", "");
        if (hex.length() < expectedLength) {
            hex = "0".repeat(expectedLength - hex.length()) + hex;
        } else if (hex.length() > expectedLength) {
            hex = hex.substring(hex.length() - expectedLength);
        }
        return hex;
    }

    private Map<String, String> parseMessageBody(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse message body", e);
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

    @Override
    public void stop() {
        running = false;
        shutdownExecutors();
        log.info("SQS consumers stopped");
    }

    private void shutdownExecutors() {
        pollExecutor.shutdown();
        messageExecutor.shutdown();
        try {
            if (!pollExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                pollExecutor.shutdownNow();
            }
            if (!messageExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                messageExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollExecutor.shutdownNow();
            messageExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // Start last, stop first
    }
}

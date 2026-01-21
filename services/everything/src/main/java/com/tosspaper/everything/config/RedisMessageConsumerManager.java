package com.tosspaper.everything.config;

import com.tosspaper.everything.healthchecks.StreamHealthService;
import com.tosspaper.models.messaging.MessageConsumerManager;
import com.tosspaper.models.messaging.MessageHandler;
import com.tosspaper.models.properties.RedisStreamsProperties;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Redis implementation of MessageConsumerManager.
 * Auto-discovers MessageHandler beans and registers them with Redis Streams.
 *
 * This implementation is active when messaging.provider=redis (default).
 * Uses SmartLifecycle for proper startup/shutdown ordering.
 */
@Component
@ConditionalOnProperty(name = "messaging.provider", havingValue = "redis", matchIfMissing = true)
@Slf4j
public class RedisMessageConsumerManager implements MessageConsumerManager {

    private final RedisConnectionFactory connectionFactory;
    private final ObservationRegistry observationRegistry;
    private final RedisStreamsProperties streamsProperties;
    private final StreamHealthService healthService;
    private final List<MessageHandler<?>> handlers;

    private final Map<String, StreamMessageListenerContainer<String, MapRecord<String, String, String>>> containers = new ConcurrentHashMap<>();
    private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running = false;

    @Value("${spring.data.redis.stream.poll-timeout:2000}")
    private long pollTimeoutMs;

    @Value("${spring.data.redis.stream.batch-size:10}")
    private int batchSize;

    public RedisMessageConsumerManager(
            RedisConnectionFactory connectionFactory,
            ObservationRegistry observationRegistry,
            RedisStreamsProperties streamsProperties,
            StreamHealthService healthService,
            List<MessageHandler<?>> handlers) {
        this.connectionFactory = connectionFactory;
        this.observationRegistry = observationRegistry;
        this.streamsProperties = streamsProperties;
        this.healthService = healthService;
        this.handlers = handlers;
    }

    @Override
    public void start() {
        running = true;
        log.info("Starting Redis consumers for {} handlers", handlers.size());

        // Build lookup: queueName -> handler
        Map<String, MessageHandler<?>> handlerMap = handlers.stream()
                .collect(Collectors.toMap(MessageHandler::getQueueName, h -> h, (a, b) -> a));

        var streams = streamsProperties.getStreams();
        if (streams == null || streams.isEmpty()) {
            log.info("No Redis Streams configured");
            healthService.setInitialized(true);
            return;
        }

        int total = countTotalConsumers(streams);
        healthService.setTotalConsumers(total);

        streams.forEach((streamKey, groups) ->
                groups.forEach((groupName, groupConfig) ->
                        registerGroup(streamKey, groupName, groupConfig, handlerMap)));

        healthService.setInitialized(true);
        log.info("Redis consumers started: {}/{}", healthService.getRegisteredConsumers(), total);
    }

    private int countTotalConsumers(Map<String, Map<String, RedisStreamsProperties.GroupConfig>> streams) {
        return streams.values().stream()
                .mapToInt(groups -> groups.values().stream()
                        .mapToInt(groupConfig -> groupConfig.getConsumers().size())
                        .sum())
                .sum();
    }

    private void registerGroup(String streamKey, String groupName,
                               RedisStreamsProperties.GroupConfig groupConfig,
                               Map<String, MessageHandler<?>> handlerMap) {

        // Extract queue name from stream key (e.g., "ai-process" from "ai-process")
        String queueName = extractQueueName(streamKey);
        MessageHandler<?> handler = handlerMap.get(queueName);
        if (handler == null) {
            log.warn("No MessageHandler found for stream: {} (queue: {})", streamKey, queueName);
            return;
        }

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .executor(virtualThreadExecutor)
                        .pollTimeout(Duration.ofMillis(pollTimeoutMs))
                        .batchSize(batchSize)
                        .errorHandler(t -> log.warn("Error in Redis stream listener", t))
                        .build();

        String containerKey = streamKey + ":" + groupName;
        var container = containers.computeIfAbsent(containerKey, key -> {
            ensureConsumerGroupExists(streamKey, groupName);
            log.info("Creating container for stream: {}, group: {}", streamKey, groupName);
            return StreamMessageListenerContainer.create(connectionFactory, options);
        });

        // Register each consumer
        for (String consumerName : groupConfig.getConsumers()) {
            container.receive(
                    Consumer.from(groupName, consumerName),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()),
                    createWrappedListener(streamKey, groupName, consumerName, handler)
            );
            healthService.incrementRegisteredConsumers();
            log.debug("Registered consumer {} for stream {}", consumerName, streamKey);
        }

        if (!container.isRunning()) {
            container.start();
        }
    }

    private String extractQueueName(String streamKey) {
        // If stream key has a prefix (e.g., "prefix:queue-name"), extract just the queue name
        int colonIndex = streamKey.lastIndexOf(':');
        return colonIndex >= 0 ? streamKey.substring(colonIndex + 1) : streamKey;
    }

    @SuppressWarnings("unchecked")
    private StreamListener<String, MapRecord<String, String, String>> createWrappedListener(
            String streamKey, String groupName, String consumerName, MessageHandler<?> handler) {

        return record -> {
            log.debug("Received message from stream: {}, group: {}, consumer: {}, messageId: {}",
                    streamKey, groupName, consumerName, record.getId());

            Map<String, String> messageValue = record.getValue();
            Context finalContext = extractTraceContext(messageValue);

            try (Scope scope = finalContext.makeCurrent()) {
                Observation.createNotStarted("redis.stream.process", observationRegistry)
                        .contextualName("Redis Stream: " + streamKey)
                        .lowCardinalityKeyValue("stream", streamKey)
                        .lowCardinalityKeyValue("group", groupName)
                        .lowCardinalityKeyValue("consumer", consumerName)
                        .highCardinalityKeyValue("message.id", record.getId().getValue())
                        .observe(() -> {
                            try {
                                ((MessageHandler<Map<String, String>>) handler).handle(messageValue);
                                acknowledgeAndDelete(streamKey, groupName, record.getId());
                                log.debug("Processed message from stream: {}, id: {}", streamKey, record.getId());
                            } catch (Exception e) {
                                log.error("Error processing message {} from stream {}: {}",
                                        record.getId(), streamKey, e.getMessage(), e);
                            }
                        });
            }
        };
    }

    private Context extractTraceContext(Map<String, String> messageValue) {
        String traceparent = messageValue.get("traceparent");
        String traceId = null;
        String spanId = null;

        if (traceparent != null && traceparent.length() >= 55) {
            String[] parts = traceparent.split("-");
            if (parts.length == 4) {
                traceId = parts[1];
                spanId = parts[2];
            }
        }

        // Extract baggage
        BaggageBuilder baggageBuilder = Baggage.builder();
        for (Map.Entry<String, String> entry : messageValue.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith("baggage.")) {
                String baggageKey = key.substring(8);
                String baggageValue = entry.getValue();
                if (baggageValue != null) {
                    baggageBuilder.put(baggageKey, baggageValue);
                }
            }
        }

        Baggage baggage = baggageBuilder.build();
        Context context = !baggage.isEmpty()
                ? baggage.storeInContext(Context.current())
                : Context.current();

        if (traceId != null && spanId != null) {
            String normalizedTraceId = normalizeHex(traceId, 32);
            String normalizedSpanId = normalizeHex(spanId, 16);
            SpanContext parentSpanContext = SpanContext.createFromRemoteParent(
                    normalizedTraceId,
                    normalizedSpanId,
                    TraceFlags.getSampled(),
                    TraceState.getDefault()
            );
            context = context.with(Span.wrap(parentSpanContext));
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

    private void acknowledgeAndDelete(String streamKey, String groupName, org.springframework.data.redis.connection.stream.RecordId recordId) {
        var connection = connectionFactory.getConnection();
        var streamCommands = connection.streamCommands();
        streamCommands.xAck(streamKey.getBytes(), groupName, recordId);
        streamCommands.xDel(streamKey.getBytes(), recordId);
    }

    private void ensureConsumerGroupExists(String streamKey, String groupName) {
        try {
            var groups = connectionFactory.getConnection().streamCommands().xInfoGroups(streamKey.getBytes());
            boolean groupExists = groups.stream()
                    .anyMatch(group -> groupName.equals(group.groupName()));
            if (groupExists) {
                return;
            }
        } catch (Exception e) {
            log.debug("Stream {} does not exist yet, will create group", streamKey);
        }

        try {
            connectionFactory.getConnection().streamCommands().xGroupCreate(
                    streamKey.getBytes(),
                    groupName,
                    ReadOffset.from("0-0"),
                    true
            );
            log.debug("Created consumer group: {} for stream: {}", groupName, streamKey);
        } catch (Exception e) {
            log.debug("Failed to create consumer group: {} for stream: {} - {}",
                    groupName, streamKey, e.getMessage());
        }
    }

    @Override
    public void stop() {
        running = false;
        containers.values().forEach(StreamMessageListenerContainer::stop);
        log.info("Redis consumers stopped");
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

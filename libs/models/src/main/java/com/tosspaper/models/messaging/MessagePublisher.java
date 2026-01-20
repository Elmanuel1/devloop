package com.tosspaper.models.messaging;

import java.util.Map;

/**
 * Common interface for message publishers.
 * Implementations handle publishing messages to different providers (Redis Streams, SQS).
 *
 * Trace context (W3C traceparent) and baggage are automatically propagated
 * to enable distributed tracing across message boundaries.
 *
 * Switching between providers is controlled via:
 * <pre>
 * messaging:
 *   provider: redis  # or "sqs"
 * </pre>
 */
public interface MessagePublisher {

    /**
     * Publish a message to the specified queue.
     * The implementation will automatically:
     * - Add trace context (W3C traceparent) for distributed tracing
     * - Propagate W3C Baggage (user-id, tenant, etc.)
     *
     * @param queueName the target queue/stream name (without prefix)
     * @param message   the message payload as key-value pairs
     */
    void publish(String queueName, Map<String, String> message);
}

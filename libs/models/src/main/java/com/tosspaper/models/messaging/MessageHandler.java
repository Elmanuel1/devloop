package com.tosspaper.models.messaging;

/**
 * Provider-agnostic message handler interface.
 * Handlers are auto-discovered via List<MessageHandler> injection - no manual registration needed.
 * Implementations should be annotated with @Component.
 *
 * @param <T> the message payload type (typically Map<String, String> for raw messages)
 */
public interface MessageHandler<T> {

    /**
     * Returns the queue/stream name this handler processes.
     * This name is used to match handlers to their respective queues.
     * Examples: "email-local-uploads", "ai-process", "vector-store-ingestion"
     *
     * @return the queue name (without prefix)
     */
    String getQueueName();

    /**
     * Process the message.
     * Exceptions thrown from this method will trigger retry behavior:
     * - Redis: message remains in pending list
     * - SQS: message becomes visible again after visibility timeout
     *
     * @param message the message payload
     */
    void handle(T message);
}

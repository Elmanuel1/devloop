package com.tosspaper.models.service;

import com.tosspaper.models.messaging.MessagePublisher;

/**
 * Interface for publishing messages to Redis streams.
 * Extends the common MessagePublisher interface for provider abstraction.
 *
 * @deprecated Use {@link MessagePublisher} directly for new code.
 *             This interface is kept for backward compatibility during migration.
 */
@Deprecated
public interface RedisStreamPublisher extends MessagePublisher {
    // All methods inherited from MessagePublisher
    // - void publish(String queueName, Map<String, String> message)
}

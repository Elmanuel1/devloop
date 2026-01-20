package com.tosspaper.models.messaging;

import org.springframework.context.SmartLifecycle;

/**
 * Common interface for message consumer managers.
 * Implementations handle the lifecycle of message consumers for different providers (Redis, SQS).
 *
 * Uses SmartLifecycle for proper startup/shutdown ordering:
 * - start() is called after all beans are initialized
 * - stop() is called during graceful shutdown
 *
 * Handlers are auto-discovered via constructor injection of List<MessageHandler>.
 * No manual registration is required.
 *
 * Switching between providers is controlled via:
 * <pre>
 * messaging:
 *   provider: redis  # or "sqs"
 * </pre>
 */
public interface MessageConsumerManager extends SmartLifecycle {
    // SmartLifecycle provides: start(), stop(), isRunning(), getPhase()
    // No additional methods needed - handlers are auto-discovered
}

package com.tosspaper.aiengine.service;

import com.tosspaper.aiengine.api.dto.ReductoWebhookPayload;

/**
 * Service for processing Reducto webhook events.
 */
public interface ReductoWebhookService {
    void processWebhook(ReductoWebhookPayload payload);
}

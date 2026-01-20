package com.tosspaper.aiengine.api;

import com.tosspaper.aiengine.api.dto.ReductoWebhookPayload;
import com.tosspaper.aiengine.service.ReductoWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Webhook controller for receiving Reducto task updates.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/reducto")
@RequiredArgsConstructor
public class ReductoWebhookController {

    private final ReductoWebhookService reductoWebhookService;

    /**
     * Handle Reducto webhook events.
     * 
     * @param payload the webhook payload
     * @return 200 if processed successfully, 400 if invalid event type
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> handleWebhook(@RequestBody ReductoWebhookPayload payload) {
        log.info("Received Reducto webhook: jobId={}, status={}", 
                payload.getJobId(), payload.getStatus());

        try {
            reductoWebhookService.processWebhook(payload);
            return ResponseEntity.accepted().build();

        } catch (Exception e) {
            log.error("Error processing Reducto webhook for jobId: {}", payload.getJobId(), e);
            throw e;
        }
    }
}

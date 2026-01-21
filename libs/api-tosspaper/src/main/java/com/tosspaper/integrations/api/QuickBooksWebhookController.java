package com.tosspaper.integrations.api;

import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.integrations.quickbooks.webhook.QuickBooksWebhookValidator;
import com.tosspaper.models.messaging.MessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for QuickBooks webhook events.
 * Receives and validates QuickBooks event notifications, then publishes them to a Redis stream
 * for asynchronous processing.
 */
@Slf4j
@RestController
@RequestMapping("/v1/quickbooks")
@RequiredArgsConstructor
public class QuickBooksWebhookController {

    private static final String STREAM_NAME = "quickbooks-events";
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final QuickBooksProperties quickBooksProperties;
    private final MessagePublisher messagePublisher;

    /**
     * Webhook endpoint for QuickBooks event notifications.
     * Validates the signature and publishes the event to a Redis stream for asynchronous processing.
     *
     * @param payload the raw webhook payload (JSON string)
     * @param signature the signature from the `intuit-signature` header
     * @return HTTP 200 if validation succeeds and event is published, 401 if signature is invalid, 400 for bad requests
     */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "intuit-signature", required = false) String signature) {

        log.debug("Received QuickBooks webhook event");

        // Validate signature
        boolean isValid = QuickBooksWebhookValidator.validateSignature(
                payload,
                signature,
                quickBooksProperties.getWebhooks().getVerifierToken()
        );

        if (!isValid) {

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid signature"));
        }

        log.info("QuickBooks webhook signature validated successfully");

        // Publish to Redis stream for asynchronous processing
        Map<String, String> streamMessage = new HashMap<>();
        streamMessage.put("payload", payload);
        streamMessage.put("signature", signature);
        streamMessage.put("timestamp", OffsetDateTime.now().format(ISO_FORMATTER));

        messagePublisher.publish(STREAM_NAME, streamMessage);

        log.info("Published QuickBooks webhook event to stream: {}", STREAM_NAME);

        // Return 200 quickly (within 3 seconds as per QuickBooks requirements)
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Webhook event received and queued for processing"
        ));
    }
}


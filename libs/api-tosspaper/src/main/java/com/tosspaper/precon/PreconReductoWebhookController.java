package com.tosspaper.precon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svix.Webhook;
import com.svix.exceptions.WebhookVerificationException;
import com.tosspaper.common.ApiErrorMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.http.HttpHeaders;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Receives Reducto webhook callbacks, verifies Svix signature, and delegates to {@link ReductoWebhookHandlerService}. */
@Slf4j
@RestController
public class PreconReductoWebhookController {

    static final String SVIX_ID_HEADER        = "svix-id";
    static final String SVIX_TIMESTAMP_HEADER  = "svix-timestamp";
    static final String SVIX_SIGNATURE_HEADER  = "svix-signature";

    private final WebhookVerifier webhookVerifier;
    private final ReductoWebhookHandlerService handlerService;
    private final ObjectMapper objectMapper;

    @Autowired
    public PreconReductoWebhookController(ReductoWebhookProperties properties,
                                           ReductoWebhookHandlerService handlerService,
                                           ObjectMapper objectMapper) {
        String secret = properties.getSvixSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("[ReductoWebhook] svixSecret not configured — webhook signature verification DISABLED");
            this.webhookVerifier = (body, headers) -> {};
        } else {
            Webhook svixWebhook = new Webhook(secret);
            this.webhookVerifier = svixWebhook::verify;
        }
        this.handlerService = handlerService;
        this.objectMapper = objectMapper;
    }

    /** Package-private constructor for testing — injects a {@link WebhookVerifier} stub. */
    PreconReductoWebhookController(WebhookVerifier webhookVerifier,
                                    ReductoWebhookHandlerService handlerService,
                                    ObjectMapper objectMapper) {
        this.webhookVerifier = webhookVerifier;
        this.handlerService = handlerService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/internal/reducto/webhook")
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = SVIX_ID_HEADER,        required = false) String svixId,
            @RequestHeader(value = SVIX_TIMESTAMP_HEADER,  required = false) String svixTs,
            @RequestHeader(value = SVIX_SIGNATURE_HEADER,  required = false) String svixSig)
            throws WebhookVerificationException, JsonProcessingException {

        log.debug("[ReductoWebhook] Received webhook — svix-id={}", svixId);

        HttpHeaders svixHeaders = buildSvixHeaders(svixId, svixTs, svixSig);
        webhookVerifier.verify(rawBody, svixHeaders);
        log.debug("[ReductoWebhook] Signature verified for svix-id={}", svixId);

        ReductoWebhookPayload payload = objectMapper.readValue(rawBody, ReductoWebhookPayload.class);
        handlerService.handle(payload);

        return ResponseEntity.ok().build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Builds Svix {@link HttpHeaders}; throws {@link WebhookVerificationException} if any required header is null. */
    private HttpHeaders buildSvixHeaders(String svixId, String svixTs, String svixSig)
            throws WebhookVerificationException {
        if (svixId == null || svixTs == null || svixSig == null) {
            throw new WebhookVerificationException(ApiErrorMessages.WEBHOOK_INVALID_SIGNATURE);
        }
        Map<String, List<String>> headers = new HashMap<>();
        headers.put(SVIX_ID_HEADER,       List.of(svixId));
        headers.put(SVIX_TIMESTAMP_HEADER, List.of(svixTs));
        headers.put(SVIX_SIGNATURE_HEADER, List.of(svixSig));
        return HttpHeaders.of(headers, (k, v) -> true);
    }
}

package com.tosspaper.precon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.svix.Webhook;
import com.svix.exceptions.WebhookVerificationException;
import com.tosspaper.common.ApiErrorMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.http.HttpHeaders;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles inbound webhook callbacks from the Reducto extraction service.
 *
 * <h3>Security</h3>
 * <p>Every request is verified against the Svix signature headers
 * ({@code svix-id}, {@code svix-timestamp}, {@code svix-signature}) before the
 * payload body is read. Requests that fail verification are rejected immediately
 * with {@code 401 Unauthorized} — no database access occurs.
 *
 * <h3>Path</h3>
 * <p>{@code POST /internal/reducto/webhook} — intentionally not behind the
 * standard JWT bearer-token filter. The Svix signature is the sole auth
 * mechanism for this endpoint.
 *
 * <h3>Design</h3>
 * <p>This controller is intentionally thin: it verifies the signature and delegates
 * all business logic to {@link ReductoWebhookHandlerService}. No business logic
 * lives here.
 *
 * <h3>Testability</h3>
 * <p>Signature verification is delegated through the {@link WebhookVerifier} interface
 * rather than calling {@link Webhook} directly, since {@code Webhook} is {@code final}
 * and cannot be mocked in unit tests.
 */
@Slf4j
@RestController
public class PreconReductoWebhookController {

    static final String SVIX_ID_HEADER        = "svix-id";
    static final String SVIX_TIMESTAMP_HEADER  = "svix-timestamp";
    static final String SVIX_SIGNATURE_HEADER  = "svix-signature";

    private final WebhookVerifier webhookVerifier;
    private final ReductoWebhookHandlerService handlerService;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the controller with an eagerly-initialised Svix {@link WebhookVerifier}.
     * The verifier wraps a real {@link Webhook} instance created from the secret at startup,
     * so any misconfigured secret (e.g. blank) fails fast.
     *
     * @param properties     webhook configuration (Svix secret)
     * @param handlerService delegate that contains all business logic
     * @param objectMapper   Jackson mapper for payload deserialisation
     */
    @Autowired
    public PreconReductoWebhookController(ReductoWebhookProperties properties,
                                           ReductoWebhookHandlerService handlerService,
                                           ObjectMapper objectMapper) {
        Webhook svixWebhook = new Webhook(properties.getSvixSecret());
        this.webhookVerifier = svixWebhook::verify;
        this.handlerService = handlerService;
        this.objectMapper = objectMapper;
    }

    /**
     * Package-private constructor for testing — accepts an injectable {@link WebhookVerifier}
     * instead of building one from a properties object.
     *
     * @param webhookVerifier the verifier implementation (typically a Spy or stub in tests)
     * @param handlerService  delegate business-logic handler
     * @param objectMapper    Jackson mapper
     */
    PreconReductoWebhookController(WebhookVerifier webhookVerifier,
                                    ReductoWebhookHandlerService handlerService,
                                    ObjectMapper objectMapper) {
        this.webhookVerifier = webhookVerifier;
        this.handlerService = handlerService;
        this.objectMapper = objectMapper;
    }

    /**
     * Receives a webhook callback from Reducto via Svix.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Verify the Svix signature using the raw request body (before any parsing).
     *       Return {@code 401} on failure.</li>
     *   <li>Deserialise the payload into a {@link ReductoWebhookPayload}.</li>
     *   <li>Delegate to {@link ReductoWebhookHandlerService#handle(ReductoWebhookPayload)}.</li>
     *   <li>Return {@code 200 OK} on success.</li>
     * </ol>
     *
     * @param rawBody   the raw request body as a UTF-8 string (needed by Svix verifier)
     * @param svixId    the {@code svix-id} header value
     * @param svixTs    the {@code svix-timestamp} header value
     * @param svixSig   the {@code svix-signature} header value
     * @return {@code 200 OK} on success, {@code 401} on bad signature,
     *         or {@code 400} on malformed payload
     */
    @PostMapping("/internal/reducto/webhook")
    public ResponseEntity<Void> receiveWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = SVIX_ID_HEADER,        required = false) String svixId,
            @RequestHeader(value = SVIX_TIMESTAMP_HEADER,  required = false) String svixTs,
            @RequestHeader(value = SVIX_SIGNATURE_HEADER,  required = false) String svixSig) {

        log.debug("[ReductoWebhook] Received webhook — svix-id={}", svixId);

        // ── Step 1: Signature verification — must happen before any DB access ───
        try {
            HttpHeaders svixHeaders = buildSvixHeaders(svixId, svixTs, svixSig);
            webhookVerifier.verify(rawBody, svixHeaders);
        } catch (WebhookVerificationException e) {
            log.warn("[ReductoWebhook] Signature verification failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.debug("[ReductoWebhook] Signature verified for svix-id={}", svixId);

        // ── Step 2: Deserialise payload ──────────────────────────────────────────
        ReductoWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, ReductoWebhookPayload.class);
        } catch (Exception e) {
            log.warn("[ReductoWebhook] Failed to parse webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // ── Step 3: Delegate to handler service ─────────────────────────────────
        handlerService.handle(payload);

        return ResponseEntity.ok().build();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Constructs a {@link HttpHeaders} from the three Svix signature header values.
     * The Svix SDK requires these three headers to be present and non-null for verification.
     *
     * @param svixId  value of the {@code svix-id} header
     * @param svixTs  value of the {@code svix-timestamp} header
     * @param svixSig value of the {@code svix-signature} header
     * @return an {@code HttpHeaders} instance containing the three Svix headers
     * @throws WebhookVerificationException if any header value is null
     */
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

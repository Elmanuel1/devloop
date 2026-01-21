package com.tosspaper.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.config.SupabaseProperties;
import com.svix.Webhook;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supabase Custom Access Token Hook controller.
 * Called by Supabase during authentication to validate user access.
 *
 * This hook currently validates the webhook and returns the original claims.
 * Authorization is handled entirely via Redis cache + database queries
 * in CompanyPermissionEvaluator, not via JWT claims.
 *
 * Future enhancements may add custom claims here if needed.
 */
@Slf4j
@RestController
@RequestMapping("/auth/hooks")
@RequiredArgsConstructor
public class SupabaseAuthHookController {

    private final ObjectMapper objectMapper;
    private final SupabaseProperties supabaseProperties;

    /**
     * Custom Access Token Hook endpoint.
     * Called by Supabase to add custom claims to JWT during authentication.
     *
     * @param payload Raw webhook payload
     * @param webhookId Webhook ID header
     * @param webhookTimestamp Webhook timestamp header
     * @param webhookSignature Webhook signature header
     * @return Map containing custom claims to add to JWT
     */
    @PostMapping("/custom-access-token")
    @SneakyThrows
    public ResponseEntity<Map<String, Object>> customAccessToken(
            @RequestBody String payload,
            @RequestHeader("webhook-id") String webhookId,
            @RequestHeader("webhook-timestamp") String webhookTimestamp,
            @RequestHeader("webhook-signature") String webhookSignature) {

        Map<String, List<String>> headers = new HashMap<>();
        headers.put("webhook-id", List.of(webhookId));
        headers.put("webhook-timestamp", List.of(webhookTimestamp));
        headers.put("webhook-signature", List.of(webhookSignature));

        new Webhook(supabaseProperties.getWebhook().getSecret()).verify(payload, java.net.http.HttpHeaders.of(headers, (k, v) -> true));
        log.debug("Webhook signature verified successfully");

        // Parse payload to extract user email
        JsonNode jsonPayload = objectMapper.readTree(payload);
        // Get original claims from payload - we must return ALL claims (original + custom)
        JsonNode claimsNode = jsonPayload.path("claims");
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = objectMapper.convertValue(claimsNode, HashMap.class);
        String email = (String) claims.getOrDefault("email", "");

        if (email.isBlank()) {
            log.warn("No email found in webhook payload");
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Email not found in payload"));
        }

        log.info("Processing custom access token hook");
        log.debug("User email domain: {}", email.contains("@") ? email.substring(email.indexOf("@") + 1) : "unknown");
        // Return original claims (no custom claims added)
        return ResponseEntity.ok(Map.of("claims", claims));
    }
}

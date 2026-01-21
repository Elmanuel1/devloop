package com.tosspaper.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.exception.BadRequestException;
import com.tosspaper.models.exception.DuplicateException;
import com.tosspaper.company.CompanyService;
import com.tosspaper.generated.model.CompanyCreate;
import com.tosspaper.models.config.SupabaseProperties;
import com.tosspaper.models.service.EmailDomainService;
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
 * Supabase Before User Created Hook controller.
 * This endpoint is called by Supabase before creating a user account.
 * It validates the email domain and optionally auto-creates a company for new users.
 *
 * Logic:
 * - If metadata contains companyId: User is joining existing company (skip company creation)
 * - If no companyId in metadata: User is self-registering (auto-create company with user as owner)
 */
@Slf4j
@RestController
@RequestMapping("/auth/hooks")
@RequiredArgsConstructor
public class SupabaseBeforeUserCreatedHookController {

    private final ObjectMapper objectMapper;
    private final SupabaseProperties supabaseProperties;
    private final EmailDomainService emailDomainService;
    private final UserOnboardingService userOnboardingService;
    private final CompanyService companyService;

    /**
     * Before User Created Hook endpoint.
     * Called by Supabase before creating a user account to validate and prepare resources.
     *
     * @param payload Raw webhook payload
     * @param webhookId Webhook ID header
     * @param webhookTimestamp Webhook timestamp header
     * @param webhookSignature Webhook signature header
     * @return Empty map on success, error object on rejection
     */
    @PostMapping("/before-user-created")
    @SneakyThrows
    public ResponseEntity<Map<String, Object>> beforeUserCreated(
            @RequestBody String payload,
            @RequestHeader("webhook-id") String webhookId,
            @RequestHeader("webhook-timestamp") String webhookTimestamp,
            @RequestHeader("webhook-signature") String webhookSignature) {

        try {
            // Verify webhook signature
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("webhook-id", List.of(webhookId));
            headers.put("webhook-timestamp", List.of(webhookTimestamp));
            headers.put("webhook-signature", List.of(webhookSignature));

            new Webhook(supabaseProperties.getWebhook().getSecret()).verify(payload, java.net.http.HttpHeaders.of(headers, (k, v) -> true));
            log.debug("Webhook signature verified successfully");

            // Parse payload
            JsonNode jsonPayload = objectMapper.readTree(payload);
            JsonNode userNode = jsonPayload.path("user");
            String email = userNode.path("email").asText();
            String userId = userNode.path("id").asText();

            if (email.isBlank()) {
                log.warn("No email found in webhook payload");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", Map.of(
                                "http_code", 400,
                                "message", "Email not found in payload"
                        )));
            }

            log.info("Processing before user created hook for userId: {}", userId);
            log.debug("User email domain: {}", extractDomain(email));

            // Validate email domain
            String domain = extractDomain(email);
            if (emailDomainService.isBlockedDomain(domain)) {
                log.warn("Blocked domain detected: {}", domain);
                return ResponseEntity.badRequest()
                        .body(Map.of("error", Map.of(
                                "http_code", 400,
                                "message", "Email domain not allowed: " + domain
                        )));
            }

            // Check if user is joining existing company (has company_id in metadata)
            JsonNode metadataNode = jsonPayload.path("user_metadata");
            if  (metadataNode.isEmpty()) {
                metadataNode = jsonPayload.path("user").path("user_metadata");
            }

            // If company_id exists, user is being invited to existing company - auto-accept invitation
            if (metadataNode.has("company_id")) {
                JsonNode companyIdNode = metadataNode.get("company_id");
                if (!companyIdNode.isIntegralNumber()) {
                    log.error("Invalid company_id format in metadata for userId: {}", userId);
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", Map.of(
                                    "http_code", 400,
                                    "message", "Invalid company_id format in metadata"
                            )));
                }
                Long companyId = companyIdNode.asLong();
                log.info("User {} is being invited to company {}, auto-accepting invitation", userId, companyId);
                userOnboardingService.acceptInvitationAndCreateAuthorizedUser(companyId, email, userId);
                return ResponseEntity.ok(Map.of());
            }

            // User is self-registering - determine company name
            String companyName = capitalizeFirstLetter(domain.split("\\.")[0]);
            if (metadataNode.has("company_name")) {
                companyName = metadataNode.path("company_name").asText();
            }

            // Get country of incorporation from metadata
            String countryOfIncorporation = null;
            if (metadataNode.has("country_of_incorporation")) {
                countryOfIncorporation = metadataNode.path("country_of_incorporation").asText();
                log.debug("Found country_of_incorporation: {}", countryOfIncorporation);
            }

            // Create company with user as owner (atomic transaction)
            CompanyCreate companyCreate = new CompanyCreate()
                    .name(companyName)
                    .countryOfIncorporation(countryOfIncorporation);
            companyService.createCompany(companyCreate, email, userId);

            // Return success
            return ResponseEntity.ok(Map.of());
        } catch (Exception e) {
            log.error("Error processing before user created hook", e);
            if (e instanceof DuplicateException || e instanceof BadRequestException) {
                return  ResponseEntity.badRequest()
                        .body(Map.of("error", Map.of(
                                "http_code", 400,
                                "message", e.getMessage()
                        )));
            }

            return  ResponseEntity.badRequest()
                    .body(Map.of("error", Map.of(
                            "http_code", 400,
                            "message", "We could not process your request, please try again later"
                    )));
        }
    }

    /**
     * Extract domain from email address
     */
    private String extractDomain(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex < 0) {
            throw new IllegalArgumentException("Invalid email format: " + email);
        }
        return email.substring(atIndex + 1).toLowerCase();
    }

    /**
     * Capitalize first letter of a string
     */
    private String capitalizeFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}

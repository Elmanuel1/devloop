package com.tosspaper.emailengine.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.emailengine.api.dto.WebhookPayload;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.emailengine.provider.ProviderAdapterFactory;
import com.tosspaper.models.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.util.*;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WebhookController {
    private static final String EMAIL_MESSAGES_ENDPOINT = "/{provider}/email-messages";
    private static final String JSON_CONTENT_TYPE = MediaType.APPLICATION_JSON_VALUE;
    private static final String MULTIPART_CONTENT_TYPE = MediaType.MULTIPART_FORM_DATA_VALUE;
    private static final String FORM_URLENCODED_CONTENT_TYPE = MediaType.APPLICATION_FORM_URLENCODED_VALUE;

    private final ProviderAdapterFactory adapterFactory;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = EMAIL_MESSAGES_ENDPOINT, consumes = JSON_CONTENT_TYPE, produces = JSON_CONTENT_TYPE)
    public ResponseEntity<Object> handleJsonWebhook(
            @PathVariable String provider,
            @RequestBody String payload) {
        
        log.info("Received JSON webhook - Provider: {}", provider);
        WebhookPayload webhookPayload = WebhookPayload.fromJson(payload);
        return processWebhook(provider, webhookPayload);
    }

    @PostMapping(value = EMAIL_MESSAGES_ENDPOINT, consumes = MULTIPART_CONTENT_TYPE, produces = JSON_CONTENT_TYPE)
    public ResponseEntity<Object> handleMultipartWebhook(
            @PathVariable String provider,
            MultipartHttpServletRequest request) {
        
        Map<String, MultipartFile> files = request.getFileMap();
        log.info("Received multipart webhook - Provider: {}, attachments: {}", provider, files.size());

        String payload = extractPayloadFromMultipart(request);
        log.debug("Multipart webhook payload size: {} bytes", payload != null ? payload.length() : 0);

        WebhookPayload webhookPayload = WebhookPayload.fromMultipart(payload, files);

        return processWebhook(provider, webhookPayload);
    }

    @PostMapping(value = EMAIL_MESSAGES_ENDPOINT, consumes = FORM_URLENCODED_CONTENT_TYPE, produces = JSON_CONTENT_TYPE)
    public ResponseEntity<Object> handleFormUrlencodedWebhook(
            @PathVariable String provider,
            @RequestParam Map<String, String> formData) {
        
        log.info("Received form-urlencoded webhook - Provider: {}, fields: {}", provider, formData.size());
        log.debug("Form data field names: {}", formData.keySet());

        try {
            // Convert form data to JSON
            String payload = objectMapper.writeValueAsString(formData);
            log.debug("Form data converted to JSON, size: {} bytes", payload.length());

            WebhookPayload webhookPayload = WebhookPayload.fromJson(payload);
            return processWebhook(provider, webhookPayload);

        } catch (Exception e) {
            log.error("Failed to process form-urlencoded webhook for provider: {}", provider, e);
            return handleWebhookError(e, provider, "form-urlencoded");
        }
    }

    private ResponseEntity<Object> processWebhook(String provider, WebhookPayload webhookPayload) {
        log.info("Processing webhook - Provider: {}", provider);
        
        try {
            EmailMessage emailMessage = adapterFactory.getAdapter(provider)
                    .parse(webhookPayload);
            
            log.info("Parsed webhook - Message ID: {}, Subject: {}, Attachments: {}", 
                    emailMessage.getProviderMessageId(),
                    emailMessage.getSubject() != null && !emailMessage.getSubject().isEmpty() ? 
                        emailMessage.getSubject() : "(no subject)",
                    emailMessage.hasAttachments() ? emailMessage.getAttachments().size() : 0);
            
            log.info("Email details - From: {}, To: {}, Direction: {}", 
                    emailMessage.getFromAddress(), 
                    emailMessage.getToAddress(), 
                    emailMessage.getDirection());
            
            if (emailMessage.hasAttachments()) {
                log.info("Attachments: {}", 
                        emailMessage.getAttachments().stream()
                                .map(att -> att.getFileName() + " (" + att.getSizeBytes() + " bytes)")
                                .toList());
            }
            
            emailService.processWebhook(emailMessage);
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Webhook processed successfully",
                "provider", provider,
                "messageId", emailMessage.getProviderMessageId(),
                "timestamp", java.time.Instant.now().toString()
            ));
            
        } catch (Exception e) {
            return handleWebhookError(e, provider, webhookPayload.getJsonPayload());
        }
    }

    private String extractPayloadFromMultipart(MultipartHttpServletRequest request) {
        Map<String, String[]> parameterMap = request.getParameterMap();
        
        log.info("Multipart request contains {} fields: {}", 
                parameterMap.size(), 
                parameterMap.keySet());
        
        try {
            // Try common field names first for direct JSON payload
            String payload = request.getParameter("emailMessage");
            if (payload == null) payload = request.getParameter("payload");
            if (payload == null) payload = request.getParameter("data");
            if (payload == null) payload = request.getParameter("webhook");
            
            if (payload != null) {
                log.info("Found direct JSON payload in field");
                return payload;
            }
            
            // If no direct JSON payload, convert all parameters to JSON
            Map<String, Object> jsonPayload = new HashMap<>();
            for (String paramName : parameterMap.keySet()) {
                if (!paramName.equals("file") && !paramName.equals("attachment")) {
                    String[] values = parameterMap.get(paramName);
                    if (values.length == 1) {
                        jsonPayload.put(paramName, values[0]);
                    } else {
                        jsonPayload.put(paramName, values);
                    }
                }
            }
            
            String jsonString = objectMapper.writeValueAsString(jsonPayload);
            log.info("Converted multipart parameters to JSON: {}", jsonString);
            return jsonString;
            
        } catch (Exception e) {
            log.error("Failed to extract payload from multipart request", e);
            return "{}";
        }
    }

    private ResponseEntity<Object> handleWebhookError(Exception e, String provider, String payload) {
        log.error("Failed to process webhook - Provider: {}", provider, e);
        log.error("Exception type: {}, Stack trace: ", e.getClass().getSimpleName(), e);
        log.debug("Raw payload that caused error: {}", payload);
        
        return ResponseEntity.internalServerError().body(Map.of(
            "status", "error",
            "message", "Failed to process webhook",
            "provider", provider,
            "timestamp", java.time.Instant.now().toString()
        ));
    }
}
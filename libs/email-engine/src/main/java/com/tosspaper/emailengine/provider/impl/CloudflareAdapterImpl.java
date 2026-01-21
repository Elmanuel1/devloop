package com.tosspaper.emailengine.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.emailengine.api.dto.WebhookPayload;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.enums.MessageDirection;
import com.tosspaper.models.enums.MessageStatus;
import com.tosspaper.models.utils.EmailUtils;
import com.tosspaper.emailengine.provider.ProviderAdapter;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.google.common.hash.Hashing;

@Slf4j
public class CloudflareAdapterImpl implements ProviderAdapter {

    private static final String PROVIDER_NAME = "cloudflare";
    private final ObjectMapper objectMapper;
    
    public CloudflareAdapterImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean validateSignature(String payload, String signature, String secret) {
        // Cloudflare Email Workers don't use signature validation in the same way
        // This would be implemented if Cloudflare adds webhook signature validation
        log.debug("Signature validation not implemented for Cloudflare provider");
        return true;
    }

    @Override
    public EmailMessage parse(WebhookPayload webhookPayload) {
        try {
            JsonNode rootNode = objectMapper.readTree(webhookPayload.getJsonPayload());
            JsonNode emailMessageNode = rootNode.get("emailMessage");
            
            if (emailMessageNode == null) {
                throw new IllegalArgumentException("Missing 'emailMessage' field in Cloudflare webhook payload");
            }

            // Clean email addresses (extract from display name format if present)
            String fromAddress = getTextValue(emailMessageNode, "fromAddress");
            String toAddress = getTextValue(emailMessageNode, "toAddress");
            String cleanFromAddress = EmailUtils.cleanEmailAddress(fromAddress);
            String cleanToAddress = EmailUtils.cleanEmailAddress(toAddress);

            return EmailMessage.builder()
                    .provider(getTextValue(emailMessageNode, "provider"))
                    .providerMessageId(getTextValue(emailMessageNode, "providerMessageId"))
                    .inReplyTo(getTextValue(emailMessageNode, "inReplyTo"))
                    .fromAddress(cleanFromAddress)
                    .toAddress(cleanToAddress)
                    .cc(getTextValue(emailMessageNode, "cc"))
                    .bcc(getTextValue(emailMessageNode, "bcc"))
                    .subject(getTextValue(emailMessageNode, "subject"))
                    .bodyText(getTextValue(emailMessageNode, "bodyText"))
                    .bodyHtml(getTextValue(emailMessageNode, "bodyHtml"))
                    .headers(getTextValue(emailMessageNode, "headers"))
                    .direction(parseDirection(getTextValue(emailMessageNode, "direction")))
                    .status(parseStatus(getTextValue(emailMessageNode, "status")))
                        .providerTimestamp(parseTimestamp(getTextValue(emailMessageNode, "providerTimestamp")))
                        .attachments(parseAttachments(webhookPayload))
                        .build();

        } catch (Exception e) {
            log.error("Failed to parse Cloudflare webhook payload: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid Cloudflare webhook payload: " + e.getMessage(), e);
        }
    }

    private List<FileObject> parseAttachments(WebhookPayload webhookPayload) {
        // Cloudflare doesn't use multipart files, only JSON payload
        try {
            JsonNode rootNode = objectMapper.readTree(webhookPayload.getJsonPayload());
            JsonNode attachmentsNode = rootNode.get("attachments");
            JsonNode emailMessageNode = rootNode.get("emailMessage");
            
            // Extract email addresses and message info for key generation and metadata
            String fromAddress = null;
            String toAddress = null;
            String providerMessageId = null;
            if (emailMessageNode != null) {
                fromAddress = getTextValue(emailMessageNode, "fromAddress");
                toAddress = getTextValue(emailMessageNode, "toAddress");
                providerMessageId = getTextValue(emailMessageNode, "providerMessageId");
            }
            
            List<FileObject> fileObjects = new ArrayList<>();
            
            if (attachmentsNode != null && attachmentsNode.isArray()) {
                for (JsonNode attachmentNode : attachmentsNode) {
                    FileObject fileObject = parseAttachment(attachmentNode, fromAddress, toAddress, providerMessageId);
                    if (fileObject != null) {
                        fileObjects.add(fileObject);
                    }
                }
            }
            
            return fileObjects;

        } catch (Exception e) {
            log.error("Failed to parse Cloudflare attachments: {}", e.getMessage());
            return List.of(); // Return empty list instead of failing
        }
    }

    private FileObject parseAttachment(JsonNode attachmentNode, String fromAddress, String toAddress, String providerMessageId) {
        try {
            String fileName = getTextValue(attachmentNode, "fileName");
            String contentType = getTextValue(attachmentNode, "contentType");
            String base64Content = getTextValue(attachmentNode, "content");
            Long sizeBytes = getLongValue(attachmentNode, "sizeBytes");
            String description = getTextValue(attachmentNode, "description");
            String contentId = getTextValue(attachmentNode, "contentId");

            if (fileName == null || contentType == null) {
                log.warn("Skipping attachment with missing fileName or contentType");
                return null;
            }

            byte[] content = null;
            if (base64Content != null && !base64Content.isEmpty()) {
                try {
                    content = Base64.getDecoder().decode(base64Content);
                } catch (IllegalArgumentException e) {
                    log.warn("Failed to decode base64 content for attachment: {}", fileName);
                }
            }

            // Generate assigned ID
            String assignedId = generateAssignedId();
            
            // Create metadata map
            Map<String, String> metadata = new HashMap<>();
            if (providerMessageId != null) {
                metadata.put("provider-message-id", providerMessageId);
            }
            if (fromAddress != null) {
                metadata.put("from-address", fromAddress);
            }
            if (toAddress != null) {
                metadata.put("to-address", toAddress);
            }
            
            // Calculate checksum
            String checksum = content != null ? 
                Hashing.sha256().hashBytes(content).toString() : null;
            
            // Create FileObject with assigned ID and metadata
            FileObject fileObject = FileObject.builder()
                    .fileName(fileName)
                    .assignedId(assignedId)
                    .contentType(contentType)
                    .content(content)
                    .sizeBytes(sizeBytes != null ? sizeBytes : (content != null ? content.length : 0))
                    .description(description)
                    .contentId(contentId)
                    .checksum(checksum)
                    .metadata(metadata)
                    .build();

            // Generate key if email addresses are available
            if (fromAddress != null && toAddress != null && !fromAddress.isBlank() && !toAddress.isBlank()) {
                try {
                    fileObject = fileObject.withGeneratedKey(toAddress, fromAddress);
                } catch (IllegalArgumentException e) {
                    log.warn("Failed to generate key for attachment {}: {}", fileName, e.getMessage());
                    return null; // Skip this attachment
                }
            } else {
                log.warn("Cannot generate key for attachment {} - missing email addresses (from: {}, to: {}). Skipping attachment.", 
                    fileName, fromAddress, toAddress);
                return null; // Skip this attachment
            }

            return fileObject;

        } catch (Exception e) {
            log.error("Failed to parse individual attachment: {}", e.getMessage());
            return null;
        }
    }
    
    private String generateAssignedId() {
        return "cf-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        return (fieldNode != null && !fieldNode.isNull()) ? fieldNode.asText() : null;
    }

    private Long getLongValue(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        return (fieldNode != null && !fieldNode.isNull()) ? fieldNode.asLong() : null;
    }

    private MessageDirection parseDirection(String direction) {
        if (direction == null) {
            return MessageDirection.INCOMING; // Default
        }
        
        try {
            return MessageDirection.valueOf(direction.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown message direction: {}, defaulting to INCOMING", direction);
            return MessageDirection.INCOMING;
        }
    }

    private MessageStatus parseStatus(String status) {
        if (status == null) {
            return MessageStatus.RECEIVED; // Default
        }
        
        try {
            return MessageStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown message status: {}, defaulting to RECEIVED", status);
            return MessageStatus.RECEIVED;
        }
    }

    private OffsetDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return OffsetDateTime.now();
        }
        
        try {
            return OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            log.warn("Failed to parse timestamp: {}, using current time", timestamp);
            return OffsetDateTime.now();
        }
    }
}

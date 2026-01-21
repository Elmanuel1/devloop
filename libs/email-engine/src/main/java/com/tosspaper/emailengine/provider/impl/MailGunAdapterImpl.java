package com.tosspaper.emailengine.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.emailengine.api.dto.WebhookPayload;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.domain.FileObject;
import com.tosspaper.emailengine.provider.ProviderAdapter;
import com.tosspaper.models.enums.MessageDirection;
import com.tosspaper.models.enums.MessageStatus;
import com.tosspaper.models.utils.EmailUtils;
import com.google.common.hash.Hashing;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Mailgun webhook adapter for parsing inbound email webhooks.
 * Handles Mailgun's webhook format and multipart file attachments.
 */
@Slf4j
public class MailGunAdapterImpl implements ProviderAdapter {
    
    private static final String PROVIDER_NAME = "mailgun";
    private final ObjectMapper objectMapper;
    
    public MailGunAdapterImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }
    
    @Override
    public boolean validateSignature(String payload, String signature, String secret) {
        throw new UnsupportedOperationException("Signature validation not implemented yet");
    }
    
    @Override
    @SneakyThrows
    public EmailMessage parse(WebhookPayload webhookPayload) {
        try {
            JsonNode rootNode = objectMapper.readTree(webhookPayload.getJsonPayload());
            
            String fromAddress = getTextValue(rootNode, "From", "from");
            String toAddress = getTextValue(rootNode, "To", "recipient");
            String cc = getTextValue(rootNode, "Cc", "cc");
            String bcc = getTextValue(rootNode, "Bcc", "bcc");
            String messageId = getTextValue(rootNode, "Message-Id");
            String subject = getTextValue(rootNode, "Subject", "subject");
            String bodyText = getTextValue(rootNode, "body-plain", "Body-plain");
            String bodyHtml = getTextValue(rootNode, "body-html", "Body-html");
            String inReplyTo = getTextValue(rootNode, "In-Reply-To", "References");
            String timestampStr = getTextValue(rootNode, "timestamp");
            
            // Parse message headers as JSON string
            JsonNode headersNode = rootNode.get("message-headers");
            String headers = headersNode != null ? headersNode.toString() : null;
            
            // Parse timestamp (Unix timestamp in seconds)
            OffsetDateTime providerTimestamp = parseMailgunTimestamp(timestampStr);
            
            // Clean email addresses (extract from display name format if present)
            String cleanFromAddress = EmailUtils.cleanEmailAddress(fromAddress);
            String cleanToAddress = EmailUtils.cleanEmailAddress(toAddress);
            
            // Parse attachments
            List<FileObject> attachments = parseAttachments(webhookPayload, cleanFromAddress, cleanToAddress, messageId);
            
            return EmailMessage.builder()
                    .provider(PROVIDER_NAME)
                    .providerMessageId(messageId)
                    .inReplyTo(inReplyTo)
                    .fromAddress(cleanFromAddress)
                    .toAddress(cleanToAddress)
                    .cc(cc)
                    .bcc(bcc)
                    .subject(subject)
                    .bodyText(bodyText)
                    .bodyHtml(bodyHtml)
                    .headers(headers)
                    .direction(MessageDirection.INCOMING)
                    .status(MessageStatus.RECEIVED)
                    .providerTimestamp(providerTimestamp)
                    .attachments(attachments)
                    .build();
                    
        } catch (Exception e) {
            log.error("Failed to parse Mailgun webhook payload: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid Mailgun webhook payload: " + e.getMessage(), e);
        }
    }

    @SneakyThrows
    private List<FileObject> parseAttachments(WebhookPayload webhookPayload, String fromAddress, String toAddress, String providerMessageId) {
        if (!webhookPayload.hasFiles()) {
            log.debug("No files in webhook payload");
            return List.of();
        }
    
        JsonNode rootNode = objectMapper.readTree(webhookPayload.getJsonPayload());
        String attachmentCountStr = getTextValue(rootNode, "attachment-count", "Attachment-count");
        
        int attachmentCount = attachmentCountStr != null ? Integer.parseInt(attachmentCountStr) : 0;
        
        if (attachmentCount == 0) {
            log.info("No attachments in Mailgun webhook");
            return List.of();
        }
        
        log.info("Processing {} attachments from Mailgun webhook", attachmentCount);
        
        List<FileObject> fileObjects = new ArrayList<>();
        Map<String, MultipartFile> files = webhookPayload.getFiles();
        
        // Extract attachment-1, attachment-2, etc.
        for (int i = 1; i <= attachmentCount; i++) {
            String attachmentKey = "attachment-" + i;
            MultipartFile file = files.get(attachmentKey);
            
            if (file != null && !file.isEmpty()) {
                FileObject fileObject = parseMultipartFile(
                    file,
                    fromAddress,
                    toAddress,
                    providerMessageId
                );
                if (fileObject != null) {
                    fileObjects.add(fileObject);
                }
            } else {
                log.warn("Attachment {} not found or empty in webhook payload", attachmentKey);
            }
        }
        
        return fileObjects;
            
    
    }

    
    private FileObject parseMultipartFile(
            MultipartFile file,
            String fromAddress,
            String toAddress,
            String providerMessageId) {
        
        try {
            String fileName = file.getOriginalFilename();
            String contentType = file.getContentType();
            byte[] content = file.getBytes();
            long sizeBytes = file.getSize();
            
            if (fileName == null || contentType == null) {
                log.warn("Skipping attachment with missing fileName or contentType");
                return null;
            }
            
            // Generate assigned ID for Mailgun
            String assignedId = "mg-" + System.currentTimeMillis() + "-" +
                               UUID.randomUUID().toString().substring(0, 8);
            
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
            String checksum = Hashing.sha256().hashBytes(content).toString();
            
            // Create FileObject
            FileObject fileObject = FileObject.builder()
                    .fileName(fileName)
                    .assignedId(assignedId)
                    .contentType(contentType)
                    .content(content)
                    .sizeBytes(sizeBytes)
                    .checksum(checksum)
                    .metadata(metadata)
                    .build();
            
            // Generate key
            if (fromAddress != null && toAddress != null && !fromAddress.isBlank() && !toAddress.isBlank()) {
                try {
                    fileObject = fileObject.withGeneratedKey(toAddress, fromAddress);
                } catch (IllegalArgumentException e) {
                    log.warn("Failed to generate key for attachment {}: {}", fileName, e.getMessage());
                    return null;
                }
            } else {
                log.warn("Cannot generate key for attachment {} - missing email addresses", fileName);
                return null;
            }
            
            return fileObject;
            
        } catch (Exception e) {
            log.error("Failed to parse multipart file: {}", e.getMessage());
            return null;
        }
    }
    
    private String getTextValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode fieldNode = node.get(fieldName);
            if (fieldNode != null && !fieldNode.isNull()) {
                String value = fieldNode.asText();
                return (value != null && !value.isBlank()) ? value : null;
            }
        }
        return null;
    }
    
    private OffsetDateTime parseMailgunTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return OffsetDateTime.now();
        }
        
        try {
            long seconds = Long.parseLong(timestamp);
            return OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(seconds),
                java.time.ZoneOffset.UTC
            );
        } catch (Exception e) {
            log.warn("Failed to parse Mailgun timestamp: {}, using current time", timestamp);
            return OffsetDateTime.now();
        }
    }
}

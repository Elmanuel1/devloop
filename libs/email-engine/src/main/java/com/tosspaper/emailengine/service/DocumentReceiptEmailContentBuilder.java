package com.tosspaper.emailengine.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Builder for document receipt notification email content.
 */
@Component
@RequiredArgsConstructor
public class DocumentReceiptEmailContentBuilder {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a");
    
    /**
     * Build email subject for document receipt notification.
     */
    public String buildSubject(String trackingId) {
        return String.format("Document Received - %s", trackingId);
    }
    
    /**
     * Build email body for document receipt notification.
     */
    public String buildBody(String senderEmail, String trackingId, String fileName, String companyName, java.time.OffsetDateTime receivedAt) {
        String receivedDateStr = receivedAt.format(DATE_FORMATTER);
        
        String companyText = String.format("Your document will be available for review by %s.\n\n", companyName);
        
        return String.format(
            "Hello,\n\n" +
            "We have successfully received your document.\n\n" +
            "Details:\n" +
            "- File received: %s\n" +
            "- Received: %s\n" +
            "- Tracking ID: %s\n\n" +
            "%s" +
            "Best regards,\n" +
            "TossPaper Team",
            fileName,
            receivedDateStr,
            trackingId,
            companyText
        );
    }
}


package com.tosspaper.emailengine.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Builder for no-attachment notification email content.
 */
@Component
@RequiredArgsConstructor
public class NoAttachmentEmailContentBuilder {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a");
    
    /**
     * Build email subject for no-attachment notification.
     */
    public String buildSubject() {
        return "No Document Attached";
    }
    
    /**
     * Build email body for no-attachment notification.
     */
    public String buildBody(String senderEmail, String toAddress, String companyName, java.time.OffsetDateTime receivedAt) {
        String receivedDateStr = receivedAt.format(DATE_FORMATTER);
        
        return String.format(
            "Hello,\n\n" +
            "We received your email, but no document attachment was found.\n\n" +
            "Details:\n" +
            "- Received: %s\n\n" +
            "To send us a document, please send an email to %s with your document attached.\n\n" +
            "Best regards,\n" +
            "TossPaper Team",
            receivedDateStr,
            toAddress
        );
    }
}


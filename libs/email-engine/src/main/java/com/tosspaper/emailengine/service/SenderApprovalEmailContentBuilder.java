package com.tosspaper.emailengine.service;

import com.tosspaper.models.config.FrontendUrlProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * Builder for sender approval notification email content.
 */
@Component
@RequiredArgsConstructor
public class SenderApprovalEmailContentBuilder {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' h:mm a");
    
    private final FrontendUrlProperties frontendUrlProperties;
    
    /**
     * Build email subject for pending sender approval notification.
     */
    public String buildSubject(String senderEmail) {
        return String.format("New Sender Pending Approval - %s", senderEmail);
    }
    
    /**
     * Build email body for pending sender approval notification.
     */
    public String buildBody(String senderEmail, String companyName, java.time.OffsetDateTime receivedAt) {
        String reviewUrl = String.format("%s/dashboard/organization?tab=sender-approvals", 
            frontendUrlProperties.getBaseUrl());
        
        String receivedDateStr = receivedAt != null 
            ? receivedAt.format(DATE_FORMATTER)
            : "Just now";
        
        return String.format(
            "Hello,\n\n" +
            "A new email sender requires your approval.\n\n" +
            "Sender Details:\n" +
            "- Email: %s\n" +
            "- Received: %s\n\n" +
            "Please review and approve or reject this sender at your earliest convenience.\n\n" +
            "Review Senders: %s\n\n" +
            "(You will be prompted to log in if not already authenticated)\n\n" +
            "Best regards,\n" +
            "TossPaper Team",
            senderEmail,
            receivedDateStr,
            reviewUrl
        );
    }
}


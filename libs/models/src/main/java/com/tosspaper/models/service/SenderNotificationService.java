package com.tosspaper.models.service;

import com.tosspaper.models.domain.EmailAttachment;
import com.tosspaper.models.domain.EmailMessage;

/**
 * Service for sending email notifications to document senders.
 * Generic interface for all sender communications.
 */
public interface SenderNotificationService {

    /**
     * Send document receipt notification to sender when an attachment is successfully uploaded to S3.
     *
     * @param attachment the email attachment that was uploaded
     */
    void sendDocumentReceiptNotification(EmailAttachment attachment);

    /**
     * Send notification to sender when email is received without attachments.
     *
     * @param emailMessage the email message without attachments
     */
    void sendNoAttachmentNotification(EmailMessage emailMessage);

    /**
     * Send notification to sender when attachments have unsupported file types.
     *
     * @param emailMessage the email message containing invalid files
     * @param invalidFiles list of invalid FileObjects
     */
    void sendUnsupportedFileTypeNotification(EmailMessage emailMessage, java.util.List<com.tosspaper.models.domain.FileObject> invalidFiles);

    /**
     * Send invitation notification to an existing user to login and join a company.
     * Used when a user already has a Supabase account and cannot receive a new invitation via Supabase.
     *
     * @param email the user's email address
     * @param companyId the company ID they're being invited to
     * @param companyName the company name
     * @param roleName the role display name (e.g., "Admin", "Viewer")
     */
    void sendExistingUserInvitationNotification(String email, Long companyId, String companyName, String roleName);

    /**
     * Send notification to company about an integration sync conflict.
     * Used when pushing entities (vendors, purchase orders) to external systems fails due to conflicts.
     *
     * @param request the sync conflict notification request containing all conflict details
     */
    void sendSyncConflictNotification(SyncConflictNotificationRequest request);
}


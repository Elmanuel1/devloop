package com.tosspaper.emailengine.service;

import com.mailgun.api.v3.MailgunMessagesApi;
import com.mailgun.model.message.Message;
import com.mailgun.model.message.MessageResponse;
import com.tosspaper.emailengine.repository.EmailMessageRepository;
import com.tosspaper.models.config.MailgunProperties;
import com.tosspaper.models.domain.EmailAttachment;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.service.CompanyLookupService;
import com.tosspaper.models.service.SenderNotificationService;
import com.tosspaper.models.service.SyncConflictNotificationRequest;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Implementation of SenderNotificationService.
 * Sends email notifications to document senders (receipt notifications, no-attachment notifications).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SenderNotificationServiceImpl implements SenderNotificationService {
    
    private final MailgunMessagesApi mailgunMessagesApi;
    private final MailgunProperties mailgunProperties;
    private final DocumentReceiptEmailContentBuilder documentReceiptEmailContentBuilder;
    private final NoAttachmentEmailContentBuilder noAttachmentEmailContentBuilder;
    private final UnsupportedFileTypeEmailContentBuilder unsupportedFileTypeEmailContentBuilder;
    private final ExistingUserInvitationEmailContentBuilder existingUserInvitationEmailContentBuilder;
    private final SyncConflictEmailContentBuilder syncConflictEmailContentBuilder;
    private final CompanyLookupService companyLookupService;
    private final EmailMessageRepository emailMessageRepository;
    private final java.util.concurrent.ExecutorService emailNotificationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    @Override
    public void sendDocumentReceiptNotification(EmailAttachment attachment) {
        CompletableFuture.runAsync(() -> {
            try {
                // Look up email message from attachment
                EmailMessage email = emailMessageRepository.findById(attachment.getMessageId());
                
                // Extract values from email and attachment
                String senderEmail = email.getFromAddress();
                String trackingId = attachment.getAssignedId();
                String fileName = attachment.getFileName();
                OffsetDateTime receivedAt = email.getProviderTimestamp() != null 
                    ? email.getProviderTimestamp() 
                    : email.getCreatedAt();
                
                // Get company name
                var companyInfo = companyLookupService.getCompanyById(email.getCompanyId());
                String companyName = companyInfo.name();
                
                // Build email content
                String subject = documentReceiptEmailContentBuilder.buildSubject(trackingId);
                String body = documentReceiptEmailContentBuilder.buildBody(senderEmail, trackingId, fileName, companyName, receivedAt);
                
                // Send email via Mailgun
                Message message = Message.builder()
                        .from(mailgunProperties.getFromEmail())
                        .to(List.of(senderEmail))
                        .subject(subject)
                        .text(body)
                        .build();
                
                log.debug("Sending document receipt notification via Mailgun - Domain: {}, From: {}, To: {}, Subject: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), senderEmail, subject);
                
                MessageResponse response = mailgunMessagesApi.sendMessage(mailgunProperties.getDomain(), message);
                log.info("Document receipt notification email sent successfully - Message ID: {}, To: {}, Tracking ID: {}, File: {}", 
                        response.getMessage(), senderEmail, trackingId, fileName);
                        
            } catch (FeignException.Unauthorized e) {
                log.error("Mailgun authentication failed - Check API key configuration. Domain: {}, From: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), e);
                // Don't throw - we don't want to fail the upload if email fails
            } catch (Exception e) {
                log.error("Failed to send document receipt notification email - Attachment assignedId: {}",
                        attachment.getAssignedId(), e);
                // Don't throw - we don't want to fail the upload if email fails
            }
        }, emailNotificationExecutor);
    }
    
    @Override
    public void sendNoAttachmentNotification(EmailMessage emailMessage) {
        CompletableFuture.runAsync(() -> {
            try {
                // Extract values from email message
                String senderEmail = emailMessage.getFromAddress();
                String toAddress = emailMessage.getToAddress();
                OffsetDateTime receivedAt = emailMessage.getProviderTimestamp() != null 
                    ? emailMessage.getProviderTimestamp() 
                    : emailMessage.getCreatedAt();
                
                // Get company name
                var companyInfo = companyLookupService.getCompanyById(emailMessage.getCompanyId());
                String companyName = companyInfo.name();
                
                // Build email content
                String subject = noAttachmentEmailContentBuilder.buildSubject();
                String body = noAttachmentEmailContentBuilder.buildBody(senderEmail, toAddress, companyName, receivedAt);
                
                // Send email via Mailgun
                Message message = Message.builder()
                        .from(mailgunProperties.getFromEmail())
                        .to(List.of(senderEmail))
                        .subject(subject)
                        .text(body)
                        .build();
                
                log.debug("Sending no-attachment notification via Mailgun - Domain: {}, From: {}, To: {}, Subject: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), senderEmail, subject);
                
                MessageResponse response = mailgunMessagesApi.sendMessage(mailgunProperties.getDomain(), message);
                log.info("No-attachment notification email sent successfully - Message ID: {}, To: {}", 
                        response.getMessage(), senderEmail);
            } catch (FeignException.Unauthorized e) {
                log.error("Mailgun authentication failed - Check API key configuration. Domain: {}, From: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), e);
                // Don't throw - we don't want to fail processing if email fails
            } catch (Exception e) {
                log.error("Failed to send no-attachment notification email - Message ID: {}",
                        emailMessage.getId(), e);
                // Don't throw - we don't want to fail processing if email fails
            }
        }, emailNotificationExecutor);
    }
    
    @Override
    public void sendUnsupportedFileTypeNotification(EmailMessage emailMessage, List<com.tosspaper.models.domain.FileObject> invalidFiles) {
        CompletableFuture.runAsync(() -> {
            try {
                // Extract values from email message
                String senderEmail = emailMessage.getFromAddress();
                String toAddress = emailMessage.getToAddress();
                OffsetDateTime receivedAt = emailMessage.getProviderTimestamp() != null
                    ? emailMessage.getProviderTimestamp()
                    : emailMessage.getCreatedAt();

                // Build email content
                String subject = unsupportedFileTypeEmailContentBuilder.buildSubject();
                String body = unsupportedFileTypeEmailContentBuilder.buildBody(senderEmail, toAddress, invalidFiles, receivedAt);

                // Send email via Mailgun
                Message message = Message.builder()
                        .from(mailgunProperties.getFromEmail())
                        .to(List.of(senderEmail))
                        .subject(subject)
                        .text(body)
                        .build();

                log.debug("Sending unsupported file type notification via Mailgun - Domain: {}, From: {}, To: {}, Subject: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), senderEmail, subject);

                MessageResponse response = mailgunMessagesApi.sendMessage(mailgunProperties.getDomain(), message);
                log.info("Unsupported file type notification email sent successfully - Message ID: {}, To: {}, Invalid files: {}",
                        response.getMessage(), senderEmail, invalidFiles.size());
            } catch (FeignException.Unauthorized e) {
                log.error("Mailgun authentication failed - Check API key configuration. Domain: {}, From: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), e);
                // Don't throw - we don't want to fail processing if email fails
            } catch (Exception e) {
                log.error("Failed to send unsupported file type notification email - Message ID: {}",
                        emailMessage.getId(), e);
                // Don't throw - we don't want to fail processing if email fails
            }
        }, emailNotificationExecutor);
    }

    @Override
    public void sendExistingUserInvitationNotification(String email, Long companyId, String companyName, String roleName) {
        CompletableFuture.runAsync(() -> {
            try {
                // Build email content
                String subject = existingUserInvitationEmailContentBuilder.buildSubject(companyName);
                String body = existingUserInvitationEmailContentBuilder.buildBody(email, companyName, roleName, companyId);

                // Send email via Mailgun
                Message message = Message.builder()
                        .from(mailgunProperties.getFromEmail())
                        .to(List.of(email))
                        .subject(subject)
                        .text(body)
                        .build();

                log.debug("Sending existing user invitation notification via Mailgun - Domain: {}, From: {}, To: {}, Subject: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), email, subject);

                MessageResponse response = mailgunMessagesApi.sendMessage(mailgunProperties.getDomain(), message);
                log.info("Existing user invitation notification email sent successfully - Message ID: {}, To: {}, Company: {}, Role: {}",
                        response.getMessage(), email, companyName, roleName);
            } catch (FeignException.Unauthorized e) {
                log.error("Mailgun authentication failed - Check API key configuration. Domain: {}, From: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), e);
                // Don't throw - we don't want to fail invitation if email fails
            } catch (Exception e) {
                log.error("Failed to send existing user invitation notification email - Email: {}, Company ID: {}",
                        email, companyId, e);
                // Don't throw - we don't want to fail invitation if email fails
            }
        }, emailNotificationExecutor);
    }

    @Override
    public void sendSyncConflictNotification(SyncConflictNotificationRequest request) {
        CompletableFuture.runAsync(() -> {
            try {
                // Validate updatedBy email
                if (request.updatedBy() == null || request.updatedBy().isBlank()) {
                    log.warn("Cannot send sync conflict notification - updatedBy is null or blank: companyId={}", request.companyId());
                    return;
                }

                // Get company info for email content
                CompanyLookupService.CompanyBasicInfo company = companyLookupService.getCompanyById(request.companyId());

                // Build email content
                String subject = syncConflictEmailContentBuilder.buildSubject(company.name(), request.provider(), request.entityType(), request.entityName());
                String body = syncConflictEmailContentBuilder.buildBody(company.name(), request);

                // Send email via Mailgun to the person who updated it
                Message message = Message.builder()
                        .from(mailgunProperties.getFromEmail())
                        .to(List.of(request.updatedBy()))
                        .subject(subject)
                        .text(body)
                        .build();

                log.debug("Sending sync conflict notification via Mailgun - Domain: {}, From: {}, To: {}, Subject: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), request.updatedBy(), subject);

                MessageResponse response = mailgunMessagesApi.sendMessage(mailgunProperties.getDomain(), message);
                log.info("Sync conflict notification email sent successfully - Message ID: {}, To: {}, Company: {}, Entity: {}",
                        response.getMessage(), request.updatedBy(), company.name(), request.entityName());
            } catch (FeignException.Unauthorized e) {
                log.error("Mailgun authentication failed - Check API key configuration. Domain: {}, From: {}",
                        mailgunProperties.getDomain(), mailgunProperties.getFromEmail(), e);
                // Don't throw - we don't want to fail sync operation if email fails
            } catch (Exception e) {
                log.error("Failed to send sync conflict notification email - Company ID: {}, Entity: {}",
                        request.companyId(), request.entityName(), e);
                // Don't throw - we don't want to fail sync operation if email fails
            }
        }, emailNotificationExecutor);
    }
}


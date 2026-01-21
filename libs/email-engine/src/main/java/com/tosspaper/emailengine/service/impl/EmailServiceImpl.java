package com.tosspaper.emailengine.service.impl;

import com.tosspaper.emailengine.repository.EmailAttachmentRepository;
import com.tosspaper.emailengine.repository.EmailMessageRepository;
import com.tosspaper.emailengine.repository.EmailThreadRepository;
import com.tosspaper.models.service.EmailService;
import com.tosspaper.models.messaging.MessagePublisher;
import com.tosspaper.models.service.CompanyLookupService;
import com.tosspaper.models.service.SenderNotificationService;
import com.tosspaper.models.domain.AttachmentStatus;
import com.tosspaper.models.domain.EmailAttachment;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.enums.MessageStatus;
import com.tosspaper.models.service.StorageService;
import com.tosspaper.models.validation.FileValidationChain;
import com.tosspaper.models.validation.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Implementation of EmailService for processing email webhooks.
 * Handles thread management, message persistence, file uploads, and stream publishing.
 */
@Slf4j
@Service
public class EmailServiceImpl implements EmailService {

    private final EmailMessageRepository emailMessageRepository;
    private final EmailThreadRepository emailThreadRepository;
    private final EmailAttachmentRepository emailAttachmentRepository;
    private final CompanyLookupService companyLookupService;
    private final MessagePublisher streamPublisher;
    private final StorageService filesystemStorageService;
    private final SenderNotificationService senderNotificationService;
    private final FileValidationChain fileValidationChain;

    public EmailServiceImpl(
            EmailMessageRepository emailMessageRepository,
            EmailThreadRepository emailThreadRepository,
            EmailAttachmentRepository emailAttachmentRepository,
            CompanyLookupService companyLookupService,
            MessagePublisher streamPublisher,
            @Qualifier("filesystemStorageService") StorageService filesystemStorageService,
            SenderNotificationService senderNotificationService,
            FileValidationChain fileValidationChain) {
        this.emailMessageRepository = emailMessageRepository;
        this.emailThreadRepository = emailThreadRepository;
        this.emailAttachmentRepository = emailAttachmentRepository;
        this.companyLookupService = companyLookupService;
        this.streamPublisher = streamPublisher;
        this.filesystemStorageService = filesystemStorageService;
        this.senderNotificationService = senderNotificationService;
        this.fileValidationChain = fileValidationChain;
    }

    @Override
    public void processWebhook(EmailMessage emailMessage) {
        List<FileObject> attachments = emailMessage.getAttachments() != null ? emailMessage.getAttachments() : List.of();
        log.info("Processing webhook for email from {} to {} with {} attachments", 
                emailMessage.getFromAddress(), emailMessage.getToAddress(), attachments.size());

        // Lookup company by toAddress and set company_id
        var companyOpt = companyLookupService.getCompanyByAssignedEmail(emailMessage.getToAddress());
        if (companyOpt.isPresent()) {
            var company = companyOpt.get();
            emailMessage.setCompanyId(company.id());
            log.info("Resolved company '{}' (ID: {}) for email to {}", 
                    company.assignedEmail(), company.id(), emailMessage.getToAddress());
        } else {
            log.warn("Company not found for email: {}. Processing will continue without company_id.", emailMessage.getToAddress());
        }

        // Save or find the message
        if (emailMessage.getInReplyTo() != null) {
            var existingThread = emailThreadRepository.findByProviderThreadId(
                emailMessage.getProvider(), 
                emailMessage.getInReplyTo()
            );
            
            if (existingThread.isPresent()) {
                log.info("Found thread with id {} for email from {} to {}", 
                         existingThread.get().getId(), emailMessage.getFromAddress(), emailMessage.getToAddress());
                emailMessage.setThreadId(existingThread.get().getId());
                emailMessage.setId(emailMessageRepository.save(emailMessage).getId());
            } else {
                // Thread not found for reply, create new thread
                var savedMessage = emailMessageRepository.saveThreadAndMessage(createNewThread(emailMessage), emailMessage);
                emailMessage.setId(savedMessage.getId());
            }
        } else {
            // New message, save thread and message
            var savedMessage = emailMessageRepository.saveThreadAndMessage(createNewThread(emailMessage), emailMessage);
            emailMessage.setId(savedMessage.getId());
        }

        // Common processing path: claim, process, update
        claimAndProcessMessage(emailMessage);
    }

    private void claimAndProcessMessage(EmailMessage emailMessage) {
        // Atomically claim the message for processing (RECEIVED -> PROCESSING)
        boolean claimed = emailMessageRepository.updateStatus(
            emailMessage.getId(), 
            MessageStatus.RECEIVED, 
            MessageStatus.PROCESSING
        );
        
        if (!claimed) {
            log.debug("Duplicate webhook detected for message id={} - already claimed by another process", 
                      emailMessage.getId());
            return;
        }

        // Process attachments for messages we successfully claimed
        processAttachments(emailMessage);
        
        // Update status to PROCESSED after successful processing
        emailMessageRepository.updateStatus(emailMessage.getId(), null, MessageStatus.PROCESSED);
        log.debug("Updated message status to PROCESSED for id={}", emailMessage.getId());
    }



    private void processAttachments(EmailMessage emailMessage) {
        if (!emailMessage.hasAttachments()) {
            // Send no-attachment notification (asynchronously handled by service)
            senderNotificationService.sendNoAttachmentNotification(emailMessage);
            log.info("Scheduled no-attachment notification for message id={} to {}", 
                emailMessage.getId(), emailMessage.getFromAddress());
            return;
        }
        
        List<FileObject> attachments = emailMessage.getAttachments();
        
        log.info("Processing {} attachments for email message {}", attachments.size(), emailMessage.getId());
        
        // Validate all attachments and separate into valid and invalid
        List<FileObject> validAttachments = new java.util.ArrayList<>();
        List<FileObject> invalidFiles = new java.util.ArrayList<>();
        
        for (FileObject attachment : attachments) {
            ValidationResult validationResult = fileValidationChain.validate(attachment);
            if (validationResult.isValid()) {
                validAttachments.add(attachment);
            } else {
                invalidFiles.add(attachment);
                log.warn("Invalid attachment detected - File: {}, Type: {}, Violations: {}", 
                    attachment.getFileName(), attachment.getContentType(), validationResult.getViolationMessage());
            }
        }
        
        // Send notification for invalid files if any (asynchronously handled by service)
        if (!invalidFiles.isEmpty()) {
            senderNotificationService.sendUnsupportedFileTypeNotification(emailMessage, invalidFiles);
            log.info("Scheduled unsupported file type notification for message id={} with {} invalid files", 
                emailMessage.getId(), invalidFiles.size());
        }
        
        // Process only valid attachments
        for (FileObject attachment : validAttachments) {
            try {
                // Use StorageService to upload single file (handles sanitization only, validation already done)
                com.tosspaper.models.storage.UploadResult uploadResult = filesystemStorageService.uploadFile(attachment);
                if (uploadResult.isFailed()) {
                    log.error("Failed to upload attachment: {}", uploadResult.getErrorMessage(), uploadResult.getError());
                }
                // Create EmailAttachment record
                EmailAttachment emailAttachment = EmailAttachment.builder()
                    .messageId(emailMessage.getId())
                    .assignedId(attachment.getAssignedId())
                    .fileName(attachment.getFileName())
                    .contentType(attachment.getContentType())
                    .sizeBytes(attachment.getSizeBytes())
                    .storageUrl(attachment.getKey())
                    .localFilePath(Optional.ofNullable(uploadResult.key()).orElse(""))
                    .checksum(attachment.getChecksum())
                    .status(uploadResult.isSuccessful() ? AttachmentStatus.pending : AttachmentStatus.failed)
                    .build();
                
                // Save attachment record to database
                emailAttachmentRepository.saveAll(List.of(emailAttachment));
                
                if (uploadResult.isSuccessful()) {
                    java.util.Map<String, String> streamMessage = java.util.Map.of("assignedId", attachment.getAssignedId());
                    streamPublisher.publish("email-local-uploads", streamMessage);

                    log.info("Successfully published to email-local-uploads {}: {}",
                            attachment.getAssignedId(), uploadResult.key());
                } else {
                    log.warn("Failed to process attachment {}", attachment.getAssignedId(), uploadResult.error());
                }
                
            } catch (Exception e) {
                log.error("Failed to save attachment record {} for message {}", 
                         attachment.getAssignedId(), emailMessage.getId(), e);
            }
        }
    }
    

    private com.tosspaper.models.domain.EmailThread createNewThread(EmailMessage emailMessage) {
        return com.tosspaper.models.domain.EmailThread.builder()
            .subject(emailMessage.getSubject())
            .provider(emailMessage.getProvider())
            .providerThreadId(emailMessage.getInReplyTo() != null ? 
                emailMessage.getInReplyTo() : emailMessage.getProviderMessageId())
            .build();
    }

}

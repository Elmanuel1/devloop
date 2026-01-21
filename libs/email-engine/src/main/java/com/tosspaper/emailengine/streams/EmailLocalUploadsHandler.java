package com.tosspaper.emailengine.streams;

import com.tosspaper.emailengine.repository.ApprovedSenderRepository;
import com.tosspaper.emailengine.service.SenderValidationService;
import com.tosspaper.models.service.SenderApprovalNotificationService;
import com.tosspaper.models.service.SenderNotificationService;
import com.tosspaper.emailengine.service.dto.ValidationResult;
import com.tosspaper.models.domain.AttachmentStatus;
import com.tosspaper.models.domain.EmailAttachment;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.storage.S3UploadResult;
import com.tosspaper.emailengine.repository.EmailAttachmentRepository;
import com.tosspaper.emailengine.repository.EmailMessageRepository;
import com.tosspaper.models.service.StorageService;
import com.tosspaper.models.messaging.MessageHandler;
import com.tosspaper.models.messaging.MessagePublisher;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Listener for email local uploads stream events.
 * Processes messages from the email-local-uploads stream to handle S3 uploads.
 * Acknowledges messages only after successful upload.
 */
@Slf4j
@Component("emailLocalUploadsStreamListener")
public class EmailLocalUploadsHandler implements MessageHandler<Map<String, String>> {

    private static final String QUEUE_NAME = "email-local-uploads";

    @Override
    public String getQueueName() {
        return QUEUE_NAME;
    }

    @Override
    public void handle(Map<String, String> message) {
        processEmailAttachmentUpload(message);
    }

    private final EmailAttachmentRepository emailAttachmentRepository;
    private final StorageService s3StorageService;
    private final MessagePublisher messagePublisher;
    private final EmailMessageRepository emailMessageRepository;
    private final ApprovedSenderRepository approvedSenderRepository;
    private final SenderValidationService senderValidationService;
    private final SenderApprovalNotificationService senderApprovalNotificationService;
    private final SenderNotificationService senderNotificationService;
    
    public EmailLocalUploadsHandler(
            EmailAttachmentRepository emailAttachmentRepository,
            @Qualifier("s3StorageService") StorageService s3StorageService,
            MessagePublisher messagePublisher,
            EmailMessageRepository emailMessageRepository,
            ApprovedSenderRepository approvedSenderRepository,
            SenderValidationService senderValidationService,
            SenderApprovalNotificationService senderApprovalNotificationService,
            SenderNotificationService senderNotificationService) {
        this.emailAttachmentRepository = emailAttachmentRepository;
        this.s3StorageService = s3StorageService;
        this.messagePublisher = messagePublisher;
        this.emailMessageRepository = emailMessageRepository;
        this.approvedSenderRepository = approvedSenderRepository;
        this.senderValidationService = senderValidationService;
        this.senderApprovalNotificationService = senderApprovalNotificationService;
        this.senderNotificationService = senderNotificationService;
    }

    private void processEmailAttachmentUpload(Map<String, String> messageData) {
        
        String assignedId = messageData.get("assignedId");
        if (assignedId == null) {
            log.warn("Missing required field - assignedId");
            return;
        }

        emailAttachmentRepository.findByAssignedId(assignedId)
            .filter(attachment -> Set.of(AttachmentStatus.pending, AttachmentStatus.processing).contains(attachment.getStatus()))
            .map(emailAttachment -> emailAttachmentRepository.updateStatusToProcessing(emailAttachment.getAssignedId()))
            .ifPresentOrElse(this::uploadAndAuthorize, () -> log.warn("Email attachment not found or completed for assignedId: {}", assignedId));
    }

    private void uploadAndAuthorize(EmailAttachment attachment) {
        //this is where you get the to address from the email message and get company id so you can check the authorized user
        var email = emailMessageRepository.findByAttachmentId(attachment.getAssignedId());
        if (email.isEmpty()) {
            log.warn("Email message not found for attachment: {}", attachment.getAssignedId());
            return;
        }

        checkSenderAndProcess(email.get(), attachment);
    }
    
    private void checkSenderAndProcess(EmailMessage email, EmailAttachment attachment) {
        String fromAddress = email.getFromAddress();
        String toAddress = email.getToAddress();
        
        log.debug("Validating sender: {} for recipient: {}", fromAddress, toAddress);
        
        // Use validation service to check sender
        ValidationResult result = senderValidationService.validateSender(fromAddress, toAddress);
        
        switch (result.getAction()) {
            case APPROVE:
                log.info("Sender approved, uploading and sending to extraction: {}", fromAddress);
                uploadAndCompleteAttachment(email, attachment);
                break;
                
            case REJECT_GRACE_PERIOD:
                log.info("AUDIT: Sender rejected but within grace period | sender={} | scheduled_deletion_at={}", 
                        fromAddress, result.getScheduledDeletionAt());
                // Upload but DON'T send to extraction queue
                uploadToS3AndMarkComplete(email, attachment);
                break;
                
            case REJECT_BLOCK:
                log.warn("AUDIT: Sender blocked (past grace period) | sender={} | message={}", 
                        fromAddress, result.getMessage());
                // Don't upload, don't process
                break;
                
            case PENDING:
                log.info("Sender has pending approval or is new: {} | message={}", 
                        fromAddress, result.getMessage());
                // Upload to S3 so file is available when sender is approved later
                uploadToS3AndMarkComplete(email, attachment);
                // Create pending approval if it's a new sender
                createPendingApproval(fromAddress, result.getCompanyId());
                break;
            default:
                log.warn("Unknown validation action: {}", result.getAction());
        }
    }
    
    /**
     * Create pending approval for a new sender
     * Note: This is called when ValidationService returns PENDING with "New sender" message
     */
    private void createPendingApproval(String fromAddress, Long companyId) {
        try {
            log.info("Creating pending approval for new sender: {} at company: {}", fromAddress, companyId);
            
            // Create pending approval record
            com.tosspaper.models.domain.ApprovedSender pendingApproval = com.tosspaper.models.domain.ApprovedSender.builder()
                .companyId(companyId)
                .senderIdentifier(fromAddress)
                .whitelistType(com.tosspaper.models.enums.EmailWhitelistValue.EMAIL)
                .status(com.tosspaper.models.enums.SenderApprovalStatus.PENDING)
                .build();
            
            // Only insert if it doesn't exist - returns true if inserted, false if already exists
            boolean wasInserted = approvedSenderRepository.insertIfNotExists(pendingApproval);
            
            if (wasInserted) {
                log.info("Created new pending sender approval for {} at company {}", fromAddress, companyId);
                
                // Send email notification to company owner only for new insertions
                // Note: sendPendingSenderApprovalNotification handles all errors internally
                senderApprovalNotificationService.sendPendingSenderApprovalNotification(fromAddress, companyId);
            } else {
                log.debug("Pending sender approval already exists for {} at company {} - skipping email notification", 
                    fromAddress, companyId);
            }
            
        } catch (Exception e) {
            log.error("Failed to create pending approval for sender: {} at company: {}", fromAddress, companyId, e);
        }
    }
    
    /**
     * Upload to S3 and mark as complete (but don't send to extraction queue)
     * Used for rejected senders within grace period
     */
    private void uploadToS3AndMarkComplete(EmailMessage email, EmailAttachment attachment) {
        if (uploadToS3(email, attachment)) {
            log.info("Uploaded attachment {} from rejected sender (grace period) - NOT sending to extraction", 
                    attachment.getAssignedId());
        }
    }
    
    @SneakyThrows
    private boolean uploadToS3(EmailMessage email, EmailAttachment attachment) {
        
        log.info("Starting S3 upload for attachment {}: {}", 
                attachment.getAssignedId(), attachment.getLocalFilePath());

        byte[] fileContent = Files.readAllBytes(Paths.get(attachment.getLocalFilePath()));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("assigned-id", attachment.getAssignedId());
        metadata.put("message-id", attachment.getMessageId().toString());
        metadata.put("original-filename", attachment.getFileName());
        metadata.put("content-type", attachment.getContentType());
        metadata.put("size-bytes", attachment.getSizeBytes().toString());
        metadata.put("checksum", attachment.getChecksum());

        FileObject fileObject = FileObject.builder()
            .assignedId(attachment.getAssignedId())
            .fileName(attachment.getFileName())
            .contentType(attachment.getContentType())
            .content(fileContent)
            .key(attachment.getStorageUrl())
            .metadata(metadata)
            .build();

        S3UploadResult result = (S3UploadResult) s3StorageService.uploadFile(fileObject);

        Map<String, String> combinedMetadata = new HashMap<>();
        if (fileObject.getMetadata() != null) combinedMetadata.putAll(fileObject.getMetadata());
        if (result.metadata() != null) {
            combinedMetadata.putAll(result.metadata());
        }

         attachment = attachment.toBuilder()
            .region(result.region())
            .metadata(combinedMetadata)
            .endpoint(result.endpoint())
            .status(result.isSuccessful() ? AttachmentStatus.uploaded : AttachmentStatus.failed)
            .build();

        emailAttachmentRepository.updateStatus(attachment, AttachmentStatus.processing);
        
        if (result.isSuccessful()) {
            senderNotificationService.sendDocumentReceiptNotification(attachment);
            log.info("Sent document receipt notification for attachment assignedId={}, file={}", 
                attachment.getAssignedId(), attachment.getFileName());
        }
        
        return result.isSuccessful();
    }

    private void uploadAndCompleteAttachment(EmailMessage email, EmailAttachment attachment) {
        if (uploadToS3(email, attachment)) {
            sendCompletedMessageToAiProcess(attachment);
        }
    }

    private void sendCompletedMessageToAiProcess(EmailAttachment attachment) {
        try {
            Map<String, String> aiProcessMessage = new HashMap<>();
            aiProcessMessage.put("assignedId", attachment.getAssignedId());
            aiProcessMessage.put("storageUrl", attachment.getStorageUrl());

            messagePublisher.publish("ai-process", aiProcessMessage);

            log.info("Sent completed message to ai-process stream for attachment: {}, storage url {}",
                    attachment.getAssignedId(), attachment.getStorageUrl());

        } catch (Exception e) {
            log.error("Failed to send completed message to ai-process stream for attachment: {}",
                    attachment.getAssignedId(), e);
            throw new RuntimeException("Failed to publish ai-process message for attachment: " + attachment.getAssignedId(), e);
        }
    }
}

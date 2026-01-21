package com.tosspaper.emailengine.service.impl;

import com.tosspaper.emailengine.repository.ApprovedSenderRepository;
import com.tosspaper.emailengine.repository.EmailAttachmentRepository;
import com.tosspaper.models.service.EmailDomainService;
import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.models.domain.EmailAttachment;
import com.tosspaper.models.enums.EmailApprovalStatus;
import com.tosspaper.models.enums.EmailWhitelistValue;
import com.tosspaper.models.exception.ForbiddenException;
import com.tosspaper.models.service.CompanyLookupService;
import com.tosspaper.models.service.EmailApprovalService;
import com.tosspaper.models.messaging.MessagePublisher;
import com.tosspaper.models.utils.EmailUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailApprovalServiceImpl implements EmailApprovalService {

    private final EmailAttachmentRepository emailAttachmentRepository;
    private final ApprovedSenderRepository approvedSenderRepository;
    private final CompanyLookupService companyLookupService;
    private final EmailDomainService emailDomainService;
    private final MessagePublisher messagePublisher;
    
    @Value("${app.rejected-documents.retention-hours}")
    private int retentionHours;

    @Override
    public void approveSender(Long companyId, String senderIdentifier, EmailApprovalStatus approvalStatus, String userId) {
        // 1. Validate company exists
        companyLookupService.getCompanyById(companyId);
        
        // 2. Detect if senderIdentifier is email or domain and validate format
        boolean isEmail = EmailUtils.isValidEmail(senderIdentifier);
        boolean isDomain = !isEmail && EmailUtils.isValidDomain(senderIdentifier);
        
        if (!isEmail && !isDomain) {
            throw new ForbiddenException("Invalid sender identifier format. Must be a valid email (e.g., user@example.com) or domain (e.g., example.com)");
        }
        
        log.debug("Processing {} approval for {} {}", approvalStatus, isEmail ? "email" : "domain", senderIdentifier);

        // 3. Handle rejection
        if (approvalStatus == EmailApprovalStatus.REJECTED) {
            // Validate not rejecting public/disposable domain
            if (isDomain && emailDomainService.isBlockedDomain(senderIdentifier)) {
                throw new ForbiddenException("api.forbidden.domain", 
                    "You are not allowed to reject public email domain " + senderIdentifier);
            }
            
            // Calculate scheduled deletion time
            OffsetDateTime scheduledDeletionAt = OffsetDateTime.now().plusHours(retentionHours);
            
            if (isDomain) {
                // Reject entire domain - soft-delete all threads from that domain
                int threadsDeleted = approvedSenderRepository.rejectDomainAndSoftDeleteThreads(
                    companyId, senderIdentifier, userId, scheduledDeletionAt);
                
                log.info("Domain rejected | company_id={} | domain={} | rejected_by={} | threads_deleted={}", 
                    companyId, senderIdentifier, userId, threadsDeleted);
            } else {
                // Reject individual email - soft-delete all their threads
                int threadsDeleted = approvedSenderRepository.rejectEmailAndSoftDeleteThreads(
                    companyId, senderIdentifier, userId, scheduledDeletionAt);
            
                log.info("Email rejected | company_id={} | sender={} | rejected_by={} | threads_deleted={}", 
                    companyId, senderIdentifier, userId, threadsDeleted);
            }
            return;
        }
        
        if (approvalStatus == EmailApprovalStatus.PENDING_APPROVAL) {
            throw new ForbiddenException("Status not allowed for this approval");
        }

        // 4. Handle approval based on type (email or domain)
        List<EmailAttachment> attachments;
        
        if (!isEmail) {
            // Approve entire domain
            String domain = senderIdentifier;

            // Validate not personal or disposable domain
            if (emailDomainService.isBlockedDomain(domain)) {
                throw new ForbiddenException("api.forbidden.domain", 
                    "You are not allowed to approve " + domain);
            }

            // Approve domain and restore all threads from that domain atomically in repository
            int emailsUpdated = approvedSenderRepository.approveDomainAndRestoreThreads(companyId, domain, userId);
            log.info("Approved domain {} for company {} (updated {} email entries)", domain, companyId, emailsUpdated);
            
            // Get ALL attachments from this domain for extraction
            attachments = emailAttachmentRepository.findByDomain(domain);
            log.info("Found {} attachments from domain {} to process", attachments.size(), domain);
        } else {
            // Approve individual email
            ApprovedSender approvedSender = ApprovedSender.builder()
                .companyId(companyId)
                .senderIdentifier(senderIdentifier)
                .whitelistType(EmailWhitelistValue.EMAIL)
                .status(com.tosspaper.models.enums.SenderApprovalStatus.APPROVED)
                .approvedBy(userId)
                .scheduledDeletionAt(null)
                .build();
            
            int threadsRestored = approvedSenderRepository.approveEmailAndRestoreThreads(approvedSender);
            log.info("Approved email {} for company {} (restored {} threads)", senderIdentifier, companyId, threadsRestored);
            
            // Get ALL attachments from this sender for extraction
            attachments = emailAttachmentRepository.findByEmail(senderIdentifier);
            log.info("Found {} attachments from sender {} to process", attachments.size(), senderIdentifier);
        }

        // 5. Process attachments for extraction
        if (attachments.isEmpty()) {
            log.warn("No attachments found for sender {}", senderIdentifier);
            return;
        }

        // 8. Publish each attachment to extraction queue (ai-process stream)
        for (EmailAttachment attachment : attachments) {
            publishToExtractionQueue(attachment);
        }

        log.info("Approved sender and triggered extraction for {} attachments", attachments.size());
    }

    /**
     * Publish attachment to ai-process stream for extraction
     */
    private void publishToExtractionQueue(EmailAttachment attachment) {
        try {
            Map<String, String> message = new HashMap<>();
            message.put("assignedId", attachment.getAssignedId());
            message.put("storageUrl", attachment.getStorageUrl());

            messagePublisher.publish("ai-process", message);

            log.info("Published attachment {} to ai-process stream", attachment.getAssignedId());
        } catch (Exception e) {
            log.error("Failed to publish attachment {} to ai-process stream", 
                    attachment.getAssignedId(), e);
        }
    }

}


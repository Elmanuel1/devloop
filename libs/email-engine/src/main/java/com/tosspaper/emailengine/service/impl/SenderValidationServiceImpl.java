package com.tosspaper.emailengine.service.impl;

import com.tosspaper.emailengine.repository.ApprovedSenderRepository;
import com.tosspaper.emailengine.service.SenderValidationService;
import com.tosspaper.emailengine.service.dto.ValidationAction;
import com.tosspaper.emailengine.service.dto.ValidationResult;
import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.models.enums.SenderApprovalStatus;
import com.tosspaper.models.service.CompanyLookupService;
import com.tosspaper.models.utils.EmailPatternMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SenderValidationServiceImpl implements SenderValidationService {

    private final ApprovedSenderRepository approvedSenderRepository;
    private final CompanyLookupService companyLookupService;

    @Override
    public ValidationResult validateSender(String fromAddress, String toAddress) {
        log.debug("Validating sender: {} for recipient: {}", fromAddress, toAddress);
        
        // Look up company by assigned email
        var companyOpt = companyLookupService.getCompanyByAssignedEmail(toAddress);
        if (companyOpt.isEmpty()) {
            log.warn("No company found for recipient email: {}", toAddress);
            return ValidationResult.builder()
                    .action(ValidationAction.REJECT_BLOCK)
                    .message("Invalid recipient address - no company found")
                    .build();
        }
        
        Long companyId = companyOpt.get().id();
        
        // Get all approved senders for this company
        List<ApprovedSender> allSenders = approvedSenderRepository.findByCompanyId(companyId);
        
        // Extract domain from sender email
        String senderDomain = EmailPatternMatcher.extractDomain(fromAddress);
        String recipientDomain = EmailPatternMatcher.extractDomain(toAddress);
        
        // Auto-approve if sender domain matches recipient domain (same company)
        if (senderDomain != null && senderDomain.equalsIgnoreCase(recipientDomain)) {
            log.info("Auto-approving sender {} - same domain as recipient {}", fromAddress, toAddress);
            return ValidationResult.builder()
                    .action(ValidationAction.APPROVE)
                    .companyId(companyId)
                    .message("Same domain as recipient")
                    .build();
        }
        
        // Check for EXACT EMAIL match FIRST (email-level rules take precedence over domain rules)
        // This allows whitelisting specific individuals from a rejected domain
        var exactEmailMatch = allSenders.stream()
                .filter(sender -> sender.getWhitelistType() == com.tosspaper.models.enums.EmailWhitelistValue.EMAIL)
                .filter(sender -> sender.getSenderIdentifier().equalsIgnoreCase(fromAddress))
                .findFirst();
        
        if (exactEmailMatch.isPresent()) {
            ApprovedSender emailSender = exactEmailMatch.get();
            
            // If email is explicitly approved (takes precedence over domain rejection)
            if (emailSender.getStatus() == SenderApprovalStatus.APPROVED) {
                log.info("Sender {} is explicitly approved (email-level takes precedence) for company {}", fromAddress, companyId);
                return ValidationResult.builder()
                        .action(ValidationAction.APPROVE)
                        .companyId(companyId)
                        .message("Sender is explicitly approved")
                        .build();
            }
            
            // If email is explicitly rejected (takes precedence over domain approval)
            if (emailSender.getStatus() == SenderApprovalStatus.REJECTED) {
                log.info("Sender {} is explicitly rejected (email-level takes precedence) for company {}", fromAddress, companyId);
                return handleRejectedSender(emailSender, fromAddress, companyId);
            }
        }
        
        // Check for DOMAIN match (only applies if no email-level rule exists)
        // If a domain is approved/rejected, it applies to ALL emails from that domain (unless overridden by email-level rule)
        var domainMatch = allSenders.stream()
                .filter(sender -> sender.getWhitelistType() == com.tosspaper.models.enums.EmailWhitelistValue.DOMAIN)
                .filter(sender -> matchesSender(fromAddress, sender))
                .findFirst();
        
        if (domainMatch.isPresent()) {
            ApprovedSender domainSender = domainMatch.get();
            
            // If domain is approved, approve all emails from that domain (unless email-level rule exists)
            if (domainSender.getStatus() == SenderApprovalStatus.APPROVED) {
                log.info("Sender {} is approved via domain match for company {}", fromAddress, companyId);
                return ValidationResult.builder()
                        .action(ValidationAction.APPROVE)
                        .companyId(companyId)
                        .message("Sender domain is approved")
                        .build();
            }
            
            // If domain is rejected, reject all emails from that domain (unless email-level rule exists)
            if (domainSender.getStatus() == SenderApprovalStatus.REJECTED) {
                log.info("Sender {} is rejected via domain match for company {}", fromAddress, companyId);
                return handleRejectedSender(domainSender, fromAddress, companyId);
            }
        }
        
        // Check if sender already has pending approval
        boolean isPending = allSenders.stream()
                .filter(sender -> sender.getStatus() == SenderApprovalStatus.PENDING)
                .anyMatch(sender -> sender.getSenderIdentifier().equals(fromAddress));
        
        if (isPending) {
            log.info("Sender {} already has pending approval for company {}", fromAddress, companyId);
            return ValidationResult.builder()
                    .action(ValidationAction.PENDING)
                    .companyId(companyId)
                    .message("Sender has pending approval")
                    .build();
        }
        
        // New sender - needs approval
        log.info("Sender {} is new for company {}, needs approval", fromAddress, companyId);
        return ValidationResult.builder()
                .action(ValidationAction.PENDING)
                .companyId(companyId)
                .message("New sender, needs approval")
                .build();
    }
    
    /**
     * Handle rejected sender with grace period logic
     */
    private ValidationResult handleRejectedSender(ApprovedSender sender, String fromAddress, Long companyId) {
        // Check if scheduled_deletion_at is set
        if (sender.getScheduledDeletionAt() != null) {
            OffsetDateTime now = OffsetDateTime.now();
            
            // Check if we're past the grace period
            if (now.isAfter(sender.getScheduledDeletionAt())) {
                log.warn("AUDIT: Sender blocked (past retention) | company_id={} | sender={} | scheduled_deletion_at={}", 
                        companyId, fromAddress, sender.getScheduledDeletionAt());
                return ValidationResult.builder()
                        .action(ValidationAction.REJECT_BLOCK)
                        .companyId(companyId)
                        .message("Sender rejected and past grace period")
                        .build();
            }
            
            // Within grace period
            log.info("AUDIT: Sender rejected but within grace period | company_id={} | sender={} | scheduled_deletion_at={}", 
                    companyId, fromAddress, sender.getScheduledDeletionAt());
            return ValidationResult.builder()
                    .action(ValidationAction.REJECT_GRACE_PERIOD)
                    .companyId(companyId)
                    .scheduledDeletionAt(sender.getScheduledDeletionAt())
                    .message("Sender rejected but within grace period")
                    .build();
        } else {
            // Rejected but no scheduled_deletion_at set (shouldn't happen, but handle gracefully)
            log.warn("Rejected sender {} has no scheduled_deletion_at set", fromAddress);
            return ValidationResult.builder()
                    .action(ValidationAction.REJECT_BLOCK)
                    .companyId(companyId)
                    .message("Sender is rejected")
                    .build();
        }
    }
    
    /**
     * Check if email matches sender (exact email or domain match)
     */
    private boolean matchesSender(String email, ApprovedSender sender) {
        switch (sender.getWhitelistType()) {
            case EMAIL:
                return sender.getSenderIdentifier().equalsIgnoreCase(email);
            case DOMAIN:
                String domain = EmailPatternMatcher.extractDomain(email);
                return domain != null && domain.equalsIgnoreCase(sender.getSenderIdentifier());
            default:
                return false;
        }
    }
}


package com.tosspaper.emailengine.service;

import com.tosspaper.emailengine.repository.ApprovedSenderRepository;
import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.models.domain.PendingSenderApproval;
import com.tosspaper.models.enums.EmailWhitelistValue;
import com.tosspaper.models.paging.Paginated;
import com.tosspaper.models.enums.SenderApprovalStatus;
import com.tosspaper.models.service.ApprovedSendersManagementService;
import com.tosspaper.models.service.CompanyLookupService;
import com.tosspaper.models.utils.EmailPatternMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovedSendersManagementServiceImpl implements ApprovedSendersManagementService {

    private final ApprovedSenderRepository approvedSenderRepository;
    private final CompanyLookupService companyLookupService;

    /**
     * List approved senders for a company filtered by status with pagination
     * 
     * @param companyId Company ID
     * @param status Sender approval status filter
     * @param page Page number (1-indexed)
     * @param pageSize Number of items per page
     * @return Paginated list of approved senders with the specified status
     */
    public Paginated<ApprovedSender> listApprovedSenders(Long companyId, SenderApprovalStatus status, int page, int pageSize) {
        log.debug("Listing approved senders for company {} with status: {} (page: {}, pageSize: {})", companyId, status, page, pageSize);
        return approvedSenderRepository.findByCompanyIdAndStatus(companyId, page, pageSize, status);
    }

    @Override
    public List<PendingSenderApproval> listPendingDocuments(Long companyId) {
        log.debug("Listing pending documents for company {}", companyId);
        
        // Get assigned email for the company
        var company = companyLookupService.getCompanyById(companyId);
        String assignedEmail = company.assignedEmail();
        
        return approvedSenderRepository.findPendingDocumentsGroupedBySender(companyId, assignedEmail);
    }

    /**
     * Update an approved sender's email/domain and whitelist type
     * 
     * @param companyId Company ID
     * @param senderId Approved sender ID
     * @param email Email address to approve
     * @param whitelistValue Whitelist type (email or domain)
     * @return Updated approved sender
     */
    public ApprovedSender updateApprovedSender(Long companyId, String senderId, String email, EmailWhitelistValue whitelistValue) {
        log.info("Updating sender approval {} for company {} with email: {}, type: {}", senderId, companyId, email, whitelistValue);
        
        // Determine sender identifier based on whitelist type
        String senderIdentifier;
        if (whitelistValue == EmailWhitelistValue.DOMAIN) {
            // Extract domain from email
            senderIdentifier = EmailPatternMatcher.extractDomain(email);
            log.info("Extracted domain: {}", senderIdentifier);
        } else {
            // Use exact email
            senderIdentifier = email;
        }
        
        // Use upsert with the new email/domain
        ApprovedSender approvedSender = ApprovedSender.builder()
            .companyId(companyId)
            .senderIdentifier(senderIdentifier)
            .whitelistType(whitelistValue)
            .status(SenderApprovalStatus.APPROVED)
            .build();
        return approvedSenderRepository.upsert(approvedSender);
    }

    /**
     * Remove an approved sender
     * 
     * @param companyId Company ID
     * @param senderId Approved sender ID
     */
    public void removeApprovedSender(Long companyId, String senderId) {
        log.info("Removing approved sender {} for company {}", senderId, companyId);
        // Delete the sender record
        approvedSenderRepository.delete(senderId, companyId);
    }
}


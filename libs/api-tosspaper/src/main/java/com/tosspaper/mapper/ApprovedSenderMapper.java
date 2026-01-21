package com.tosspaper.mapper;

import com.tosspaper.generated.model.ApprovedSenderResponse;
import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.models.service.EmailDomainService;
import com.tosspaper.models.utils.EmailUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApprovedSenderMapper {

    private final EmailDomainService emailDomainService;

    public ApprovedSenderResponse toApiResponse(ApprovedSender domainModel) {
        ApprovedSenderResponse response = new ApprovedSenderResponse();
        response.setId(domainModel.getId());
        response.setCompanyId(domainModel.getCompanyId());
        response.setSenderIdentifier(domainModel.getSenderIdentifier());
        response.setWhitelistType(ApprovedSenderResponse.WhitelistTypeEnum.fromValue(domainModel.getWhitelistType().getValue()));
        response.setStatus(ApprovedSenderResponse.StatusEnum.fromValue(domainModel.getStatus().getValue()));
        response.setApprovedBy(domainModel.getApprovedBy());
        response.setApprovedAt(domainModel.getApprovedAt());
        response.setUpdatedAt(domainModel.getUpdatedAt());
        response.setCreatedAt(domainModel.getCreatedAt());
        response.setScheduledDeletionAt(domainModel.getScheduledDeletionAt());
        
        // Calculate domainAccessAllowed: extract domain from email and check if it's blocked
        String domain = extractDomain(domainModel.getSenderIdentifier());
        // Treat null/empty domain as not allowed
        if (domain == null || domain.isBlank()) {
            response.setDomainAccessAllowed(false);
        } else {
            response.setDomainAccessAllowed(!emailDomainService.isBlockedDomain(domain));
        }

        return response;
    }

    /**
     * Extract domain from email address or return as-is if already a domain.
     * Handles display-name formats like "Name <email@domain>".
     *
     * @param senderIdentifier the sender identifier (email or domain)
     * @return the extracted domain in lowercase, or null if extraction fails
     */
    private String extractDomain(String senderIdentifier) {
        if (senderIdentifier == null || senderIdentifier.isBlank()) {
            return null;
        }

        String identifier = senderIdentifier.trim();

        // Handle display-name format: "Name <email@domain>"
        if (identifier.contains("<") && identifier.contains(">")) {
            int start = identifier.indexOf('<');
            int end = identifier.indexOf('>');
            if (start < end) {
                identifier = identifier.substring(start + 1, end).trim();
            }
        }

        // Check if it's a valid email and extract domain
        if (EmailUtils.isValidEmail(identifier)) {
            int atIndex = identifier.lastIndexOf('@');
            if (atIndex > 0 && atIndex < identifier.length() - 1) {
                return identifier.substring(atIndex + 1).toLowerCase();
            }
            return null;
        }

        // Assume it's already a domain if it contains a dot and no @
        if (identifier.contains(".") && !identifier.contains("@")) {
            return identifier.toLowerCase();
        }

        return null;
    }
}


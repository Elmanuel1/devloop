package com.tosspaper.models.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Minimal lookup service for Company information.
 * Provides basic company data needed by other modules without full dependency.
 */
public interface CompanyLookupService {

    /**
     * Basic company information record.
     */
    record CompanyBasicInfo(Long id, String assignedEmail, String ownerEmail, String name) {}

    /**
     * Auto-approval settings for a company.
     */
    record AutoApprovalSettings(boolean enabled, BigDecimal threshold, String currency) {
        public boolean shouldAutoApprove(BigDecimal amount) {
            return enabled && threshold != null && amount != null && amount.compareTo(threshold) < 0;
        }
    }
    
    /**
     * Get a company by ID.
     *
     * @param companyId the company ID
     * @return the company with basic fields (id, assignedEmail)
     */
    CompanyBasicInfo getCompanyById(Long companyId);

    /**
     * Get a company by assigned email address.
     *
     * @param assignedEmail the company's assigned email address
     * @return the company with basic fields (id, assignedEmail) if found
     */
    Optional<CompanyBasicInfo> getCompanyByAssignedEmail(String assignedEmail);

    /**
     * Get auto-approval settings for a company.
     *
     * @param companyId the company ID
     * @return the auto-approval settings
     */
    AutoApprovalSettings getAutoApprovalSettings(Long companyId);
}

package com.tosspaper.models.service;

/**
 * Service for checking email domain types (personal, disposable, etc.)
 */
public interface EmailDomainService {
    
    /**
     * Check if the given domain is blocked (either disposable or personal)
     * @param domain The domain to check (e.g., "gmail.com", "tempmail.com")
     * @return true if the domain is blocked
     */
    boolean isBlockedDomain(String domain);
}


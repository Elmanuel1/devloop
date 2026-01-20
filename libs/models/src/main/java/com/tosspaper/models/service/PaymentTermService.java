package com.tosspaper.models.service;

import com.tosspaper.models.domain.PaymentTerm;

import java.util.List;

/**
 * Service for payment terms operations.
 * Used for syncing payment terms from external providers.
 */
public interface PaymentTermService {
    
    /**
     * Upsert payment terms from provider.
     * Creates new terms or updates existing ones based on provider + external_id.
     * 
     * @param companyId company ID
     * @param provider provider name (e.g., "QUICKBOOKS")
     * @param terms list of payment terms to upsert
     */
    void upsertFromProvider(Long companyId, String provider, List<PaymentTerm> terms);
}




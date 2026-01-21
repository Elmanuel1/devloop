package com.tosspaper.payment_terms;

import com.tosspaper.models.domain.PaymentTerm;

import java.util.List;

/**
 * Repository for payment terms operations.
 * Used for syncing payment terms from external providers.
 */
public interface PaymentTermRepository {
    
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




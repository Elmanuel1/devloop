package com.tosspaper.models.service;

import com.tosspaper.models.common.PushResult;
import com.tosspaper.models.domain.Invoice;

import java.util.List;
import java.util.Map;

/**
 * Service for invoice/bill sync operations.
 * Used for syncing invoices to external providers.
 */
public interface InvoiceSyncService {

    /**
     * Upsert invoices from provider (Bills pulled from QuickBooks).
     *
     * @param companyId company ID
     * @param invoices  list of invoices to upsert
     */
    void upsertFromProvider(Long companyId, List<Invoice> invoices);

    /**
     * Find invoices that need to be pushed to external system.
     *
     * @param companyId the company ID
     * @param provider  the integration provider name (e.g., "quickbooks")
     * @param limit     maximum number of records to return
     * @return list of invoices that need pushing
     */
    List<Invoice> findNeedingPush(Long companyId, String provider, int limit);

    /**
     * Find accepted invoices that need to be pushed to external system.
     * Returns invoices where status='accepted' and (updated_at > last_sync_at OR last_sync_at IS NULL).
     * Excludes permanently failed invoices and those exceeding max retry attempts.
     *
     * @param companyId the company ID
     * @param limit     maximum number of records to return
     * @return list of accepted invoices that need pushing
     */
    List<Invoice> findAcceptedNeedingPush(Long companyId, int limit);

    /**
     * Mark invoices as pushed with their external IDs and sync timestamps.
     * Resets retry tracking on successful push.
     *
     * @param results list of push results containing document ID, external ID, and sync timestamp
     * @return number of invoices successfully marked as pushed
     */
    int markAsPushed(List<PushResult> results);

    /**
     * Increment retry count for a failed invoice push attempt.
     *
     * @param invoiceId the invoice ID
     * @param errorMessage the error message from the failed attempt
     */
    void incrementRetryCount(String invoiceId, String errorMessage);

    /**
     * Mark an invoice as permanently failed (non-retryable error or exceeded max retries).
     *
     * @param invoiceId the invoice ID
     * @param errorMessage the error message explaining the failure
     */
    void markAsPermanentlyFailed(String invoiceId, String errorMessage);

    /**
     * Reset retry tracking for an invoice (used by admin operations).
     *
     * @param invoiceId the invoice ID
     */
    void resetRetryTracking(String invoiceId);

    /**
     * Find an invoice by ID.
     *
     * @param invoiceId the invoice ID
     * @return the invoice, or null if not found
     */
    Invoice findById(String invoiceId);
}

package com.tosspaper.invoices;

import com.tosspaper.models.common.PushResult;
import com.tosspaper.models.domain.Invoice;

import java.util.List;
import java.util.Map;

/**
 * Repository for invoice/bill sync operations.
 * Used for pushing invoices to external providers (e.g., QuickBooks Bills).
 */
public interface InvoiceSyncRepository {

    /**
     * Find invoices that need to be pushed to external system.
     * Returns invoices where:
     * - last_sync_at IS NULL (invoices are only pushed once, never updated after creation)
     * - push_permanently_failed = false
     * - push_retry_count < maxRetries
     * - po_number IS NOT NULL (invoice must have a PO number)
     * - Linked PO (by po_number = display_id) must not have line items missing both item_id and account_id
     *
     * @param companyId the company ID
     * @param limit maximum number of records to return
     * @param maxRetries maximum number of retry attempts allowed
     * @return list of Invoice objects that need pushing
     */
    List<Invoice> findNeedingPush(Long companyId, int limit, int maxRetries);

    /**
     * Mark invoices as pushed successfully.
     * Sets external_id, last_sync_at, provider, and resets retry tracking.
     *
     * @param results list of push results
     * @return number of records successfully marked as pushed
     */
    int markAsPushed(List<PushResult> results);

    /**
     * Increment retry count for a failed push attempt.
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


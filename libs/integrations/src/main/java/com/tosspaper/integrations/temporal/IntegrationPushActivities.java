package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.Invoice;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Activities for PUSH operations (pushing data to QuickBooks).
 * Handles pushing accepted invoices as Bills to external providers.
 */
@ActivityInterface
public interface IntegrationPushActivities {

    /**
     * Get connection data by ID.
     * Fetches and decrypts tokens for use in subsequent activities.
     */
    @ActivityMethod(name = "BillPushGetConnection")
    SyncConnectionData getConnection(String connectionId);

    /**
     * Fetch accepted invoices that need to be pushed to external system.
     * Returns invoices where status='accepted' and last_sync_at IS NULL.
     *
     * @param connection the connection data
     * @param limit maximum number of invoices to fetch
     * @return list of invoices needing push
     */
    @ActivityMethod
    List<Invoice> fetchAcceptedInvoicesNeedingPush(@NotNull SyncConnectionData connection, int limit);

    /**
     * Push invoices as Bills to QuickBooks.
     *
     * @param connection the connection data
     * @param invoices list of invoices to push
     * @return map of invoice ID to sync result
     */
    @ActivityMethod
    Map<String, SyncResult> pushInvoicesAsBills(@NotNull SyncConnectionData connection, List<Invoice> invoices);

    /**
     * Mark invoices as pushed after successful sync.
     * Updates last_sync_at and external_id in the database.
     *
     * @param results map of invoice ID to sync result (containing external ID and timestamp)
     * @return number of invoices successfully marked as pushed
     */
    @ActivityMethod
    int markInvoicesAsPushed(Map<String, SyncResult> results);
}


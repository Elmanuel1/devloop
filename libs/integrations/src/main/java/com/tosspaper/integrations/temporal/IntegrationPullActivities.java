package com.tosspaper.integrations.temporal;

import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PaymentTerm;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.Item;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Activities for PULL operations (fetching data from QuickBooks).
 * Separate from IntegrationSyncActivities to follow one interface per workflow concern pattern.
 */
@ActivityInterface
public interface IntegrationPullActivities {

    /**
     * Get connection data by ID.
     * Fetches and decrypts tokens for use in subsequent activities.
     *
     * @param connectionId the connection ID
     * @return connection data with decrypted tokens
     */
    @ActivityMethod(name = "PullGetConnection")
    SyncConnectionData getConnection(String connectionId);

    /**
     * Get current time from activity (for deterministic workflow execution).
     *
     * @return current time
     */
    @ActivityMethod
    OffsetDateTime getCurrentTime();

    /**
     * Fetch vendors from QuickBooks since last sync.
     *
     * @param connection the connection
     * @return list of vendors to upsert locally
     */
    @ActivityMethod
    List<Party> fetchVendorsSinceLastSync(@NotNull SyncConnectionData connection);

    /**
     * Store vendors in contacts table.
     *
     * @param connection connection data
     * @param vendors vendors to store
     */
    @ActivityMethod
    void storeVendorsInContacts(@NotNull SyncConnectionData connection, List<Party> vendors);

    /**
     * Fetch accounts from QuickBooks since last sync.
     *
     * @param connection the connection
     * @return list of accounts to upsert locally
     */
    @ActivityMethod
    List<IntegrationAccount> fetchAccountsSinceLastSync(@NotNull SyncConnectionData connection);

    /**
     * Store accounts in integration_accounts table.
     *
     * @param connection connection data
     * @param accounts accounts to store
     */
    @ActivityMethod
    void storeAccounts(@NotNull SyncConnectionData connection, List<IntegrationAccount> accounts);

    /**
     * Fetch payment terms from QuickBooks since last sync.
     *
     * @param connection the connection
     * @return list of payment terms to upsert locally
     */
    @ActivityMethod
    List<PaymentTerm> fetchPaymentTermsSinceLastSync(@NotNull SyncConnectionData connection);

    /**
     * Store payment terms in payment_terms table.
     *
     * @param connection connection data
     * @param terms payment terms to store
     */
    @ActivityMethod
    void storePaymentTerms(@NotNull SyncConnectionData connection, List<PaymentTerm> terms);

    /**
     * Fetch items from QuickBooks since last sync.
     *
     * @param connection the connection
     * @return list of items to upsert locally
     */
    @ActivityMethod
    List<Item> fetchItemsSinceLastSync(@NotNull SyncConnectionData connection);

    /**
     * Store items in items table.
     *
     * @param connection connection data
     * @param items items to store
     */
    @ActivityMethod
    void storeItems(@NotNull SyncConnectionData connection, List<Item> items);

    /**
     * Fetch purchase orders from QuickBooks since last sync.
     *
     * @param connection the connection
     * @return list of purchase orders to upsert locally
     */
    @ActivityMethod
    List<PurchaseOrder> fetchPurchaseOrdersSinceLastSync(@NotNull SyncConnectionData connection);

    /**
     * Store purchase orders in purchase_orders table.
     *
     * @param connection connection data
     * @param purchaseOrders purchase orders to store
     */
    @ActivityMethod
    void storePurchaseOrders(@NotNull SyncConnectionData connection, List<PurchaseOrder> purchaseOrders);

    /**
     * Sync Preferences from QuickBooks and update connection defaultCurrency.
     * Fetches Preferences once per pull workflow to update connection metadata.
     *
     * @param connection the connection
     */
    @ActivityMethod
    void syncPreferences(@NotNull SyncConnectionData connection);

    /**
     * Update lastSyncAt checkpoint after successful PULL.
     *
     * @param connectionId connection ID
     * @param timestamp timestamp to set (use sync start time, not NOW)
     * @return updated connection data
     */
    @ActivityMethod
    SyncConnectionData updateLastSyncAt(String connectionId, OffsetDateTime timestamp);
}

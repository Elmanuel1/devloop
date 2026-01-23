package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.integration.Item;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Activities for PUSH operations (pushing items to QuickBooks).
 * Handles pushing locally-created/updated items to external providers.
 */
@ActivityInterface
public interface ItemPushActivities {

    /**
     * Get connection data by ID.
     * Fetches and decrypts tokens for use in subsequent activities.
     */
    @ActivityMethod(name = "ItemPushGetConnection")
    SyncConnectionData getConnection(String connectionId);

    /**
     * Fetch items that need to be pushed to external system.
     * Returns items where last_sync_at IS NULL (never synced or failed sync).
     *
     * @param connection the connection data
     * @param limit maximum number of items to fetch
     * @return list of items needing push
     */
    @ActivityMethod
    List<Item> fetchItemsNeedingPush(@NotNull SyncConnectionData connection, int limit);

    /**
     * Push items to QuickBooks.
     *
     * @param connection the connection data
     * @param items list of items to push
     * @return map of item ID to sync result
     */
    @ActivityMethod
    Map<String, SyncResult> pushItems(@NotNull SyncConnectionData connection, List<Item> items);

    /**
     * Mark items as pushed after successful sync.
     * Updates last_sync_at, provider, external_id, and provider_version in the database.
     *
     * @param provider provider name (e.g., "QUICKBOOKS")
     * @param results map of item ID to sync result (containing external ID and version)
     * @return number of items successfully marked as pushed
     */
    @ActivityMethod
    int markItemsAsPushed(String provider, Map<String, SyncResult> results);
}

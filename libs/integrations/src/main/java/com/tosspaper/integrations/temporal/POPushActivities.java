package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.PurchaseOrder;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Activities for PUSH operations (pushing purchase orders to QuickBooks).
 */
@ActivityInterface
public interface POPushActivities {

    /**
     * Get connection data by ID.
     * Fetches and decrypts tokens for use in subsequent activities.
     */
    @ActivityMethod(name = "POPushGetConnection")
    SyncConnectionData getConnection(String connectionId);

    @ActivityMethod
    List<PurchaseOrder> fetchPOsNeedingPush(@NotNull SyncConnectionData connection, int limit);

    @ActivityMethod
    Map<String, SyncResult> pushPOs(@NotNull SyncConnectionData connection, List<PurchaseOrder> pos);

    @ActivityMethod
    int markPOsAsPushed(Map<String, SyncResult> results);
}

package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.Party;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Activities for PUSH operations (pushing customers to QuickBooks).
 */
@ActivityInterface
public interface CustomerPushActivities {

    /**
     * Get connection data by ID.
     * Fetches and decrypts tokens for use in subsequent activities.
     */
    @ActivityMethod(name = "CustomerPushGetConnection")
    SyncConnectionData getConnection(String connectionId);

    @ActivityMethod
    List<Party> fetchCustomersNeedingPush(@NotNull SyncConnectionData connection, int limit);

    @ActivityMethod
    Map<String, SyncResult> pushCustomers(@NotNull SyncConnectionData connection, List<Party> customers);

    @ActivityMethod
    int markCustomersAsPushed(String provider, Map<String, SyncResult> results);
}

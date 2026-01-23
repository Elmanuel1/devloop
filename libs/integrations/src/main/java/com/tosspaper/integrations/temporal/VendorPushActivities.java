package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.Party;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Activities for PUSH operations (pushing vendors to QuickBooks).
 */
@ActivityInterface
public interface VendorPushActivities {

    /**
     * Get connection data by ID.
     * Fetches and decrypts tokens for use in subsequent activities.
     */
    @ActivityMethod(name = "VendorPushGetConnection")
    SyncConnectionData getConnection(String connectionId);

    @ActivityMethod
    List<Party> fetchVendorsNeedingPush(@NotNull SyncConnectionData connection, int limit);

    @ActivityMethod
    Map<String, SyncResult> pushVendors(@NotNull SyncConnectionData connection, List<Party> vendors);

    @ActivityMethod
    int markVendorsAsPushed(String provider, Map<String, SyncResult> results);
}

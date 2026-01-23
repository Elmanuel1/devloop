package com.tosspaper.integrations.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activities for the parent sync workflow.
 * Contains common operations needed by the orchestrator workflow.
 */
@ActivityInterface
public interface IntegrationSyncActivities {

    /**
     * Get connection by ID.
     *
     * @param connectionId connection ID
     * @return connection data or throws exception if not found/not enabled
     */
    @ActivityMethod(name = "SyncGetConnection")
    SyncConnectionData getConnection(String connectionId);
}


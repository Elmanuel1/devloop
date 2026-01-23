package com.tosspaper.integrations.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal workflow for syncing approved documents to external integration systems.
 * Processes documents in batches per connection.
 */
@WorkflowInterface
public interface IntegrationSyncWorkflow {

    /**
     * Sync all pending approved documents for a connection.
     *
     * @param connectionId the integration connection ID
     */
    @WorkflowMethod
    void syncConnection(String connectionId);
}

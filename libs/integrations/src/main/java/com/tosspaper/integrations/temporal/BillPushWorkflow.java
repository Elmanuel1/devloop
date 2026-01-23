package com.tosspaper.integrations.temporal;

import io.temporal.workflow.WorkflowInterface;

/**
 * Workflow interface for pushing bills to external providers.
 * Extends PushWorkflow to provide a unique workflow type for Temporal registration.
 */
@WorkflowInterface
public interface BillPushWorkflow extends PushWorkflow {
    // Inherits push(SyncConnectionData connection) from PushWorkflow
}

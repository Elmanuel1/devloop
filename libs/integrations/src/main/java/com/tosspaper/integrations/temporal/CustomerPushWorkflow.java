package com.tosspaper.integrations.temporal;

import io.temporal.workflow.WorkflowInterface;

/**
 * Workflow interface for pushing customers to external providers.
 * Extends PushWorkflow to provide a unique workflow type for Temporal registration.
 */
@WorkflowInterface
public interface CustomerPushWorkflow extends PushWorkflow {
    // Inherits push(SyncConnectionData connection) from PushWorkflow
}

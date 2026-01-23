package com.tosspaper.integrations.temporal;

import io.temporal.workflow.WorkflowInterface;

/**
 * Workflow interface for pushing purchase orders to external providers.
 * Extends PushWorkflow to provide a unique workflow type for Temporal registration.
 */
@WorkflowInterface
public interface POPushWorkflow extends PushWorkflow {
    // Inherits push(SyncConnectionData connection) from PushWorkflow
}

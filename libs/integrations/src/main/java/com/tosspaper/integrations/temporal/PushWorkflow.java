package com.tosspaper.integrations.temporal;

import io.temporal.workflow.WorkflowMethod;

/**
 * Base interface for push workflows.
 * This is a marker interface - child interfaces should have @WorkflowInterface annotation.
 * Implementations handle pushing specific entity types (e.g., Bills, Items, Vendors, Purchase Orders).
 */
public interface PushWorkflow {

    /**
     * Push data to external provider for the given connection.
     * Connection data (including tokens) is fetched inside the workflow via activity,
     * keeping sensitive data out of workflow history inputs.
     *
     * @param connectionId the connection ID
     */
    @WorkflowMethod
    void push(String connectionId);
}


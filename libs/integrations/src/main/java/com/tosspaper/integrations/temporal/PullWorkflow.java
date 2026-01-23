package com.tosspaper.integrations.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Child workflow for pulling data from QuickBooks.
 * Pulls vendors, accounts, payment terms, and purchase orders.
 */
@WorkflowInterface
public interface PullWorkflow {

    /**
     * Pull all data from QuickBooks for the given connection.
     * Pulls vendors, accounts, terms in parallel, then purchase orders.
     * Connection data (including tokens) is fetched inside the workflow via activity,
     * keeping sensitive data out of workflow history inputs.
     *
     * @param connectionId the connection ID
     */
    @WorkflowMethod
    void pull(String connectionId);
}


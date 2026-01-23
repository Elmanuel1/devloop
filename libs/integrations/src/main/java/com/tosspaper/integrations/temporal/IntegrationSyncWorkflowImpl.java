package com.tosspaper.integrations.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;

/**
 * Implementation of the integration sync workflow.
 * Orchestrates pull and push child workflows.
 */
@Slf4j
@WorkflowImpl(taskQueues = "integration-sync")
public class IntegrationSyncWorkflowImpl implements IntegrationSyncWorkflow {

    private static final String TASK_QUEUE = "integration-sync";

    private final IntegrationSyncActivities syncActivities;

    private final org.slf4j.Logger logger = Workflow.getLogger(IntegrationSyncWorkflowImpl.class);

    public IntegrationSyncWorkflowImpl() {
        ActivityOptions activityOptions = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofMillis(500))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofMinutes(1))
                        .build())
                .build();

        this.syncActivities = Workflow.newActivityStub(IntegrationSyncActivities.class, activityOptions);
    }

    private ChildWorkflowOptions baseChildWorkflowOptions(String connectionId, String workflowPrefix) {
        return ChildWorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId(workflowPrefix + "-" + connectionId)
                .build();
    }

    @Override
    public void syncConnection(String connectionId) {
        logger.debug("Starting sync for connection {}", connectionId);

        // Validate connection exists and is enabled (quick check, no token fetch)
        // Child workflows will fetch connection data via activities, keeping tokens out of workflow history
        syncActivities.getConnection(connectionId);

        // ===== PULL PHASE =====
        logger.debug("Starting PULL phase for connection {}", connectionId);

        ChildWorkflowOptions pullOptions = baseChildWorkflowOptions(connectionId, "pull");
        PullWorkflow pullWorkflow = Workflow.newChildWorkflowStub(PullWorkflow.class, pullOptions);
        pullWorkflow.pull(connectionId);
        logger.debug("PULL phase complete for connection {}", connectionId);

        // ===== PUSH PHASE =====
        logger.debug("Starting PUSH phase for connection {}", connectionId);

        // Phase 1: Push items, vendors, and bills in parallel (POs depend on these)
        logger.debug("Starting parallel push for items, vendors, and bills for connection {}", connectionId);

        List<Class<? extends PushWorkflow>> parallelPushWorkflows = List.of(
            BillPushWorkflow.class,
            ItemPushWorkflow.class,
            VendorPushWorkflow.class
        );

        // Create and start parallel push workflows
        List<Promise<Void>> parallelPushPromises = parallelPushWorkflows.stream()
            .map(workflowInterface -> {
                String workflowType = workflowInterface.getSimpleName()
                    .replace("PushWorkflow", "")
                    .toLowerCase();
                ChildWorkflowOptions pushOptions = baseChildWorkflowOptions(
                    connectionId,
                    "push-" + workflowType
                );

                return Async.procedure(() -> {
                    PushWorkflow pushWorkflow = Workflow.newChildWorkflowStub(workflowInterface, pushOptions);
                    pushWorkflow.push(connectionId);
                    logger.debug("{} complete for connection {}", workflowInterface.getSimpleName(), connectionId);
                });
            })
            .toList();

        // Wait for parallel push workflows to complete
        Promise.allOf(parallelPushPromises.toArray(new Promise[0])).get();
        logger.debug("Parallel push (items, vendors, bills) complete for connection {}", connectionId);

        // Phase 2: Push purchase orders last (depends on items, vendors, bills)
        logger.debug("Starting PO push for connection {} (after items, vendors, bills complete)", connectionId);

        ChildWorkflowOptions poPushOptions = baseChildWorkflowOptions(connectionId, "push-po");
        POPushWorkflow poPushWorkflow = Workflow.newChildWorkflowStub(POPushWorkflow.class, poPushOptions);
        poPushWorkflow.push(connectionId);
        logger.debug("POPushWorkflow complete for connection {}", connectionId);

        logger.info("Sync complete for connection {}", connectionId);
    }
}

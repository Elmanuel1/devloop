package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.PurchaseOrder;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@WorkflowImpl(taskQueues = "integration-sync")
public class POPushWorkflowImpl implements POPushWorkflow {

    private static final int BATCH_SIZE = 50;

    private final POPushActivities pushActivities;

    private final org.slf4j.Logger logger = Workflow.getLogger(POPushWorkflowImpl.class);

    public POPushWorkflowImpl() {
        ActivityOptions activityOptions = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofMillis(500))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofMinutes(1))
                        .build())
                .build();

        this.pushActivities = Workflow.newActivityStub(POPushActivities.class, activityOptions);
    }

    @Override
    public void push(String connectionId) {
        // Fetch connection data (with decrypted tokens) via activity
        // This keeps sensitive tokens out of workflow history inputs
        SyncConnectionData connection = pushActivities.getConnection(connectionId);
        logger.debug("Starting PO push workflow for connection {}", connectionId);

        int totalPushed = 0;
        int batchNumber = 0;

        while (true) {
            batchNumber++;
            logger.debug("Processing batch {} for connection {}", batchNumber, connection.getId());

            List<PurchaseOrder> pos = pushActivities.fetchPOsNeedingPush(connection, BATCH_SIZE);

            if (pos.isEmpty()) {
                logger.debug("No more POs to push for connection {}", connection.getId());
                break;
            }

            logger.debug("Found {} POs to push in batch {}", pos.size(), batchNumber);

            Map<String, SyncResult> results = pushActivities.pushPOs(connection, pos);

            int markedCount = pushActivities.markPOsAsPushed(results);
            totalPushed += markedCount;

            logger.debug("Batch {} complete: {} POs pushed successfully", batchNumber, markedCount);

            if (pos.size() < BATCH_SIZE) {
                break;
            }
        }

        if (totalPushed > 0) {
            logger.info("PO push workflow complete for connection {}: {} total POs pushed in {} batches",
                    connection.getId(), totalPushed, batchNumber);
        } else {
            logger.debug("PO push workflow complete for connection {}: {} total POs pushed in {} batches",
                    connection.getId(), totalPushed, batchNumber);
        }
    }
}

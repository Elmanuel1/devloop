package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.Party;
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
public class VendorPushWorkflowImpl implements VendorPushWorkflow {

    private static final int BATCH_SIZE = 50;

    private final VendorPushActivities pushActivities;

    private final org.slf4j.Logger logger = Workflow.getLogger(VendorPushWorkflowImpl.class);

    public VendorPushWorkflowImpl() {
        ActivityOptions activityOptions = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofMillis(500))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofMinutes(1))
                        .build())
                .build();

        this.pushActivities = Workflow.newActivityStub(VendorPushActivities.class, activityOptions);
    }

    @Override
    public void push(String connectionId) {
        // Fetch connection data (with decrypted tokens) via activity
        // This keeps sensitive tokens out of workflow history inputs
        SyncConnectionData connection = pushActivities.getConnection(connectionId);
        logger.debug("Starting vendor push workflow for connection {}", connectionId);

        int totalPushed = 0;
        int batchNumber = 0;

        while (true) {
            batchNumber++;
            logger.debug("Processing batch {} for connection {}", batchNumber, connection.getId());

            List<Party> vendors = pushActivities.fetchVendorsNeedingPush(connection, BATCH_SIZE);

            if (vendors.isEmpty()) {
                logger.debug("No more vendors to push for connection {}", connection.getId());
                break;
            }

            logger.debug("Found {} vendors to push in batch {}", vendors.size(), batchNumber);

            Map<String, SyncResult> results = pushActivities.pushVendors(connection, vendors);

            int markedCount = pushActivities.markVendorsAsPushed(connection.getProvider().getValue(), results);
            totalPushed += markedCount;

            logger.debug("Batch {} complete: {} vendors pushed successfully", batchNumber, markedCount);

            if (vendors.size() < BATCH_SIZE) {
                break;
            }
        }

        if (totalPushed > 0) {
            logger.info("Vendor push workflow complete for connection {}: {} total vendors pushed in {} batches",
                    connection.getId(), totalPushed, batchNumber);
        } else {
            logger.debug("Vendor push workflow complete for connection {}: {} total vendors pushed in {} batches",
                    connection.getId(), totalPushed, batchNumber);
        }
    }
}

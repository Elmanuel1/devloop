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
public class CustomerPushWorkflowImpl implements CustomerPushWorkflow {

    private static final int BATCH_SIZE = 50;

    private final CustomerPushActivities pushActivities;

    private final org.slf4j.Logger logger = Workflow.getLogger(CustomerPushWorkflowImpl.class);

    public CustomerPushWorkflowImpl() {
        ActivityOptions activityOptions = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofMillis(500))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofMinutes(1))
                        .build())
                .build();

        this.pushActivities = Workflow.newActivityStub(CustomerPushActivities.class, activityOptions);
    }

    @Override
    public void push(String connectionId) {
        // Fetch connection data (with decrypted tokens) via activity
        // This keeps sensitive tokens out of workflow history inputs
        SyncConnectionData connection = pushActivities.getConnection(connectionId);
        logger.info("Starting customer push workflow for connection {}", connectionId);

        int totalPushed = 0;
        int batchNumber = 0;

        while (true) {
            batchNumber++;
            logger.info("Processing batch {} for connection {}", batchNumber, connection.getId());

            List<Party> customers = pushActivities.fetchCustomersNeedingPush(connection, BATCH_SIZE);

            if (customers.isEmpty()) {
                logger.info("No more customers to push for connection {}", connection.getId());
                break;
            }

            logger.info("Found {} customers to push in batch {}", customers.size(), batchNumber);

            Map<String, SyncResult> results = pushActivities.pushCustomers(connection, customers);

            int markedCount = pushActivities.markCustomersAsPushed(connection.getProvider().getValue(), results);
            totalPushed += markedCount;

            logger.info("Batch {} complete: {} customers pushed successfully", batchNumber, markedCount);

            if (customers.size() < BATCH_SIZE) {
                break;
            }
        }

        logger.info("Customer push workflow complete for connection {}: {} total customers pushed in {} batches",
                connection.getId(), totalPushed, batchNumber);
    }
}

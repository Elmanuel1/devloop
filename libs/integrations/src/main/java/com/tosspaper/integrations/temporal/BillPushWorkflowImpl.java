package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.Invoice;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Implementation of PushWorkflow for pushing accepted invoices as Bills to QuickBooks.
 * Processes invoices in batches.
 */
@Slf4j
@WorkflowImpl(taskQueues = "integration-sync")
public class BillPushWorkflowImpl implements BillPushWorkflow {

    private static final int BATCH_SIZE = 50;

    private final IntegrationPushActivities pushActivities;

    private final org.slf4j.Logger logger = Workflow.getLogger(BillPushWorkflowImpl.class);

    public BillPushWorkflowImpl() {
        ActivityOptions activityOptions = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofMillis(500))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofMinutes(1))
                        .build())
                .build();

        this.pushActivities = Workflow.newActivityStub(IntegrationPushActivities.class, activityOptions);
    }

    @Override
    public void push(String connectionId) {
        // Fetch connection data (with decrypted tokens) via activity
        // This keeps sensitive tokens out of workflow history inputs
        SyncConnectionData connection = pushActivities.getConnection(connectionId);
        logger.debug("Starting bill push workflow for connection {}", connectionId);

        int totalPushed = 0;
        int batchNumber = 0;

        // Process batches until no more invoices need pushing
        while (true) {
            batchNumber++;
            logger.debug("Processing batch {} for connection {}", batchNumber, connection.getId());

            // Fetch accepted invoices needing push
            List<Invoice> invoices = pushActivities.fetchAcceptedInvoicesNeedingPush(connection, BATCH_SIZE);

            if (invoices.isEmpty()) {
                logger.debug("No more invoices to push for connection {}", connection.getId());
                break;
            }

            logger.debug("Found {} invoices to push in batch {}", invoices.size(), batchNumber);

            // Push invoices as bills
            Map<String, SyncResult> results = pushActivities.pushInvoicesAsBills(connection, invoices);

            // Mark successful pushes
            int markedCount = pushActivities.markInvoicesAsPushed(results);
            totalPushed += markedCount;

            logger.debug("Batch {} complete: {} invoices pushed successfully", batchNumber, markedCount);

            // If we got fewer invoices than batch size, we're done
            if (invoices.size() < BATCH_SIZE) {
                break;
            }
        }

        if (totalPushed > 0) {
            logger.info("Bill push workflow complete for connection {}: {} total invoices pushed in {} batches",
                    connection.getId(), totalPushed, batchNumber);
        } else {
            logger.debug("Bill push workflow complete for connection {}: {} total invoices pushed in {} batches",
                    connection.getId(), totalPushed, batchNumber);
        }
    }
}


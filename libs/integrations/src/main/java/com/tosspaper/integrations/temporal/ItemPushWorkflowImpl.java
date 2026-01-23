package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.models.domain.integration.Item;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Implementation of ItemPushWorkflow for pushing items to QuickBooks.
 * Processes items in batches, retrying failed pushes.
 */
@Slf4j
@WorkflowImpl(taskQueues = "integration-sync")
public class ItemPushWorkflowImpl implements ItemPushWorkflow {

    private static final int BATCH_SIZE = 50;

    private final ItemPushActivities pushActivities;

    private final org.slf4j.Logger logger = Workflow.getLogger(ItemPushWorkflowImpl.class);

    public ItemPushWorkflowImpl() {
        ActivityOptions activityOptions = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofMillis(500))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofMinutes(1))
                        .build())
                .build();

        this.pushActivities = Workflow.newActivityStub(ItemPushActivities.class, activityOptions);
    }

    @Override
    public void push(String connectionId) {
        // Fetch connection data (with decrypted tokens) via activity
        // This keeps sensitive tokens out of workflow history inputs
        SyncConnectionData connection = pushActivities.getConnection(connectionId);
        logger.debug("Starting item push workflow for connection {}", connectionId);

        int totalPushed = 0;
        int batchNumber = 0;

        // Process batches until no more items need pushing
        while (true) {
            batchNumber++;
            logger.debug("Processing batch {} for connection {}", batchNumber, connection.getId());

            // Fetch items needing push
            List<Item> items = pushActivities.fetchItemsNeedingPush(connection, BATCH_SIZE);

            if (items.isEmpty()) {
                logger.debug("No more items to push for connection {}", connection.getId());
                break;
            }

            logger.debug("Found {} items to push in batch {}", items.size(), batchNumber);

            // Push items
            Map<String, SyncResult> results = pushActivities.pushItems(connection, items);

            // Mark successful pushes
            int markedCount = pushActivities.markItemsAsPushed(connection.getProvider().getValue(), results);
            totalPushed += markedCount;

            logger.debug("Batch {} complete: {} items pushed successfully", batchNumber, markedCount);

            // If we got fewer items than batch size, we're done
            if (items.size() < BATCH_SIZE) {
                break;
            }
        }

        if (totalPushed > 0) {
            logger.info("Item push workflow complete for connection {}: {} total items pushed in {} batches",
                    connection.getId(), totalPushed, batchNumber);
        } else {
            logger.debug("Item push workflow complete for connection {}: {} total items pushed in {} batches",
                    connection.getId(), totalPushed, batchNumber);
        }
    }
}

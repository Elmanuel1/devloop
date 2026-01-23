package com.tosspaper.integrations.temporal;

import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PaymentTerm;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.Item;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Child workflow implementation for pulling data from QuickBooks.
 * Pulls vendors, accounts, payment terms in parallel, then purchase orders.
 */
@Slf4j
@WorkflowImpl(taskQueues = "integration-sync")
public class PullWorkflowImpl implements PullWorkflow {

    private final IntegrationPullActivities pullActivities;

    private final org.slf4j.Logger logger = Workflow.getLogger(PullWorkflowImpl.class);

    public PullWorkflowImpl() {
        ActivityOptions activityOptions = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(5))
                .setRetryOptions(RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setInitialInterval(Duration.ofMillis(500))
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(Duration.ofMinutes(1))
                        .build())
                .build();

        this.pullActivities = Workflow.newActivityStub(IntegrationPullActivities.class, activityOptions);
    }

    @Override
    public void pull(String connectionId) {
        // Fetch connection data (with decrypted tokens) via activity
        // This keeps sensitive tokens out of workflow history inputs
        SyncConnectionData connection = pullActivities.getConnection(connectionId);

        // Capture sync start time (before pull)
        OffsetDateTime syncStartTime = pullActivities.getCurrentTime();
        logger.debug("Starting pull workflow for connection {} at {}", connectionId, syncStartTime);

        // Parallel Group: Vendors, Accounts, Terms, Items, Preferences (independent)
        // Note: Order matters for workflow history compatibility - keep existing order first
        Promise<Void> vendorPull = Async.procedure(() -> {
            List<Party> vendors = pullActivities.fetchVendorsSinceLastSync(connection);
            if (!vendors.isEmpty()) {
                pullActivities.storeVendorsInContacts(connection, vendors);
                logger.info("Pulled {} vendors", vendors.size());
            }
        });

        Promise<Void> accountPull = Async.procedure(() -> {
            List<IntegrationAccount> accounts = pullActivities.fetchAccountsSinceLastSync(connection);
            if (!accounts.isEmpty()) {
                pullActivities.storeAccounts(connection, accounts);
                logger.info("Pulled {} accounts", accounts.size());
            }
        });

        Promise<Void> termsPull = Async.procedure(() -> {
            List<PaymentTerm> terms = pullActivities.fetchPaymentTermsSinceLastSync(connection);
            if (!terms.isEmpty()) {
                pullActivities.storePaymentTerms(connection, terms);
                logger.info("Pulled {} payment terms", terms.size());
            }
        });

        Promise<Void> itemsPull = Async.procedure(() -> {
            List<Item> items = pullActivities.fetchItemsSinceLastSync(connection);
            if (!items.isEmpty()) {
                pullActivities.storeItems(connection, items);
                logger.info("Pulled {} items", items.size());
            }
        });

        Promise<Void> preferencesPull = Async.procedure(() -> {
            pullActivities.syncPreferences(connection);
            logger.debug("Synced preferences");
        });

        // Wait for all parallel pulls to complete
        Promise.allOf(vendorPull, accountPull, termsPull, itemsPull, preferencesPull).get();
        logger.debug("Completed parallel pull (vendors, accounts, terms, items, preferences)");

        // Sequential: Purchase Orders (depends on vendors/accounts)
        List<PurchaseOrder> pos = pullActivities.fetchPurchaseOrdersSinceLastSync(connection);
        if (!pos.isEmpty()) {
            pullActivities.storePurchaseOrders(connection, pos);
            logger.info("Pulled {} purchase orders", pos.size());
        }

        // Update checkpoint with START time
        pullActivities.updateLastSyncAt(connection.getId(), syncStartTime);
        logger.debug("Pull workflow complete - updated lastSyncAt to {}", syncStartTime);
    }
}


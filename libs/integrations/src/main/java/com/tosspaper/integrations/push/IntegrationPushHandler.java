package com.tosspaper.integrations.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.messaging.MessageHandler;
import com.tosspaper.integrations.common.IntegrationPushCoordinator;
import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.provider.IntegrationPullProvider;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.domain.integration.Item;
import com.tosspaper.models.domain.Invoice;
import com.tosspaper.models.service.ContactSyncService;
import com.tosspaper.models.service.ItemService;
import com.tosspaper.models.service.PurchaseOrderSyncService;
import com.tosspaper.models.service.SenderNotificationService;
import com.tosspaper.models.service.SyncConflictNotificationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Redis Stream listener for processing integration push events.
 * Receives push events and routes them to the appropriate provider.
 * Provider-agnostic - works with any integration provider.
 */
@Slf4j
@Component("integrationPushStreamListener")
@RequiredArgsConstructor
public class IntegrationPushHandler implements MessageHandler<Map<String, String>> {

    private static final String QUEUE_NAME = "integration-push-events";

    private final ObjectMapper objectMapper;

    @Override
    public String getQueueName() {
        return QUEUE_NAME;
    }

    @Override
    public void handle(Map<String, String> message) {
        String payload = message.get("message");
        if (payload == null || payload.isBlank()) {
            log.warn("Missing payload in integration push message");
            return;
        }
        processPushEvent(payload);
    }
    private final IntegrationConnectionService connectionService;
    private final IntegrationPushCoordinator pushCoordinator;
    private final IntegrationProviderFactory providerFactory;
    private final ContactSyncService contactSyncService;
    private final ItemService itemService;
    private final PurchaseOrderSyncService purchaseOrderSyncService;
    private final com.tosspaper.models.service.InvoiceSyncService invoiceSyncService;
    private final SenderNotificationService senderNotificationService;

    /**
     * Process a push event payload.
     * Deserializes the event and routes to the appropriate provider.
     */
    private void processPushEvent(String payload) {
        try {
            IntegrationPushEvent event = objectMapper.readValue(payload, IntegrationPushEvent.class);

            log.info("Processing push event: provider={}, companyId={}, payload={}",
                    event.provider(), event.companyId(), event.payload());

            // Get connection
            IntegrationConnection connection = connectionService.findById(event.connectionId());

            // Ensure active token
            connection = connectionService.ensureActiveToken(connection);

            // Phase 1.5: Push items (POs depend on item externalId)
            if (event.entityType().equals(IntegrationEntityType.ITEM)) {
                var item = objectMapper.readValue(event.payload(), Item.class);
                pushItem(event.provider(), connection, item, event.updatedBy());
                return;
            }

            // Phase 1: Push vendors first (POs depend on vendor externalId)
            if (event.entityType().equals(IntegrationEntityType.VENDOR)) {
                var vendor = objectMapper.readValue(event.payload(), Party.class);
                pushVendor(event.provider(), connection, vendor, event.updatedBy());
                return;
            }

            // Phase 1.6: Push job locations (ship-to locations, POs depend on job location
            // externalId)
            if (event.entityType().equals(IntegrationEntityType.JOB_LOCATION)) {
                var customer = objectMapper.readValue(event.payload(), Party.class);
                pushCustomer(event.provider(), connection, customer, event.updatedBy());
                return;
            }

            // Phase 2: Push purchase orders
            if (event.entityType().equals(IntegrationEntityType.PURCHASE_ORDER)) {
                var po = objectMapper.readValue(event.payload(), PurchaseOrder.class);
                pushPurchaseOrder(event.provider(), connection, po, event.updatedBy());
                return;
            }

            // Phase 3: Push Bills (Invoices)
            if (event.entityType().equals(IntegrationEntityType.BILL)) {
                var invoice = objectMapper.readValue(event.payload(), Invoice.class);
                pushBill(event.provider(), connection, invoice, event.updatedBy());
                return;
            }

        } catch (Exception e) {
            log.error("Failed to process integration push event", e);
        }
    }

    /**
     * Push vendor to the provider.
     */
    private void pushVendor(
            IntegrationProvider provider,
            IntegrationConnection connection,
            Party vendor,
            String updatedBy) {

        try {
            // Use coordinator for push (handles dependencies automatically)
            SyncResult result = pushCoordinator.pushWithDependencies(
                    connection,
                    IntegrationEntityType.VENDOR,
                    vendor);

            if (result.isSuccess()) {
                // Update DB with provider, externalId, providerVersion, and
                // providerLastUpdatedAt
                contactSyncService.updateSyncStatus(
                        vendor.getId(),
                        provider.getValue(),
                        result.getExternalId(),
                        result.getProviderVersion(),
                        result.getProviderLastUpdatedAt());
                log.info("Successfully pushed vendor: id={}, externalId={}",
                        vendor.getId(), result.getExternalId());
                return;
            }

            if (result.isConflictDetected()) {
                // Pull latest vendor data before notifying
                log.warn("Vendor push conflict: id={}, name={}, error={}",
                        vendor.getId(), vendor.getName(), result.getErrorMessage());
                var pullProviderOpt = providerFactory.getPullProvider(provider, IntegrationEntityType.VENDOR);
                if (pullProviderOpt.isPresent()) {
                    @SuppressWarnings("unchecked")
                    IntegrationPullProvider<Party> vendorPullProvider = (IntegrationPullProvider<Party>) pullProviderOpt
                            .get();
                    pullVendor(vendorPullProvider, connection, vendor.getId(), result.getExternalId());
                    notifyConflict(connection.getCompanyId(), provider.getDisplayName(), "Vendor", vendor.getName(),
                            result.getErrorMessage(), updatedBy);
                }
                return;
            }

            log.error("Failed to push vendor: id={}, error={}",
                    vendor.getId(), result.getErrorMessage());

        } catch (Exception e) {
            log.error("Exception pushing vendor: id={}", vendor.getId(), e);
        }
    }

    /**
     * Push customer (ship-to location) to the provider.
     */
    private void pushCustomer(
            IntegrationProvider provider,
            IntegrationConnection connection,
            Party customer,
            String updatedBy) {

        try {
            // Use coordinator for push (handles dependencies automatically)
            SyncResult result = pushCoordinator.pushWithDependencies(
                    connection,
                    IntegrationEntityType.JOB_LOCATION,
                    customer);

            if (result.isSuccess()) {
                // Update DB with provider, externalId, providerVersion, and
                // providerLastUpdatedAt
                contactSyncService.updateSyncStatus(
                        customer.getId(),
                        provider.getValue(),
                        result.getExternalId(),
                        result.getProviderVersion(),
                        result.getProviderLastUpdatedAt());
                log.info("Successfully pushed customer: id={}, externalId={}",
                        customer.getId(), result.getExternalId());
                return;
            }

            if (result.isConflictDetected()) {
                // Pull latest customer data before notifying
                log.warn("Customer push conflict: id={}, name={}, error={}",
                        customer.getId(), customer.getName(), result.getErrorMessage());
                var pullProviderOpt = providerFactory.getPullProvider(provider, IntegrationEntityType.JOB_LOCATION);
                if (pullProviderOpt.isPresent()) {
                    @SuppressWarnings("unchecked")
                    IntegrationPullProvider<Party> customerPullProvider = (IntegrationPullProvider<Party>) pullProviderOpt
                            .get();
                    pullCustomer(customerPullProvider, connection, customer.getId(), result.getExternalId());
                    notifyConflict(connection.getCompanyId(), provider.getDisplayName(), "Customer", customer.getName(),
                            result.getErrorMessage(), updatedBy);
                }
                return;
            }

            log.error("Failed to push customer: id={}, error={}",
                    customer.getId(), result.getErrorMessage());

        } catch (Exception e) {
            log.error("Exception pushing customer: id={}", customer.getId(), e);
        }
    }

    /**
     * Push item to the provider.
     */
    private void pushItem(
            IntegrationProvider provider,
            IntegrationConnection connection,
            Item item,
            String updatedBy) {

        try {
            // Use coordinator for push (handles dependencies automatically)
            SyncResult result = pushCoordinator.pushWithDependencies(
                    connection,
                    IntegrationEntityType.ITEM,
                    item);

            if (result.isSuccess()) {
                itemService.updateSyncStatus(
                        item.getId(),
                        provider.getValue(),
                        result.getExternalId(),
                        result.getProviderVersion(),
                        result.getProviderLastUpdatedAt());
                log.info("Successfully pushed item: id={}, externalId={}",
                        item.getId(), result.getExternalId());
                return;
            }

            if (result.isConflictDetected()) {
                log.warn("Item push conflict: id={}, name={}, error={}",
                        item.getId(), item.getName(), result.getErrorMessage());
                var pullProviderOpt = providerFactory.getPullProvider(provider, IntegrationEntityType.ITEM);
                if (pullProviderOpt.isPresent()) {
                    @SuppressWarnings("unchecked")
                    IntegrationPullProvider<Item> itemPullProvider = (IntegrationPullProvider<Item>) pullProviderOpt
                            .get();
                    pullItem(itemPullProvider, connection, item.getId(), result.getExternalId());
                    notifyConflict(connection.getCompanyId(), provider.getDisplayName(), "Item", item.getName(),
                            result.getErrorMessage(), updatedBy);
                }
                return;
            }

            log.error("Failed to push item: id={}, error={}",
                    item.getId(), result.getErrorMessage());

        } catch (Exception e) {
            log.error("Exception pushing item: id={}", item.getId(), e);
        }
    }

    /**
     * Push purchase order to the provider.
     * Coordinator handles dependency resolution automatically (e.g., ensuring
     * vendor has externalId).
     */
    private void pushPurchaseOrder(
            IntegrationProvider provider,
            IntegrationConnection connection,
            PurchaseOrder po,
            String updatedBy) {

        try {
            // Use coordinator for push (handles dependencies automatically, including
            // vendor externalId)
            SyncResult result = pushCoordinator.pushWithDependencies(
                    connection,
                    IntegrationEntityType.PURCHASE_ORDER,
                    po);

            if (result.isSuccess()) {
                // Update DB with externalId and providerVersion
                purchaseOrderSyncService.updateSyncStatus(
                        po.getId(),
                        result.getExternalId(),
                        result.getProviderVersion(),
                        result.getProviderLastUpdatedAt());
                log.info("Successfully pushed PO: id={}, externalId={}",
                        po.getId(), result.getExternalId());
                return;
            }

            if (result.isConflictDetected()) {
                // Pull latest purchase order data before notifying
                log.warn("PO push conflict: id={}, displayId={}, error={}",
                        po.getId(), po.getDisplayId(), result.getErrorMessage());
                var pullProviderOpt = providerFactory.getPullProvider(provider, IntegrationEntityType.PURCHASE_ORDER);
                if (pullProviderOpt.isPresent()) {
                    @SuppressWarnings("unchecked")
                    IntegrationPullProvider<PurchaseOrder> poPullProvider = (IntegrationPullProvider<PurchaseOrder>) pullProviderOpt
                            .get();
                    pullPurchaseOrder(poPullProvider, connection, po.getId(), result.getExternalId());
                }
                notifyConflict(connection.getCompanyId(), provider.getDisplayName(),
                        IntegrationEntityType.PURCHASE_ORDER.getDisplayName(), po.getDisplayId(),
                        result.getErrorMessage(), updatedBy);
                return;
            }

            log.error("Failed to push PO: id={}, error={}", po.getId(), result.getErrorMessage());

        } catch (Exception e) {
            log.error("Exception pushing purchase order: id={}", po.getId(), e);
        }
    }

    /**
     * Push bill (invoice) to the provider.
     */
    private void pushBill(
            IntegrationProvider provider,
            IntegrationConnection connection,
            Invoice invoice,
            String updatedBy) {

        try {
            // Use coordinator for push (handles dependencies automatically via
            // BillDependencyStrategy)
            SyncResult result = pushCoordinator.pushWithDependencies(
                    connection,
                    IntegrationEntityType.BILL,
                    invoice);

            if (result.isSuccess()) {
                // Update DB with externalId and sync timestamp
                // Note: IntegrationPushActivitiesImpl.markInvoicesAsPushed handles this for
                // scheduled flow.
                // For event driven, we should also update it.
                // InvoiceSyncService doesn't have updateSyncStatus like others, but has
                // markAsPushed(Map).
                // Let's use markAsPushed.

                invoiceSyncService.markAsPushed(List.of(
                        com.tosspaper.models.common.PushResult.builder()
                                .documentId(invoice.getAssignedId())
                                .externalId(result.getExternalId())
                                .syncedAt(java.time.OffsetDateTime.now())
                                .build()));

                log.info("Successfully pushed Bill: id={}, externalId={}",
                        invoice.getExternalId(), result.getExternalId());
                return;
            }

            log.error("Failed to push Bill: id={}, error={}", invoice.getAssignedId(), result.getErrorMessage());
            // We might want to notify conflict here too if relevant, handling similar to
            // POs

        } catch (Exception e) {
            log.error("Exception pushing Bill: id={}", invoice.getAssignedId(), e);
        }
    }

    /**
     * Pull a single vendor from the provider by external ID.
     */
    private void pullVendor(IntegrationPullProvider<Party> vendorPullProvider, IntegrationConnection connection,
            String vendorId, String externalId) {
        try {
            if (externalId == null || externalId.isBlank()) {
                log.warn("Cannot pull vendor without externalId: vendorId={}", vendorId);
                return;
            }

            if (!vendorPullProvider.isEnabled()) {
                log.warn("Vendor pull provider disabled");
                return;
            }

            Party vendor = vendorPullProvider.getById(externalId, connection);
            if (vendor != null) {
                contactSyncService.upsertFromProvider(connection.getCompanyId(), List.of(vendor));
                log.info("Pulled vendor after conflict: vendorId={}, externalId={}", vendorId, externalId);
            } else {
                log.warn("Vendor not found in QuickBooks: vendorId={}, externalId={}", vendorId, externalId);
            }
        } catch (Exception e) {
            log.error("Failed to pull vendor after conflict: vendorId={}, externalId={}", vendorId, externalId, e);
        }
    }

    /**
     * Pull a single customer from the provider by external ID.
     */
    private void pullCustomer(IntegrationPullProvider<Party> customerPullProvider, IntegrationConnection connection,
            String customerId, String externalId) {
        try {
            if (externalId == null || externalId.isBlank()) {
                log.warn("Cannot pull customer without externalId: customerId={}", customerId);
                return;
            }

            if (!customerPullProvider.isEnabled()) {
                log.warn("Customer pull provider disabled");
                return;
            }

            Party customer = customerPullProvider.getById(externalId, connection);
            if (customer != null) {
                contactSyncService.upsertFromProvider(connection.getCompanyId(), List.of(customer));
                log.info("Pulled customer after conflict: customerId={}, externalId={}", customerId, externalId);
            } else {
                log.warn("Customer not found in QuickBooks: customerId={}, externalId={}", customerId, externalId);
            }
        } catch (Exception e) {
            log.error("Failed to pull customer after conflict: customerId={}, externalId={}", customerId, externalId,
                    e);
        }
    }

    /**
     * Pull latest item from provider after conflict.
     */
    private void pullItem(IntegrationPullProvider<Item> itemPullProvider, IntegrationConnection connection,
            String itemId, String externalId) {
        try {
            if (externalId == null || externalId.isBlank()) {
                log.warn("Cannot pull item without externalId: itemId={}", itemId);
                return;
            }

            if (!itemPullProvider.isEnabled()) {
                log.warn("Item pull provider disabled");
                return;
            }

            Item item = itemPullProvider.getById(externalId, connection);
            if (item != null) {
                itemService.upsertFromProvider(connection.getCompanyId(), connection.getId(), List.of(item));
                log.info("Pulled item after conflict: itemId={}, externalId={}", itemId, externalId);
            } else {
                log.warn("Item not found in QuickBooks: itemId={}, externalId={}", itemId, externalId);
            }
        } catch (Exception e) {
            log.error("Failed to pull item after conflict: itemId={}, externalId={}", itemId, externalId, e);
        }
    }

    /**
     * Pull a single purchase order from the provider by external ID.
     */
    private void pullPurchaseOrder(IntegrationPullProvider<PurchaseOrder> poPullProvider,
            IntegrationConnection connection, String poId, String externalId) {
        try {
            if (externalId == null || externalId.isBlank()) {
                log.warn("Cannot pull purchase order without externalId: poId={}", poId);
                return;
            }

            if (!poPullProvider.isEnabled()) {
                log.warn("Purchase order pull provider disabled");
                return;
            }

            PurchaseOrder po = poPullProvider.getById(externalId, connection);
            if (po != null) {
                purchaseOrderSyncService.upsertFromProvider(connection.getCompanyId(), List.of(po));
                log.info("Pulled purchase order after conflict: poId={}, externalId={}", poId, externalId);
            } else {
                log.warn("Purchase order not found in QuickBooks: poId={}, externalId={}", poId, externalId);
            }
        } catch (Exception e) {
            log.error("Failed to pull purchase order after conflict: poId={}, externalId={}", poId, externalId, e);
        }
    }

    /**
     * Notify user of a sync conflict.
     */
    private void notifyConflict(Long companyId, String provider, String entityType, String entityName,
            String errorMessage, String updatedBy) {
        try {
            SyncConflictNotificationRequest request = new SyncConflictNotificationRequest(
                    companyId,
                    provider,
                    entityType,
                    entityName,
                    errorMessage,
                    updatedBy);
            senderNotificationService.sendSyncConflictNotification(request);
        } catch (Exception e) {
            log.error("Failed to send sync conflict notification: companyId={}, entityType={}, entityName={}",
                    companyId, entityType, entityName, e);
            // Don't throw - we don't want to fail sync operation if notification fails
        }
    }
}

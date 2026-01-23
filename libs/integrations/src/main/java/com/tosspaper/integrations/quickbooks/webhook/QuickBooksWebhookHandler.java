package com.tosspaper.integrations.quickbooks.webhook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tosspaper.models.messaging.MessageHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.ipp.core.IEntity;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.intuit.ipp.data.*;
import com.tosspaper.integrations.common.PurchaseOrderLineItemResolver;
import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.account.AccountMapper;
import com.tosspaper.integrations.quickbooks.customer.CustomerMapper;
import com.tosspaper.integrations.quickbooks.item.ItemMapper;
import com.tosspaper.integrations.quickbooks.purchaseorder.QBOPurchaseOrderMapper;
import com.tosspaper.integrations.quickbooks.term.PaymentTermMapper;
import com.tosspaper.integrations.quickbooks.vendor.VendorMapper;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PaymentTerm;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.domain.integration.Item;
import com.tosspaper.models.service.ContactSyncService;
import com.tosspaper.models.service.IntegrationAccountService;
import com.tosspaper.models.service.ItemService;
import com.tosspaper.models.service.PaymentTermService;
import com.tosspaper.models.service.PurchaseOrderSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Redis Stream listener for processing QuickBooks webhook events.
 * Receives validated webhook events from the quickbooks-events stream and processes them.
 * Supports CloudEvents format from QuickBooks webhooks.
 */
@Slf4j
@Component("quickBooksWebhookStreamListener")
@RequiredArgsConstructor
public class QuickBooksWebhookHandler implements MessageHandler<Map<String, String>> {

    private static final String QUEUE_NAME = "quickbooks-events";

    private final ObjectMapper objectMapper;

    @Override
    public String getQueueName() {
        return QUEUE_NAME;
    }

    @Override
    public void handle(Map<String, String> message) {
        String payload = message.get("payload");
        if (payload == null || payload.isBlank()) {
            log.warn("Missing payload in QuickBooks webhook message");
            return;
        }
        processWebhookPayload(payload);
    }
    private final IntegrationConnectionService connectionService;
    private final QuickBooksApiClient apiClient;
    private final ContactSyncService contactSyncService;
    private final PurchaseOrderSyncService purchaseOrderSyncService;
    private final PaymentTermService paymentTermService;
    private final IntegrationAccountService integrationAccountService;
    private final ItemService itemService;
    private final PurchaseOrderLineItemResolver lineItemResolver;
    private final com.tosspaper.integrations.common.PurchaseOrderContactEnricher contactEnricher;
    private final QBOPurchaseOrderMapper poMapper;
    private final VendorMapper vendorMapper;
    private final CustomerMapper customerMapper;
    private final PaymentTermMapper paymentTermMapper;
    private final AccountMapper accountMapper;
    private final ItemMapper itemMapper;
    private final com.tosspaper.integrations.quickbooks.preferences.PreferencesPullProvider preferencesPullProvider;

    private static final TypeReference<List<QBOCloudEvent>> CLOUD_EVENTS_TYPE = new TypeReference<>() {};

    /**
     * Process a QuickBooks webhook payload.
     * Parses the CloudEvents JSON array, groups by accountId, and processes each group.
     *
     * @param payload the JSON payload string (CloudEvents array)
     */
    private void processWebhookPayload(String payload) {
        final List<QBOCloudEvent> events;
        try {
            events = objectMapper.readValue(payload, CLOUD_EVENTS_TYPE);
        } catch (Exception e) {
            log.error("Failed to parse QuickBooks webhook payload as CloudEvents", e);
            return;
        }

        log.info("Received {} CloudEvent(s) from QuickBooks webhook", events.size());
        Map<String, List<QBOCloudEvent>> eventsByAccount = events.stream()
                .collect(Collectors.groupingBy(QBOCloudEvent::getAccountId));

        for (Map.Entry<String, List<QBOCloudEvent>> entry : eventsByAccount.entrySet()) {
            String accountId = entry.getKey();
            List<QBOCloudEvent> accountEvents = entry.getValue();
            try {
                processEventsForAccount(accountId, accountEvents);
            } catch (Exception e) {
                log.error("Failed processing QuickBooks webhook events for accountId: {}", accountId, e);
            }
        }
    }

    /**
     * Process all events for a single QuickBooks account.
     *
     * @param accountId the QuickBooks realm ID
     * @param events the events for this account
     */
    private void processEventsForAccount(String accountId, List<QBOCloudEvent> events) throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        var connectionOpt = connectionService.findByProviderCompanyIdAndProvider(accountId, IntegrationProvider.QUICKBOOKS);
        if (connectionOpt.isEmpty()) {
            log.error("No connection found for QuickBooks accountId: {}", accountId);
            return;
        }

        IntegrationConnection connection = connectionOpt.get();
        log.info("Processing {} event(s) for companyId: {}, accountId: {}",
                events.size(), connection.getCompanyId(), accountId);

        // Filter events by operation type
        List<QBOCloudEvent> deletedEvents = new ArrayList<>();
        List<QBOCloudEvent> createdOrUpdatedEvents = new ArrayList<>();

        for (QBOCloudEvent event : events) {
            QBOEventOperation operation = event.getOperation();
            if (operation == QBOEventOperation.DELETED) {
                deletedEvents.add(event);
            } else if (operation == QBOEventOperation.CREATED || operation == QBOEventOperation.UPDATED) {
                createdOrUpdatedEvents.add(event);
            }
        }

        // Process deleted events - group by entity type and collect IDs
        Map<IntegrationEntityType, List<String>> deletedIdsByType = deletedEvents.stream()
                .filter(event -> !isEventBeforeLastSync(event, connection))
                .collect(Collectors.groupingBy(
                        QBOCloudEvent::getEntityType,
                        Collectors.mapping(QBOCloudEvent::getEntityId, Collectors.toList())
                ));

        // Process deleted entities by type
        String provider = IntegrationProvider.QUICKBOOKS.getValue();
        for (Map.Entry<IntegrationEntityType, List<String>> entry : deletedIdsByType.entrySet()) {
            IntegrationEntityType entityType = entry.getKey();
            List<String> externalIds = entry.getValue();
            
            if (externalIds.isEmpty()) {
                continue;
            }

            switch (entityType) {
                case PURCHASE_ORDER -> {
                    int deleted = purchaseOrderSyncService.deleteByProviderAndExternalIds(
                            connection.getCompanyId(), provider, externalIds);
                    log.info("Soft deleted {} purchase orders for companyId: {}", deleted, connection.getCompanyId());
                }
                case VENDOR -> {
                    log.info("Vendors cannot be deleted - skipping {} deleted vendor events for companyId: {}",
                            externalIds.size(), connection.getCompanyId());
                }
                case JOB_LOCATION -> {
                    log.info("Job locations cannot be deleted - skipping {} deleted job location events for companyId: {}",
                            externalIds.size(), connection.getCompanyId());
                }
                case PAYMENT_TERM -> {
                    log.info("Payment terms cannot be deleted (QuickBooks rules) - skipping {} deleted payment term events for companyId: {}", 
                            externalIds.size(), connection.getCompanyId());
                }
                case ACCOUNT -> {
                    log.info("Accounts cannot be deleted (QuickBooks rules) - skipping {} deleted account events for connectionId: {}", 
                            externalIds.size(), connection.getId());
                }
                case ITEM -> {
                    log.info("Items cannot be deleted (QuickBooks rules) - skipping {} deleted item events for connectionId: {}", 
                            externalIds.size(), connection.getId());
                }
                default -> {
                    log.warn("Delete not implemented for entityType: {}", entityType);
                }
            }
        }

        Map<IntegrationEntityType, List<IEntity>> grouped = new HashMap<>();

        getEntities(createdOrUpdatedEvents, connection)
            .forEach(entity ->
                grouped.computeIfAbsent(
                    IntegrationEntityType.fromEntity(entity),
                    k -> new ArrayList<>()
                ).add(entity)
            );

        for (IntegrationEntityType type: IntegrationEntityType.values()) {
            var entities = grouped.getOrDefault(type, List.of());
            if (entities.isEmpty()) {
                continue;
            }
            
            switch (type) {
                case PURCHASE_ORDER -> {
                    List<PurchaseOrder> purchaseOrders = entities.stream()
                            .map(entity -> poMapper.toDomain(
                                (com.intuit.ipp.data.PurchaseOrder) entity,
                                connection.getId(),
                                connection.getDefaultCurrency()))
                            .toList();
                    log.info("Syncing {} purchase orders from webhook for companyId: {}",
                            purchaseOrders.size(), connection.getCompanyId());
                    purchaseOrders.forEach(po ->
                        log.debug("  - PO: externalId={}, displayId={}, vendor={}, items={}",
                            po.getExternalId(), po.getDisplayId(),
                            po.getVendorContact() != null ? po.getVendorContact().getName() : "N/A",
                            po.getItems() != null ? po.getItems().size() : 0)
                    );
                    // Resolve itemIds and accountIds before saving
                    lineItemResolver.resolveLineItemReferences(connection.getId(), purchaseOrders);
                    // Enrich vendor and ship-to contacts with full details from database BEFORE upserting
                    contactEnricher.enrichContacts(connection.getCompanyId(), IntegrationProvider.QUICKBOOKS, purchaseOrders);
                    purchaseOrderSyncService.upsertFromProvider(connection.getCompanyId(), purchaseOrders);
                }
                case VENDOR -> {
                    List<Party> vendors = entities.stream()
                            .map(entity -> vendorMapper.toDomain((Vendor) entity))
                            .toList();
                    log.info("Syncing {} vendors from webhook for companyId: {}",
                            vendors.size(), connection.getCompanyId());
                    vendors.forEach(vendor ->
                        log.debug("  - Vendor: externalId={}, name={}", vendor.getExternalId(), vendor.getName())
                    );
                    contactSyncService.upsertFromProvider(connection.getCompanyId(), vendors);
                }
                case JOB_LOCATION -> {
                    List<Party> customers = entities.stream()
                            .map(entity -> customerMapper.toDomain((com.intuit.ipp.data.Customer) entity))
                            .filter(java.util.Objects::nonNull) // Filter out null (non-job-location customers)
                            .toList();

                    if (customers.isEmpty()) {
                        log.debug("No job locations found in {} customer webhook events for companyId: {}",
                                entities.size(), connection.getCompanyId());
                    } else {
                        log.info("Syncing {} job locations from webhook for companyId: {}",
                                customers.size(), connection.getCompanyId());
                        customers.forEach(customer ->
                                log.debug("  - Job Location: externalId={}, name={}", customer.getExternalId(), customer.getName())
                        );
                        contactSyncService.upsertFromProvider(connection.getCompanyId(), customers);
                    }
                }
                case PAYMENT_TERM -> {
                    List<PaymentTerm> paymentTerms = entities.stream()
                            .map(entity -> paymentTermMapper.toDomain((Term) entity))
                            .toList();
                    log.info("Syncing {} payment terms from webhook for companyId: {}",
                            paymentTerms.size(), connection.getCompanyId());
                    paymentTerms.forEach(term ->
                        log.debug("  - PaymentTerm: externalId={}, name={}", term.getExternalId(), term.getName())
                    );
                    paymentTermService.upsertFromProvider(
                            connection.getCompanyId(),
                            IntegrationProvider.QUICKBOOKS.getValue(),
                            paymentTerms);
                }
                case ACCOUNT -> {
                    List<IntegrationAccount> accounts = entities.stream()
                            .map(entity -> accountMapper.toDomain((Account) entity))
                            .toList();
                    log.info("Syncing {} accounts from webhook for companyId: {}",
                            accounts.size(), connection.getCompanyId());
                    accounts.forEach(account ->
                        log.debug("  - Account: externalId={}, name={}, type={}",
                            account.getExternalId(), account.getName(), account.getAccountType())
                    );
                    integrationAccountService.upsert(connection.getId(), accounts);
                }
                case ITEM -> {
                    List<Item> items = entities.stream()
                            .map(entity -> itemMapper.toDomain((com.intuit.ipp.data.Item) entity))
                            .toList();
                    log.info("Syncing {} items from webhook for companyId: {}",
                            items.size(), connection.getCompanyId());
                    items.forEach(item ->
                        log.debug("  - Item: externalId={}, code/SKU={}, name={}, type={}, active={}",
                            item.getExternalId(), item.getCode(), item.getName(), item.getType(), item.getActive())
                    );
                    itemService.upsertFromProvider(connection.getCompanyId(), connection.getId(), items);
                }
                case PREFERENCES -> {
                    log.info("Preferences updated webhook received for companyId: {}, fetching latest preferences",
                            connection.getCompanyId());
                    // Fetch latest preferences from QuickBooks
                    List<com.tosspaper.models.domain.integration.Preferences> prefsList =
                            preferencesPullProvider.pullBatch(connection);
                    if (!prefsList.isEmpty()) {
                        com.tosspaper.models.domain.integration.Preferences prefs = prefsList.getFirst();
                        log.info("Updating connection currency settings - defaultCurrency: {}, multicurrencyEnabled: {}",
                                prefs.getDefaultCurrency(), prefs.getMulticurrencyEnabled());

                        // Update connection with new currency settings
                        connectionService.updateCurrencySettings(
                                connection.getId(),
                                prefs.getDefaultCurrency(),
                                prefs.getMulticurrencyEnabled()
                        );
                    }
                }
                default -> log.warn("Unsupported entity type in created/updated CloudEvent: {}", type);
            }
        }

    }

    /**
     * Process created/updated events by fetching entities in batch.
     *
     * @param events the created/updated CloudEvents
     * @param connection the integration connection
     * @return list of fetched entity IDs
     */
    private List<IEntity> getEntities(List<QBOCloudEvent> events, IntegrationConnection connection) {
        try {
            // Filter out events that occurred before last sync
            List<QBOCloudEvent> validEvents = events.stream()
                    .filter(event -> !isEventBeforeLastSync(event, connection))
                    .toList();

            if (validEvents.isEmpty()) {
                return List.of();
            }

            // Log all events being processed for debugging
            validEvents.forEach(event ->
                log.debug("Processing event - type: {}, entityType: {}, entityId: {}, operation: {}",
                    event.getType(), event.getEntityType(), event.getEntityId(), event.getOperation())
            );

            // Filter out events with unknown entity types and log them
            List<QBOCloudEvent> eventsWithUnknownType = validEvents.stream()
                    .filter(event -> event.getEntityType() == null)
                    .toList();

            if (!eventsWithUnknownType.isEmpty()) {
                eventsWithUnknownType.forEach(event ->
                    log.warn("Skipping event with unknown entity type - raw type: '{}', entityId: {}, accountId: {}",
                        event.getType(), event.getEntityId(), event.getAccountId())
                );
            }

            // Group events by entity type and collect IDs (filter out nulls)
            Map<IntegrationEntityType, List<String>> idsByType = validEvents.stream()
                    .filter(event -> event.getEntityType() != null)
                    .collect(Collectors.groupingBy(
                            QBOCloudEvent::getEntityType,
                            Collectors.mapping(QBOCloudEvent::getEntityId, Collectors.toList())
                    ));

            log.info("Fetching {} entities across {} types for companyId: {} - types: {}",
                    validEvents.size() - eventsWithUnknownType.size(), idsByType.size(),
                    connection.getCompanyId(), idsByType.keySet());


            // Fetch all entities in batch
            return apiClient.queryEntitiesByIdsBatch(connectionService.ensureActiveToken(connection), idsByType);


        } catch (Exception e) {
            throw new IntegrationException(
                    "Failed to process created/updated events for companyId: " + connection.getCompanyId(), e);
        }
    }

    /**
     * Check if an event occurred before the last sync completion time.
     *
     * @param event the CloudEvent to check
     * @param connection the integration connection
     * @return true if the event occurred before last sync, false otherwise
     */
    private boolean isEventBeforeLastSync(QBOCloudEvent event, IntegrationConnection connection) {
        if (event.getTime() != null && connection.getLastSyncCompletedAt() != null
                && event.getTime().isBefore(connection.getLastSyncCompletedAt())) {
            log.debug("Ignoring event before last sync: eventTime={}, lastSyncCompletedAt={}",
                    event.getTime(), connection.getLastSyncCompletedAt());
            return true;
        }
        return false;
    }

}

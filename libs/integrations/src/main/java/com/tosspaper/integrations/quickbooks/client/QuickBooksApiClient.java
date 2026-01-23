package com.tosspaper.integrations.quickbooks.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.intuit.ipp.core.IEntity;
import com.intuit.ipp.data.*;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.services.BatchOperation;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.services.QueryResult;
import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.integrations.quickbooks.config.QuickBooksResilienceConfig;
import com.tosspaper.integrations.quickbooks.purchaseorder.QBOPurchaseOrderMapper;
import com.tosspaper.integrations.utils.ProviderTrackingUtil;
import com.tosspaper.models.domain.Address;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PaymentTerm;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.domain.integration.ProviderTracked;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * High-level QuickBooks API client.
 * Wraps the SDK DataService with resilience and error handling.
 * Supports batch operations for efficient bulk processing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickBooksApiClient {

    private final QuickBooksClientFactory clientFactory;
    private final QuickBooksResilienceHelper resilienceHelper;
    private final QBOPurchaseOrderMapper qboPurchaseOrderMapper;
    private final ObjectMapper objectMapper;
    private final QuickBooksProperties qbProperties;

    /**
     * Create a single Bill in QuickBooks.
     *
     * @param connection the integration connection
     * @param bill the bill to create
     * @return the created bill
     * @throws IntegrationException if creation fails
     */
    public Bill createBill(IntegrationConnection connection, Bill bill) {
        return resilienceHelper.execute(connection.getRealmId(), () -> {
            try {
                DataService service = clientFactory.createDataService(connection);
                return service.add(bill);
            } catch (FMSException e) {
                log.error("Failed to create bill in QuickBooks", e);
                throw new IntegrationException("Failed to create bill: " + e.getMessage(), e);
            }
        });
    }


    /**
     * Query Purchase Orders from QuickBooks.
     *
     * @param connection the integration connection
     * @param query      the SQL query string
     * @return list of purchase orders
     */
    public List<com.intuit.ipp.data.PurchaseOrder> queryPurchaseOrders(IntegrationConnection connection, String query) {
        return executeQuery(connection, query);
    }

    /**
     * Create or update any QuickBooks entity (Item, Vendor, PurchaseOrder, etc.).
     * If entity has no Id, creates new entity. Otherwise updates existing entity.
     *
     * @param connection the integration connection
     * @param entity     the entity to create/update
     * @param <T>        the entity type (must extend IntuitEntity)
     * @return the created/updated entity with Id and SyncToken
     * @throws ProviderVersionConflictException if SyncToken mismatch on update
     * @throws IntegrationException             if operation fails
     */
    @SuppressWarnings("unchecked")
    public <T extends com.intuit.ipp.data.IntuitEntity> T save(IntegrationConnection connection, T entity) {
        return resilienceHelper.execute(connection.getRealmId(), () -> {
            try {
                DataService service = clientFactory.createDataService(connection);
                return (T) (entity.getId() == null ? service.add(entity) : service.update(entity));
            } catch (FMSException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                String lower = msg.toLowerCase();

                // Sync token conflicts (non-retryable)
                if (lower.contains("synctoken") || lower.contains("stale")) {
                    throw new ProviderVersionConflictException("QuickBooks rejected update due to SyncToken mismatch", e);
                }

                // Duplicate name errors (non-retryable) - QuickBooks error 6240
                if (lower.contains("duplicate name") ||
                    lower.contains("name supplied already exists") ||
                    lower.contains("already using this name")) {
                    throw new com.tosspaper.models.exception.DuplicateException("QuickBooks rejected due to duplicate name: " + msg, e);
                }

                throw new IntegrationException("Failed to save entity: " + msg, e);
            }
        });
    }

    public <T extends com.intuit.ipp.data.IntuitEntity> List<BatchResult<T>> saveBatch(
            IntegrationConnection connection,
            List<T> entities) {
        return executeBatch(connection, entities);
    }

    /**
     * Query Vendors from QuickBooks.
     *
     * @param connection the integration connection
     * @param query      the SQL query string
     * @return list of vendors
     */
    public List<Vendor> queryVendors(IntegrationConnection connection, String query) {
        return executeQuery(connection, query);
    }

    /**
     * Query Customers from QuickBooks.
     *
     * @param connection the integration connection
     * @param query      the SQL query string
     * @return list of customers
     */
    public List<com.intuit.ipp.data.Customer> queryCustomers(IntegrationConnection connection, String query) {
        return executeQuery(connection, query);
    }

    /**
     * Query Accounts from QuickBooks.
     * Use this to find expense accounts for AccountBasedExpenseLine.
     *
     * @param connection the integration connection
     * @param query      the SQL query string (e.g., "SELECT * FROM Account WHERE AccountType = 'Expense'")
     * @return list of accounts
     */
    public List<Account> queryAccounts(IntegrationConnection connection, String query) {
        return executeQuery(connection, query);
    }

    /**
     * Query accounts from QuickBooks since last sync.
     *
     * @param connection the integration connection
     * @return list of accounts mapped to IntegrationAccount domain models
     */
    public List<IntegrationAccount> queryAccountsSinceLastSync(IntegrationConnection connection) {
        OffsetDateTime lastSyncAt = connection.getLastSyncAt();
        
        String query = lastSyncAt == null 
            ? "SELECT * FROM Account"
            : String.format(
                "SELECT * FROM Account WHERE MetaData.LastUpdatedTime > '%s'",
                lastSyncAt
            );
        
        List<Account> accounts = executeQuery(connection, query);
        return accounts.stream()
            .map(this::mapAccount)
            .collect(Collectors.toList());
    }

    /**
     * Query Terms from QuickBooks.
     *
     * @param connection the integration connection
     * @param query      the SQL query string
     * @return list of terms
     */
    public List<Term> queryTerms(IntegrationConnection connection, String query) {
        return executeQuery(connection, query);
    }

    /**
     * Query payment terms from QuickBooks since last sync.
     *
     * @param connection the integration connection
     * @return list of payment terms mapped to PaymentTerm domain models
     */
    public List<PaymentTerm> queryPaymentTermsSinceLastSync(IntegrationConnection connection) {
        OffsetDateTime lastSyncAt = connection.getLastSyncAt();
        
        String query = lastSyncAt == null 
            ? "SELECT * FROM Term"
            : String.format(
                "SELECT * FROM Term WHERE MetaData.LastUpdatedTime > '%s'",
                lastSyncAt
            );
        
        List<Term> terms = executeQuery(connection, query);
        return terms.stream()
            .map(this::mapTerm)
            .collect(Collectors.toList());
    }

    private IntegrationAccount mapAccount(Account account) {
        IntegrationAccount acc = IntegrationAccount.builder()
            .name(account.getName())
            .accountType(account.getAccountType() != null ? account.getAccountType().value() : null)
            .accountSubType(account.getAccountSubType())
            .classification(account.getClassification() != null ? account.getClassification().value() : null)
            .active(true)  // Default to true, QB SDK doesn't expose Active field directly
            .currentBalance(account.getCurrentBalance())
            .build();
        
        Date createTime = account.getMetaData().getCreateTime();
        Date lastUpdatedTime = account.getMetaData().getLastUpdatedTime();
        ProviderTrackingUtil.populateProviderFields(
            acc,
            IntegrationProvider.QUICKBOOKS.getValue(),
            account.getId(),
            createTime != null ? OffsetDateTime.ofInstant(createTime.toInstant(), java.time.ZoneId.systemDefault()) : null,
            lastUpdatedTime != null ? OffsetDateTime.ofInstant(lastUpdatedTime.toInstant(), java.time.ZoneId.systemDefault()) : null
        );
        
        return acc;
    }

    private PaymentTerm mapTerm(Term term) {
        PaymentTerm paymentTerm = PaymentTerm.builder()
            .name(term.getName())
            .dueDays(term.getDueDays())
            .discountPercent(term.getDiscountPercent() != null ? term.getDiscountPercent() : null)
            .discountDays(term.getDiscountDays())
            .active(term.isActive())
            .build();
        
        Date createTime = term.getMetaData().getCreateTime();
        Date lastUpdatedTime = term.getMetaData().getLastUpdatedTime();
        ProviderTrackingUtil.populateProviderFields(
            paymentTerm,
            IntegrationProvider.QUICKBOOKS.getValue(),
            term.getId(),
            createTime != null ? OffsetDateTime.ofInstant(createTime.toInstant(), java.time.ZoneId.systemDefault()) : null,
            lastUpdatedTime != null ? OffsetDateTime.ofInstant(lastUpdatedTime.toInstant(), java.time.ZoneId.systemDefault()) : null
        );
        
        return paymentTerm;
    }

    public <T extends IEntity> List<T> executeQuery(IntegrationConnection connection, String query) {
        return resilienceHelper.execute(connection.getRealmId(), () -> {
            try {
                DataService service = clientFactory.createDataService(connection);
                QueryResult result = service.executeQuery(query);
                return (List<T>) result.getEntities();
            } catch (FMSException e) {
                log.error("QuickBooks query failed: {}", query, e);
                throw new IntegrationException("Query failed: " + e.getMessage(), e);
            }
        });
    }


    public List<CDCResult> getCDC(IntegrationConnection connection, IntegrationEntityType entityType) {
        return resilienceHelper.execute(connection.getRealmId(), () -> {
            try {
                String url = qbProperties.getApiBaseUrl() + "/" + connection.getRealmId() +
                        "/cdc?entities=" + entityType.getValue();

                if (connection.getLastSyncAt() != null) {
                    String changedSince = connection.getLastSyncAt()
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    // URL-encode to handle '+' and other special characters in ISO timestamps
                    url += "&changedSince=" + URLEncoder.encode(changedSince, StandardCharsets.UTF_8);
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .timeout(Duration.ofSeconds(30))
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + connection.getAccessToken())
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IntegrationException("CDC request failed with status " + response.statusCode() + ": " + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode cdcResponse = root.path("CDCResponse");

                List<CDCResult> results = new ArrayList<>();
                if (cdcResponse.isArray()) {
                    for (JsonNode cdcItem : cdcResponse) {
                        JsonNode queryResponses = cdcItem.path("QueryResponse");
                        if (queryResponses.isArray()) {
                            for (JsonNode queryResponse : queryResponses) {
                                JsonNode entities = queryResponse.path(entityType.getValue());
                                if (entities.isArray()) {
                                    for (JsonNode entityNode : entities) {
                                        results.add(parseCDCResult(entityNode));
                                    }
                                }
                            }
                        }
                    }
                }

                return results;

            } catch (IntegrationException e) {
                throw e;
            } catch (Exception e) {
                throw new IntegrationException("Failed to get CDC data for " + entityType.getValue(), e);
            }
        });
    }

    private CDCResult parseCDCResult(JsonNode node) {
        String id = node.path("Id").asText(null);
        String status = node.path("status").asText(null);
        boolean deleted = "Deleted".equalsIgnoreCase(status);

        OffsetDateTime lastUpdatedTime = null;
        JsonNode metaData = node.path("MetaData");
        if (!metaData.isMissingNode()) {
            String lastUpdated = metaData.path("LastUpdatedTime").asText(null);
            if (lastUpdated != null) {
                try {
                    lastUpdatedTime = OffsetDateTime.parse(lastUpdated, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                } catch (Exception e) {
                    log.warn("Failed to parse LastUpdatedTime: {}", lastUpdated);
                }
            }
        }

        return new CDCResult(id, deleted, lastUpdatedTime);
    }

    private <T extends IEntity> List<BatchResult<T>> executeBatch(
            IntegrationConnection connection,
            List<T> entities) {

        if (entities.isEmpty()) {
            return List.of();
        }

        List<BatchResult<T>> allResults = new ArrayList<>();
        List<List<T>> chunks = Lists.partition(entities, QuickBooksResilienceConfig.MAX_BATCH_SIZE);

        for (List<T> chunk : chunks) {
            List<BatchResult<T>> chunkResults = executeSingleBatch(connection, chunk);
            allResults.addAll(chunkResults);
        }

        return allResults;
    }

    @SuppressWarnings("unchecked")
    private <T extends IEntity> List<BatchResult<T>> executeSingleBatch(
            IntegrationConnection connection,
            List<T> entities) {

        return resilienceHelper.execute(connection.getRealmId(), () -> {
            try {
                DataService service = clientFactory.createDataService(connection);
                BatchOperation batchOperation = new BatchOperation();
                
                for (int i = 0; i < entities.size(); i++) {
                    T entity = entities.get(i);
                    com.intuit.ipp.data.IntuitEntity intuitEntity = (com.intuit.ipp.data.IntuitEntity) entity;
                    OperationEnum operation = intuitEntity.getId() == null 
                        ? OperationEnum.CREATE 
                        : OperationEnum.UPDATE;
                    batchOperation.addEntity(entity, operation, "batch-" + i);
                }

                service.executeBatch(batchOperation);

                List<BatchResult<T>> results = new ArrayList<>();
                for (int i = 0; i < entities.size(); i++) {
                    String bId = "batch-" + i;
                    Fault fault = batchOperation.getFault(bId);
                    
                    if (fault != null) {
                        String errorMsg = "Unknown batch error";
                        if (fault.getError() != null && !fault.getError().isEmpty()) {
                            var firstError = fault.getError().getFirst();
                            if (firstError.getDetail() != null && !firstError.getDetail().isEmpty()) {
                                errorMsg = firstError.getMessage() + ": " + firstError.getDetail();
                            } else {
                                errorMsg = firstError.getMessage();
                            }
                        }
                        results.add(BatchResult.failure(errorMsg));
                    } else {
                        IEntity result = batchOperation.getEntity(bId);
                        if (result != null) {
                            results.add(BatchResult.success((T) result));
                        } else {
                            results.add(BatchResult.failure("No result returned"));
                        }
                    }
                }

                return results;
            } catch (FMSException e) {
                log.error("Batch operation failed for {} entities", entities.size(), e);
                return entities.stream()
                        .map(entity -> BatchResult.<T>failure("Batch failed: " + e.getMessage()))
                        .toList();
            }
        });
    }


    /**
     * Query multiple entities by IDs from QuickBooks using batch operation.
     * Handles mixed entity types in a single call (e.g., webhook events with PurchaseOrders, Bills, Vendors).
     * Uses batchOperation.addQuery() with individual queries for each ID.
     * Batches are limited to 30 queries per QuickBooks limits.
     * 
     * The method first generates all queries as a flat list (with entity type info),
     * then partitions them evenly into batches of 30. This spreads queries across batches
     * rather than grouping by entity type, ensuring better load distribution.
     *
     * @param connection the integration connection
     * @param idsByType map of entity type to list of IDs to fetch
     * @return flat list of all entities (only successful results, failures logged)
     */
    public List<IEntity> queryEntitiesByIdsBatch(
            IntegrationConnection connection,
            Map<IntegrationEntityType, List<String>> idsByType) {

        if (idsByType == null || idsByType.isEmpty()) {
            return List.of();
        }

        List<QueryWithType> allQueries = new ArrayList<>();
        for (Map.Entry<IntegrationEntityType, List<String>> entry : idsByType.entrySet()) {
            IntegrationEntityType entityType = entry.getKey();
            List<String> ids = entry.getValue();

            if (ids == null || ids.isEmpty()) {
                continue;
            }

            for (String id : ids) {
                StringBuilder queryBuilder = new StringBuilder("SELECT * FROM ");
                queryBuilder.append(entityType.getValue());
                queryBuilder.append(" WHERE Id = '");
                queryBuilder.append(id);
                queryBuilder.append("'");
                allQueries.add(new QueryWithType(queryBuilder.toString(), entityType, id));
            }
        }

        int totalQueries = allQueries.size();
        log.info("Querying {} entities across {} types using batch API", totalQueries, idsByType.size());

        List<List<QueryWithType>> queryBatches = Lists.partition(allQueries, QuickBooksResilienceConfig.MAX_BATCH_SIZE);

        List<IEntity> allResults = new ArrayList<>();
        for (List<QueryWithType> queryBatch : queryBatches) {
            List<IEntity> batchResults = queryEntitiesByIdsSingleBatch(connection, queryBatch);
            allResults.addAll(batchResults);
        }

        log.info("Successfully fetched {}/{} entities", allResults.size(), totalQueries);
        return allResults;
    }

    /**
     * Helper record to hold query string with entity type and ID for result processing.
     */
    private record QueryWithType(
            String query,
            IntegrationEntityType entityType,
            String id
    ) {}

    /**
     * Execute a single batch query request (up to 30 queries).
     * Uses batchOperation.addQuery() for each query.
     */
    private List<IEntity> queryEntitiesByIdsSingleBatch(
            IntegrationConnection connection,
            List<QueryWithType> queries) {

        return resilienceHelper.execute(connection.getRealmId(), () -> {
            try {
                DataService service = clientFactory.createDataService(connection);
                BatchOperation batchOperation = new BatchOperation();

                for (int i = 0; i < queries.size(); i++) {
                    batchOperation.addQuery(queries.get(i).query(), "batch-" + i);
                }

                service.executeBatch(batchOperation);

                List<IEntity> results = new ArrayList<>();
                for (int i = 0; i < queries.size(); i++) {
                    String bId = "batch-" + i;
                    QueryWithType queryWithType = queries.get(i);

                    Fault fault = batchOperation.getFault(bId);
                    if (fault != null) {
                        String errorMsg = extractBatchErrorMessage(fault);
                        log.warn("Failed to fetch {} {}: {}",
                                queryWithType.entityType().getDisplayName(), queryWithType.id(), errorMsg);
                        continue;
                    }

                    QueryResult queryResult = batchOperation.getQueryResponse(bId);
                    if (queryResult != null && queryResult.getEntities() != null
                            && !queryResult.getEntities().isEmpty()) {
                        results.add(queryResult.getEntities().get(0));
                    } else {
                        log.warn("{} not found: {}",
                                queryWithType.entityType().getDisplayName(), queryWithType.id());
                    }
                }

                return results;
            } catch (FMSException e) {
                log.error("Batch query operation failed for {} queries", queries.size(), e);
                return List.of();
            }
        });
    }

    /**
     * Extract error message from QuickBooks Fault object.
     */
    private String extractBatchErrorMessage(Fault fault) {
        if (fault.getError() != null && !fault.getError().isEmpty()) {
            var firstError = fault.getError().getFirst();
            if (firstError.getDetail() != null && !firstError.getDetail().isEmpty()) {
                return firstError.getMessage() + ": " + firstError.getDetail();
            }
            return firstError.getMessage();
        }
        return "Unknown batch error";
    }

    /**
     * Result of a batch operation for a single entity.
     *
     * @param <T> the entity type
     */
    public record BatchResult<T>(
            boolean success,
            T entity,
            String errorMessage
    ) {
        public static <T> BatchResult<T> success(T entity) {
            return new BatchResult<>(true, entity, null);
        }

        public static <T> BatchResult<T> failure(String errorMessage) {
            return new BatchResult<>(false, null, errorMessage);
        }
    }
}

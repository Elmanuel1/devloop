package com.tosspaper.integrations.quickbooks.purchaseorder;

import com.tosspaper.integrations.common.PurchaseOrderContactEnricher;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPullProvider;
import com.tosspaper.integrations.quickbooks.client.CDCResult;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderPullProvider implements IntegrationPullProvider<PurchaseOrder> {

    private final QuickBooksApiClient apiClient;
    private final QBOPurchaseOrderMapper poMapper;
    private final QuickBooksProperties properties;
    private final PurchaseOrderContactEnricher contactEnricher;


    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.PURCHASE_ORDER;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public List<PurchaseOrder> pullBatch(IntegrationConnection connection) {

        List<PurchaseOrder> list = new ArrayList<>();

        StringBuilder query = new StringBuilder("SELECT * FROM PurchaseOrder");
        OffsetDateTime lastSyncTime = connection.getLastSyncAt();
        if (lastSyncTime != null) {
            var cdc = apiClient.getCDC(connection, IntegrationEntityType.PURCHASE_ORDER);
            for (CDCResult result: cdc) {
                if(result.deleted()) {
                    list.add(poMapper.toDeletedPurchaseOrder(result.id(), result.lastUpdatedTime()));
                }
            }

            String formattedDate = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(lastSyncTime);
            query.append(" WHERE MetaData.LastUpdatedTime > '").append(formattedDate).append("'");
        }
        query.append(" MAXRESULTS 1000");

        log.debug("Connection default currency: {}", connection.getDefaultCurrency());

        var fetched =  apiClient.queryPurchaseOrders(connection, query.toString())
                .stream()
                .map(qboPo -> {
                    log.debug("Mapping PO docNumber={}, QB CurrencyRef={}, connection defaultCurrency={}",
                            qboPo.getDocNumber(),
                            qboPo.getCurrencyRef() != null ? qboPo.getCurrencyRef().getValue() : "null",
                            connection.getDefaultCurrency());
                    return poMapper.toDomain(
                        qboPo,
                        connection.getId(),
                        connection.getDefaultCurrency()
                    );
                })
                .toList();
        list.addAll(fetched);

        // Enrich vendor and ship-to contacts with full details from database
        contactEnricher.enrichContacts(connection.getCompanyId(), IntegrationProvider.QUICKBOOKS, list);

        return list;
    }

    @Override
    public PurchaseOrder getById(String externalId, IntegrationConnection connection) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        String query = "SELECT * FROM PurchaseOrder WHERE Id = '" + externalId + "'";
        List<com.intuit.ipp.data.PurchaseOrder> pos = apiClient.queryPurchaseOrders(connection, query);
        if (pos.isEmpty()) {
            return null;
        }

        PurchaseOrder po = poMapper.toDomain(
            pos.getFirst(),
            connection.getId(),
            connection.getDefaultCurrency()
        );

        // Enrich vendor and ship-to contacts with full details from database
        contactEnricher.enrichContacts(connection.getCompanyId(), IntegrationProvider.QUICKBOOKS, List.of(po));

        return po;
    }
}


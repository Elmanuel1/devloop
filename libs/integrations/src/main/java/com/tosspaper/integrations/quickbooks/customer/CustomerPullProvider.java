package com.tosspaper.integrations.quickbooks.customer;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPullProvider;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerPullProvider implements IntegrationPullProvider<Party> {

    private final QuickBooksApiClient apiClient;
    private final CustomerMapper customerMapper;
    private final QuickBooksProperties properties;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.JOB_LOCATION;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public List<Party> pullBatch(IntegrationConnection connection) {
        StringBuilder query = new StringBuilder("SELECT * FROM Customer");
        OffsetDateTime lastSyncTime = connection.getLastSyncAt();
        if (lastSyncTime != null) {
            String formattedDate = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(lastSyncTime);
            query.append(" WHERE MetaData.LastUpdatedTime > '").append(formattedDate).append("'");
        }
        query.append(" MAXRESULTS 1000");

        return apiClient.queryCustomers(connection, query.toString())
                .stream()
                .map(customerMapper::toDomain)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Override
    public Party getById(String externalId, IntegrationConnection connection) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        String query = "SELECT * FROM Customer WHERE Id = '" + externalId + "'";
        List<com.intuit.ipp.data.Customer> customers = apiClient.queryCustomers(connection, query);
        return customers.isEmpty() ? null : customerMapper.toDomain(customers.getFirst());
    }
}

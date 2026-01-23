package com.tosspaper.integrations.quickbooks.vendor;

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
public class VendorPullProvider implements IntegrationPullProvider<Party> {

    private final QuickBooksApiClient apiClient;
    private final VendorMapper vendorMapper;
    private final QuickBooksProperties properties;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.VENDOR;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public List<Party> pullBatch(IntegrationConnection connection) {
        StringBuilder query = new StringBuilder("SELECT * FROM Vendor");
        OffsetDateTime lastSyncTime = connection.getLastSyncAt();
        if (lastSyncTime != null) {
            String formattedDate = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(lastSyncTime);
            query.append(" WHERE MetaData.LastUpdatedTime > '").append(formattedDate).append("'");
        }
        query.append(" MAXRESULTS 1000");

        return apiClient.queryVendors(connection, query.toString())
                .stream()
                .map(vendorMapper::toDomain)
                .toList();
    }

    @Override
    public Party getById(String externalId, IntegrationConnection connection) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        String query = "SELECT * FROM Vendor WHERE Id = '" + externalId + "'";
        List<com.intuit.ipp.data.Vendor> vendors = apiClient.queryVendors(connection, query);
        return vendors.isEmpty() ? null : vendorMapper.toDomain(vendors.getFirst());
    }
}


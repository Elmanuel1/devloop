package com.tosspaper.integrations.provider;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Factory for resolving integration providers by provider ID.
 */
@Component
public class IntegrationProviderFactory {

    private final Map<IntegrationProvider, IntegrationOAuthProvider> oauthProviders;
    private final Map<IntegrationProvider, IntegrationCompanyInfoProvider> companyInfoProviders;
    private final Table<IntegrationProvider, IntegrationEntityType, IntegrationPushProvider<?>> pushProviders;
    private final Table<IntegrationProvider, IntegrationEntityType, IntegrationPullProvider<?>> pullProviders;
    @Getter
    private final List<ProviderInfo> allProviders;

    public IntegrationProviderFactory(
            List<IntegrationOAuthProvider> oauthProviderList,
            List<IntegrationCompanyInfoProvider> companyInfoProviderList,
            List<IntegrationPushProvider<?>> pushProviderList,
            List<IntegrationPullProvider<?>> pullProviderList) {
        this.oauthProviders = oauthProviderList.stream()
                .collect(Collectors.toMap(
                        IntegrationOAuthProvider::getProviderId,
                        Function.identity()
                ));

        this.companyInfoProviders = companyInfoProviderList.stream()
                .collect(Collectors.toMap(
                        IntegrationCompanyInfoProvider::getProviderId,
                        Function.identity()
                ));

        this.pushProviders = HashBasedTable.create();
        for (IntegrationPushProvider<?> provider : pushProviderList) {
            pushProviders.put(provider.getProviderId(), provider.getEntityType(), provider);
        }

        this.pullProviders = HashBasedTable.create();
        for (IntegrationPullProvider<?> provider : pullProviderList) {
            pullProviders.put(provider.getProviderId(), provider.getEntityType(), provider);
        }

        this.allProviders = oauthProviderList.stream()
                .map(p -> new ProviderInfo(
                        p.getProviderId().getValue(),
                        p.getDisplayName(),
                        p.getProviderId().getCategory().getValue()))
                .toList();
    }

    public IntegrationOAuthProvider getOAuthProvider(IntegrationProvider provider) {
        IntegrationOAuthProvider oauthProvider = oauthProviders.get(provider);
        if (oauthProvider == null) {
            throw new IllegalArgumentException("OAuth provider not found: " + provider);
        }
        return oauthProvider;
    }

    public Optional<IntegrationCompanyInfoProvider> getCompanyInfoProvider(IntegrationProvider provider) {
        return Optional.ofNullable(companyInfoProviders.get(provider));
    }

    public Optional<IntegrationPushProvider<?>> getPushProvider(IntegrationProvider provider, IntegrationEntityType entityType) {
        return Optional.ofNullable(pushProviders.get(provider, entityType));
    }

    public Optional<IntegrationPullProvider<?>> getPullProvider(IntegrationProvider provider, IntegrationEntityType entityType) {
        return Optional.ofNullable(pullProviders.get(provider, entityType));
    }

    /**
     * Provider information DTO.
     */
    public record ProviderInfo(
            String id,
            String displayName,
            String category
    ) {}
}

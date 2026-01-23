package com.tosspaper.integrations.quickbooks.company;

import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.services.QueryResult;
import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.provider.IntegrationCompanyInfoProvider;
import com.tosspaper.integrations.quickbooks.client.QuickBooksClientFactory;
import com.tosspaper.integrations.quickbooks.client.QuickBooksResilienceHelper;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * QuickBooks implementation for fetching company information.
 * Used during OAuth callback to populate externalCompanyName.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickBooksCompanyInfoProvider implements IntegrationCompanyInfoProvider {

    private final QuickBooksClientFactory clientFactory;
    private final QuickBooksResilienceHelper resilienceHelper;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public CompanyInfo fetchCompanyInfo(String accessToken, String realmId) {
        return resilienceHelper.execute(realmId, () -> {
            try {
                DataService service = clientFactory.createDataService(accessToken, realmId);
                QueryResult result = service.executeQuery("SELECT * FROM CompanyInfo");

                @SuppressWarnings("unchecked")
                List<com.intuit.ipp.data.CompanyInfo> companyInfoList =
                        (List<com.intuit.ipp.data.CompanyInfo>) result.getEntities();

                if (companyInfoList == null || companyInfoList.isEmpty()) {
                    throw new IntegrationException("No company info found for realmId: " + realmId);
                }

                com.intuit.ipp.data.CompanyInfo qboCompanyInfo = companyInfoList.getFirst();

                var companyName = Optional.ofNullable(qboCompanyInfo.getLegalName()).orElse(qboCompanyInfo.getCompanyName());
                log.info("Fetched QuickBooks company info: realmId={}, companyName={}", realmId, companyName);

                return new CompanyInfo(
                        qboCompanyInfo.getId(),
                        companyName,
                        null // Currency will be fetched via pull provider
                );

            } catch (FMSException e) {
                log.error("Failed to fetch company info from QuickBooks: realmId={}", realmId, e);
                throw new IntegrationException("Failed to fetch company info: " + e.getMessage(), e);
            }
        });
    }
}

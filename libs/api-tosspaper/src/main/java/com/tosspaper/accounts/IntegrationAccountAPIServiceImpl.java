package com.tosspaper.accounts;

import com.tosspaper.generated.model.IntegrationAccount;
import com.tosspaper.generated.model.IntegrationAccountList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationAccountAPIServiceImpl implements IntegrationAccountAPIService {

    private final IntegrationAccountRepository integrationAccountRepository;
    private final IntegrationAccountMapper integrationAccountMapper;

    @Override
    public IntegrationAccountList getAccounts(Long companyId, AccountType accountType) {
        log.debug("Fetching accounts for companyId: {}, accountType: {}", companyId, accountType);

        List<com.tosspaper.models.domain.integration.IntegrationAccount> domainAccounts =
                integrationAccountRepository.findByCompanyId(companyId, accountType);

        List<IntegrationAccount> apiAccounts = domainAccounts.stream()
                .map(integrationAccountMapper::toApi)
                .toList();

        IntegrationAccountList accountList = new IntegrationAccountList();
        accountList.setData(apiAccounts);

        return accountList;
    }
}

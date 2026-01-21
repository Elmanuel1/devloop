package com.tosspaper.accounts;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.IntegrationAccountsApi;
import com.tosspaper.generated.model.IntegrationAccountList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class IntegrationAccountController implements IntegrationAccountsApi {

    private final IntegrationAccountAPIService integrationAccountService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'accounts:view')")
    public ResponseEntity<IntegrationAccountList> getIntegrationAccounts(String xContextId, String type) {
        log.info("GET /v1/accounts - Fetching accounts: companyId={}, type={}", xContextId, type);
        AccountType accountType = AccountType.fromString(type);
        IntegrationAccountList accountList = integrationAccountService.getAccounts(
                HeaderUtils.parseCompanyId(xContextId), accountType);
        return ResponseEntity.ok(accountList);
    }
}

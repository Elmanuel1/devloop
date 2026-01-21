package com.tosspaper.accounts;

import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.service.IntegrationAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationAccountServiceImpl implements IntegrationAccountService {

    private final IntegrationAccountRepository integrationAccountRepository;

    @Override
    public void upsert(String connectionId, List<IntegrationAccount> accounts) {
        log.debug("Upserting {} accounts for connection: {}", accounts.size(), connectionId);
        integrationAccountRepository.upsert(connectionId, accounts);
    }

    @Override
    public List<IntegrationAccount> findByConnectionId(String connectionId) {
        log.debug("Finding accounts for connection: {}", connectionId);
        return integrationAccountRepository.findByConnectionId(connectionId);
    }

    @Override
    public IntegrationAccount findById(String id) {
        log.debug("Finding account by ID: {}", id);
        return integrationAccountRepository.findById(id);
    }

    @Override
    public List<IntegrationAccount> findByIds(String connectionId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        log.debug("Finding {} accounts by IDs for connection: {}", ids.size(), connectionId);
        return integrationAccountRepository.findByIds(connectionId, ids);
    }

    @Override
    public Map<String, String> findIdsByExternalIdsAndConnection(String connectionId, List<String> externalIds) {
        return integrationAccountRepository.findIdsByExternalIdsAndConnection(connectionId, externalIds);
    }
}


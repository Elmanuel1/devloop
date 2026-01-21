package com.tosspaper.service.impl;

import com.tosspaper.contact.ContactSyncRepository;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.service.ContactSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactSyncServiceImpl implements ContactSyncService {

    private final ContactSyncRepository contactSyncRepository;

    @Override
    public void upsertFromProvider(Long companyId, List<Party> contacts) {
        contactSyncRepository.upsertFromProvider(companyId, contacts);
    }

    @Override
    public void updateSyncStatus(String contactId, String provider, String externalId, String providerVersion, java.time.OffsetDateTime providerLastUpdatedAt) {
        contactSyncRepository.updateSyncStatus(contactId, provider, externalId, providerVersion, providerLastUpdatedAt);
    }

    @Override
    public void batchUpdateSyncStatus(java.util.List<com.tosspaper.models.common.SyncStatusUpdate> updates) {
        contactSyncRepository.batchUpdateSyncStatus(updates);
    }

    @Override
    public Party findById(String id) {
        return contactSyncRepository.findById(id);
    }

    @Override
    public List<Party> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return contactSyncRepository.findByIds(ids);
    }

    @Override
    public List<Party> findNeedingPush(Long companyId, int limit, List<String> tags, int maxRetries) {
        return contactSyncRepository.findNeedingPush(companyId, limit, tags, maxRetries);
    }

    @Override
    public List<Party> findByProviderAndExternalIds(Long companyId, String provider, List<String> externalIds) {
        if (externalIds == null || externalIds.isEmpty()) {
            return List.of();
        }
        return contactSyncRepository.findByProviderAndExternalIds(companyId, provider, externalIds);
    }

    @Override
    public void incrementRetryCount(String contactId, String errorMessage) {
        contactSyncRepository.incrementRetryCount(contactId, errorMessage);
    }

    @Override
    public void markAsPermanentlyFailed(String contactId, String errorMessage) {
        contactSyncRepository.markAsPermanentlyFailed(contactId, errorMessage);
    }

    @Override
    public void resetRetryTracking(String contactId) {
        contactSyncRepository.resetRetryTracking(contactId);
    }

}



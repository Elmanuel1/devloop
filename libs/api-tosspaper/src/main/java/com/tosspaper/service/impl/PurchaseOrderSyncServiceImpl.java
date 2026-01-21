package com.tosspaper.service.impl;

import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.service.PurchaseOrderSyncService;
import com.tosspaper.purchaseorder.PurchaseOrderSyncRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderSyncServiceImpl implements PurchaseOrderSyncService {

    private final PurchaseOrderSyncRepository purchaseOrderSyncRepository;

    @Override
    public void upsertFromProvider(Long companyId, List<PurchaseOrder> purchaseOrders) {
        purchaseOrderSyncRepository.upsertFromProvider(companyId, purchaseOrders);
    }

    @Override
    public int deleteByProviderAndExternalIds(Long companyId, String provider, List<String> externalIds) {
        return purchaseOrderSyncRepository.deleteByProviderAndExternalIds(companyId, provider, externalIds);
    }

    @Override
    public void updateSyncStatus(String poId, String externalId, String providerVersion,
            java.time.OffsetDateTime providerLastUpdatedAt) {
        purchaseOrderSyncRepository.updateSyncStatus(poId, externalId, providerVersion, providerLastUpdatedAt);
    }

    @Override
    public List<PurchaseOrder> findNeedingPush(Long companyId, int limit, int maxRetries) {
        return purchaseOrderSyncRepository.findNeedingPush(companyId, limit, maxRetries);
    }

    @Override
    public PurchaseOrder findByProviderAndExternalId(Long companyId, String provider, String externalId) {
        return purchaseOrderSyncRepository.findByProviderAndExternalId(companyId, provider, externalId);
    }

    @Override
    public List<PurchaseOrder> findByCompanyIdAndDisplayIds(Long companyId, List<String> displayIds) {
        return purchaseOrderSyncRepository.findByCompanyIdAndDisplayIds(companyId, displayIds);
    }

    @Override
    public PurchaseOrder findById(String poId) {
        return purchaseOrderSyncRepository.findById(poId);
    }

    @Override
    public void incrementRetryCount(String poId, String errorMessage) {
        purchaseOrderSyncRepository.incrementRetryCount(poId, errorMessage);
    }

    @Override
    public void markAsPermanentlyFailed(String poId, String errorMessage) {
        purchaseOrderSyncRepository.markAsPermanentlyFailed(poId, errorMessage);
    }

    @Override
    public void resetRetryTracking(String poId) {
        purchaseOrderSyncRepository.resetRetryTracking(poId);
    }
}

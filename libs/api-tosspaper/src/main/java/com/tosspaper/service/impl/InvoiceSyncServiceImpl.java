package com.tosspaper.service.impl;

import com.tosspaper.integrations.config.PushRetryConfig;
import com.tosspaper.invoices.InvoiceSyncRepository;
import com.tosspaper.models.common.PushResult;
import com.tosspaper.models.domain.Invoice;
import com.tosspaper.models.service.InvoiceSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceSyncServiceImpl implements InvoiceSyncService {

    private final InvoiceSyncRepository invoiceSyncRepository;
    private final PushRetryConfig pushRetryConfig;

    @Override
    public void upsertFromProvider(Long companyId, List<Invoice> invoices) {
        // Not implemented - Bills are pushed, not pulled
        log.warn("upsertFromProvider not implemented for invoices");
    }

    @Override
    public List<Invoice> findNeedingPush(Long companyId, String provider, int limit) {
        // Provider parameter is ignored - we use status filter instead
        return invoiceSyncRepository.findNeedingPush(companyId, limit, pushRetryConfig.getMaxAttempts());
    }

    @Override
    public List<Invoice> findAcceptedNeedingPush(Long companyId, int limit) {
        return invoiceSyncRepository.findNeedingPush(companyId, limit, pushRetryConfig.getMaxAttempts());
    }

    @Override
    public int markAsPushed(List<PushResult> results) {
        return invoiceSyncRepository.markAsPushed(results);
    }

    @Override
    public void incrementRetryCount(String invoiceId, String errorMessage) {
        invoiceSyncRepository.incrementRetryCount(invoiceId, errorMessage);
    }

    @Override
    public void markAsPermanentlyFailed(String invoiceId, String errorMessage) {
        invoiceSyncRepository.markAsPermanentlyFailed(invoiceId, errorMessage);
    }

    @Override
    public void resetRetryTracking(String invoiceId) {
        invoiceSyncRepository.resetRetryTracking(invoiceId);
    }

    @Override
    public Invoice findById(String invoiceId) {
        return invoiceSyncRepository.findById(invoiceId);
    }
}

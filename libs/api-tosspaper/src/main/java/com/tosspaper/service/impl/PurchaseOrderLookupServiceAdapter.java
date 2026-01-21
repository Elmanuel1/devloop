package com.tosspaper.service.impl;

import com.tosspaper.generated.model.PurchaseOrderStatusUpdate;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.PurchaseOrderStatus;
import com.tosspaper.models.service.PurchaseOrderLookupService;
import com.tosspaper.models.service.PurchaseOrderLookupService.PurchaseOrderBasicInfo;
import com.tosspaper.purchaseorder.PurchaseOrderRepository;
import com.tosspaper.purchaseorder.PurchaseOrderService;
import com.tosspaper.purchaseorder.PurchaseOrderSyncRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Adapter that implements PurchaseOrderLookupService by delegating to PurchaseOrderService and PurchaseOrderRepository.
 * This allows ai-engine module to access PO operations without depending on api-tosspaper.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderLookupServiceAdapter implements PurchaseOrderLookupService {
    
    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderSyncRepository purchaseOrderSyncRepository;
    
    @Override
    public PurchaseOrderBasicInfo getPurchaseOrder(Long companyId, String id) {
        var po = purchaseOrderService.getPurchaseOrder(companyId, id);
        var status = PurchaseOrderStatus.fromValue(po.getStatus().getValue());
        return new PurchaseOrderBasicInfo(po.getId(), po.getCompanyId(), status, po.getProjectId(), po.getDisplayId());
    }
    
    @Override
    public Optional<PurchaseOrderBasicInfo> findByCompanyIdAndDisplayId(Long companyId, String displayId) {
        return purchaseOrderRepository.findByCompanyIdAndDisplayId(companyId, displayId)
            .map(record -> {
                var status = PurchaseOrderStatus.fromValue(record.getStatus());
                return new PurchaseOrderBasicInfo(record.getId(), record.getCompanyId(), status, record.getProjectId(), record.getDisplayId());
            });
    }
    
    @Override
    public Optional<PurchaseOrder> getPoWithItemsByPoNumber(Long companyId, String poNumber) {
        List<PurchaseOrder> results = purchaseOrderSyncRepository.findByCompanyIdAndDisplayIds(companyId, List.of(poNumber));
        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(results.get(0));
    }
    
    @Override
    public void updatePurchaseOrderStatus(Long companyId, String id, PurchaseOrderStatus newStatus, String notes, String authorId) {
        var statusUpdate = new PurchaseOrderStatusUpdate();
        var generatedStatus = com.tosspaper.generated.model.PurchaseOrderStatus.fromValue(newStatus.getValue());
        if (generatedStatus == null) {
            throw new IllegalArgumentException("Unknown PurchaseOrderStatus: " + newStatus.getValue());
        }
        statusUpdate.setStatus(generatedStatus);
        statusUpdate.setNotes(notes);

        purchaseOrderService.updatePurchaseOrderStatus(companyId, id, statusUpdate, authorId);
    }
}


package com.tosspaper.delivery_slips;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.DeliverySlipsApi;
import com.tosspaper.generated.model.DeliverySlipList;
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
public class DeliverySlipController implements DeliverySlipsApi {

    private final DeliverySlipService deliverySlipService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<DeliverySlipList> getDeliverySlips(
            String xContextId,
            String projectId,
            String purchaseOrderId,
            String poNumber,
            Integer limit,
            String cursor,
            String search) {
        log.debug("GET /v1/delivery-slips - projectId={}, purchaseOrderId={}, poNumber={}, limit={}, cursor={}, search={}",
                projectId, purchaseOrderId, poNumber, limit, cursor, search);
        
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        DeliverySlipList deliverySlips = deliverySlipService.getDeliverySlips(companyId, projectId, purchaseOrderId, poNumber, search, limit, cursor);
        
        return ResponseEntity.ok(deliverySlips);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<com.tosspaper.generated.model.DeliverySlip> getDeliverySlipById(
            String xContextId,
            String id) {
        log.debug("GET /v1/delivery-slips/{}", id);
        
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        com.tosspaper.generated.model.DeliverySlip deliverySlip = deliverySlipService.getDeliverySlipById(companyId, id);
        
        return ResponseEntity.ok(deliverySlip);
    }
}


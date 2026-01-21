package com.tosspaper.purchaseorder;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.common.security.SecurityUtils;
import com.tosspaper.generated.api.PurchaseOrdersApi;
import com.tosspaper.generated.model.PurchaseOrder;
import com.tosspaper.generated.model.PurchaseOrderCreate;
import com.tosspaper.generated.model.PurchaseOrderList;
import com.tosspaper.generated.model.PurchaseOrderStatus;
import com.tosspaper.generated.model.PurchaseOrderStatusUpdate;
import com.tosspaper.generated.model.PurchaseOrderUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.OffsetDateTime;

@RestController
@RequiredArgsConstructor
public class PurchaseOrderController implements PurchaseOrdersApi {

    private final PurchaseOrderService purchaseOrderService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'purchase-orders:create')")
    public ResponseEntity<Void> createProjectPurchaseOrder(String xContextId, String projectId, PurchaseOrderCreate purchaseOrderCreate) {
        // The xContextId header is assumed to be the company ID.
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        PurchaseOrder createdPurchaseOrder = purchaseOrderService.createPurchaseOrder(companyId, projectId, purchaseOrderCreate);
        
        // Build the location URI for the created resource
        URI location = ServletUriComponentsBuilder
                .fromCurrentContextPath()
                .path("/v1/purchase-orders/{id}")
                .buildAndExpand(createdPurchaseOrder.getId())
                .toUri();
        
        return ResponseEntity.created(location).build();
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'purchase-orders:view')")
    public ResponseEntity<PurchaseOrder> getPurchaseOrderById(String xContextId, String id) {
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        return ResponseEntity.ok(purchaseOrderService.getPurchaseOrder(companyId, id));
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'purchase-orders:view')")
    public ResponseEntity<PurchaseOrderList> getProjectPurchaseOrders(String xContextId, String projectId, String displayId, PurchaseOrderStatus status, OffsetDateTime dueDate, OffsetDateTime createdDateFrom, OffsetDateTime createdDateTo, Integer page, Integer pageSize, String search) {
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        var domainStatus = (status != null) ? com.tosspaper.purchaseorder.model.PurchaseOrderStatus.valueOf(status.name()) : null;
        
        return ResponseEntity.ok(purchaseOrderService.getPurchaseOrdersByProjectId(companyId, projectId, displayId, domainStatus, dueDate, createdDateFrom, createdDateTo, page, pageSize, search));
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'purchase-orders:view')")
    public ResponseEntity<PurchaseOrderList> getPurchaseOrders(String xContextId, String projectId, String displayId, PurchaseOrderStatus status, OffsetDateTime dueDate, OffsetDateTime createdDateFrom, OffsetDateTime createdDateTo, Integer page, Integer pageSize, String search) {
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        var domainStatus = (status != null) ? com.tosspaper.purchaseorder.model.PurchaseOrderStatus.valueOf(status.name()) : null;
        
        return ResponseEntity.ok(purchaseOrderService.getPurchaseOrdersByProjectId(companyId, projectId, displayId, domainStatus, dueDate, createdDateFrom, createdDateTo, page, pageSize, search));
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'purchase-orders:edit')")
    public ResponseEntity<Void> updatePurchaseOrder(String xContextId, String id, PurchaseOrderUpdate purchaseOrderUpdate) {
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        String authorId = SecurityUtils.getSubjectFromJwt();
        purchaseOrderService.updatePurchaseOrder(companyId, id, purchaseOrderUpdate, authorId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'purchase-orders:edit')")
    public ResponseEntity<Void> updatePurchaseOrderStatus(String xContextId, String id, PurchaseOrderStatusUpdate purchaseOrderStatusUpdate) {
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        String authorId = SecurityUtils.getSubjectFromJwt();
        purchaseOrderService.updatePurchaseOrderStatus(companyId, id, purchaseOrderStatusUpdate, authorId);
        return ResponseEntity.noContent().build();
    }
} 
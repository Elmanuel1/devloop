package com.tosspaper.purchaseorder;

import com.tosspaper.generated.model.PurchaseOrder;
import com.tosspaper.generated.model.PurchaseOrderCreate;
import com.tosspaper.generated.model.PurchaseOrderList;
import com.tosspaper.generated.model.PurchaseOrderUpdate;
import com.tosspaper.generated.model.PurchaseOrderStatusUpdate;
import com.tosspaper.purchaseorder.model.PurchaseOrderStatus;

import java.time.OffsetDateTime;

public interface PurchaseOrderService {
    PurchaseOrder getPurchaseOrder(Long companyId, String id);
    PurchaseOrderList getPurchaseOrdersByProjectId(Long companyId, String projectId, String displayId, PurchaseOrderStatus status, OffsetDateTime dueDate, OffsetDateTime createdDateFrom, OffsetDateTime createdDateTo, Integer page, Integer pageSize, String search);
    PurchaseOrder createPurchaseOrder(Long companyId, String projectId, PurchaseOrderCreate purchaseOrderCreate);
    PurchaseOrder updatePurchaseOrder(Long companyId, String id, PurchaseOrderUpdate purchaseOrderUpdate, String authorId);
    PurchaseOrder updatePurchaseOrderStatus(Long companyId, String id, PurchaseOrderStatusUpdate purchaseOrderStatusUpdate, String authorId);
} 
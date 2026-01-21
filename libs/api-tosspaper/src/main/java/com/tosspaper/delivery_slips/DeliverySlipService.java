package com.tosspaper.delivery_slips;

import com.tosspaper.generated.model.DeliverySlip;
import com.tosspaper.generated.model.DeliverySlipList;

public interface DeliverySlipService {
    DeliverySlipList getDeliverySlips(Long companyId, String projectId, String purchaseOrderId, String poNumber, String search, Integer limit, String cursor);
    DeliverySlip getDeliverySlipById(Long companyId, String id);
}


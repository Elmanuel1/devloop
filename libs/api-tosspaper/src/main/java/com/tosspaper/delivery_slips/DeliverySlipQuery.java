package com.tosspaper.delivery_slips;

import com.tosspaper.models.query.BaseQuery;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class DeliverySlipQuery extends BaseQuery {
    String projectId;
    String purchaseOrderId;
    String poNumber;
}


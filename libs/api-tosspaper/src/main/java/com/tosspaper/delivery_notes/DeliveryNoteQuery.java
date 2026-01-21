package com.tosspaper.delivery_notes;

import com.tosspaper.models.query.BaseQuery;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class DeliveryNoteQuery extends BaseQuery {
    String projectId;
    String purchaseOrderId;
    String poNumber;
}

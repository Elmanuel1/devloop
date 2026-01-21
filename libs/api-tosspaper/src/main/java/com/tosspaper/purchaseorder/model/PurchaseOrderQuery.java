package com.tosspaper.purchaseorder.model;

import com.tosspaper.models.query.BaseQuery;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;

@Getter
@SuperBuilder
public class PurchaseOrderQuery extends BaseQuery {
    String projectId;
    String displayId;
    OffsetDateTime dueDate;
} 
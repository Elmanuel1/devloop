package com.tosspaper.invoices;

import com.tosspaper.models.query.BaseQuery;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

@Getter
@SuperBuilder
public class InvoiceQuery extends BaseQuery {
    String projectId;
    String purchaseOrderId;
    String poNumber;
    LocalDate dueDateFrom;
    LocalDate dueDateTo;
}


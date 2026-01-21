package com.tosspaper.invoices;

import com.tosspaper.generated.model.Invoice;
import com.tosspaper.generated.model.InvoiceList;

import java.time.LocalDate;

public interface InvoiceService {
    InvoiceList getInvoices(Long companyId, String projectId, String purchaseOrderId, String poNumber, String search, Integer limit, String cursor, LocalDate dueDateFrom, LocalDate dueDateTo);
    Invoice getInvoiceById(Long companyId, String id);
}


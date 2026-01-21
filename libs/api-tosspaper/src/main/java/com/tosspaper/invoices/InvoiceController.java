package com.tosspaper.invoices;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.InvoicesApi;
import com.tosspaper.generated.model.InvoiceList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class InvoiceController implements InvoicesApi {

    private final InvoiceService invoiceService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<InvoiceList> getInvoices(
            String xContextId,
            String projectId,
            String purchaseOrderId,
            String poNumber,
            Integer limit,
            String cursor,
            String search,
            LocalDate dueDateFrom,
            LocalDate dueDateTo) {
        log.debug("GET /v1/invoices - projectId={}, purchaseOrderId={}, poNumber={}, limit={}, cursor={}, search={}, dueDateFrom={}, dueDateTo={}",
                projectId, purchaseOrderId, poNumber, limit, cursor, search, dueDateFrom, dueDateTo);
        
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        InvoiceList invoices = invoiceService.getInvoices(companyId, projectId, purchaseOrderId, poNumber, search, limit, cursor, dueDateFrom, dueDateTo);
        
        return ResponseEntity.ok(invoices);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<com.tosspaper.generated.model.Invoice> getInvoiceById(
            String xContextId,
            String id) {
        log.debug("GET /v1/invoices/{}", id);
        
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        com.tosspaper.generated.model.Invoice invoice = invoiceService.getInvoiceById(companyId, id);
        
        return ResponseEntity.ok(invoice);
    }
}


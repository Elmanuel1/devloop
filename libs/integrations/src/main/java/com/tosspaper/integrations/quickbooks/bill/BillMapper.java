package com.tosspaper.integrations.quickbooks.bill;

import com.intuit.ipp.data.Bill;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.ReferenceType;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.domain.Invoice;
import com.tosspaper.models.domain.LineItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Component
public class BillMapper {

    public Bill mapToBill(DocumentSyncRequest<Invoice> request, String vendorId) {
        Invoice invoice = request.getDocument();
        Bill bill = new Bill();

        ReferenceType vendorRef = new ReferenceType();
        vendorRef.setValue(vendorId);
        bill.setVendorRef(vendorRef);

        if (invoice.getDocumentDate() != null) {
            bill.setTxnDate(Date.from(invoice.getDocumentDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        if (invoice.getInvoiceDetails() != null && invoice.getInvoiceDetails().getDueDate() != null) {
            LocalDate dueDate = LocalDate.parse(invoice.getInvoiceDetails().getDueDate());
            bill.setDueDate(Date.from(dueDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }

        bill.setDocNumber(invoice.getDocumentNumber());

        if (invoice.getLineItems() != null && !invoice.getLineItems().isEmpty()) {
            bill.setLine(mapLines(invoice.getLineItems()));
        }

        String memo = "";
        if (request.getPoNumber() != null) {
            memo += "PO: " + request.getPoNumber();
        }
        bill.setPrivateNote(memo);

        return bill;
    }

    private List<Line> mapLines(List<LineItem> lineItems) {
        List<Line> lines = new ArrayList<>();

        for (LineItem item : lineItems) {
            Line line = new Line();

            BigDecimal amount = null;
            if (item.getTotal() != null) {
                amount = BigDecimal.valueOf(item.getTotal());
            } else if (item.getUnitPrice() != null && item.getQuantity() != null) {
                amount = BigDecimal.valueOf(item.getUnitPrice()).multiply(BigDecimal.valueOf(item.getQuantity()));
            }
            if (amount != null) {
                line.setAmount(amount);
            }

            line.setDescription(item.getDescription());

            // Use same pattern as PO mapper: check for externalItemId or externalAccountId
            if (item.getExternalItemId() != null) {
                // Item-based expense line (for products/services)
                line.setDetailType(LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL);
                com.intuit.ipp.data.ItemBasedExpenseLineDetail detail = new com.intuit.ipp.data.ItemBasedExpenseLineDetail();

                ReferenceType itemRef = new ReferenceType();
                itemRef.setValue(item.getExternalItemId());
                detail.setItemRef(itemRef);

                if (item.getQuantity() != null) {
                    detail.setQty(BigDecimal.valueOf(item.getQuantity()));
                }
                if (item.getUnitPrice() != null) {
                    detail.setUnitPrice(BigDecimal.valueOf(item.getUnitPrice()));
                }

                line.setItemBasedExpenseLineDetail(detail);
            } else if (item.getExternalAccountId() != null) {
                // Account-based expense line (for general expenses)
                line.setDetailType(LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL);
                com.intuit.ipp.data.AccountBasedExpenseLineDetail detail = new com.intuit.ipp.data.AccountBasedExpenseLineDetail();

                ReferenceType accountRef = new ReferenceType();
                accountRef.setValue(item.getExternalAccountId());
                detail.setAccountRef(accountRef);

                line.setAccountBasedExpenseLineDetail(detail);
            } else {
                throw new IllegalStateException(
                    "Line item missing required external ID: must have either externalItemId or externalAccountId. " +
                    "Line: " + item.getDescription());
            }

            lines.add(line);
        }
        return lines;
    }
}



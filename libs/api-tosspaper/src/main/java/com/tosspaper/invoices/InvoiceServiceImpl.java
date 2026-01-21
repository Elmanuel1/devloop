package com.tosspaper.invoices;

import com.tosspaper.models.utils.CursorUtils;
import com.tosspaper.generated.model.InvoiceList;
import com.tosspaper.generated.model.Invoice;
import com.tosspaper.generated.model.Pagination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {
    
    private final InvoiceRepository invoiceRepository;
    private final InvoiceMapper invoiceMapper;

    @Override
    public InvoiceList getInvoices(Long companyId, String projectId, String purchaseOrderId, String poNumber, String search, Integer limit, String cursor, java.time.LocalDate dueDateFrom, java.time.LocalDate dueDateTo) {
        // Decode cursor if provided
        OffsetDateTime cursorCreatedAt = null;
        String cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                CursorUtils.CursorPair cursorPair = CursorUtils.decodeCursor(cursor);
                cursorCreatedAt = cursorPair.createdAt();
                cursorId = cursorPair.id();
            } catch (IllegalArgumentException e) {
                log.warn("Invalid cursor format: {}", cursor, e);
                throw new IllegalArgumentException("Invalid cursor format", e);
            }
        }
        
        // Build query object
        InvoiceQuery query = InvoiceQuery.builder()
            .projectId(projectId)
            .purchaseOrderId(purchaseOrderId)
            .poNumber(poNumber)
            .search(search)
            .pageSize(limit != null && limit > 0 ? limit : 20)
            .cursorCreatedAt(cursorCreatedAt)
            .cursorId(cursorId)
            .dueDateFrom(dueDateFrom)
            .dueDateTo(dueDateTo)
            .build();
        
        List<com.tosspaper.models.jooq.tables.records.InvoicesRecord> records = invoiceRepository.findInvoices(companyId, query);
        
        // Map records to DTOs
        List<Invoice> invoices = invoiceMapper.toDtoList(records);
        
        // Generate cursor from last record if we got exactly the pageSize (indicates there might be more)
        String nextCursor = null;
        int actualLimit = query.getPageSize() != null && query.getPageSize() > 0 ? query.getPageSize() : 20;
        if (records.size() == actualLimit) {
            com.tosspaper.models.jooq.tables.records.InvoicesRecord lastRecord = records.get(records.size() - 1);
            nextCursor = CursorUtils.encodeCursor(lastRecord.getCreatedAt(), lastRecord.getId());
        }
        
        // Build pagination
        Pagination pagination = new Pagination()
            .cursor(nextCursor);
        
        InvoiceList result = new InvoiceList();
        result.setData(invoices);
        result.setPagination(pagination);
        
        return result;
    }

    @Override
    public Invoice getInvoiceById(Long companyId, String id) {
        // Find by ID (no companyId check in repository)
        com.tosspaper.models.jooq.tables.records.InvoicesRecord record = invoiceRepository.findById(id);
        
        // Check if record exists
        if (record == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Invoice not found");
        }
        
        // Verify companyId matches
        if (!record.getCompanyId().equals(companyId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Invoice not found");
        }
        
        // Map to DTO and return
        return invoiceMapper.toDto(record);
    }
}


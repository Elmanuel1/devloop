package com.tosspaper.delivery_notes;

import com.tosspaper.models.utils.CursorUtils;
import com.tosspaper.generated.model.DeliveryNoteList;
import com.tosspaper.generated.model.DeliveryNote;
import com.tosspaper.generated.model.Pagination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryNoteServiceImpl implements DeliveryNoteService {
    
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final DeliveryNoteMapper deliveryNoteMapper;

    @Override
    public DeliveryNoteList getDeliveryNotes(Long companyId, String projectId, String purchaseOrderId, String poNumber, String search, Integer limit, String cursor) {
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
        DeliveryNoteQuery query = DeliveryNoteQuery.builder()
            .projectId(projectId)
            .purchaseOrderId(purchaseOrderId)
            .poNumber(poNumber)
            .search(search)
            .pageSize(limit != null && limit > 0 ? limit : 20)
            .cursorCreatedAt(cursorCreatedAt)
            .cursorId(cursorId)
            .build();
        
        List<com.tosspaper.models.jooq.tables.records.DeliveryNotesRecord> records = deliveryNoteRepository.findDeliveryNotes(companyId, query);
        
        // Map records to DTOs
        List<DeliveryNote> deliveryNotes = deliveryNoteMapper.toDtoList(records);
        
        // Generate cursor from last record if we got exactly the pageSize (indicates there might be more)
        String nextCursor = null;
        int actualLimit = query.getPageSize() != null && query.getPageSize() > 0 ? query.getPageSize() : 20;
        if (records.size() == actualLimit && !records.isEmpty()) {
            com.tosspaper.models.jooq.tables.records.DeliveryNotesRecord lastRecord = records.get(records.size() - 1);
            nextCursor = CursorUtils.encodeCursor(lastRecord.getCreatedAt(), lastRecord.getId());
        }
        
        // Build pagination
        Pagination pagination = new Pagination()
            .cursor(nextCursor);
        
        DeliveryNoteList result = new DeliveryNoteList();
        result.setData(deliveryNotes);
        result.setPagination(pagination);
        
        return result;
    }

    @Override
    public DeliveryNote getDeliveryNoteById(Long companyId, String id) {
        // Find by ID (no companyId check in repository)
        com.tosspaper.models.jooq.tables.records.DeliveryNotesRecord record = deliveryNoteRepository.findById(id);
        
        // Check if record exists
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery note not found");
        }
        
        // Verify companyId matches
        if (!record.getCompanyId().equals(companyId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Delivery note not found");
        }
        
        // Map to DTO and return
        return deliveryNoteMapper.toDto(record);
    }
}


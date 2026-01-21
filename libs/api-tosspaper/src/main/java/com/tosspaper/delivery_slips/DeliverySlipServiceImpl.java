package com.tosspaper.delivery_slips;

import com.tosspaper.models.utils.CursorUtils;
import com.tosspaper.generated.model.DeliverySlipList;
import com.tosspaper.generated.model.DeliverySlip;
import com.tosspaper.generated.model.Pagination;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliverySlipServiceImpl implements DeliverySlipService {
    
    private final DeliverySlipRepository deliverySlipRepository;
    private final DeliverySlipMapper deliverySlipMapper;

    @Override
    public DeliverySlipList getDeliverySlips(Long companyId, String projectId, String purchaseOrderId, String poNumber, String search, Integer limit, String cursor) {
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
        DeliverySlipQuery query = DeliverySlipQuery.builder()
            .projectId(projectId)
            .purchaseOrderId(purchaseOrderId)
            .poNumber(poNumber)
            .search(search)
            .pageSize(limit != null && limit > 0 ? limit : 20)
            .cursorCreatedAt(cursorCreatedAt)
            .cursorId(cursorId)
            .build();
        
        List<com.tosspaper.models.jooq.tables.records.DeliverySlipsRecord> records = deliverySlipRepository.findDeliverySlips(companyId, query);
        
        // Map records to DTOs
        List<DeliverySlip> deliverySlips = deliverySlipMapper.toDtoList(records);
        
        // Generate cursor from last record if we got exactly the pageSize (indicates there might be more)
        String nextCursor = null;
        int actualLimit = query.getPageSize() != null && query.getPageSize() > 0 ? query.getPageSize() : 20;
        if (records.size() == actualLimit && !records.isEmpty()) {
            com.tosspaper.models.jooq.tables.records.DeliverySlipsRecord lastRecord = records.get(records.size() - 1);
            nextCursor = CursorUtils.encodeCursor(lastRecord.getCreatedAt(), lastRecord.getId());
        }
        
        // Build pagination
        Pagination pagination = new Pagination()
            .cursor(nextCursor);
        
        DeliverySlipList result = new DeliverySlipList();
        result.setData(deliverySlips);
        result.setPagination(pagination);
        
        return result;
    }

    @Override
    public DeliverySlip getDeliverySlipById(Long companyId, String id) {
        // Find by ID (no companyId check in repository)
        com.tosspaper.models.jooq.tables.records.DeliverySlipsRecord record = deliverySlipRepository.findById(id);
        
        // Check if record exists
        if (record == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Delivery slip not found");
        }
        
        // Verify companyId matches
        if (!record.getCompanyId().equals(companyId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Delivery slip not found");
        }
        
        // Map to DTO and return
        return deliverySlipMapper.toDto(record);
    }
}


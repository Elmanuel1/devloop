package com.tosspaper.precon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.common.DuplicateException;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.generated.model.Tender;
import com.tosspaper.generated.model.TenderCreateRequest;
import com.tosspaper.generated.model.TenderListResponse;
import com.tosspaper.generated.model.TenderPagination;
import com.tosspaper.generated.model.TenderSortDirection;
import com.tosspaper.generated.model.TenderSortField;
import com.tosspaper.generated.model.TenderStatus;
import com.tosspaper.generated.model.TenderUpdateRequest;
import com.tosspaper.models.exception.CannotDeleteException;
import com.tosspaper.models.exception.IfMatchRequiredException;
import com.tosspaper.models.exception.InvalidStatusTransitionException;
import com.tosspaper.models.exception.StaleVersionException;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenderServiceImpl implements TenderService {

    private final TenderRepository tenderRepository;
    private final TenderMapper tenderMapper;
    private final ObjectMapper objectMapper;

    // Valid status transitions: from -> Set<to>
    private static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
            "draft", Set.of("pending"),
            "pending", Set.of("submitted", "cancelled"),
            "submitted", Set.of("won", "lost"),
            "cancelled", Set.of("archived")
    );

    // Final statuses with no further transitions
    private static final Set<String> FINAL_STATUSES = Set.of("won", "lost");

    @Override
    public Tender createTender(Long companyId, TenderCreateRequest request) {
        String companyIdStr = companyId.toString();

        // Validate name
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("api.validation.nameRequired", "Tender name is required");
        }

        // Validate closing_date is not in the past
        if (request.getClosingDate() != null && request.getClosingDate().isBefore(OffsetDateTime.now())) {
            throw new BadRequestException("api.validation.dateInPast", "closing_date must not be in the past");
        }

        // Build fields map
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", request.getName());
        fields.put("created_by", getCurrentUserId());

        if (request.getPlatform() != null) {
            fields.put("platform", request.getPlatform());
        }
        if (request.getCurrency() != null) {
            fields.put("currency", request.getCurrency());
        }
        if (request.getClosingDate() != null) {
            fields.put("closing_date", request.getClosingDate());
        }
        if (request.getDeliveryMethod() != null) {
            fields.put("delivery_method", request.getDeliveryMethod());
        }

        try {
            TendersRecord record = tenderRepository.insert(companyIdStr, fields);
            return tenderMapper.toDto(record);
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("api.tender.duplicateName",
                    "A tender with name '" + request.getName() + "' already exists");
        }
    }

    @Override
    public TenderListResponse listTenders(Long companyId, Integer limit, String cursor, String search,
                                          TenderSortField sort, TenderSortDirection direction, TenderStatus status) {
        String companyIdStr = companyId.toString();

        // Validate limit
        int effectiveLimit = limit != null ? limit : 20;
        if (effectiveLimit < 1 || effectiveLimit > 100) {
            throw new BadRequestException("api.validation.invalidLimit", "Limit must be between 1 and 100");
        }

        // Decode cursor
        OffsetDateTime cursorCreatedAt = null;
        String cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                CursorUtils.CursorPair cursorPair = CursorUtils.decodeCursor(cursor);
                cursorCreatedAt = cursorPair.createdAt();
                cursorId = cursorPair.id();
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("api.validation.invalidCursor", "Invalid cursor format");
            }
        }

        TenderQuery query = TenderQuery.builder()
                .search(search)
                .status(status != null ? status.getValue() : null)
                .sortBy(sort != null ? sort.getValue() : "created_at")
                .sortDirection(direction != null ? direction.getValue() : "desc")
                .limit(effectiveLimit)
                .cursorCreatedAt(cursorCreatedAt)
                .cursorId(cursorId)
                .build();

        List<TendersRecord> records = tenderRepository.findByCompanyId(companyIdStr, query);

        // Determine if there are more results
        boolean hasMore = records.size() > query.getLimit();
        if (hasMore) {
            records = records.subList(0, query.getLimit());
        }

        List<Tender> tenders = tenderMapper.toDtoList(records);

        // Build pagination
        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            TendersRecord lastRecord = records.get(records.size() - 1);
            nextCursor = CursorUtils.encodeCursor(lastRecord.getCreatedAt(), lastRecord.getId());
        }

        TenderPagination pagination = new TenderPagination();
        pagination.setCursor(nextCursor);
        pagination.setHasMore(hasMore);

        TenderListResponse response = new TenderListResponse();
        response.setData(tenders);
        response.setPagination(pagination);

        return response;
    }

    @Override
    public Tender getTender(Long companyId, String tenderId) {
        String companyIdStr = companyId.toString();

        TendersRecord record = tenderRepository.findById(tenderId)
                .orElseThrow(() -> new NotFoundException("api.tender.notFound", "Tender not found"));

        // Verify company ownership
        if (!record.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException("api.tender.notFound", "Tender not found");
        }

        return tenderMapper.toDto(record);
    }

    @Override
    public Tender updateTender(Long companyId, String tenderId, TenderUpdateRequest request, String ifMatch) {
        String companyIdStr = companyId.toString();

        // If-Match is required
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IfMatchRequiredException("api.validation.ifMatchRequired",
                    "If-Match header is required for updates. Use the ETag from GET /v1/tenders/{id}.");
        }

        int expectedVersion = HeaderUtils.parseETagVersion(ifMatch);

        // Load existing tender
        TendersRecord existing = tenderRepository.findById(tenderId)
                .orElseThrow(() -> new NotFoundException("api.tender.notFound", "Tender not found"));

        // Verify company ownership
        if (!existing.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException("api.tender.notFound", "Tender not found");
        }

        // Validate status transition if status is being changed
        if (request.getStatus() != null) {
            String currentStatus = existing.getStatus();
            String newStatus = request.getStatus().getValue();
            validateStatusTransition(currentStatus, newStatus);
        }

        // Validate closing_date
        if (request.getClosingDate() != null && request.getClosingDate().isBefore(OffsetDateTime.now())) {
            throw new BadRequestException("api.validation.dateInPast", "closing_date must not be in the past");
        }

        // Build update fields
        Map<String, Object> fields = buildUpdateFields(request);

        // Perform atomic update with version guard
        try {
            int rowsUpdated = tenderRepository.update(tenderId, fields, expectedVersion);
            if (rowsUpdated == 0) {
                throw new StaleVersionException("api.tender.staleVersion",
                        "Tender has been modified by another request. Please refresh and try again.");
            }
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("api.tender.duplicateName",
                    "A tender with name '" + request.getName() + "' already exists");
        }

        // Reload and return updated tender
        TendersRecord updated = tenderRepository.findById(tenderId)
                .orElseThrow(() -> new NotFoundException("api.tender.notFound", "Tender not found"));

        return tenderMapper.toDto(updated);
    }

    @Override
    public void deleteTender(Long companyId, String tenderId) {
        String companyIdStr = companyId.toString();

        TendersRecord record = tenderRepository.findById(tenderId)
                .orElseThrow(() -> new NotFoundException("api.tender.notFound", "Tender not found"));

        // Verify company ownership
        if (!record.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException("api.tender.notFound", "Tender not found");
        }

        // Only draft and pending can be deleted
        String status = record.getStatus();
        if (!"draft".equals(status) && !"pending".equals(status)) {
            throw new CannotDeleteException("api.tender.cannotDelete",
                    "Only tenders in draft or pending status can be deleted. Current status: " + status);
        }

        tenderRepository.softDelete(tenderId);
    }

    private void validateStatusTransition(String currentStatus, String newStatus) {
        if (currentStatus.equals(newStatus)) {
            return; // No-op transition
        }

        if (FINAL_STATUSES.contains(currentStatus)) {
            throw new InvalidStatusTransitionException("api.tender.invalidStatusTransition",
                    "Cannot transition from '" + currentStatus + "' — it is a final status. No further transitions allowed.");
        }

        Set<String> allowed = VALID_TRANSITIONS.get(currentStatus);
        if (allowed == null || !allowed.contains(newStatus)) {
            String allowedStr = allowed != null ? String.join(", ", allowed) : "none";
            throw new InvalidStatusTransitionException("api.tender.invalidStatusTransition",
                    "Invalid status transition from '" + currentStatus + "' to '" + newStatus + "'. Allowed transitions: " + allowedStr);
        }
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            return auth.getName();
        }
        return "system";
    }

    private Map<String, Object> buildUpdateFields(TenderUpdateRequest request) {
        Map<String, Object> fields = new HashMap<>();

        if (request.getName() != null) {
            fields.put("name", request.getName());
        }
        if (request.getPlatform() != null) {
            fields.put("platform", request.getPlatform());
        }
        if (request.getStatus() != null) {
            fields.put("status", request.getStatus().getValue());
        }
        if (request.getCurrency() != null) {
            fields.put("currency", request.getCurrency());
        }
        if (request.getReferenceNumber() != null) {
            fields.put("reference_number", request.getReferenceNumber());
        }
        if (request.getScopeOfWork() != null) {
            fields.put("scope_of_work", request.getScopeOfWork());
        }
        if (request.getDeliveryMethod() != null) {
            fields.put("delivery_method", request.getDeliveryMethod());
        }
        if (request.getClosingDate() != null) {
            fields.put("closing_date", request.getClosingDate());
        }
        if (request.getInquiryDeadline() != null) {
            fields.put("inquiry_deadline", request.getInquiryDeadline());
        }
        if (request.getSubmissionMethod() != null) {
            fields.put("submission_method", request.getSubmissionMethod());
        }
        if (request.getSubmissionUrl() != null) {
            fields.put("submission_url", request.getSubmissionUrl());
        }
        if (request.getLiquidatedDamages() != null) {
            fields.put("liquidated_damages", request.getLiquidatedDamages());
        }

        // JSONB fields - serialize to JSON strings
        try {
            if (request.getBonds() != null) {
                fields.put("bonds", objectMapper.writeValueAsString(request.getBonds()));
            }
            if (request.getConditions() != null) {
                fields.put("conditions", objectMapper.writeValueAsString(request.getConditions()));
            }
            if (request.getParties() != null) {
                fields.put("parties", objectMapper.writeValueAsString(request.getParties()));
            }
            if (request.getLocation() != null) {
                fields.put("location", objectMapper.writeValueAsString(request.getLocation()));
            }
            if (request.getMetadata() != null) {
                fields.put("metadata", objectMapper.writeValueAsString(request.getMetadata()));
            }
        } catch (Exception e) {
            throw new BadRequestException("api.validation.error", "Failed to serialize request fields: " + e.getMessage());
        }

        return fields;
    }
}

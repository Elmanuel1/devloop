package com.tosspaper.precon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.generated.model.Tender;
import com.tosspaper.generated.model.TenderCreateRequest;
import com.tosspaper.generated.model.TenderListResponse;
import com.tosspaper.generated.model.TenderPagination;
import com.tosspaper.generated.model.TenderUpdateRequest;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    public Tender createTender(Long companyId, TenderCreateRequest request, String createdBy) {
        String companyIdStr = companyId.toString();

        // Validate name
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("api.validation.nameRequired", "Tender name is required");
        }

        // Check duplicate name (case-insensitive)
        if (tenderRepository.existsByCompanyIdAndName(companyIdStr, request.getName().toLowerCase())) {
            throw new DuplicateNameException("api.tender.duplicateName",
                    "A tender with name '" + request.getName() + "' already exists");
        }

        // Validate closing_date is not in the past
        if (request.getClosingDate() != null && request.getClosingDate().isBefore(OffsetDateTime.now())) {
            throw new BadRequestException("api.validation.dateInPast", "closing_date must not be in the past");
        }

        // Build fields map
        Map<String, Object> fields = new HashMap<>();
        fields.put("name", request.getName());
        fields.put("created_by", createdBy);

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

        TendersRecord record = tenderRepository.insert(companyIdStr, fields);
        return tenderMapper.toDto(record);
    }

    @Override
    public TenderListResponse listTenders(Long companyId, TenderQuery query) {
        String companyIdStr = companyId.toString();

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
    public Tender updateTender(Long companyId, String tenderId, TenderUpdateRequest request, int expectedVersion) {
        String companyIdStr = companyId.toString();

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

        // Check name uniqueness if name is being changed
        if (request.getName() != null && !request.getName().isBlank()) {
            if (tenderRepository.existsByCompanyIdAndNameExcludingSelf(
                    companyIdStr, request.getName().toLowerCase(), tenderId)) {
                throw new DuplicateNameException("api.tender.duplicateName",
                        "A tender with name '" + request.getName() + "' already exists");
            }
        }

        // Validate closing_date
        if (request.getClosingDate() != null && request.getClosingDate().isBefore(OffsetDateTime.now())) {
            throw new BadRequestException("api.validation.dateInPast", "closing_date must not be in the past");
        }

        // Build update fields
        Map<String, Object> fields = buildUpdateFields(request);

        // Perform atomic update with version guard
        int rowsUpdated = tenderRepository.update(tenderId, fields, expectedVersion);
        if (rowsUpdated == 0) {
            throw new StaleVersionException("api.tender.staleVersion",
                    "Tender has been modified by another request. Please refresh and try again.");
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

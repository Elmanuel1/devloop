package com.tosspaper.precon;

import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.common.DuplicateException;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.precon.generated.model.Pagination;
import com.tosspaper.precon.generated.model.SortDirection;
import com.tosspaper.precon.generated.model.SortField;
import com.tosspaper.precon.generated.model.Tender;
import com.tosspaper.precon.generated.model.TenderCreateRequest;
import com.tosspaper.precon.generated.model.TenderListResponse;
import com.tosspaper.precon.generated.model.TenderStatus;
import com.tosspaper.precon.generated.model.TenderUpdateRequest;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenderServiceImpl implements TenderService {

    private final TenderRepository tenderRepository;
    private final TenderMapper tenderMapper;

    // Valid status transitions: from -> Set<to>
    private static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
            "pending", Set.of("submitted", "cancelled"),
            "submitted", Set.of("won", "lost")
    );

    // Final statuses with no further transitions
    private static final Set<String> FINAL_STATUSES = Set.of("won", "lost", "cancelled");

    @Override
    public TenderResult createTender(Long companyId, TenderCreateRequest request) {
        TendersRecord record = tenderMapper.toRecord(request, companyId.toString(), getCurrentUserId());

        try {
            TendersRecord inserted = tenderRepository.insert(record);
            return new TenderResult(tenderMapper.toDto(inserted), inserted.getVersion());
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("api.tender.duplicateName",
                    "A tender with name '" + request.getName() + "' already exists");
        }
    }

    @Override
    public TenderListResponse listTenders(Long companyId, Integer limit, String cursor, String search,
                                          SortField sort, SortDirection direction, TenderStatus status) {
        String companyIdStr = companyId.toString();

        // Clamp limit to valid range
        int effectiveLimit = limit != null ? limit : 20;
        if (effectiveLimit < 1 || effectiveLimit > 100) {
            effectiveLimit = 20;
        }

        // Decode cursor (returns null if absent, throws BadRequestException if malformed)
        CursorUtils.CursorPair cursorPair = CursorUtils.parseCursor(cursor);

        TenderQuery query = TenderQuery.builder()
                .search(search)
                .status(status != null ? status.getValue() : null)
                .sortBy(sort != null ? sort.getValue() : "created_at")
                .sortDirection(direction != null ? direction.getValue() : "desc")
                .limit(effectiveLimit)
                .cursorCreatedAt(cursorPair != null ? cursorPair.createdAt() : null)
                .cursorId(cursorPair != null ? cursorPair.id() : null)
                .build();

        List<TendersRecord> records = tenderRepository.findByCompanyId(companyIdStr, query);

        // Determine if there are more results
        boolean hasMore = records.size() > query.getLimit();
        if (hasMore) {
            records = records.subList(0, query.getLimit());
        }

        List<Tender> tenders = tenderMapper.toDtoList(records);

        // Build pagination — cursor is null when no more results
        String nextCursor = null;
        if (hasMore && !records.isEmpty()) {
            TendersRecord lastRecord = records.getLast();
            nextCursor = CursorUtils.encodeCursor(lastRecord.getCreatedAt(), lastRecord.getId());
        }

        Pagination pagination = new Pagination();
        pagination.setCursor(nextCursor);

        TenderListResponse response = new TenderListResponse();
        response.setData(tenders);
        response.setPagination(pagination);

        return response;
    }

    @Override
    public TenderResult getTender(Long companyId, String tenderId) {
        String companyIdStr = companyId.toString();

        TendersRecord record = tenderRepository.findById(tenderId);

        // Verify company ownership
        if (!record.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException("api.tender.notFound", "Tender not found");
        }

        return new TenderResult(tenderMapper.toDto(record), record.getVersion());
    }

    @Override
    public TenderResult updateTender(Long companyId, String tenderId, TenderUpdateRequest request, String ifMatch) {
        String companyIdStr = companyId.toString();

        // If-Match is required
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IfMatchRequiredException("api.validation.ifMatchRequired",
                    "If-Match header is required for updates. Use the ETag from GET /v1/tenders/{id}.");
        }

        int expectedVersion = HeaderUtils.parseETagVersion(ifMatch);

        // Load existing tender
        TendersRecord existing = tenderRepository.findById(tenderId);

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

        // Apply update fields via mapper (only non-null fields are set)
        tenderMapper.updateRecord(request, existing);

        // Perform atomic update with version guard
        try {
            int rowsUpdated = tenderRepository.update(tenderId, existing, expectedVersion);
            if (rowsUpdated == 0) {
                throw new StaleVersionException("api.tender.staleVersion",
                        "Tender has been modified by another request. Please refresh and try again.");
            }
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("api.tender.duplicateName",
                    "A tender with name '" + request.getName() + "' already exists");
        }

        // Reload and return updated tender
        TendersRecord updated = tenderRepository.findById(tenderId);

        return new TenderResult(tenderMapper.toDto(updated), updated.getVersion());
    }

    @Override
    public void deleteTender(Long companyId, String tenderId) {
        String companyIdStr = companyId.toString();

        TendersRecord record = tenderRepository.findById(tenderId);

        // Verify company ownership
        if (!record.getCompanyId().equals(companyIdStr)) {
            throw new NotFoundException("api.tender.notFound", "Tender not found");
        }

        // Only pending can be deleted
        String status = record.getStatus();
        if (!"pending".equals(status)) {
            throw new CannotDeleteException("api.tender.cannotDelete",
                    "Only tenders in pending status can be deleted. Current status: " + status);
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

}

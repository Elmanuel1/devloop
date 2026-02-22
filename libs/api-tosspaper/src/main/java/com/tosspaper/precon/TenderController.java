package com.tosspaper.precon;

import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.TendersApi;
import com.tosspaper.generated.model.Tender;
import com.tosspaper.generated.model.TenderCreateRequest;
import com.tosspaper.generated.model.TenderListResponse;
import com.tosspaper.generated.model.TenderSortDirection;
import com.tosspaper.generated.model.TenderSortField;
import com.tosspaper.generated.model.TenderStatus;
import com.tosspaper.generated.model.TenderUpdateRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class TenderController implements TendersApi {

    private final TenderService tenderService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:create')")
    public ResponseEntity<Tender> createTender(String xContextId, TenderCreateRequest tenderCreateRequest) {
        log.debug("POST /v1/tenders - xContextId={}", xContextId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        String createdBy = getCurrentUserId();

        Tender tender = tenderService.createTender(companyId, tenderCreateRequest, createdBy);

        return ResponseEntity
                .created(URI.create("/v1/tenders/" + tender.getId()))
                .eTag(formatETag(tender.getVersion()))
                .body(tender);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<TenderListResponse> listTenders(
            String xContextId,
            Integer limit,
            String cursor,
            String search,
            TenderSortField sort,
            TenderSortDirection direction,
            TenderStatus status) {
        log.debug("GET /v1/tenders - xContextId={}, limit={}, cursor={}, search={}, sort={}, direction={}, status={}",
                xContextId, limit, cursor, search, sort, direction, status);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

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

        TenderListResponse response = tenderService.listTenders(companyId, query);
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<Tender> getTender(String xContextId, UUID tenderId) {
        log.debug("GET /v1/tenders/{} - xContextId={}", tenderId, xContextId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        Tender tender = tenderService.getTender(companyId, tenderId.toString());

        return ResponseEntity.ok()
                .eTag(formatETag(tender.getVersion()))
                .body(tender);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<Tender> updateTender(
            String xContextId,
            UUID tenderId,
            TenderUpdateRequest tenderUpdateRequest,
            String ifMatch) {
        log.debug("PATCH /v1/tenders/{} - xContextId={}, ifMatch={}", tenderId, xContextId, ifMatch);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        // If-Match is required
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IfMatchRequiredException("api.validation.ifMatchRequired",
                    "If-Match header is required for updates. Use the ETag from GET /v1/tenders/{id}.");
        }

        // Parse version from ETag: "v0" -> 0
        int expectedVersion = parseETagVersion(ifMatch);

        Tender tender = tenderService.updateTender(companyId, tenderId.toString(), tenderUpdateRequest, expectedVersion);

        return ResponseEntity.ok()
                .eTag(formatETag(tender.getVersion()))
                .body(tender);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:delete')")
    public ResponseEntity<Void> deleteTender(String xContextId, UUID tenderId) {
        log.debug("DELETE /v1/tenders/{} - xContextId={}", tenderId, xContextId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        tenderService.deleteTender(companyId, tenderId.toString());

        return ResponseEntity.noContent().build();
    }

    private String formatETag(Integer version) {
        return "\"v" + (version != null ? version : 0) + "\"";
    }

    private int parseETagVersion(String etag) {
        try {
            // Remove quotes and "v" prefix: "v0" -> 0, or "\"v0\"" -> 0
            String cleaned = etag.replace("\"", "").replace("W/", "");
            if (cleaned.startsWith("v")) {
                return Integer.parseInt(cleaned.substring(1));
            }
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            throw new BadRequestException("api.validation.invalidETag",
                    "Invalid ETag format. Expected format: \"v{version}\"");
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

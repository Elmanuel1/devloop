package com.tosspaper.precon;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.precon.generated.api.TendersApi;
import com.tosspaper.precon.generated.model.SortDirection;
import com.tosspaper.precon.generated.model.SortField;
import com.tosspaper.precon.generated.model.Tender;
import com.tosspaper.precon.generated.model.TenderCreateRequest;
import com.tosspaper.precon.generated.model.TenderCreateResponse;
import com.tosspaper.precon.generated.model.TenderListResponse;
import com.tosspaper.precon.generated.model.TenderStatus;
import com.tosspaper.precon.generated.model.TenderUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class TenderController implements TendersApi {

    private final TenderService tenderService;
    private final HttpServletRequest request;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:create')")
    public ResponseEntity<TenderCreateResponse> createTender(String xContextId, TenderCreateRequest tenderCreateRequest, UUID idempotencyKey) {
        log.debug("POST /v1/tenders - xContextId={}", xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        TenderResult result = tenderService.createTender(companyId, tenderCreateRequest);
        TenderCreateResponse response = new TenderCreateResponse();
        response.setId(result.tender().getId());
        return ResponseEntity
                .created(URI.create("/v1/tenders/" + result.tender().getId()))
                .eTag(HeaderUtils.formatETag(result.version()))
                .body(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<TenderListResponse> listTenders(
            String xContextId,
            Integer limit,
            String cursor,
            String search,
            SortField sort,
            SortDirection direction,
            TenderStatus status) {
        log.debug("GET /v1/tenders - xContextId={}", xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        TenderListResponse response = tenderService.listTenders(companyId, limit, cursor, search, sort, direction, status);
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:view')")
    public ResponseEntity<Tender> getTender(String xContextId, UUID tenderId) {
        log.debug("GET /v1/tenders/{} - xContextId={}", tenderId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        TenderResult result = tenderService.getTender(companyId, tenderId.toString());
        String currentETag = HeaderUtils.formatETag(result.version());

        if (HeaderUtils.isNotModified(request, currentETag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(CacheControl.noCache())
                    .eTag(currentETag)
                    .build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .eTag(currentETag)
                .body(result.tender());
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<Tender> updateTender(
            String xContextId,
            String ifMatch,
            UUID tenderId,
            TenderUpdateRequest tenderUpdateRequest) {
        log.debug("PATCH /v1/tenders/{} - xContextId={}", tenderId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        TenderResult result = tenderService.updateTender(companyId, tenderId.toString(), tenderUpdateRequest, ifMatch);
        return ResponseEntity.ok()
                .eTag(HeaderUtils.formatETag(result.version()))
                .body(result.tender());
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:delete')")
    public ResponseEntity<Void> deleteTender(String xContextId, UUID tenderId) {
        log.debug("DELETE /v1/tenders/{} - xContextId={}", tenderId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        tenderService.deleteTender(companyId, tenderId.toString());
        return ResponseEntity.noContent().build();
    }

}

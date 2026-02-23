package com.tosspaper.precon;

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

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:create')")
    public ResponseEntity<Tender> createTender(String xContextId, TenderCreateRequest tenderCreateRequest) {
        log.debug("POST /v1/tenders - xContextId={}", xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        Tender tender = tenderService.createTender(companyId, tenderCreateRequest);
        return ResponseEntity
                .created(URI.create("/v1/tenders/" + tender.getId()))
                .eTag(HeaderUtils.formatETag(tender.getVersion()))
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
        Tender tender = tenderService.getTender(companyId, tenderId.toString());
        return ResponseEntity.ok()
                .eTag(HeaderUtils.formatETag(tender.getVersion()))
                .body(tender);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'tenders:edit')")
    public ResponseEntity<Tender> updateTender(
            String xContextId,
            UUID tenderId,
            TenderUpdateRequest tenderUpdateRequest,
            String ifMatch) {
        log.debug("PATCH /v1/tenders/{} - xContextId={}", tenderId, xContextId);
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        Tender tender = tenderService.updateTender(companyId, tenderId.toString(), tenderUpdateRequest, ifMatch);
        return ResponseEntity.ok()
                .eTag(HeaderUtils.formatETag(tender.getVersion()))
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

}

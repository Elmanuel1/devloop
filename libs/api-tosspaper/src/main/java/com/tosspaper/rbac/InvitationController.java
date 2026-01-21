package com.tosspaper.rbac;

import com.tosspaper.generated.api.CompanyInvitationsApi;
import com.tosspaper.generated.model.CompanyInvitation;
import com.tosspaper.generated.model.PaginatedCompanyInvitationList;
import com.tosspaper.generated.model.SendInvitationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.tosspaper.common.security.SecurityUtils.getSubjectFromJwt;

/**
 * Controller for company invitation operations.
 * Handles sending, listing, and cancelling invitations for a company.
 *
 * All endpoints require company context and proper permissions.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InvitationController implements CompanyInvitationsApi {

    private final CompanyInvitationService invitationService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'invitations:view')")
    public ResponseEntity<PaginatedCompanyInvitationList> listCompanyInvitations(
            @RequestHeader("X-Context-Id") Long xContextId,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "10") Integer limit) {

        log.info("Listing invitations for company {} with filters: status={}, cursor={}, limit={}",
                xContextId, status, cursor, limit);

        PaginatedCompanyInvitationList result = invitationService.listCompanyInvitations(
                xContextId, email, status, cursor, limit);

        return ResponseEntity.ok(result);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'invitations:send')")
    public ResponseEntity<CompanyInvitation> sendInvitation(
            @RequestHeader("X-Context-Id") Long xContextId,
            @RequestBody SendInvitationRequest sendInvitationRequest) {

        String inviterEmail = getSubjectFromJwt();
        log.info("Sending invitation for company {} with role {}",
                xContextId, sendInvitationRequest.getRoleId());

        CompanyInvitation invitation = invitationService.sendInvitation(
                xContextId,
                sendInvitationRequest.getEmail(),
                sendInvitationRequest.getRoleId().getValue());

        return ResponseEntity.status(201).body(invitation);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'invitations:cancel')")
    public ResponseEntity<Void> cancelInvitation(
            @RequestHeader("X-Context-Id") Long xContextId,
            @PathVariable("email") String email) {

        log.info("Cancelling invitation from company {}", xContextId);

        invitationService.cancelInvitation(xContextId, email);

        return ResponseEntity.noContent().build();
    }

    @PutMapping("/v1/invitations/{code}")
    public ResponseEntity<CompanyInvitation> acceptInvitation(@PathVariable("code") String code) {
        log.info("Accepting invitation with code: {}", code);

        CompanyInvitation acceptedInvitation = invitationService.acceptInvitationByCode(code);

        log.info("Invitation accepted successfully");

        return ResponseEntity.ok(acceptedInvitation);
    }
}

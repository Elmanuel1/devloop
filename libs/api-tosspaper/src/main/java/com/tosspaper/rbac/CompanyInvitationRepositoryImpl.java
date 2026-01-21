package com.tosspaper.rbac;

import com.tosspaper.models.domain.CompanyInvitation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.COMPANY_INVITATIONS;

/**
 * JOOQ implementation of CompanyInvitationRepository.
 * Manages email invitations with cursor pagination.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CompanyInvitationRepositoryImpl implements CompanyInvitationRepository {

    private final DSLContext dsl;

    @Override
    public List<CompanyInvitation> findByCompanyId(Long companyId, CompanyInvitationQuery query) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(COMPANY_INVITATIONS.COMPANY_ID.eq(companyId));

        // Apply filters
        if (query.getEmail() != null && !query.getEmail().isBlank()) {
            conditions.add(COMPANY_INVITATIONS.EMAIL.likeIgnoreCase("%" + query.getEmail() + "%"));
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            conditions.add(COMPANY_INVITATIONS.STATUS.eq(query.getStatus()));
        }

        // Apply cursor pagination (cursor is email - unique per company, stored in cursorId)
        if (query.getCursorId() != null && !query.getCursorId().isBlank()) {
            conditions.add(COMPANY_INVITATIONS.EMAIL.gt(query.getCursorId()));
        }

        SelectConditionStep<?> selectQuery = dsl.selectFrom(COMPANY_INVITATIONS)
                .where(conditions);

        return selectQuery.orderBy(COMPANY_INVITATIONS.EMAIL.asc())
                .limit(query.getPageSize())
                .fetch(this::mapRecordToDomain);
    }

    @Override
    public Optional<CompanyInvitation> findByCompanyIdAndEmail(Long companyId, String email) {
        return findByCompanyIdAndEmail(dsl, companyId, email);
    }

    @Override
    public Optional<CompanyInvitation> findByCompanyIdAndEmail(DSLContext dsl, Long companyId, String email) {
        return dsl.selectFrom(COMPANY_INVITATIONS)
                .where(COMPANY_INVITATIONS.COMPANY_ID.eq(companyId))
                .and(COMPANY_INVITATIONS.EMAIL.eq(email))
                .fetchOptional(this::mapRecordToDomain);
    }

    @Override
    public CompanyInvitation save(CompanyInvitation invitation) {
        return save(dsl, invitation);
    }

    @Override
    public CompanyInvitation save(DSLContext dsl, CompanyInvitation invitation) {
        var record = dsl.insertInto(COMPANY_INVITATIONS)
                .set(COMPANY_INVITATIONS.COMPANY_ID, invitation.companyId())
                .set(COMPANY_INVITATIONS.EMAIL, invitation.email())
                .set(COMPANY_INVITATIONS.ROLE_ID, invitation.roleId())
                .set(COMPANY_INVITATIONS.ROLE_NAME, invitation.roleName())
                .set(COMPANY_INVITATIONS.STATUS, invitation.status().getValue())
                .set(COMPANY_INVITATIONS.CREATED_AT, invitation.createdAt() != null ? invitation.createdAt() : OffsetDateTime.now())
                .set(COMPANY_INVITATIONS.UPDATED_AT, OffsetDateTime.now())
                .set(COMPANY_INVITATIONS.EXPIRES_AT, invitation.expiresAt())
                .onConflict(COMPANY_INVITATIONS.COMPANY_ID, COMPANY_INVITATIONS.EMAIL)
                .doUpdate()
                .set(COMPANY_INVITATIONS.ROLE_ID, invitation.roleId())
                .set(COMPANY_INVITATIONS.ROLE_NAME, invitation.roleName())
                .set(COMPANY_INVITATIONS.STATUS, invitation.status().getValue())
                .set(COMPANY_INVITATIONS.UPDATED_AT, OffsetDateTime.now())
                .set(COMPANY_INVITATIONS.EXPIRES_AT, invitation.expiresAt())
                .returning()
                .fetchSingle();

        return mapRecordToDomain(record);
    }

    @Override
    public boolean delete(Long companyId, String email) {
        return dsl.deleteFrom(COMPANY_INVITATIONS)
                .where(COMPANY_INVITATIONS.COMPANY_ID.eq(companyId))
                .and(COMPANY_INVITATIONS.EMAIL.eq(email))
                .execute() > 0;
    }

    /**
     * Map JOOQ record to domain model
     */
    private CompanyInvitation mapRecordToDomain(org.jooq.Record record) {
        return CompanyInvitation.builder()
                .companyId(record.get(COMPANY_INVITATIONS.COMPANY_ID))
                .email(record.get(COMPANY_INVITATIONS.EMAIL))
                .roleId(record.get(COMPANY_INVITATIONS.ROLE_ID))
                .roleName(record.get(COMPANY_INVITATIONS.ROLE_NAME))
                .status(CompanyInvitation.InvitationStatus.fromValue(record.get(COMPANY_INVITATIONS.STATUS)))
                .createdAt(record.get(COMPANY_INVITATIONS.CREATED_AT))
                .updatedAt(record.get(COMPANY_INVITATIONS.UPDATED_AT))
                .expiresAt(record.get(COMPANY_INVITATIONS.EXPIRES_AT))
                .build();
    }
}

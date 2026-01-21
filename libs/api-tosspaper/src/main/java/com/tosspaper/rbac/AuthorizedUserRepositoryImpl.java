package com.tosspaper.rbac;

import com.tosspaper.models.domain.AuthorizedUser;
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

import static com.tosspaper.models.jooq.Tables.AUTHORIZED_USERS;

/**
 * JOOQ implementation of AuthorizedUserRepository.
 * Manages authorized users with role-based access and cursor pagination.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AuthorizedUserRepositoryImpl implements AuthorizedUserRepository {

    private final DSLContext dsl;

    @Override
    public List<AuthorizedUser> findByEmail(String email) {
        return dsl.selectFrom(AUTHORIZED_USERS)
                .where(AUTHORIZED_USERS.EMAIL.eq(email))
                .and(AUTHORIZED_USERS.STATUS.eq(AuthorizedUser.UserStatus.ENABLED.getValue()))
                .fetch(this::mapRecordToDomain);
    }

    @Override
    public List<AuthorizedUser> findByCompanyId(Long companyId, AuthorizedUserQuery query) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(AUTHORIZED_USERS.COMPANY_ID.eq(companyId));

        // Apply filters
        if (query.getEmail() != null && !query.getEmail().isBlank()) {
            conditions.add(AUTHORIZED_USERS.EMAIL.likeIgnoreCase("%" + query.getEmail() + "%"));
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            conditions.add(AUTHORIZED_USERS.STATUS.eq(query.getStatus()));
        }
        if (query.getRoleId() != null && !query.getRoleId().isBlank()) {
            conditions.add(AUTHORIZED_USERS.ROLE_ID.eq(query.getRoleId()));
        }

        // Apply cursor pagination (cursor is email - unique per company, stored in cursorId)
        if (query.getCursorId() != null && !query.getCursorId().isBlank()) {
            conditions.add(AUTHORIZED_USERS.EMAIL.gt(query.getCursorId()));
        }

        SelectConditionStep<?> selectQuery = dsl.selectFrom(AUTHORIZED_USERS)
                .where(conditions);

        return selectQuery.orderBy(AUTHORIZED_USERS.EMAIL.asc())
                .limit(query.getPageSize())
                .fetch(this::mapRecordToDomain);
    }

    @Override
    public Optional<AuthorizedUser> findByCompanyIdAndEmail(Long companyId, String email) {
        return dsl.selectFrom(AUTHORIZED_USERS)
                .where(AUTHORIZED_USERS.COMPANY_ID.eq(companyId))
                .and(AUTHORIZED_USERS.EMAIL.eq(email))
                .fetchOptional(this::mapRecordToDomain);
    }

    @Override
    public AuthorizedUser findById(String userId) {
        return dsl.selectFrom(AUTHORIZED_USERS)
                .where(AUTHORIZED_USERS.ID.eq(userId))
                .fetchOptional(this::mapRecordToDomain)
                .orElseThrow(() -> new com.tosspaper.common.NotFoundException("api.authorized_user.not_found", "Authorized user not found with id: " + userId));
    }

    @Override
    public AuthorizedUser save(AuthorizedUser user) {
        return save(dsl, user);
    }

    @Override
    public AuthorizedUser save(DSLContext dsl, AuthorizedUser user) {
        var record = dsl.insertInto(AUTHORIZED_USERS)
                .set(AUTHORIZED_USERS.ID, user.id())
                .set(AUTHORIZED_USERS.COMPANY_ID, user.companyId())
                .set(AUTHORIZED_USERS.USER_ID, user.userId())
                .set(AUTHORIZED_USERS.EMAIL, user.email())
                .set(AUTHORIZED_USERS.ROLE_ID, user.roleId())
                .set(AUTHORIZED_USERS.ROLE_NAME, user.roleName())
                .set(AUTHORIZED_USERS.STATUS, user.status().getValue())
                .set(AUTHORIZED_USERS.CREATED_AT, user.createdAt() != null ? user.createdAt() : OffsetDateTime.now())
                .set(AUTHORIZED_USERS.UPDATED_AT, OffsetDateTime.now())
                .set(AUTHORIZED_USERS.LAST_UPDATED_BY, user.lastUpdatedBy())
                .onConflict(AUTHORIZED_USERS.ID)
                .doUpdate()
                .set(AUTHORIZED_USERS.ROLE_ID, user.roleId())
                .set(AUTHORIZED_USERS.ROLE_NAME, user.roleName())
                .set(AUTHORIZED_USERS.STATUS, user.status().getValue())
                .set(AUTHORIZED_USERS.UPDATED_AT, OffsetDateTime.now())
                .set(AUTHORIZED_USERS.LAST_UPDATED_BY, user.lastUpdatedBy())
                .returning()
                .fetchOne();

        return mapRecordToDomain(record);
    }

    @Override
    public void delete(String userId) {
        dsl.deleteFrom(AUTHORIZED_USERS)
                .where(AUTHORIZED_USERS.ID.eq(userId))
                .execute();
    }

    /**
     * Map JOOQ record to domain model
     */
    private AuthorizedUser mapRecordToDomain(org.jooq.Record record) {
        return AuthorizedUser.builder()
                .id(record.get(AUTHORIZED_USERS.ID))
                .companyId(record.get(AUTHORIZED_USERS.COMPANY_ID))
                .userId(record.get(AUTHORIZED_USERS.USER_ID))
                .email(record.get(AUTHORIZED_USERS.EMAIL))
                .roleId(record.get(AUTHORIZED_USERS.ROLE_ID))
                .roleName(record.get(AUTHORIZED_USERS.ROLE_NAME))
                .status(AuthorizedUser.UserStatus.fromValue(record.get(AUTHORIZED_USERS.STATUS)))
                .createdAt(record.get(AUTHORIZED_USERS.CREATED_AT))
                .updatedAt(record.get(AUTHORIZED_USERS.UPDATED_AT))
                .lastUpdatedBy(record.get(AUTHORIZED_USERS.LAST_UPDATED_BY))
                .build();
    }
}

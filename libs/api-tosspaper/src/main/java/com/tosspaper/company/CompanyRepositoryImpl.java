package com.tosspaper.company;

import com.tosspaper.common.DuplicateException;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.models.jooq.tables.records.CompaniesRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.exception.NoDataFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.AUTHORIZED_USERS;
import static com.tosspaper.models.jooq.Tables.COMPANIES;

@Repository
@RequiredArgsConstructor
public class CompanyRepositoryImpl implements CompanyRepository {

    private final DSLContext dsl;

    @Override
    public CompaniesRecord save(CompaniesRecord company) {
        return save(dsl, company);
    }

    @Override
    public CompaniesRecord save(DSLContext dsl, CompaniesRecord company) {
        try {
            return dsl.insertInto(COMPANIES)
                    .set(company)
                    .returning()
                    .fetchSingle();
        } catch (DuplicateKeyException e) {
            // Check which unique constraint was violated
//            String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
//
//            if (errorMessage.contains("assigned_email")) {
//                throw new DuplicateException(
//                    ApiErrorMessages.COMPANY_ASSIGNED_EMAIL_ALREADY_EXISTS_CODE,
//                    ApiErrorMessages.COMPANY_ASSIGNED_EMAIL_ALREADY_EXISTS.formatted(company.getAssignedEmail())
//                );
//            }

            // Default to email duplicate
            throw new DuplicateException(
                ApiErrorMessages.COMPANY_ALREADY_EXISTS_CODE,
                ApiErrorMessages.COMPANY_ALREADY_EXISTS.formatted(company.getEmail())
            );
        }
    }

    @Override
    public CompaniesRecord findById(Long id) {
        try {
            return dsl.selectFrom(COMPANIES)
                    .where(COMPANIES.ID.eq(id))
                    .fetchSingle();
        } catch (NoDataFoundException e) {
            throw new NotFoundException(ApiErrorMessages.COMPANY_NOT_FOUND_CODE, ApiErrorMessages.COMPANY_NOT_FOUND);
        }
    }

    @Override
    public CompaniesRecord update(CompaniesRecord company)  {
        try {
            return dsl.update(COMPANIES)
                    .set(company)
                    .set(COMPANIES.UPDATED_AT, OffsetDateTime.now())
                    .where(COMPANIES.ID.eq(company.getId()))
                    .returning()
                    .fetchOptional()
                    .orElseThrow(() -> new NotFoundException(ApiErrorMessages.COMPANY_NOT_FOUND_CODE, ApiErrorMessages.COMPANY_NOT_FOUND));
        } catch (DuplicateKeyException e) {
            // Check which unique constraint was violated
            String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            
            if (errorMessage.contains("assigned_email")) {
                throw new DuplicateException(
                    ApiErrorMessages.COMPANY_ASSIGNED_EMAIL_ALREADY_EXISTS_CODE,
                    ApiErrorMessages.COMPANY_ASSIGNED_EMAIL_ALREADY_EXISTS.formatted(company.getAssignedEmail())
                );
            }
            
            // Default to email duplicate
            throw new DuplicateException(
                ApiErrorMessages.COMPANY_ALREADY_EXISTS_CODE,
                ApiErrorMessages.COMPANY_ALREADY_EXISTS.formatted(company.getEmail())
            );
        }
    }

    @Override
    public void deleteById(Long id)  {
        int deleted = dsl.deleteFrom(COMPANIES)
                .where(COMPANIES.ID.eq(id))
                .execute();
        if (deleted == 0) {
            throw new NotFoundException(ApiErrorMessages.COMPANY_NOT_FOUND_CODE, ApiErrorMessages.COMPANY_NOT_FOUND);
        }
    }

    @Override
    public Optional<CompaniesRecord> findByEmail(String email) {
        return dsl.selectFrom(COMPANIES)
                .where(COMPANIES.EMAIL.eq(email))
                .fetchOptional();
    }

    @Override
    public Optional<CompaniesRecord> findByAssignedEmail(String assignedEmail) {
        return dsl.selectFrom(COMPANIES)
                .where(COMPANIES.ASSIGNED_EMAIL.eq(assignedEmail))
                .fetchOptional();
    }

    @Override
    public List<CompanyWithRole> findAuthorizedCompaniesByEmail(String email) {
        return dsl.select(COMPANIES.asterisk(), AUTHORIZED_USERS.ROLE_ID)
                .from(COMPANIES)
                .innerJoin(AUTHORIZED_USERS)
                .on(COMPANIES.ID.eq(AUTHORIZED_USERS.COMPANY_ID))
                .where(AUTHORIZED_USERS.EMAIL.eq(email))
                .and(AUTHORIZED_USERS.STATUS.eq("enabled"))
                .fetch(record -> new CompanyWithRole(
                        record.into(COMPANIES),
                        record.get(AUTHORIZED_USERS.ROLE_ID)
                ));
    }
} 
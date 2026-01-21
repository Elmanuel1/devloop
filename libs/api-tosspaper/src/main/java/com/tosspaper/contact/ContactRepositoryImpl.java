package com.tosspaper.contact;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.DuplicateException;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.query.PaginatedResult;
import com.tosspaper.models.jooq.tables.records.ContactsRecord;
import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.models.service.EmailDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.exception.NoDataFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.CONTACTS;
import static com.tosspaper.models.jooq.Tables.APPROVED_SENDERS;
import static org.jooq.impl.DSL.falseCondition;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ContactRepositoryImpl implements ContactRepository {

    private final DSLContext dsl;

    @Override
    public PaginatedResult<ContactsRecord> findByCompanyIdPaginated(
            Long companyId,
            int page,
            int pageSize,
            String search,
            ContactTag tag,
            ContactStatus status
    ) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(CONTACTS.COMPANY_ID.eq(companyId));

        // Full-text search
        if (search != null && !search.trim().isEmpty()) {
            String prefixQuery = java.util.Arrays.stream(search.trim().split("\\s+"))
                .map(term -> term + ":*")
                .collect(java.util.stream.Collectors.joining(" & "));
            
            if (!prefixQuery.isEmpty()) {
                conditions.add(org.jooq.impl.DSL.condition("search_vector @@ to_tsquery('english', ?)", prefixQuery));
            }
        }

        if (tag != null) {
            conditions.add(CONTACTS.TAG.eq(tag.getValue()));
        }

        if (status != null) {
            conditions.add(CONTACTS.STATUS.eq(status.getValue()));
        }

        // Count total records
        int total = dsl.selectCount()
                .from(CONTACTS)
                .where(conditions)
                .fetchOne(0, int.class);

        // Fetch paginated records
        int offset = (page - 1) * pageSize;
        List<ContactsRecord> records = dsl.selectFrom(CONTACTS)
                .where(conditions)
                .orderBy(CONTACTS.CREATED_AT.desc())
                .limit(pageSize)
                .offset(offset)
                .fetch();

        return new PaginatedResult<>(records, total, page, pageSize);
    }

    @Override
    public ContactsRecord findById(String id) {
        return dsl.selectFrom(CONTACTS)
                .where(CONTACTS.ID.eq(id))
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException(ApiErrorMessages.CONTACT_NOT_FOUND_CODE, ApiErrorMessages.CONTACT_NOT_FOUND));
    }

    @Override
    public Optional<ContactsRecord> findByEmailOrPhoneAndCompanyId(String email, String phone, Long companyId) {
        if (email == null && phone == null) {
            return Optional.empty();
        }

        Condition condition = CONTACTS.COMPANY_ID.eq(companyId);

        Condition emailOrPhoneCondition = falseCondition();
        if (email != null) {
            emailOrPhoneCondition = emailOrPhoneCondition.or(CONTACTS.EMAIL.eq(email));
        }
        if (phone != null) {
            emailOrPhoneCondition = emailOrPhoneCondition.or(CONTACTS.PHONE.eq(phone));
        }

        return dsl.selectFrom(CONTACTS)
                .where(condition.and(emailOrPhoneCondition))
                .fetchOptional();
    }

    @Override
    public ContactsRecord create(ContactsRecord record) {
        try {
            return dsl.insertInto(CONTACTS)
                    .set(record)
                    .returning()
                    .fetchOne();
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(ApiErrorMessages.CONTACT_ALREADY_EXISTS_CODE, ApiErrorMessages.CONTACT_ALREADY_EXISTS);
        } catch (DataIntegrityViolationException e) {
            // Check if it's the email/phone constraint violation
            if (e.getMessage() != null && (e.getMessage().contains("contacts_email_phone_check") || e.getMessage().contains("contacts_check"))) {
                throw new BadRequestException(
                    ApiErrorMessages.CONTACT_EMAIL_OR_PHONE_REQUIRED_CODE,
                    ApiErrorMessages.CONTACT_EMAIL_OR_PHONE_REQUIRED
                );
            }
            // Re-throw if it's a different constraint violation
            throw e;
        }
    }

    @Override
    public ContactsRecord createWithApproval(ContactsRecord contactRecord, ApprovedSender approvedSender) {
        return dsl.transactionResult(configuration -> {
            var txDsl = configuration.dsl();
            
            // 1. Insert contact
            ContactsRecord createdContact;
            try {
                createdContact = txDsl.insertInto(CONTACTS)
                        .set(CONTACTS.COMPANY_ID, contactRecord.getCompanyId())
                        .set(CONTACTS.NAME, contactRecord.getName())
                        .set(CONTACTS.EMAIL, contactRecord.getEmail())
                        .set(CONTACTS.PHONE, contactRecord.getPhone())
                        .set(CONTACTS.TAG, contactRecord.getTag())
                        .set(CONTACTS.ADDRESS, contactRecord.getAddress())
                        .set(CONTACTS.NOTES, contactRecord.getNotes())
                        .set(CONTACTS.STATUS, contactRecord.getStatus())
                        .set(CONTACTS.CURRENCY_CODE, contactRecord.getCurrencyCode())
                        .returning()
                        .fetchSingle();
            } catch (DuplicateKeyException e) {
                throw new DuplicateException(ApiErrorMessages.CONTACT_ALREADY_EXISTS_CODE, ApiErrorMessages.CONTACT_ALREADY_EXISTS);
            } catch (DataIntegrityViolationException e) {
                if (e.getMessage() != null && (e.getMessage().contains("contacts_email_phone_check") || e.getMessage().contains("contacts_check"))) {
                    throw new BadRequestException(
                        ApiErrorMessages.CONTACT_EMAIL_OR_PHONE_REQUIRED_CODE,
                        ApiErrorMessages.CONTACT_EMAIL_OR_PHONE_REQUIRED
                    );
                }
                throw e;
            }
            
            // 2. Insert/update approved_senders if provided
            if (approvedSender != null) {
                txDsl.insertInto(APPROVED_SENDERS)
                    .set(APPROVED_SENDERS.COMPANY_ID, approvedSender.getCompanyId())
                    .set(APPROVED_SENDERS.SENDER_IDENTIFIER, approvedSender.getSenderIdentifier())
                    .set(APPROVED_SENDERS.WHITELIST_TYPE, approvedSender.getWhitelistType().getValue())
                    .set(APPROVED_SENDERS.STATUS, approvedSender.getStatus().getValue())
                    .set(APPROVED_SENDERS.APPROVED_BY, approvedSender.getApprovedBy())
                    .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, approvedSender.getScheduledDeletionAt())
                    .onConflict(APPROVED_SENDERS.COMPANY_ID, APPROVED_SENDERS.SENDER_IDENTIFIER)
                    .doUpdate()
                    .set(APPROVED_SENDERS.STATUS, approvedSender.getStatus().getValue())
                    .set(APPROVED_SENDERS.APPROVED_BY, approvedSender.getApprovedBy())
                    .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, approvedSender.getScheduledDeletionAt())
                    .set(APPROVED_SENDERS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                    .where(APPROVED_SENDERS.COMPANY_ID.eq(approvedSender.getCompanyId()))
                    .execute();
                
                log.info("Auto-approved {} for contact (type: {})", 
                    approvedSender.getSenderIdentifier(), approvedSender.getWhitelistType().getValue());
            }
            
            return createdContact;
        });
    }

    @Override
    public ContactsRecord update(ContactsRecord record) {
        try {
            var updateStep = dsl.update(CONTACTS)
                    .set(CONTACTS.UPDATED_AT, OffsetDateTime.now());
            
            // Only update fields that are not null
            if (record.getName() != null) {
                updateStep = updateStep.set(CONTACTS.NAME, record.getName());
            }
            if (record.getEmail() != null) {
                updateStep = updateStep.set(CONTACTS.EMAIL, record.getEmail());
            }
            if (record.getPhone() != null) {
                updateStep = updateStep.set(CONTACTS.PHONE, record.getPhone());
            }
            if (record.getAddress() != null) {
                updateStep = updateStep.set(CONTACTS.ADDRESS, record.getAddress());
            }
            if (record.getNotes() != null) {
                updateStep = updateStep.set(CONTACTS.NOTES, record.getNotes());
            }
            if (record.getTag() != null) {
                updateStep = updateStep.set(CONTACTS.TAG, record.getTag());
            }

            if (record.getStatus() != null) {
                updateStep = updateStep.set(CONTACTS.STATUS, record.getStatus());
            }

            if(record.getCurrencyCode() != null) {
                updateStep = updateStep.set(CONTACTS.CURRENCY_CODE, record.getCurrencyCode());
            }
            
            return updateStep
                    .where(CONTACTS.ID.eq(record.getId()))
                    .returning()
                    .fetchSingle();
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(ApiErrorMessages.CONTACT_ALREADY_EXISTS_CODE, ApiErrorMessages.CONTACT_ALREADY_EXISTS);
        } catch (NoDataFoundException e) {
            throw new NotFoundException(ApiErrorMessages.CONTACT_NOT_FOUND_CODE, ApiErrorMessages.CONTACT_NOT_FOUND);
        } catch (DataIntegrityViolationException e) {
            // Check if it's the email/phone constraint violation
            if (e.getMessage() != null && (e.getMessage().contains("contacts_email_phone_check") || e.getMessage().contains("contacts_check"))) {
                throw new BadRequestException(
                    ApiErrorMessages.CONTACT_EMAIL_OR_PHONE_REQUIRED_CODE,
                    ApiErrorMessages.CONTACT_EMAIL_OR_PHONE_REQUIRED
                );
            }
            // Re-throw if it's a different constraint violation
            throw e;
        }
    }

    @Override
    public ContactsRecord updateWithApprovalChanges(ContactsRecord record, String oldApprovalToDelete, ApprovedSender newApprovalToAdd) {
        log.debug("Updating contact {} with approval changes - oldApproval: {}, newApproval: {}", 
            record.getId(), oldApprovalToDelete, 
            newApprovalToAdd != null ? newApprovalToAdd.getSenderIdentifier() : null);
        
        try {
            return dsl.transactionResult(configuration -> {
                var txDsl = configuration.dsl();
                
                // 1. Update contact - only update fields that are not null to preserve existing values
                record.setUpdatedAt(OffsetDateTime.now());
                var updateStep = txDsl.update(CONTACTS)
                    .set(CONTACTS.UPDATED_AT, record.getUpdatedAt());
                
                // Only update fields that are not null (preserves existing address if not in update)
                if (record.getName() != null) {
                    updateStep = updateStep.set(CONTACTS.NAME, record.getName());
                }
                if (record.getEmail() != null) {
                    updateStep = updateStep.set(CONTACTS.EMAIL, record.getEmail());
                }
                if (record.getPhone() != null) {
                    updateStep = updateStep.set(CONTACTS.PHONE, record.getPhone());
                }
                if (record.getAddress() != null) {
                    updateStep = updateStep.set(CONTACTS.ADDRESS, record.getAddress());
                }
                if (record.getNotes() != null) {
                    updateStep = updateStep.set(CONTACTS.NOTES, record.getNotes());
                }
                if (record.getTag() != null) {
                    updateStep = updateStep.set(CONTACTS.TAG, record.getTag());
                }
                if (record.getStatus() != null) {
                    updateStep = updateStep.set(CONTACTS.STATUS, record.getStatus());
                }
                if (record.getCurrencyCode() != null) {
                    updateStep = updateStep.set(CONTACTS.CURRENCY_CODE, record.getCurrencyCode());
                }
                
                var updatedRecord = updateStep
                    .where(CONTACTS.ID.eq(record.getId()))
                    .returning()
                    .fetchSingle();
                log.debug("Contact {} updated successfully", updatedRecord.getId());
            
            // 2. Delete old approval if provided
            if (oldApprovalToDelete != null) {
                int deleted = txDsl.deleteFrom(APPROVED_SENDERS)
                    .where(APPROVED_SENDERS.COMPANY_ID.eq(record.getCompanyId()))
                    .and(APPROVED_SENDERS.SENDER_IDENTIFIER.eq(oldApprovalToDelete))
                    .execute();
                log.debug("Deleted {} old approval record(s) for: {}", deleted, oldApprovalToDelete);
            }
            
            // 3. Add new approval if provided
            if (newApprovalToAdd != null) {
                txDsl.insertInto(APPROVED_SENDERS)
                    .set(APPROVED_SENDERS.COMPANY_ID, newApprovalToAdd.getCompanyId())
                    .set(APPROVED_SENDERS.SENDER_IDENTIFIER, newApprovalToAdd.getSenderIdentifier())
                    .set(APPROVED_SENDERS.WHITELIST_TYPE, newApprovalToAdd.getWhitelistType().getValue())
                    .set(APPROVED_SENDERS.STATUS, newApprovalToAdd.getStatus().getValue())
                    .set(APPROVED_SENDERS.APPROVED_BY, newApprovalToAdd.getApprovedBy())
                    .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, newApprovalToAdd.getScheduledDeletionAt())
                    .onConflict(APPROVED_SENDERS.COMPANY_ID, APPROVED_SENDERS.SENDER_IDENTIFIER)
                    .doUpdate()
                    .set(APPROVED_SENDERS.STATUS, newApprovalToAdd.getStatus().getValue())
                    .set(APPROVED_SENDERS.APPROVED_BY, newApprovalToAdd.getApprovedBy())
                    .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, newApprovalToAdd.getScheduledDeletionAt())
                    .set(APPROVED_SENDERS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                    .execute();
                log.debug("Upserted new approval: {} (type: {})", 
                    newApprovalToAdd.getSenderIdentifier(), newApprovalToAdd.getWhitelistType().getValue());
            }
            
            log.debug("Transaction completed successfully for contact {}", updatedRecord.getId());
            return updatedRecord;
        });
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(ApiErrorMessages.CONTACT_ALREADY_EXISTS_CODE, ApiErrorMessages.CONTACT_ALREADY_EXISTS);
        } catch (NoDataFoundException e) {
            throw new NotFoundException(ApiErrorMessages.CONTACT_NOT_FOUND_CODE, ApiErrorMessages.CONTACT_NOT_FOUND);
        } catch (DataIntegrityViolationException e) {
            // Check if it's the email/phone constraint violation
            if (e.getMessage() != null && (e.getMessage().contains("contacts_email_phone_check") || e.getMessage().contains("contacts_check"))) {
                throw new BadRequestException(
                    ApiErrorMessages.CONTACT_EMAIL_OR_PHONE_REQUIRED_CODE,
                    ApiErrorMessages.CONTACT_EMAIL_OR_PHONE_REQUIRED
                );
            }
            // Re-throw if it's a different constraint violation
            throw e;
        }
    }

    @Override
    public void deleteById(String id) {
        try {
            dsl.deleteFrom(CONTACTS)
                    .where(CONTACTS.ID.eq(id))
                    .execute();
        } catch (DataIntegrityViolationException e) {
            // Check if this is a foreign key constraint violation (SQL state 23503)
            if (e.getCause() instanceof java.sql.SQLException sqlException && 
                "23503".equals(sqlException.getSQLState())) {
                throw new BadRequestException("contact_in_use", ApiErrorMessages.CONTACT_DELETE_CONSTRAINT_VIOLATION);
            }
            // Re-throw other constraint violations as-is
            throw e;
        }
    }
} 
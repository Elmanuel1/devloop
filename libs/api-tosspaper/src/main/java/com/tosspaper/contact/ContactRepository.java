package com.tosspaper.contact;

import com.tosspaper.models.jooq.tables.records.ContactsRecord;
import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.common.query.PaginatedResult;

import java.util.Optional;

public interface ContactRepository {
    PaginatedResult<ContactsRecord> findByCompanyIdPaginated(
        Long companyId, 
        int page, 
        int pageSize, 
        String search, 
        ContactTag tag, 
        ContactStatus status
    );

    ContactsRecord findById(String id);

    Optional<ContactsRecord> findByEmailOrPhoneAndCompanyId(String email, String phone, Long companyId);

    ContactsRecord create(ContactsRecord contactsRecord);
    
    /**
     * Create contact and optionally create approved_senders record atomically.
     * @param contactRecord Contact to create
     * @param approvedSender Optional approval record to create (null to skip)
     * @return Created contact record
     */
    ContactsRecord createWithApproval(ContactsRecord contactRecord, ApprovedSender approvedSender);

    ContactsRecord update(ContactsRecord record);
    
    /**
     * Update contact and handle approval changes atomically.
     * @param record Contact to update
     * @param oldApprovalToDelete Old approval identifier to delete (null to skip)
     * @param newApprovalToAdd New approval to add (null to skip)
     * @return Updated contact record
     */
    ContactsRecord updateWithApprovalChanges(ContactsRecord record, String oldApprovalToDelete, ApprovedSender newApprovalToAdd);

    void deleteById(String id);
}
package com.tosspaper.contact;

import com.tosspaper.generated.model.Contact;
import com.tosspaper.generated.model.ContactCreate;
import com.tosspaper.generated.model.ContactList;
import com.tosspaper.generated.model.ContactUpdate;

public interface ContactService {
    ContactList getContactsPaginated(
        Long companyId, 
        Integer page, 
        Integer pageSize, 
        String search, 
        com.tosspaper.generated.model.ContactTagEnum tag, 
        com.tosspaper.generated.model.ContactStatus status
    );

    Contact createContact(Long companyId, ContactCreate contactCreate);

    Contact getContactById(Long companyId, String contactId);

    void updateContact(Long companyId, String contactId, ContactUpdate contactUpdate);

    void deleteContact(Long companyId, String contactId);
} 
package com.tosspaper.contact;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.ContactsApi;
import com.tosspaper.generated.model.Contact;
import com.tosspaper.generated.model.ContactCreate;
import com.tosspaper.generated.model.ContactUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@Validated
public class ContactController implements ContactsApi {

    private final ContactService contactService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'contacts:create')")
    public ResponseEntity<Contact> createContact(String xContextId, ContactCreate contactCreate) {
        log.info("POST /v1/contacts - Received API request: tag={}, currencyCode={}",
                contactCreate.getTag(), contactCreate.getCurrencyCode());
        var contact = contactService.createContact(HeaderUtils.parseCompanyId(xContextId), contactCreate);
        return ResponseEntity.status(HttpStatus.CREATED).body(contact);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'contacts:delete')")
    public ResponseEntity<Void> deleteContact(String xContextId, String id) {
        contactService.deleteContact(HeaderUtils.parseCompanyId(xContextId), id);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'contacts:view')")
    public ResponseEntity<Contact> getContactById(String xContextId, String id) {
        var contact = contactService.getContactById(HeaderUtils.parseCompanyId(xContextId), id);
        return ResponseEntity.ok(contact);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'contacts:view')")
    public ResponseEntity<com.tosspaper.generated.model.ContactList> getContacts(
            String xContextId,
            com.tosspaper.generated.model.ContactTagEnum tag,
            Integer page,
            Integer pageSize,
            String search,
            com.tosspaper.generated.model.ContactStatus status
    ) {
        var contactList = contactService.getContactsPaginated(
            HeaderUtils.parseCompanyId(xContextId), page, pageSize, search, tag, status
        );
        return ResponseEntity.ok(contactList);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'contacts:edit')")
    public ResponseEntity<Void> updateContact(String xContextId, String id, ContactUpdate contactUpdate) {
        contactService.updateContact(HeaderUtils.parseCompanyId(xContextId), id, contactUpdate);
        return ResponseEntity.noContent().build();
    }
} 
package com.tosspaper.contact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.ForbiddenException;
import com.tosspaper.common.security.SecurityUtils;
import com.tosspaper.generated.model.Contact;
import com.tosspaper.generated.model.ContactCreate;
import com.tosspaper.generated.model.ContactList;
import com.tosspaper.generated.model.ContactUpdate;
import com.tosspaper.integrations.push.IntegrationPushEvent;
import com.tosspaper.integrations.push.IntegrationPushStreamPublisher;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationCategory;
import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.models.enums.EmailWhitelistValue;
import com.tosspaper.models.enums.SenderApprovalStatus;
import com.tosspaper.models.service.CompanyLookupService;
import com.tosspaper.models.service.EmailDomainService;
import com.tosspaper.models.utils.EmailPatternMatcher;
import com.tosspaper.models.jooq.tables.records.ContactsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContactServiceImpl implements ContactService {

    private final ContactRepository contactRepository;
    private final ContactMapper contactMapper;
    private final CompanyLookupService companyLookupService;
    private final EmailDomainService emailDomainService;
    private final IntegrationPushStreamPublisher integrationPushStreamPublisher;
    private final IntegrationConnectionService integrationConnectionService;
    private final ObjectMapper objectMapper;

    @Override
    public ContactList getContactsPaginated(
            Long companyId,
            Integer page,
            Integer pageSize,
            String search,
            com.tosspaper.generated.model.ContactTagEnum tag,
            com.tosspaper.generated.model.ContactStatus status) {
        int effectivePage = Optional.ofNullable(page).orElse(1);
        int effectivePageSize = Optional.ofNullable(pageSize).orElse(20);

        ContactTag tagEnum = Optional.ofNullable(tag)
                .map(com.tosspaper.generated.model.ContactTagEnum::getValue)
                .map(ContactTag::fromValue).orElse(null);

        ContactStatus statusEnum = Optional.ofNullable(status)
                .map(s -> ContactStatus.fromValue(s.getValue()))
                .orElse(null);

        var paginatedResult = contactRepository.findByCompanyIdPaginated(
                companyId, effectivePage, effectivePageSize, search, tagEnum, statusEnum);

        log.debug("{} Contacts retrieved for company {} (page {}, pageSize {})",
                paginatedResult.getData().size(), companyId, effectivePage, effectivePageSize);

        return contactMapper.toContactList(paginatedResult);
    }

    @Override
    public Contact createContact(Long companyId, ContactCreate contactCreate) {
        log.info("ContactService.createContact - Received: companyId={}, currencyCode={}, name={}",
                companyId, contactCreate.getCurrencyCode(), contactCreate.getName());

        // Validate currency if provided
        if (contactCreate.getCurrencyCode() != null && !contactCreate.getCurrencyCode().isBlank()) {
            validateContactCurrency(companyId, contactCreate.getCurrencyCode());
        }

        var record = contactMapper.toRecord(companyId, contactCreate);
        log.info("ContactService.createContact - After mapping toRecord: currencyCode={}, recordId={}",
                record.getCurrencyCode(), record.getId());

        // Determine if auto-approval should be created
        Optional<ApprovedSender> approvedSender = buildApprovedSenderIfNeeded(companyId, record.getEmail());

        // Create with or without approval based on presence
        var createdRecord = approvedSender.isPresent()
                ? contactRepository.createWithApproval(record, approvedSender.get())
                : contactRepository.create(record);

        log.debug("Contact {} created{}", createdRecord.getId(),
                approvedSender.isPresent() ? " with auto-approval" : "");

        // Publish integration push event if there's an active connection
        publishIntegrationPushEventIfNeeded(createdRecord, SecurityUtils.getSubjectFromJwt());

        return contactMapper.toDto(createdRecord);
    }

    private Optional<ApprovedSender> buildApprovedSenderIfNeeded(Long companyId, String contactEmail) {
        // Skip if no email
        if (contactEmail == null || contactEmail.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            // Get company's assigned email
            String assignedEmail = companyLookupService.getCompanyById(companyId).assignedEmail();
            String recipientDomain = EmailPatternMatcher.extractDomain(assignedEmail);
            String contactDomain = EmailPatternMatcher.extractDomain(contactEmail);

            // Skip if same domain (already auto-approved by validation)
            if (contactDomain.equalsIgnoreCase(recipientDomain)) {
                log.debug("Skipping auto-approval for same-domain email: {}", contactEmail);
                return Optional.empty();
            }

            // Determine whitelist type based on domain
            boolean isPersonalEmail = emailDomainService.isBlockedDomain(contactDomain);
            EmailWhitelistValue whitelistType = isPersonalEmail
                    ? EmailWhitelistValue.EMAIL
                    : EmailWhitelistValue.DOMAIN;
            String senderIdentifier = isPersonalEmail ? contactEmail : contactDomain;

            // Build approval record
            return Optional.of(ApprovedSender.builder()
                    .companyId(companyId)
                    .senderIdentifier(senderIdentifier)
                    .whitelistType(whitelistType)
                    .status(SenderApprovalStatus.APPROVED)
                    .approvedBy("system")
                    .scheduledDeletionAt(null)
                    .build());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid email format for auto-approval: {}", contactEmail, e);
            return Optional.empty();
        }
    }

    @Override
    public Contact getContactById(Long companyId, String contactId) {
        var contact = contactRepository.findById(contactId);
        if (!contact.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, ApiErrorMessages.ACCESS_DENIED_TO_CONTACT);
        }
        log.debug("Contact {} retrieved", contact.getId());
        return contactMapper.toDto(contact);
    }

    @Override
    public void updateContact(Long companyId, String contactId, ContactUpdate contactUpdate) {
        log.info("ContactService.updateContact - Received: companyId={}, contactId={}, currencyCode={}, name={}",
                companyId, contactId, contactUpdate.getCurrencyCode(), contactUpdate.getName());

        var record = contactRepository.findById(contactId);
        if (!record.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, ApiErrorMessages.ACCESS_DENIED_TO_CONTACT);
        }

        log.info("ContactService.updateContact - Before update: recordCurrencyCode={}", record.getCurrencyCode());

        // Validate currency if provided in update
        if (contactUpdate.getCurrencyCode() != null && !contactUpdate.getCurrencyCode().isBlank()) {
            validateContactCurrency(companyId, contactUpdate.getCurrencyCode());
        }

        String oldEmail = record.getEmail();
        contactMapper.updateRecord(contactUpdate, record);
        String newEmail = record.getEmail();

        log.info("ContactService.updateContact - After mapping: recordCurrencyCode={}", record.getCurrencyCode());

        log.debug("Email change detected - old: {}, new: {}", oldEmail, newEmail);

        // Check if email changed
        if (oldEmail != null && newEmail != null && !oldEmail.equals(newEmail)) {
            // Prepare old approval identifier to delete
            String oldIdentifier = null;
            try {
                String oldDomain = EmailPatternMatcher.extractDomain(oldEmail);
                oldIdentifier = emailDomainService.isBlockedDomain(oldDomain) ? oldEmail : oldDomain;
                log.debug("Old approval identifier to delete: {}", oldIdentifier);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid old email format: {}", oldEmail);
            }

            // Prepare new approval to add
            ApprovedSender newApproval = buildApprovedSenderIfNeeded(companyId, newEmail).orElse(null);
            log.debug("New approval to add: {}", newApproval != null ? newApproval.getSenderIdentifier() : "none");

            // Update with approval changes in single transaction
            var updatedRecord = contactRepository.updateWithApprovalChanges(record, oldIdentifier, newApproval);
            log.info("Contact {} email changed: {} -> {}", contactId, oldEmail, newEmail);

            // Publish integration push event if there's an active connection
            publishIntegrationPushEventIfNeeded(updatedRecord, SecurityUtils.getSubjectFromJwt());
        } else {
            // No email change - regular update
            log.debug("No email change detected, performing regular update");
            var updatedRecord = contactRepository.update(record);

            // Publish integration push event if there's an active connection
            publishIntegrationPushEventIfNeeded(updatedRecord, SecurityUtils.getSubjectFromJwt());
        }

        log.debug("Contact {} update completed", contactId);
    }

    @Override
    public void deleteContact(Long companyId, String contactId) {
        var record = contactRepository.findById(contactId);
        if (!record.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, ApiErrorMessages.ACCESS_DENIED_TO_CONTACT);
        }

        contactRepository.deleteById(record.getId());
        log.debug("Contact {} deleted", record.getId());
    }

    /**
     * Publish integration push event for vendor if there are active integration
     * connections.
     * Only publishes for contacts tagged as SUPPLIER (vendors).
     * Publishes to all active connections for the company.
     */
    private void publishIntegrationPushEventIfNeeded(ContactsRecord record, String updatedBy) {
        try {

            Long companyId = record.getCompanyId();
            log.info("Checking for active integration connections for company {} to publish vendor push event",
                    companyId);

            // Get all active connections for the company
            List<IntegrationConnection> connections = integrationConnectionService.listByCompany(companyId)
                    .stream()
                    .filter(conn -> conn
                            .getStatus() == com.tosspaper.models.domain.integration.IntegrationConnectionStatus.ENABLED)
                    .toList();

            if (connections.isEmpty()) {
                log.info("No active integration connections for company {}, skipping integration push", companyId);
                return;
            }

            log.info("Found {} active integration connection(s) for company {}, publishing vendor push event",
                    connections.size(), companyId);

            // Convert ContactsRecord to Party domain model
            Party party = contactMapper.toParty(record);

            // Log currency before serialization for debugging
            log.info("Party currency before serialization: contactId={}, partyCurrencyCode={}, recordCurrencyCode={}",
                    record.getId(),
                    party.getCurrencyCode() != null ? party.getCurrencyCode().getCode() : "null",
                    record.getCurrencyCode());

            // Serialize Party to JSON payload once
            String payload = objectMapper.writeValueAsString(party);

            // Log the serialized payload to verify currencyCode is included
            log.info("Serialized Party payload for contact {}: {}", record.getId(), payload);

            // Route entity type based on contact tag
            IntegrationEntityType entityType = record.getTag().equals("ship_to")
                    ? IntegrationEntityType.JOB_LOCATION // Ship-to → Job Location (QB Customer)
                    : IntegrationEntityType.VENDOR; // Supplier → Vendor in QB

            // Publish event for each active connection
            for (IntegrationConnection connection : connections) {
                try {
                    IntegrationPushEvent event = new IntegrationPushEvent(
                            connection.getProvider(),
                            entityType,
                            companyId,
                            connection.getId(),
                            payload,
                            updatedBy);

                    integrationPushStreamPublisher.publish(event);
                    log.info("Published {} push event for contact: id={}, name={}, provider={}",
                            entityType, record.getId(), record.getName(), connection.getProvider());
                } catch (Exception e) {
                    log.error("Failed to publish integration push event for contact: id={}, provider={}",
                            record.getId(), connection.getProvider(), e);
                    // Continue with other connections
                }
            }

        } catch (Exception e) {
            log.error("Failed to publish integration push event for contact: id={}", record.getId(), e);
            // Don't throw - we don't want to fail contact operations if push fails
        }
    }

    /**
     * Validate contact currency matches company default currency when multicurrency
     * is disabled.
     * Only validates if there's an active ACCOUNTING category connection.
     */
    private void validateContactCurrency(Long companyId, String currencyCode) {
        // Find active ACCOUNTING category connection
        Optional<IntegrationConnection> activeAccountingConnection = integrationConnectionService
                .findActiveByCompanyAndCategory(
                        companyId, IntegrationCategory.ACCOUNTING);

        if (activeAccountingConnection.isEmpty()) {
            log.debug("No active ACCOUNTING connection for company {}, skipping contact currency validation",
                    companyId);
            return; // No active ACCOUNTING connection, skip validation
        }

        IntegrationConnection connection = activeAccountingConnection.get();

        // Check if multicurrency is enabled
        if (Boolean.TRUE.equals(connection.getMulticurrencyEnabled())) {
            log.debug("Multicurrency is enabled for company {}, allowing any currency", companyId);
            return; // Multicurrency enabled, allow any currency
        }

        // Multicurrency is disabled - currency must match default currency
        if (connection.getDefaultCurrency() == null) {
            log.warn("No default currency set for active ACCOUNTING connection, skipping currency validation");
            return;
        }

        String defaultCurrencyCode = connection.getDefaultCurrency().getCode();
        if (!currencyCode.equals(defaultCurrencyCode)) {
            String providerName = connection.getProvider().getDisplayName();
            throw new BadRequestException(
                    "contact_currency_mismatch",
                    String.format(
                            "Contact currency '%s' does not match company default currency '%s'. Please enable multicurrency in %s to use different currencies.",
                            currencyCode,
                            defaultCurrencyCode,
                            providerName));
        }
    }

}
package com.tosspaper.service.impl

import com.tosspaper.contact.ContactSyncRepository
import com.tosspaper.models.common.SyncStatusUpdate
import com.tosspaper.models.domain.Party
import spock.lang.Specification

import java.time.OffsetDateTime

class ContactSyncServiceSpec extends Specification {

    ContactSyncRepository contactSyncRepository
    ContactSyncServiceImpl service

    def setup() {
        contactSyncRepository = Mock()
        service = new ContactSyncServiceImpl(contactSyncRepository)
    }

    // ==================== upsertFromProvider ====================

    def "upsertFromProvider delegates to repository"() {
        given: "contacts to upsert"
            def companyId = 1L
            def contacts = [createParty("contact-1", "Vendor A"), createParty("contact-2", "Vendor B")]

        when: "upserting contacts"
            service.upsertFromProvider(companyId, contacts)

        then: "repository is called"
            1 * contactSyncRepository.upsertFromProvider(companyId, contacts)
    }

    // ==================== updateSyncStatus ====================

    def "updateSyncStatus delegates to repository"() {
        given: "sync status params"
            def contactId = "contact-123"
            def provider = "QUICKBOOKS"
            def externalId = "qb-123"
            def providerVersion = "1"
            def lastUpdatedAt = OffsetDateTime.now()

        when: "updating sync status"
            service.updateSyncStatus(contactId, provider, externalId, providerVersion, lastUpdatedAt)

        then: "repository is called"
            1 * contactSyncRepository.updateSyncStatus(contactId, provider, externalId, providerVersion, lastUpdatedAt)
    }

    // ==================== batchUpdateSyncStatus ====================

    def "batchUpdateSyncStatus delegates to repository"() {
        given: "batch updates"
            def updates = [
                new SyncStatusUpdate("contact-1", "QUICKBOOKS", "qb-1", "1", OffsetDateTime.now()),
                new SyncStatusUpdate("contact-2", "QUICKBOOKS", "qb-2", "1", OffsetDateTime.now())
            ]

        when: "batch updating"
            service.batchUpdateSyncStatus(updates)

        then: "repository is called"
            1 * contactSyncRepository.batchUpdateSyncStatus(updates)
    }

    // ==================== findById ====================

    def "findById returns contact from repository"() {
        given: "a contact exists"
            def contactId = "contact-123"
            def contact = createParty(contactId, "Test Vendor")

        when: "finding contact"
            def result = service.findById(contactId)

        then: "repository is queried"
            1 * contactSyncRepository.findById(contactId) >> contact

        and: "contact is returned"
            result.id == contactId
    }

    // ==================== findByIds ====================

    def "findByIds returns contacts from repository"() {
        given: "contact IDs"
            def ids = ["contact-1", "contact-2"]
            def contacts = [createParty("contact-1", "Vendor 1"), createParty("contact-2", "Vendor 2")]

        when: "finding contacts"
            def result = service.findByIds(ids)

        then: "repository is queried"
            1 * contactSyncRepository.findByIds(ids) >> contacts

        and: "contacts are returned"
            result.size() == 2
    }

    def "findByIds returns empty list for null IDs"() {
        when: "finding with null IDs"
            def result = service.findByIds(null)

        then: "repository not called"
            0 * contactSyncRepository.findByIds(_)

        and: "empty list returned"
            result.isEmpty()
    }

    def "findByIds returns empty list for empty IDs"() {
        when: "finding with empty IDs"
            def result = service.findByIds([])

        then: "repository not called"
            0 * contactSyncRepository.findByIds(_)

        and: "empty list returned"
            result.isEmpty()
    }

    // ==================== findNeedingPush ====================

    def "findNeedingPush returns contacts from repository"() {
        given: "contacts needing push"
            def companyId = 1L
            def limit = 10
            def tags = ["vendor"]
            def maxRetries = 5
            def contacts = [createParty("contact-1", "Vendor 1")]

        when: "finding contacts needing push"
            def result = service.findNeedingPush(companyId, limit, tags, maxRetries)

        then: "repository is queried"
            1 * contactSyncRepository.findNeedingPush(companyId, limit, tags, maxRetries) >> contacts

        and: "contacts are returned"
            result.size() == 1
    }

    // ==================== findByProviderAndExternalIds ====================

    def "findByProviderAndExternalIds returns contacts"() {
        given: "provider and external IDs"
            def companyId = 1L
            def provider = "QUICKBOOKS"
            def externalIds = ["qb-1", "qb-2"]
            def contacts = [createParty("contact-1", "Vendor 1")]

        when: "finding contacts"
            def result = service.findByProviderAndExternalIds(companyId, provider, externalIds)

        then: "repository is queried"
            1 * contactSyncRepository.findByProviderAndExternalIds(companyId, provider, externalIds) >> contacts

        and: "contacts are returned"
            result.size() == 1
    }

    def "findByProviderAndExternalIds returns empty for null externalIds"() {
        when: "finding with null external IDs"
            def result = service.findByProviderAndExternalIds(1L, "QUICKBOOKS", null)

        then: "repository not called"
            0 * contactSyncRepository.findByProviderAndExternalIds(_, _, _)

        and: "empty list returned"
            result.isEmpty()
    }

    // ==================== incrementRetryCount ====================

    def "incrementRetryCount delegates to repository"() {
        given: "contact ID and error"
            def contactId = "contact-123"
            def errorMessage = "Push failed"

        when: "incrementing retry count"
            service.incrementRetryCount(contactId, errorMessage)

        then: "repository is called"
            1 * contactSyncRepository.incrementRetryCount(contactId, errorMessage)
    }

    // ==================== markAsPermanentlyFailed ====================

    def "markAsPermanentlyFailed delegates to repository"() {
        given: "contact ID and error"
            def contactId = "contact-123"
            def errorMessage = "Duplicate name"

        when: "marking as permanently failed"
            service.markAsPermanentlyFailed(contactId, errorMessage)

        then: "repository is called"
            1 * contactSyncRepository.markAsPermanentlyFailed(contactId, errorMessage)
    }

    // ==================== resetRetryTracking ====================

    def "resetRetryTracking delegates to repository"() {
        given: "contact ID"
            def contactId = "contact-123"

        when: "resetting retry tracking"
            service.resetRetryTracking(contactId)

        then: "repository is called"
            1 * contactSyncRepository.resetRetryTracking(contactId)
    }

    // ==================== Helper Methods ====================

    private Party createParty(String id, String name) {
        Party.builder()
            .id(id)
            .name(name)
            .companyId(1L)
            .build()
    }
}

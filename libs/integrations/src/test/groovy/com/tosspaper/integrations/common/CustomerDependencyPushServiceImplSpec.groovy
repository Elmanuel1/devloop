package com.tosspaper.integrations.common

import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPushProvider
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.service.ContactSyncService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

class CustomerDependencyPushServiceImplSpec extends Specification {

    ContactSyncService contactSyncService = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    IntegrationPushProvider pushProvider = Mock()

    @Subject
    CustomerDependencyPushServiceImpl service = new CustomerDependencyPushServiceImpl(
        contactSyncService, providerFactory
    )

    def connection = IntegrationConnection.builder()
        .id("conn-1")
        .companyId(100L)
        .provider(IntegrationProvider.QUICKBOOKS)
        .build()

    def "should return success when all customers already have external IDs"() {
        given: "customers with external IDs"
            def customer = Party.builder().name("Existing").build()
            customer.id = "c1"
            customer.externalId = "qb-100"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [customer])

        then: "no push needed"
            0 * providerFactory._
            result.success
    }

    def "should push customers without external IDs and update sync status"() {
        given: "a customer without external ID"
            def customer = Party.builder().name("New Customer").build()
            customer.id = "c1"
            def syncResult = SyncResult.success("qb-100", "New Customer", "0", OffsetDateTime.now())

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [customer])

        then: "push provider is obtained and batch push is called"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["c1": syncResult]

        and: "sync status is updated"
            1 * contactSyncService.batchUpdateSyncStatus(_)

        and: "in-memory customer is updated"
            customer.externalId == "qb-100"
            customer.provider == "quickbooks"

        and: "result is success"
            result.success
    }

    def "should return failure when push result is missing"() {
        given: "a customer without external ID"
            def customer = Party.builder().name("Missing").build()
            customer.id = "c1"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [customer])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> [:]

        and: "result is failure"
            !result.success
            result.message.contains("No result returned")
    }

    def "should return failure when push fails"() {
        given: "a customer without external ID"
            def customer = Party.builder().name("Fail").build()
            customer.id = "c1"
            def failResult = SyncResult.failure("Push failed", true)

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [customer])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["c1": failResult]

        and: "result is failure"
            !result.success
    }

    def "should return failure when batch push throws exception"() {
        given: "a customer without external ID"
            def customer = Party.builder().name("Exception").build()
            customer.id = "c1"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [customer])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> { throw new RuntimeException("Error") }

        and: "result is failure"
            !result.success
    }

    def "should throw when no push provider is found"() {
        given: "a customer without external ID"
            def customer = Party.builder().name("No Provider").build()
            customer.id = "c1"

        when: "ensuring external IDs"
            service.ensureHaveExternalIds(connection, [customer])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.empty()
            thrown(IllegalStateException)
    }
}

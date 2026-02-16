package com.tosspaper.integrations.quickbooks.webhook

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.intuit.ipp.data.Account
import com.intuit.ipp.data.Customer
import com.intuit.ipp.data.Item
import com.intuit.ipp.data.PurchaseOrder
import com.intuit.ipp.data.Term
import com.intuit.ipp.data.Vendor
import com.tosspaper.integrations.common.PurchaseOrderContactEnricher
import com.tosspaper.integrations.common.PurchaseOrderLineItemResolver
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.integrations.quickbooks.account.AccountMapper
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient
import com.tosspaper.integrations.quickbooks.customer.CustomerMapper
import com.tosspaper.integrations.quickbooks.item.ItemMapper
import com.tosspaper.integrations.quickbooks.preferences.PreferencesPullProvider
import com.tosspaper.integrations.quickbooks.purchaseorder.QBOPurchaseOrderMapper
import com.tosspaper.integrations.quickbooks.term.PaymentTermMapper
import com.tosspaper.integrations.quickbooks.vendor.VendorMapper
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PaymentTerm
import com.tosspaper.models.domain.integration.IntegrationAccount
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.Preferences
import com.tosspaper.models.service.ContactSyncService
import com.tosspaper.models.service.IntegrationAccountService
import com.tosspaper.models.service.ItemService
import com.tosspaper.models.service.PaymentTermService
import com.tosspaper.models.service.PurchaseOrderSyncService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Comprehensive tests for QuickBooksWebhookHandler.
 * Tests all webhook event processing, entity routing, and batch operations.
 */
class QuickBooksWebhookHandlerSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
    IntegrationConnectionService connectionService = Mock()
    QuickBooksApiClient apiClient = Mock()
    ContactSyncService contactSyncService = Mock()
    PurchaseOrderSyncService purchaseOrderSyncService = Mock()
    PaymentTermService paymentTermService = Mock()
    IntegrationAccountService integrationAccountService = Mock()
    ItemService itemService = Mock()
    PurchaseOrderLineItemResolver lineItemResolver = Mock()
    PurchaseOrderContactEnricher contactEnricher = Mock()
    QBOPurchaseOrderMapper poMapper = Mock()
    VendorMapper vendorMapper = Mock()
    CustomerMapper customerMapper = Mock()
    PaymentTermMapper paymentTermMapper = Mock()
    AccountMapper accountMapper = Mock()
    ItemMapper itemMapper = Mock()
    PreferencesPullProvider preferencesPullProvider = Mock()

    @Subject
    QuickBooksWebhookHandler handler = new QuickBooksWebhookHandler(
        objectMapper,
        connectionService,
        apiClient,
        contactSyncService,
        purchaseOrderSyncService,
        paymentTermService,
        integrationAccountService,
        itemService,
        lineItemResolver,
        contactEnricher,
        poMapper,
        vendorMapper,
        customerMapper,
        paymentTermMapper,
        accountMapper,
        itemMapper,
        preferencesPullProvider
    )

    def "getQueueName returns correct queue name"() {
        expect:
        handler.getQueueName() == "quickbooks-events"
    }

    def "handle should process valid webhook payload"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.vendor.created.v1",
            accountId: "realm-123",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def qboVendor = new Vendor()
        qboVendor.setId("45")
        qboVendor.setDisplayName("Test Vendor")

        def domainVendor = Party.builder()
            .name("Test Vendor")
            .build()
        domainVendor.setExternalId("45")

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", IntegrationProvider.QUICKBOOKS) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> [qboVendor]
        1 * vendorMapper.toDomain(qboVendor) >> domainVendor
        1 * contactSyncService.upsertFromProvider(100L, [domainVendor])
    }

    def "handle should log warning when payload is missing"() {
        given:
        def message = [payload: null]

        when:
        handler.handle(message)

        then:
        0 * connectionService._
    }

    def "handle should log warning when payload is blank"() {
        given:
        def message = [payload: ""]

        when:
        handler.handle(message)

        then:
        0 * connectionService._
    }

    def "handle should parse and process multiple CloudEvents"() {
        given:
        def event1 = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.vendor.created.v1",
            accountId: "realm-123",
            entityId: "1",
            time: OffsetDateTime.now()
        )
        def event2 = new QBOCloudEvent(
            id: "event-2",
            type: "qbo.vendor.updated.v1",
            accountId: "realm-123",
            entityId: "2",
            time: OffsetDateTime.now()
        )

        def payload = objectMapper.writeValueAsString([event1, event2])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(_, _) >> []
    }

    def "processEventsForAccount should skip when connection not found"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.vendor.created.v1",
            accountId: "unknown-realm",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("unknown-realm", _) >> Optional.empty()
        0 * apiClient._
    }

    def "processEventsForAccount should handle deleted purchase orders"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.purchaseorder.deleted.v1",
            accountId: "realm-123",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * purchaseOrderSyncService.deleteByProviderAndExternalIds(100L, "quickbooks", ["45"]) >> 1
        0 * apiClient._
    }

    def "processEventsForAccount should skip deleted vendors"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.vendor.deleted.v1",
            accountId: "realm-123",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        0 * contactSyncService.deleteByProviderAndExternalIds(_, _, _)
    }

    def "processEventsForAccount should sync created/updated vendors"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.vendor.created.v1",
            accountId: "realm-123",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def qboVendor = new Vendor()
        qboVendor.setId("45")
        qboVendor.setDisplayName("ACME Corp")

        def domainVendor = Party.builder()
            .name("ACME Corp")
            .build()
        domainVendor.setExternalId("45")

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> [qboVendor]
        1 * vendorMapper.toDomain(qboVendor) >> domainVendor
        1 * contactSyncService.upsertFromProvider(100L, [domainVendor])
    }

    def "processEventsForAccount should sync created/updated customers"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.customer.created.v1",
            accountId: "realm-123",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def qboCustomer = new Customer()
        qboCustomer.setId("45")
        qboCustomer.setDisplayName("Job Site A")

        def domainCustomer = Party.builder()
            .name("Job Site A")
            .build()
        domainCustomer.setExternalId("45")

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> [qboCustomer]
        1 * customerMapper.toDomain(qboCustomer) >> domainCustomer
        1 * contactSyncService.upsertFromProvider(100L, [domainCustomer])
    }

    def "processEventsForAccount should filter out null customers from mapper"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.customer.created.v1",
            accountId: "realm-123",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def qboCustomer = new Customer()
        qboCustomer.setId("45")

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> [qboCustomer]
        1 * customerMapper.toDomain(qboCustomer) >> null
        0 * contactSyncService.upsertFromProvider(_, _)
    }

    def "processEventsForAccount should sync purchase orders with enrichment"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.purchaseorder.updated.v1",
            accountId: "realm-123",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .defaultCurrency(Currency.USD)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def qboPO = new PurchaseOrder()
        qboPO.setId("45")
        qboPO.setDocNumber("PO-001")

        def domainPO = com.tosspaper.models.domain.PurchaseOrder.builder()
            .displayId("PO-001")
            .build()
        domainPO.setExternalId("45")

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> [qboPO]
        1 * poMapper.toDomain(qboPO, "conn-1", Currency.USD) >> domainPO
        1 * lineItemResolver.resolveLineItemReferences("conn-1", [domainPO])
        1 * contactEnricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [domainPO])
        1 * purchaseOrderSyncService.upsertFromProvider(100L, [domainPO])
    }

    def "processEventsForAccount should sync payment terms"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.term.updated.v1",
            accountId: "realm-123",
            entityId: "5",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def qboTerm = new Term()
        qboTerm.setId("5")
        qboTerm.setName("Net 30")

        def domainTerm = PaymentTerm.builder()
            .name("Net 30")
            .build()
        domainTerm.setExternalId("5")

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> [qboTerm]
        1 * paymentTermMapper.toDomain(qboTerm) >> domainTerm
        1 * paymentTermService.upsertFromProvider(100L, "quickbooks", [domainTerm])
    }

    def "processEventsForAccount should sync accounts"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.account.updated.v1",
            accountId: "realm-123",
            entityId: "10",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def qboAccount = new Account()
        qboAccount.setId("10")
        qboAccount.setName("Expense Account")

        def domainAccount = IntegrationAccount.builder()
            .name("Expense Account")
            .build()
        domainAccount.setExternalId("10")

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> [qboAccount]
        1 * accountMapper.toDomain(qboAccount) >> domainAccount
        1 * integrationAccountService.upsert("conn-1", [domainAccount])
    }

    def "processEventsForAccount should sync items"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.item.created.v1",
            accountId: "realm-123",
            entityId: "20",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def qboItem = new Item()
        qboItem.setId("20")
        qboItem.setName("Widget")

        def domainItem = com.tosspaper.models.domain.integration.Item.builder()
            .name("Widget")
            .build()
        domainItem.setExternalId("20")

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> [qboItem]
        1 * itemMapper.toDomain(qboItem) >> domainItem
        1 * itemService.upsertFromProvider(100L, "conn-1", [domainItem])
    }

    def "processEventsForAccount should handle preferences update"() {
        given: "preferences.updated is not in entity type map, so it is treated as unknown"
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.preferences.updated.v1",
            accountId: "realm-123",
            entityId: "1",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then: "preferences event is skipped as unknown entity type, but API batch call still happens with empty map"
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> []
        0 * preferencesPullProvider.pullBatch(_)
        0 * connectionService.updateCurrencySettings(_, _, _)
    }

    def "processEventsForAccount should handle empty preferences list"() {
        given: "preferences.updated is not in entity type map, so it is treated as unknown"
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.preferences.updated.v1",
            accountId: "realm-123",
            entityId: "1",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then: "preferences event is skipped as unknown entity type, API batch call still happens with empty map"
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> []
        0 * preferencesPullProvider.pullBatch(_)
        0 * connectionService.updateCurrencySettings(_, _, _)
    }

    def "processEventsForAccount should filter events before lastSyncCompletedAt"() {
        given:
        def lastSync = OffsetDateTime.now()
        def oldEventTime = lastSync.minusHours(1)

        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.vendor.created.v1",
            accountId: "realm-123",
            entityId: "45",
            time: oldEventTime
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .lastSyncCompletedAt(lastSync)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        0 * apiClient.queryEntitiesByIdsBatch(_, _)
    }

    def "processEventsForAccount should skip events with unknown entity types"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.unknownentity.created.v1",
            accountId: "realm-123",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(connection, _) >> []
    }

    def "processWebhookPayload should handle invalid JSON"() {
        given:
        def message = [payload: "{ invalid json }"]

        when:
        handler.handle(message)

        then:
        0 * connectionService._
        notThrown(Exception)
    }

    def "processEventsForAccount should group events by accountId"() {
        given:
        def event1 = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.vendor.created.v1",
            accountId: "realm-1",
            entityId: "1",
            time: OffsetDateTime.now()
        )
        def event2 = new QBOCloudEvent(
            id: "event-2",
            type: "qbo.vendor.created.v1",
            accountId: "realm-2",
            entityId: "2",
            time: OffsetDateTime.now()
        )

        def payload = objectMapper.writeValueAsString([event1, event2])
        def message = [payload: payload]

        def connection1 = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-1")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def connection2 = IntegrationConnection.builder()
            .id("conn-2")
            .companyId(200L)
            .realmId("realm-2")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-1", _) >> Optional.of(connection1)
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-2", _) >> Optional.of(connection2)
        (2.._) * connectionService.ensureActiveToken(_) >> { IntegrationConnection conn -> conn }
        (2.._) * apiClient.queryEntitiesByIdsBatch(_, _) >> []
    }

    def "processEventsForAccount should handle batch API exceptions"() {
        given:
        def event = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.vendor.created.v1",
            accountId: "realm-123",
            entityId: "45",
            time: OffsetDateTime.now()
        )
        def payload = objectMapper.writeValueAsString([event])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * apiClient.queryEntitiesByIdsBatch(_, _) >> { throw new RuntimeException("API error") }
        notThrown(Exception)
    }

    def "processEventsForAccount should delete multiple purchase orders in batch"() {
        given:
        def event1 = new QBOCloudEvent(
            id: "event-1",
            type: "qbo.purchaseorder.deleted.v1",
            accountId: "realm-123",
            entityId: "1",
            time: OffsetDateTime.now()
        )
        def event2 = new QBOCloudEvent(
            id: "event-2",
            type: "qbo.purchaseorder.deleted.v1",
            accountId: "realm-123",
            entityId: "2",
            time: OffsetDateTime.now()
        )

        def payload = objectMapper.writeValueAsString([event1, event2])
        def message = [payload: payload]

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .realmId("realm-123")
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        handler.handle(message)

        then:
        1 * connectionService.findByProviderCompanyIdAndProvider("realm-123", _) >> Optional.of(connection)
        1 * purchaseOrderSyncService.deleteByProviderAndExternalIds(100L, "quickbooks", ["1", "2"]) >> 2
    }
}

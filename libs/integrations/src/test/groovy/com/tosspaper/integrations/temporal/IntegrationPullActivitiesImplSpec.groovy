package com.tosspaper.integrations.temporal

import com.tosspaper.integrations.common.PurchaseOrderLineItemResolver
import com.tosspaper.integrations.common.exception.IntegrationException
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPullProvider
import com.tosspaper.integrations.repository.IntegrationConnectionRepository
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PaymentTerm
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.integration.IntegrationAccount
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.integration.Item
import com.tosspaper.models.domain.integration.Preferences
import com.tosspaper.models.service.CompanySyncService
import com.tosspaper.models.service.ContactSyncService
import com.tosspaper.models.service.IntegrationAccountService
import com.tosspaper.models.service.ItemService
import com.tosspaper.models.service.PaymentTermService
import com.tosspaper.models.service.PurchaseOrderSyncService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Comprehensive tests for IntegrationPullActivitiesImpl.
 * Tests all pull activities with mocked services.
 */
class IntegrationPullActivitiesImplSpec extends Specification {

    IntegrationConnectionService connectionService = Mock()
    IntegrationConnectionRepository connectionRepository = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    ContactSyncService contactSyncService = Mock()
    IntegrationAccountService integrationAccountService = Mock()
    ItemService itemService = Mock()
    PaymentTermService paymentTermService = Mock()
    PurchaseOrderSyncService purchaseOrderSyncService = Mock()
    CompanySyncService companySyncService = Mock()
    PurchaseOrderLineItemResolver lineItemResolver = Mock()

    @Subject
    IntegrationPullActivitiesImpl activities = new IntegrationPullActivitiesImpl(
        connectionService,
        connectionRepository,
        providerFactory,
        contactSyncService,
        integrationAccountService,
        itemService,
        paymentTermService,
        purchaseOrderSyncService,
        companySyncService,
        lineItemResolver
    )

    private static SyncConnectionData createConnectionData(Map args) {
        new SyncConnectionData(
            args.id as String,
            args.companyId as Long,
            args.provider as IntegrationProvider,
            args.expiresAt as OffsetDateTime,
            args.realmId as String,
            args.lastSyncAt as OffsetDateTime,
            args.syncFrom as OffsetDateTime
        )
    }

    def "getConnection should return connection data when connection is enabled"() {
        given:
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .status(IntegrationConnectionStatus.ENABLED)
                .accessToken("token")
                .build()

        when:
            def result = activities.getConnection("conn-1")

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            result.id == "conn-1"
            result.companyId == 100L
            result.provider == IntegrationProvider.QUICKBOOKS
    }

    def "getConnection should throw exception when connection not found"() {
        when:
            activities.getConnection("conn-999")

        then:
            1 * connectionService.findById("conn-999") >> null
            thrown(IntegrationException)
    }

    def "getConnection should throw exception when connection is disabled"() {
        given:
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.DISABLED)
                .build()

        when:
            activities.getConnection("conn-1")

        then:
            1 * connectionService.findById("conn-1") >> connection
            thrown(IntegrationException)
    }

    def "fetchVendorsSinceLastSync should pull vendors from provider"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)
            def vendors = [
                Party.builder().id("vendor-1").name("ACME Corp").build(),
                Party.builder().id("vendor-2").name("XYZ Inc").build()
            ]

        when:
            def result = activities.fetchVendorsSinceLastSync(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.pullBatch(connection) >> vendors
            result.size() == 2
    }

    def "fetchVendorsSinceLastSync should return empty list when provider is disabled"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)

        when:
            def result = activities.fetchVendorsSinceLastSync(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> false
            result.isEmpty()
    }

    def "fetchVendorsSinceLastSync should throw exception when provider not found"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

        when:
            activities.fetchVendorsSinceLastSync(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.empty()
            thrown(IntegrationException)
    }

    def "storeVendorsInContacts should upsert vendors"() {
        given:
            def connectionData = createConnectionData(companyId: 100L)
            def vendors = [
                Party.builder().id("vendor-1").name("ACME Corp").build()
            ]

        when:
            activities.storeVendorsInContacts(connectionData, vendors)

        then:
            1 * contactSyncService.upsertFromProvider(100L, vendors)
    }

    def "fetchAccountsSinceLastSync should pull accounts from provider"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)
            def account = IntegrationAccount.builder().name("Cash").build()
            account.setExternalId("acct-1")
            def accounts = [account]

        when:
            def result = activities.fetchAccountsSinceLastSync(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ACCOUNT) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.pullBatch(connection) >> accounts
            result.size() == 1
    }

    def "storeAccounts should upsert accounts"() {
        given:
            def connectionData = createConnectionData(id: "conn-1")
            def account = IntegrationAccount.builder().name("Cash").build()
            account.setExternalId("acct-1")
            def accounts = [account]

        when:
            activities.storeAccounts(connectionData, accounts)

        then:
            1 * integrationAccountService.upsert("conn-1", accounts)
    }

    def "fetchPaymentTermsSinceLastSync should pull payment terms from provider"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)
            def terms = [
                PaymentTerm.builder().name("Net 30").build()
            ]

        when:
            def result = activities.fetchPaymentTermsSinceLastSync(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PAYMENT_TERM) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.pullBatch(connection) >> terms
            result.size() == 1
    }

    def "storePaymentTerms should upsert payment terms"() {
        given:
            def connectionData = createConnectionData(
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )
            def terms = [
                PaymentTerm.builder().name("Net 30").build()
            ]

        when:
            activities.storePaymentTerms(connectionData, terms)

        then:
            1 * paymentTermService.upsertFromProvider(100L, "QUICKBOOKS", terms)
    }

    def "fetchItemsSinceLastSync should pull items from provider"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)
            def items = [
                Item.builder().id("item-1").name("Widget").build()
            ]

        when:
            def result = activities.fetchItemsSinceLastSync(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.pullBatch(connection) >> items
            result.size() == 1
    }

    def "storeItems should upsert items"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L
            )
            def items = [
                Item.builder().id("item-1").name("Widget").build()
            ]

        when:
            activities.storeItems(connectionData, items)

        then:
            1 * itemService.upsertFromProvider(100L, "conn-1", items)
    }

    def "fetchPurchaseOrdersSinceLastSync should pull purchase orders from provider"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)
            def pos = [
                PurchaseOrder.builder().id("po-1").displayId("PO-001").build()
            ]

        when:
            def result = activities.fetchPurchaseOrdersSinceLastSync(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.pullBatch(connection) >> pos
            result.size() == 1
    }

    def "storePurchaseOrders should resolve line items and upsert purchase orders"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L
            )
            def pos = [
                PurchaseOrder.builder().id("po-1").displayId("PO-001").build()
            ]

        when:
            activities.storePurchaseOrders(connectionData, pos)

        then:
            1 * lineItemResolver.resolveLineItemReferences("conn-1", pos)
            1 * purchaseOrderSyncService.upsertFromProvider(100L, pos)
    }

    def "updateLastSyncAt should update connection and return updated data"() {
        given:
            def timestamp = OffsetDateTime.now()
            def updatedConnection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .lastSyncAt(timestamp)
                .build()

        when:
            def result = activities.updateLastSyncAt("conn-1", timestamp)

        then:
            1 * connectionRepository.updateLastSyncAt("conn-1", timestamp) >> updatedConnection
            result.id == "conn-1"
            result.companyId == 100L
    }

    def "getCurrentTime should return current timestamp"() {
        when:
            def result = activities.getCurrentTime()

        then:
            result != null
            result instanceof OffsetDateTime
    }

    def "syncPreferences should update preferences and company currency"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)
            def prefs = Preferences.builder()
                .defaultCurrency(Currency.USD)
                .multicurrencyEnabled(true)
                .build()

        when:
            activities.syncPreferences(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PREFERENCES) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.pullBatch(connection) >> [prefs]
            1 * connectionRepository.updatePreferences("conn-1", prefs)
            1 * companySyncService.updateCurrencyFromIntegration(100L, Currency.USD, true)
    }

    def "syncPreferences should handle empty preferences list gracefully"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)

        when:
            activities.syncPreferences(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PREFERENCES) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.pullBatch(connection) >> []
            0 * connectionRepository.updatePreferences(_, _)
            0 * companySyncService.updateCurrencyFromIntegration(_, _, _)
    }

    def "syncPreferences should handle null preferences list gracefully"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)

        when:
            activities.syncPreferences(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PREFERENCES) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.pullBatch(connection) >> null
            0 * connectionRepository.updatePreferences(_, _)
            0 * companySyncService.updateCurrencyFromIntegration(_, _, _)
    }

    def "syncPreferences should handle exception gracefully"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

        when:
            activities.syncPreferences(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PREFERENCES) >> { throw new RuntimeException("Provider error") }
            notThrown(Exception)
    }

    def "syncPreferences should handle preferences with null currency"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pullProvider = Mock(IntegrationPullProvider)
            def prefs = Preferences.builder()
                .defaultCurrency(null)
                .multicurrencyEnabled(false)
                .build()

        when:
            activities.syncPreferences(connectionData)

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PREFERENCES) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.pullBatch(connection) >> [prefs]
            1 * connectionRepository.updatePreferences("conn-1", prefs)
            1 * companySyncService.updateCurrencyFromIntegration(100L, null, false)
    }
}

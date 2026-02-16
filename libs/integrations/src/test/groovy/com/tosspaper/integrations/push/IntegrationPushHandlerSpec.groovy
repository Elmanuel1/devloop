package com.tosspaper.integrations.push

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.tosspaper.integrations.common.IntegrationPushCoordinator
import com.tosspaper.integrations.common.SyncResult
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPullProvider
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.domain.Invoice
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.integration.Item
import com.tosspaper.models.service.ContactSyncService
import com.tosspaper.models.service.InvoiceSyncService
import com.tosspaper.models.service.ItemService
import com.tosspaper.models.service.PurchaseOrderSyncService
import com.tosspaper.models.service.SenderNotificationService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Comprehensive tests for IntegrationPushHandler.
 * Tests all push event handling methods and entity type routing.
 */
class IntegrationPushHandlerSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
    IntegrationConnectionService connectionService = Mock()
    IntegrationPushCoordinator pushCoordinator = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    ContactSyncService contactSyncService = Mock()
    ItemService itemService = Mock()
    PurchaseOrderSyncService purchaseOrderSyncService = Mock()
    InvoiceSyncService invoiceSyncService = Mock()
    SenderNotificationService senderNotificationService = Mock()

    @Subject
    IntegrationPushHandler handler = new IntegrationPushHandler(
        objectMapper,
        connectionService,
        pushCoordinator,
        providerFactory,
        contactSyncService,
        itemService,
        purchaseOrderSyncService,
        invoiceSyncService,
        senderNotificationService
    )

    def "getQueueName returns correct queue name"() {
        expect:
        handler.getQueueName() == "integration-push-events"
    }

    def "handle should process message with valid payload"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .name("Test Vendor")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .accessToken("token")
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(vendor),
            "user@test.com"
        )

        def message = [message: objectMapper.writeValueAsString(event)]

        def syncResult = SyncResult.success("ext-123", null, "1", OffsetDateTime.now())

        when:
        handler.handle(message)

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.VENDOR, _) >> syncResult
        1 * contactSyncService.updateSyncStatus("vendor-123", "quickbooks", "ext-123", "1", _)
    }

    def "handle should log warning when payload is missing"() {
        given:
        def message = [message: null]

        when:
        handler.handle(message)

        then:
        0 * connectionService._
        0 * pushCoordinator._
    }

    def "handle should log warning when payload is blank"() {
        given:
        def message = [message: ""]

        when:
        handler.handle(message)

        then:
        0 * connectionService._
        0 * pushCoordinator._
    }

    def "processPushEvent should successfully push vendor"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .name("ACME Corp")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(vendor),
            "user@test.com"
        )

        def syncResult = SyncResult.success("ext-456", null, "2", OffsetDateTime.now())

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.VENDOR, _) >> syncResult
        1 * contactSyncService.updateSyncStatus("vendor-123", "quickbooks", "ext-456", "2", _)
    }

    def "processPushEvent should handle vendor push conflict"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .name("Conflict Vendor")
            .build()
        vendor.setExternalId("ext-123")

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(vendor),
            "user@test.com"
        )

        // Conflict result must include externalId so pullVendor can fetch by it
        def conflictResult = SyncResult.builder()
            .success(false)
            .conflictDetected(true)
            .externalId("ext-123")
            .errorMessage("Version mismatch")
            .build()
        def pullProvider = Mock(IntegrationPullProvider)
        def pulledVendor = Party.builder().id("vendor-123").name("Updated Vendor").build()

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.VENDOR, _) >> conflictResult
        1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pullProvider)
        1 * pullProvider.isEnabled() >> true
        1 * pullProvider.getById("ext-123", connection) >> pulledVendor
        1 * contactSyncService.upsertFromProvider(100L, [pulledVendor])
        1 * senderNotificationService.sendSyncConflictNotification(_)
    }

    def "processPushEvent should skip pull when result external ID is missing on conflict"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .name("No External ID Vendor")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(vendor),
            "user@test.com"
        )

        // Conflict result without externalId - pullVendor will skip due to null externalId check
        def conflictResult = SyncResult.conflict("Version mismatch")
        def pullProvider = Mock(IntegrationPullProvider)

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.VENDOR, _) >> conflictResult
        1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pullProvider)
        0 * pullProvider.getById(_, _)
    }

    def "processPushEvent should successfully push item"() {
        given:
        def item = Item.builder()
            .id("item-123")
            .name("Widget")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.ITEM,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(item),
            "user@test.com"
        )

        def syncResult = SyncResult.success("item-ext-789", null, "1", OffsetDateTime.now())

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.ITEM, _) >> syncResult
        1 * itemService.updateSyncStatus("item-123", "quickbooks", "item-ext-789", "1", _)
    }

    def "processPushEvent should handle item push conflict and pull latest"() {
        given:
        def item = Item.builder()
            .id("item-123")
            .name("Conflicted Item")
            .build()
        item.setExternalId("item-ext-123")

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.ITEM,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(item),
            "user@test.com"
        )

        // Conflict result must include externalId so pullItem can fetch by it
        def conflictResult = SyncResult.builder()
            .success(false)
            .conflictDetected(true)
            .externalId("item-ext-123")
            .errorMessage("Item version conflict")
            .build()
        def pullProvider = Mock(IntegrationPullProvider)
        def pulledItem = Item.builder().id("item-123").name("Updated Item").build()

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.ITEM, _) >> conflictResult
        1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pullProvider)
        1 * pullProvider.isEnabled() >> true
        1 * pullProvider.getById("item-ext-123", connection) >> pulledItem
        1 * itemService.upsertFromProvider(100L, "conn-1", [pulledItem])
        1 * senderNotificationService.sendSyncConflictNotification(_)
    }

    def "processPushEvent should successfully push customer (job location)"() {
        given:
        def customer = Party.builder()
            .id("customer-123")
            .name("Job Site A")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.JOB_LOCATION,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(customer),
            "user@test.com"
        )

        def syncResult = SyncResult.success("cust-ext-999", null, "1", OffsetDateTime.now())

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.JOB_LOCATION, _) >> syncResult
        1 * contactSyncService.updateSyncStatus("customer-123", "quickbooks", "cust-ext-999", "1", _)
    }

    def "processPushEvent should successfully push purchase order"() {
        given:
        def po = PurchaseOrder.builder()
            .id("po-123")
            .displayId("PO-001")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.PURCHASE_ORDER,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(po),
            "user@test.com"
        )

        def syncResult = SyncResult.success("po-ext-555", null, "3", OffsetDateTime.now())

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, _) >> syncResult
        1 * purchaseOrderSyncService.updateSyncStatus("po-123", "po-ext-555", "3", _)
    }

    def "processPushEvent should handle PO push conflict and pull latest"() {
        given:
        def po = PurchaseOrder.builder()
            .id("po-123")
            .displayId("PO-CONFLICT")
            .build()
        po.setExternalId("po-ext-123")

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.PURCHASE_ORDER,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(po),
            "user@test.com"
        )

        // Conflict result must include externalId so pullPurchaseOrder can fetch by it
        def conflictResult = SyncResult.builder()
            .success(false)
            .conflictDetected(true)
            .externalId("po-ext-123")
            .errorMessage("PO already modified")
            .build()
        def pullProvider = Mock(IntegrationPullProvider)
        def pulledPO = PurchaseOrder.builder().id("po-123").displayId("PO-CONFLICT").build()

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, _) >> conflictResult
        1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.of(pullProvider)
        1 * pullProvider.isEnabled() >> true
        1 * pullProvider.getById("po-ext-123", connection) >> pulledPO
        1 * purchaseOrderSyncService.upsertFromProvider(100L, [pulledPO])
        // For PO, notifyConflict is called outside pullProviderOpt.isPresent() block
        1 * senderNotificationService.sendSyncConflictNotification(_)
    }

    def "processPushEvent should successfully push bill (invoice)"() {
        given:
        def invoice = Invoice.builder()
            .assignedId("inv-123")
            .build()
        invoice.setExternalId("bill-ext-777")

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.BILL,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(invoice),
            "user@test.com"
        )

        def syncResult = SyncResult.success("bill-ext-888", null, "1", OffsetDateTime.now())

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.BILL, _) >> syncResult
        1 * invoiceSyncService.markAsPushed(_)
    }

    def "processPushEvent should log error on failed bill push"() {
        given:
        def invoice = Invoice.builder()
            .assignedId("inv-fail")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.BILL,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(invoice),
            "user@test.com"
        )

        def failureResult = SyncResult.failure("Bill validation failed", false)

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.BILL, _) >> failureResult
        0 * invoiceSyncService.markAsPushed(_)
    }

    def "processPushEvent should handle invalid JSON gracefully"() {
        given:
        def message = [message: "{ invalid json }"]

        when:
        handler.handle(message)

        then:
        0 * connectionService._
        0 * pushCoordinator._
        notThrown(Exception)
    }

    def "processPushEvent should handle exception during vendor push"() {
        given:
        def vendor = Party.builder()
            .id("vendor-err")
            .name("Error Vendor")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(vendor),
            "user@test.com"
        )

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(_, _, _) >> { throw new RuntimeException("Push failed") }
        0 * contactSyncService.updateSyncStatus(_, _, _, _, _)
        notThrown(Exception)
    }

    def "processPushEvent should skip pull when pull provider is disabled"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .name("Vendor")
            .build()
        vendor.setExternalId("ext-123")

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(vendor),
            "user@test.com"
        )

        // Conflict result with externalId; pull will be skipped because provider is disabled
        def conflictResult = SyncResult.builder()
            .success(false)
            .conflictDetected(true)
            .externalId("ext-123")
            .errorMessage("Conflict")
            .build()
        def pullProvider = Mock(IntegrationPullProvider)

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
        1 * providerFactory.getPullProvider(_, _) >> Optional.of(pullProvider)
        1 * pullProvider.isEnabled() >> false
        0 * pullProvider.getById(_, _)
    }

    def "processPushEvent should skip pull when pull provider not found"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .name("Vendor")
            .build()
        vendor.setExternalId("ext-123")

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(vendor),
            "user@test.com"
        )

        // Conflict result with externalId; pull will be skipped because no pull provider
        def conflictResult = SyncResult.builder()
            .success(false)
            .conflictDetected(true)
            .externalId("ext-123")
            .errorMessage("Conflict")
            .build()

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
        1 * providerFactory.getPullProvider(_, _) >> Optional.empty()
    }

    def "processPushEvent should handle null vendor returned from pull provider"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .name("Vendor")
            .build()
        vendor.setExternalId("ext-not-found")

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(vendor),
            "user@test.com"
        )

        // Conflict result with externalId that doesn't match anything in provider
        def conflictResult = SyncResult.builder()
            .success(false)
            .conflictDetected(true)
            .externalId("ext-not-found")
            .errorMessage("Conflict")
            .build()
        def pullProvider = Mock(IntegrationPullProvider)

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
        1 * providerFactory.getPullProvider(_, _) >> Optional.of(pullProvider)
        1 * pullProvider.isEnabled() >> true
        1 * pullProvider.getById("ext-not-found", connection) >> null
        0 * contactSyncService.upsertFromProvider(_, _)
    }

    def "processPushEvent should handle notification failure gracefully"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .name("Vendor")
            .build()
        vendor.setExternalId("ext-123")

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        def event = new IntegrationPushEvent(
            IntegrationProvider.QUICKBOOKS,
            IntegrationEntityType.VENDOR,
            100L,
            "conn-1",
            objectMapper.writeValueAsString(vendor),
            "user@test.com"
        )

        // Conflict result with externalId for pull
        def conflictResult = SyncResult.builder()
            .success(false)
            .conflictDetected(true)
            .externalId("ext-123")
            .errorMessage("Conflict")
            .build()
        def pullProvider = Mock(IntegrationPullProvider)
        def pulledVendor = Party.builder().id("vendor-123").name("Updated").build()

        when:
        handler.handle([message: objectMapper.writeValueAsString(event)])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
        1 * providerFactory.getPullProvider(_, _) >> Optional.of(pullProvider)
        1 * pullProvider.isEnabled() >> true
        1 * pullProvider.getById("ext-123", _) >> pulledVendor
        1 * contactSyncService.upsertFromProvider(_, _)
        1 * senderNotificationService.sendSyncConflictNotification(_) >> { throw new RuntimeException("Notification failed") }
        notThrown(Exception)
    }

    // ==================== Vendor Push Failure (non-conflict, non-success) ====================

    def "processPushEvent should log error on failed vendor push (non-conflict)"() {
        given: "a vendor push that fails without conflict"
            def vendor = Party.builder()
                .id("vendor-fail")
                .name("Fail Vendor")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.VENDOR,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(vendor),
                "user@test.com"
            )

            def failResult = SyncResult.failure("Some vendor error", true)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "push fails but does not throw"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.VENDOR, _) >> failResult
            0 * contactSyncService.updateSyncStatus(_, _, _, _, _)
            notThrown(Exception)
    }

    // ==================== Customer Push Tests ====================

    def "processPushEvent should handle customer push conflict and pull latest"() {
        given: "a customer push that results in conflict"
            def customer = Party.builder()
                .id("cust-123")
                .name("Conflicted Customer")
                .build()
            customer.setExternalId("cust-ext-123")

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.JOB_LOCATION,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(customer),
                "user@test.com"
            )

            def conflictResult = SyncResult.builder()
                .success(false)
                .conflictDetected(true)
                .externalId("cust-ext-123")
                .errorMessage("Customer version mismatch")
                .build()
            def pullProvider = Mock(IntegrationPullProvider)
            def pulledCustomer = Party.builder().id("cust-123").name("Updated Customer").build()

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "conflict is detected, pull and notify are invoked"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.JOB_LOCATION, _) >> conflictResult
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.getById("cust-ext-123", connection) >> pulledCustomer
            1 * contactSyncService.upsertFromProvider(100L, [pulledCustomer])
            1 * senderNotificationService.sendSyncConflictNotification(_)
    }

    def "processPushEvent should log error on failed customer push (non-conflict)"() {
        given: "a customer push that fails"
            def customer = Party.builder()
                .id("cust-fail")
                .name("Fail Customer")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.JOB_LOCATION,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(customer),
                "user@test.com"
            )

            def failResult = SyncResult.failure("Customer push failed", true)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "push fails but does not throw"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.JOB_LOCATION, _) >> failResult
            0 * contactSyncService.updateSyncStatus(_, _, _, _, _)
            notThrown(Exception)
    }

    def "processPushEvent should handle exception during customer push"() {
        given: "a customer push that throws"
            def customer = Party.builder()
                .id("cust-err")
                .name("Error Customer")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.JOB_LOCATION,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(customer),
                "user@test.com"
            )

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "exception is caught and does not propagate"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> { throw new RuntimeException("Customer push error") }
            notThrown(Exception)
    }

    // ==================== Item Push Failure and Exception ====================

    def "processPushEvent should log error on failed item push (non-conflict)"() {
        given: "an item push that fails"
            def item = Item.builder()
                .id("item-fail")
                .name("Fail Item")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.ITEM,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(item),
                "user@test.com"
            )

            def failResult = SyncResult.failure("Item push failed", true)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "push fails but does not throw"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.ITEM, _) >> failResult
            0 * itemService.updateSyncStatus(_, _, _, _, _)
            notThrown(Exception)
    }

    def "processPushEvent should handle exception during item push"() {
        given: "an item push that throws"
            def item = Item.builder()
                .id("item-err")
                .name("Error Item")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.ITEM,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(item),
                "user@test.com"
            )

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "exception is caught"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> { throw new RuntimeException("Item error") }
            notThrown(Exception)
    }

    // ==================== PO Push Failure and Exception ====================

    def "processPushEvent should log error on failed PO push (non-conflict)"() {
        given: "a PO push that fails"
            def po = PurchaseOrder.builder()
                .id("po-fail")
                .displayId("PO-FAIL")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.PURCHASE_ORDER,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(po),
                "user@test.com"
            )

            def failResult = SyncResult.failure("PO push failed", true)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "push fails but does not throw"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, _) >> failResult
            0 * purchaseOrderSyncService.updateSyncStatus(_, _, _, _)
            notThrown(Exception)
    }

    def "processPushEvent should handle exception during PO push"() {
        given: "a PO push that throws"
            def po = PurchaseOrder.builder()
                .id("po-err")
                .displayId("PO-ERR")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.PURCHASE_ORDER,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(po),
                "user@test.com"
            )

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "exception is caught"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> { throw new RuntimeException("PO error") }
            notThrown(Exception)
    }

    // ==================== Bill Push Exception ====================

    def "processPushEvent should handle exception during bill push"() {
        given: "a bill push that throws"
            def invoice = Invoice.builder()
                .assignedId("inv-err")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.BILL,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(invoice),
                "user@test.com"
            )

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "exception is caught"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> { throw new RuntimeException("Bill error") }
            notThrown(Exception)
    }

    // ==================== Pull Customer Edge Cases ====================

    def "processPushEvent should handle customer conflict with null externalId in pull"() {
        given: "a customer push conflict where result has no externalId"
            def customer = Party.builder()
                .id("cust-123")
                .name("No ExtID Customer")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.JOB_LOCATION,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(customer),
                "user@test.com"
            )

            def conflictResult = SyncResult.conflict("Customer version mismatch")
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "pull is skipped because externalId is null"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.of(pullProvider)
            0 * pullProvider.getById(_, _)
    }

    def "processPushEvent should handle customer conflict when pull provider disabled"() {
        given: "a customer push conflict with pull provider disabled"
            def customer = Party.builder()
                .id("cust-123")
                .name("Customer")
                .build()
            customer.setExternalId("cust-ext-1")

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.JOB_LOCATION,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(customer),
                "user@test.com"
            )

            def conflictResult = SyncResult.builder()
                .success(false)
                .conflictDetected(true)
                .externalId("cust-ext-1")
                .errorMessage("Conflict")
                .build()
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "pull provider is disabled, getById is not called"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> false
            0 * pullProvider.getById(_, _)
    }

    def "processPushEvent should handle null customer from pull provider"() {
        given: "a customer pull returns null"
            def customer = Party.builder()
                .id("cust-123")
                .name("Customer")
                .build()
            customer.setExternalId("cust-ext-gone")

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.JOB_LOCATION,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(customer),
                "user@test.com"
            )

            def conflictResult = SyncResult.builder()
                .success(false)
                .conflictDetected(true)
                .externalId("cust-ext-gone")
                .errorMessage("Conflict")
                .build()
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "pull returns null, upsert not called"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(_, _) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.getById("cust-ext-gone", connection) >> null
            0 * contactSyncService.upsertFromProvider(_, _)
    }

    // ==================== Pull Item Edge Cases ====================

    def "processPushEvent should skip item pull when externalId is null on conflict"() {
        given: "an item push conflict where result has no externalId"
            def item = Item.builder()
                .id("item-123")
                .name("No ExtID Item")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.ITEM,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(item),
                "user@test.com"
            )

            def conflictResult = SyncResult.conflict("Item conflict")
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "pull is skipped because externalId is null"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pullProvider)
            0 * pullProvider.getById(_, _)
    }

    def "processPushEvent should skip item pull when pull provider disabled"() {
        given: "an item conflict with disabled pull provider"
            def item = Item.builder()
                .id("item-123")
                .name("Item")
                .build()
            item.setExternalId("item-ext-1")

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.ITEM,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(item),
                "user@test.com"
            )

            def conflictResult = SyncResult.builder()
                .success(false)
                .conflictDetected(true)
                .externalId("item-ext-1")
                .errorMessage("Item conflict")
                .build()
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "pull provider is disabled"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(_, _) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> false
            0 * pullProvider.getById(_, _)
    }

    def "processPushEvent should handle null item from pull provider"() {
        given: "an item pull returns null"
            def item = Item.builder()
                .id("item-123")
                .name("Item")
                .build()
            item.setExternalId("item-ext-gone")

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.ITEM,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(item),
                "user@test.com"
            )

            def conflictResult = SyncResult.builder()
                .success(false)
                .conflictDetected(true)
                .externalId("item-ext-gone")
                .errorMessage("Item conflict")
                .build()
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "null returned, upsert not called"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(_, _) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.getById("item-ext-gone", connection) >> null
            0 * itemService.upsertFromProvider(_, _, _)
    }

    // ==================== Pull PO Edge Cases ====================

    def "processPushEvent should skip PO pull when externalId is null on conflict"() {
        given: "a PO push conflict with no externalId in result"
            def po = PurchaseOrder.builder()
                .id("po-123")
                .displayId("PO-NULL-EXT")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.PURCHASE_ORDER,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(po),
                "user@test.com"
            )

            def conflictResult = SyncResult.conflict("PO conflict")
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "pull skipped because externalId is null"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.of(pullProvider)
            0 * pullProvider.getById(_, _)
            // notifyConflict is still called for PO since it's outside the pull block
            1 * senderNotificationService.sendSyncConflictNotification(_)
    }

    def "processPushEvent should skip PO pull when pull provider disabled"() {
        given: "a PO conflict with disabled pull provider"
            def po = PurchaseOrder.builder()
                .id("po-123")
                .displayId("PO-DISABLED")
                .build()
            po.setExternalId("po-ext-1")

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.PURCHASE_ORDER,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(po),
                "user@test.com"
            )

            def conflictResult = SyncResult.builder()
                .success(false)
                .conflictDetected(true)
                .externalId("po-ext-1")
                .errorMessage("PO conflict")
                .build()
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "pull is skipped because disabled"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(_, _) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> false
            0 * pullProvider.getById(_, _)
            1 * senderNotificationService.sendSyncConflictNotification(_)
    }

    def "processPushEvent should handle null PO from pull provider"() {
        given: "PO pull returns null"
            def po = PurchaseOrder.builder()
                .id("po-123")
                .displayId("PO-NULL")
                .build()
            po.setExternalId("po-ext-gone")

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.PURCHASE_ORDER,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(po),
                "user@test.com"
            )

            def conflictResult = SyncResult.builder()
                .success(false)
                .conflictDetected(true)
                .externalId("po-ext-gone")
                .errorMessage("PO conflict")
                .build()
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "pull returns null, upsert not called"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(_, _) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.getById("po-ext-gone", connection) >> null
            0 * purchaseOrderSyncService.upsertFromProvider(_, _)
            1 * senderNotificationService.sendSyncConflictNotification(_)
    }

    // ==================== Pull Exception Handling ====================

    def "processPushEvent should handle exception during vendor pull gracefully"() {
        given: "a vendor pull that throws"
            def vendor = Party.builder()
                .id("vendor-123")
                .name("Vendor")
                .build()
            vendor.setExternalId("ext-123")

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def event = new IntegrationPushEvent(
                IntegrationProvider.QUICKBOOKS,
                IntegrationEntityType.VENDOR,
                100L,
                "conn-1",
                objectMapper.writeValueAsString(vendor),
                "user@test.com"
            )

            def conflictResult = SyncResult.builder()
                .success(false)
                .conflictDetected(true)
                .externalId("ext-123")
                .errorMessage("Conflict")
                .build()
            def pullProvider = Mock(IntegrationPullProvider)

        when: "handling push event"
            handler.handle([message: objectMapper.writeValueAsString(event)])

        then: "pull exception is caught, does not propagate"
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushWithDependencies(_, _, _) >> conflictResult
            1 * providerFactory.getPullProvider(_, _) >> Optional.of(pullProvider)
            1 * pullProvider.isEnabled() >> true
            1 * pullProvider.getById("ext-123", connection) >> { throw new RuntimeException("Pull failed") }
            notThrown(Exception)
    }
}

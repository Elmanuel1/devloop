package com.tosspaper.purchaseorder

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.BadRequestException
import com.tosspaper.models.exception.ForbiddenException
import com.tosspaper.common.NotFoundException
import com.tosspaper.company.CompanyRepository
import com.tosspaper.contact.ContactService
import com.tosspaper.generated.model.Contact
import com.tosspaper.generated.model.PurchaseOrder
import com.tosspaper.generated.model.PurchaseOrderCreate
import com.tosspaper.generated.model.PurchaseOrderItem
import com.tosspaper.generated.model.PurchaseOrderList
import com.tosspaper.generated.model.PurchaseOrderStatus
import com.tosspaper.generated.model.PurchaseOrderStatusUpdate
import com.tosspaper.generated.model.PurchaseOrderUpdate
import com.tosspaper.ingestion.VectorStoreIngestionPublisher
import com.tosspaper.integrations.push.IntegrationPushStreamPublisher
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.domain.integration.IntegrationCategory
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.jooq.tables.pojos.PurchaseOrderItems
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import com.tosspaper.models.jooq.tables.records.ProjectsRecord
import com.tosspaper.models.jooq.tables.records.PurchaseOrderFlatItemsRecord
import com.tosspaper.models.jooq.tables.records.PurchaseOrdersRecord
import com.tosspaper.project.ProjectRepository
import com.tosspaper.purchaseorder.model.PurchaseOrderQuery
import spock.lang.Specification

import java.time.OffsetDateTime

class PurchaseOrderServiceSpec extends Specification {

    PurchaseOrderRepository purchaseOrderRepository
    ProjectRepository projectRepository
    PurchaseOrderMapper purchaseOrderMapper
    ObjectMapper objectMapper
    CompanyRepository companyRepository
    VectorStoreIngestionPublisher vectorStoreIngestionPublisher
    IntegrationPushStreamPublisher integrationPushStreamPublisher
    IntegrationConnectionService integrationConnectionService
    ContactService contactService
    PurchaseOrderServiceImpl service

    def setup() {
        purchaseOrderRepository = Mock()
        projectRepository = Mock()
        purchaseOrderMapper = Mock()
        objectMapper = new ObjectMapper()
        companyRepository = Mock()
        vectorStoreIngestionPublisher = Mock()
        integrationPushStreamPublisher = Mock()
        integrationConnectionService = Mock()
        contactService = Mock()
        service = new PurchaseOrderServiceImpl(
            purchaseOrderRepository,
            projectRepository,
            purchaseOrderMapper,
            objectMapper,
            companyRepository,
            vectorStoreIngestionPublisher,
            integrationPushStreamPublisher,
            integrationConnectionService,
            contactService
        )
    }

    // ==================== getPurchaseOrder ====================

    def "getPurchaseOrder returns purchase order when found and company matches"() {
        given: "an existing purchase order"
            def companyId = 1L
            def poId = "po-123"
            def flatRecords = [createFlatRecord(poId, companyId)]
            def purchaseOrder = createPurchaseOrder(poId, companyId)

        when: "fetching purchase order by ID"
            def result = service.getPurchaseOrder(companyId, poId)

        then: "repository returns records"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords

        and: "records are mapped"
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [purchaseOrder]

        and: "result has correct fields"
            with(result) {
                id == poId
                it.companyId == companyId
            }
    }

    def "getPurchaseOrder throws NotFoundException when not found"() {
        given: "non-existent purchase order"
            def companyId = 1L
            def poId = "non-existent"

        when: "fetching purchase order"
            service.getPurchaseOrder(companyId, poId)

        then: "repository returns empty list"
            1 * purchaseOrderRepository.findById(poId) >> []
            1 * purchaseOrderMapper.fromFlatRecords([]) >> []

        and: "NotFoundException is thrown"
            thrown(NotFoundException)
    }

    def "getPurchaseOrder throws ForbiddenException when company does not match"() {
        given: "a purchase order from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def poId = "po-123"
            def flatRecords = [createFlatRecord(poId, differentCompanyId)]
            def purchaseOrder = createPurchaseOrder(poId, differentCompanyId)

        when: "fetching purchase order"
            service.getPurchaseOrder(companyId, poId)

        then: "repository returns records"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [purchaseOrder]

        and: "ForbiddenException is thrown"
            thrown(ForbiddenException)
    }

    // ==================== getPurchaseOrdersByProjectId ====================

    def "getPurchaseOrdersByProjectId returns paginated list"() {
        given: "purchase orders for a project"
            def companyId = 1L
            def projectId = "proj-1"
            def records = [createPurchaseOrderRecord("po-1", companyId)]
            def purchaseOrders = [createPurchaseOrder("po-1", companyId)]

        when: "fetching purchase orders"
            def result = service.getPurchaseOrdersByProjectId(companyId, projectId, null, null, null, null, null, 1, 20, null)

        then: "repository is called with query"
            1 * purchaseOrderRepository.find(companyId, _ as PurchaseOrderQuery) >> { Long cId, PurchaseOrderQuery q ->
                assert q.projectId == projectId
                assert q.page == 1
                assert q.pageSize == 20
                return records
            }

        and: "count is fetched for pagination"
            1 * purchaseOrderRepository.count(companyId, _ as PurchaseOrderQuery) >> 1

        and: "records are mapped"
            1 * purchaseOrderMapper.toDtoListWithoutItems(records) >> purchaseOrders

        and: "result contains pagination"
            with(result) {
                data.size() == 1
                data[0].id == "po-1"
                pagination.page == 1
                pagination.pageSize == 20
                pagination.totalItems == 1
            }
    }

    def "getPurchaseOrdersByProjectId uses default pagination when not provided"() {
        given: "no pagination parameters"
            def companyId = 1L

        when: "fetching purchase orders without page/pageSize"
            service.getPurchaseOrdersByProjectId(companyId, null, null, null, null, null, null, null, null, null)

        then: "repository is called with defaults"
            1 * purchaseOrderRepository.find(companyId, _ as PurchaseOrderQuery) >> { Long cId, PurchaseOrderQuery q ->
                assert q.page == 1
                assert q.pageSize == 20
                return []
            }
            1 * purchaseOrderRepository.count(companyId, _) >> 0
            1 * purchaseOrderMapper.toDtoListWithoutItems([]) >> []
    }

    def "getPurchaseOrdersByProjectId filters by status"() {
        given: "status filter"
            def companyId = 1L
            def status = com.tosspaper.purchaseorder.model.PurchaseOrderStatus.PENDING

        when: "fetching purchase orders with status filter"
            service.getPurchaseOrdersByProjectId(companyId, null, null, status, null, null, null, 1, 20, null)

        then: "repository is called with status"
            1 * purchaseOrderRepository.find(companyId, _ as PurchaseOrderQuery) >> { Long cId, PurchaseOrderQuery q ->
                assert q.status == "pending"
                return []
            }
            1 * purchaseOrderRepository.count(companyId, _) >> 0
            1 * purchaseOrderMapper.toDtoListWithoutItems([]) >> []
    }

    // ==================== createPurchaseOrder ====================

    def "createPurchaseOrder creates PO and publishes events"() {
        given: "a create request with valid items"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = [createItem(100.0, 2)]
            createRequest.vendorContact = createContactDto("vendor-1")
            createRequest.shipToContact = createContactDto("shipto-1")

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)
            def company = createCompanyRecord(companyId)

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "no accounting connection for currency validation"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.empty()

        and: "project is fetched"
            1 * projectRepository.findById(projectId) >> project

        and: "record is mapped"
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderMapper.toItemsPojos(createRequest.items) >> [new PurchaseOrderItems()]

        and: "record is created"
            1 * purchaseOrderRepository.create(record, _) >> record

        and: "result is mapped"
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder

        and: "company is fetched for vector ingestion"
            1 * companyRepository.findById(companyId) >> company

        and: "vector store event is published"
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(companyId, projectId, purchaseOrder, _, _, _)

        and: "integration connections are checked"
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == "po-new"
    }

    def "createPurchaseOrder allows zero total price (free samples, promotional items)"() {
        given: "a create request with zero total"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = [createItem(0.0, 5)]
            createRequest.vendorContact = createContactDto("vendor-1")

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)
            def company = createCompanyRecord(companyId)

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "no accounting connection"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.empty()

        and: "project is fetched and PO is created (zero total is allowed)"
            1 * projectRepository.findById(projectId) >> project
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderMapper.toItemsPojos(_) >> []
            1 * purchaseOrderRepository.create(record, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == "po-new"
    }

    def "createPurchaseOrder validates vendor currency when multicurrency disabled"() {
        given: "a vendor with non-default currency"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = [createItem(100.0, 1)]
            def vendorContact = createContactDto("vendor-1")
            vendorContact.currencyCode = "EUR"
            createRequest.vendorContact = vendorContact

            def connection = Mock(IntegrationConnection) {
                getMulticurrencyEnabled() >> false
                getDefaultCurrency() >> Currency.USD
                getProvider() >> IntegrationProvider.QUICKBOOKS
            }

            def fetchedVendor = createContactDto("vendor-1")
            fetchedVendor.currencyCode = "EUR"

        when: "creating purchase order"
            service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "accounting connection exists"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)

        and: "vendor is fetched for currency check"
            1 * contactService.getContactById(companyId, "vendor-1") >> fetchedVendor

        and: "BadRequestException is thrown for currency mismatch"
            def ex = thrown(BadRequestException)
            ex.message.contains("EUR")
            ex.message.contains("USD")
    }

    def "createPurchaseOrder allows any currency when multicurrency enabled"() {
        given: "a vendor with non-default currency and multicurrency enabled"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = [createItem(100.0, 1)]
            def vendorContact = createContactDto("vendor-1")
            vendorContact.currencyCode = "EUR"
            createRequest.vendorContact = vendorContact

            def connection = Mock(IntegrationConnection) {
                getMulticurrencyEnabled() >> true
            }

            def fetchedVendor = createContactDto("vendor-1")
            fetchedVendor.currencyCode = "EUR"

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)
            def company = createCompanyRecord(companyId)

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "multicurrency is enabled"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)

        and: "vendor is fetched for currency check"
            1 * contactService.getContactById(companyId, "vendor-1") >> fetchedVendor

        and: "PO is created without currency error"
            1 * projectRepository.findById(projectId) >> project
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderMapper.toItemsPojos(_) >> []
            1 * purchaseOrderRepository.create(record, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == "po-new"
    }

    // ==================== updatePurchaseOrder ====================

    def "updatePurchaseOrder updates PO and publishes events"() {
        given: "an existing purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.PENDING
            existingPO.items = []

            def updateRequest = new PurchaseOrderUpdate()
            updateRequest.items = [createItem(150.0, 3)]

            def record = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            def company = createCompanyRecord(companyId)

        when: "updating purchase order"
            def result = service.updatePurchaseOrder(companyId, poId, updateRequest, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "record is mapped for update"
            1 * purchaseOrderMapper.toRecord(existingPO) >> record
            1 * purchaseOrderMapper.updateRecordFromDto(updateRequest, record)
            1 * purchaseOrderMapper.toItemsPojos(updateRequest.items) >> [new PurchaseOrderItems()]

        and: "record is updated"
            1 * purchaseOrderRepository.update(record, _, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> updatedPO

        and: "events are published"
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == poId
    }

    def "updatePurchaseOrder throws ForbiddenException for provider-synced PO"() {
        given: "a provider-synced purchase order"
            def companyId = 1L
            def poId = "po-123"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.provider = "QUICKBOOKS"

            def updateRequest = new PurchaseOrderUpdate()

        when: "updating purchase order"
            service.updatePurchaseOrder(companyId, poId, updateRequest, "user@test.com")

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "ForbiddenException is thrown"
            def ex = thrown(ForbiddenException)
            ex.message.contains("provider")
    }

    def "updatePurchaseOrder prevents displayId change on non-PENDING status"() {
        given: "a non-pending purchase order"
            def companyId = 1L
            def poId = "po-123"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.IN_PROGRESS
            existingPO.displayId = "PO-001"
            existingPO.items = []

            def updateRequest = new PurchaseOrderUpdate()
            updateRequest.displayId = "PO-002"

        when: "updating displayId"
            service.updatePurchaseOrder(companyId, poId, updateRequest, "user@test.com")

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "ForbiddenException is thrown"
            thrown(ForbiddenException)
    }

    // ==================== updatePurchaseOrderStatus ====================

    def "updatePurchaseOrderStatus transitions from PENDING to IN_PROGRESS"() {
        given: "a pending purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.PENDING

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.IN_PROGRESS
            statusUpdate.notes = "Starting work"

            def updatedRecord = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.status = PurchaseOrderStatus.IN_PROGRESS
            def company = createCompanyRecord(companyId)

        when: "updating status"
            def result = service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "status is updated"
            1 * purchaseOrderRepository.updateStatus(poId, "in_progress", _) >> updatedRecord
            1 * purchaseOrderMapper.toDto(updatedRecord, _) >> updatedPO

        and: "events are published"
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result has new status"
            result.status == PurchaseOrderStatus.IN_PROGRESS
    }

    def "updatePurchaseOrderStatus returns same PO when status unchanged"() {
        given: "a purchase order with same status"
            def companyId = 1L
            def poId = "po-123"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.IN_PROGRESS

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.IN_PROGRESS

        when: "updating with same status"
            def result = service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, "user@test.com")

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "no update occurs"
            0 * purchaseOrderRepository.updateStatus(_, _, _)

        and: "same PO is returned"
            result.id == poId
    }

    def "updatePurchaseOrderStatus throws BadRequestException for invalid transition from COMPLETED"() {
        given: "a completed purchase order"
            def companyId = 1L
            def poId = "po-123"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.COMPLETED

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.PENDING  // Invalid transition

        when: "attempting invalid transition"
            service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, "user@test.com")

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("completed")
            ex.message.contains("pending")
    }

    def "updatePurchaseOrderStatus allows transition from CANCELLED to PENDING"() {
        given: "a cancelled purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.CANCELLED

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.PENDING

            def updatedRecord = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.status = PurchaseOrderStatus.PENDING
            def company = createCompanyRecord(companyId)

        when: "reactivating cancelled PO"
            def result = service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "status is updated"
            1 * purchaseOrderRepository.updateStatus(poId, "pending", _) >> updatedRecord
            1 * purchaseOrderMapper.toDto(updatedRecord, _) >> updatedPO
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result has new status"
            result.status == PurchaseOrderStatus.PENDING
    }

    // ==================== Coverage Gap Tests - getPurchaseOrder ====================

    def "getPurchaseOrder throws ServiceException when multiple POs found with same ID"() {
        given: "multiple purchase orders with the same ID"
            def companyId = 1L
            def poId = "po-123"
            def flatRecords = [createFlatRecord(poId, companyId), createFlatRecord(poId, companyId)]
            def purchaseOrders = [createPurchaseOrder(poId, companyId), createPurchaseOrder(poId, companyId)]

        when: "fetching purchase order by ID"
            service.getPurchaseOrder(companyId, poId)

        then: "repository returns multiple records"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> purchaseOrders

        and: "ServiceException is thrown"
            def ex = thrown(com.tosspaper.common.exception.ServiceException)
            ex.message.contains("Multiple purchase orders")
    }

    // ==================== Coverage Gap Tests - createPurchaseOrder ====================

    def "createPurchaseOrder handles exception when fetching company for vector ingestion"() {
        given: "a create request with valid items"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = [createItem(100.0, 2)]
            createRequest.vendorContact = createContactDto("vendor-1")

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "no accounting connection"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.empty()

        and: "project is fetched"
            1 * projectRepository.findById(projectId) >> project

        and: "record is mapped and created"
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderMapper.toItemsPojos(_) >> [new PurchaseOrderItems()]
            1 * purchaseOrderRepository.create(record, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder

        and: "company fetch throws exception (logged as warning)"
            1 * companyRepository.findById(companyId) >> { throw new RuntimeException("DB connection failed") }

        and: "vector store event still published with null assignedEmail"
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(companyId, projectId, purchaseOrder, null, _, _)

        and: "integration connections checked"
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned despite company fetch failure"
            result.id == "po-new"
    }

    def "createPurchaseOrder validates with null items list"() {
        given: "a create request with null items"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = null
            createRequest.vendorContact = createContactDto("vendor-1")

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)
            def company = createCompanyRecord(companyId)

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "no accounting connection"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.empty()

        and: "project is fetched and PO is created"
            1 * projectRepository.findById(projectId) >> project
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderRepository.create(record, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == "po-new"
    }

    def "createPurchaseOrder validates with empty items list"() {
        given: "a create request with empty items"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = []
            createRequest.vendorContact = createContactDto("vendor-1")

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)
            def company = createCompanyRecord(companyId)

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "no accounting connection"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.empty()

        and: "project is fetched and PO is created"
            1 * projectRepository.findById(projectId) >> project
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderMapper.toItemsPojos([]) >> []
            1 * purchaseOrderRepository.create(record, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == "po-new"
    }

    def "createPurchaseOrder allows items missing unitPrice or quantity (contribute zero to total)"() {
        given: "a create request with items missing unitPrice"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            def itemWithNoPrice = new PurchaseOrderItem()
            itemWithNoPrice.quantity = 5
            itemWithNoPrice.unitPrice = null
            createRequest.items = [itemWithNoPrice]
            createRequest.vendorContact = createContactDto("vendor-1")

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)
            def company = createCompanyRecord(companyId)

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "no accounting connection"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.empty()

        and: "project is fetched and PO is created (zero total from null unitPrice is allowed)"
            1 * projectRepository.findById(projectId) >> project
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderMapper.toItemsPojos(_) >> []
            1 * purchaseOrderRepository.create(record, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == "po-new"
    }

    def "createPurchaseOrder skips vendor currency validation when vendor has no currency"() {
        given: "a vendor with null currency code"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = [createItem(100.0, 1)]
            def vendorContact = createContactDto("vendor-1")
            createRequest.vendorContact = vendorContact

            def connection = Mock(IntegrationConnection) {
                getMulticurrencyEnabled() >> false
            }

            def fetchedVendor = createContactDto("vendor-1")
            fetchedVendor.currencyCode = null  // No currency set

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)
            def company = createCompanyRecord(companyId)

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "accounting connection exists"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)

        and: "vendor is fetched but has no currency"
            1 * contactService.getContactById(companyId, "vendor-1") >> fetchedVendor

        and: "PO is created (currency validation skipped due to null vendor currency)"
            1 * projectRepository.findById(projectId) >> project
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderMapper.toItemsPojos(_) >> []
            1 * purchaseOrderRepository.create(record, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == "po-new"
    }

    def "createPurchaseOrder allows vendor currency when it matches default currency"() {
        given: "a vendor with matching currency"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = [createItem(100.0, 1)]
            def vendorContact = createContactDto("vendor-1")
            vendorContact.currencyCode = "USD"
            createRequest.vendorContact = vendorContact

            def connection = Mock(IntegrationConnection) {
                getMulticurrencyEnabled() >> false
                getDefaultCurrency() >> Currency.USD
            }

            def fetchedVendor = createContactDto("vendor-1")
            fetchedVendor.currencyCode = "USD"  // Matches default

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)
            def company = createCompanyRecord(companyId)

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "accounting connection exists"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)

        and: "vendor is fetched with matching currency"
            1 * contactService.getContactById(companyId, "vendor-1") >> fetchedVendor

        and: "PO is created (currency matches)"
            1 * projectRepository.findById(projectId) >> project
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderMapper.toItemsPojos(_) >> []
            1 * purchaseOrderRepository.create(record, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == "po-new"
    }

    def "createPurchaseOrder publishes integration push event when active connections exist"() {
        given: "a create request with valid items"
            def companyId = 1L
            def projectId = "proj-1"
            def createRequest = new PurchaseOrderCreate()
            createRequest.items = [createItem(100.0, 2)]
            createRequest.vendorContact = createContactDto("vendor-1")

            def project = createProjectRecord(projectId)
            def record = createPurchaseOrderRecord("po-new", companyId)
            def purchaseOrder = createPurchaseOrder("po-new", companyId)
            def company = createCompanyRecord(companyId)

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> IntegrationProvider.QUICKBOOKS
                getStatus() >> IntegrationConnectionStatus.ENABLED
            }

        when: "creating purchase order"
            def result = service.createPurchaseOrder(companyId, projectId, createRequest)

        then: "no accounting connection for currency validation"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.empty()

        and: "PO is created"
            1 * projectRepository.findById(projectId) >> project
            1 * purchaseOrderMapper.toRecord(companyId, projectId, createRequest) >> record
            1 * purchaseOrderMapper.toItemsPojos(_) >> [new PurchaseOrderItems()]
            1 * purchaseOrderRepository.create(record, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> purchaseOrder
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)

        and: "active integration connections exist"
            1 * integrationConnectionService.listByCompany(companyId) >> [connection]

        and: "integration push event is published"
            1 * integrationPushStreamPublisher.publish(_)

        and: "result is returned"
            result.id == "po-new"
    }

    // ==================== Coverage Gap Tests - updatePurchaseOrder ====================

    def "updatePurchaseOrder handles exception when fetching company for vector ingestion"() {
        given: "an existing purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.PENDING
            existingPO.items = []

            def updateRequest = new PurchaseOrderUpdate()
            updateRequest.items = [createItem(150.0, 3)]

            def record = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)

        when: "updating purchase order"
            def result = service.updatePurchaseOrder(companyId, poId, updateRequest, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "record is updated"
            1 * purchaseOrderMapper.toRecord(existingPO) >> record
            1 * purchaseOrderMapper.updateRecordFromDto(updateRequest, record)
            1 * purchaseOrderMapper.toItemsPojos(updateRequest.items) >> [new PurchaseOrderItems()]
            1 * purchaseOrderRepository.update(record, _, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> updatedPO

        and: "company fetch throws exception (logged as warning)"
            1 * companyRepository.findById(companyId) >> { throw new RuntimeException("DB failure") }

        and: "vector store event still published with null assignedEmail"
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(companyId, _, updatedPO, null, _, _)

        and: "integration connections checked"
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.id == poId
    }

    def "updatePurchaseOrder allows displayId change on PENDING status"() {
        given: "a pending purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.PENDING
            existingPO.displayId = "PO-001"
            existingPO.items = []

            def updateRequest = new PurchaseOrderUpdate()
            updateRequest.displayId = "PO-002"
            updateRequest.items = [createItem(100.0, 1)]
            updateRequest.notes = "Changed display ID"

            def record = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.displayId = "PO-002"
            def company = createCompanyRecord(companyId)

        when: "updating displayId on pending PO"
            def result = service.updatePurchaseOrder(companyId, poId, updateRequest, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "record is updated including displayId"
            1 * purchaseOrderMapper.toRecord(existingPO) >> record
            1 * purchaseOrderMapper.updateRecordFromDto(updateRequest, record)
            1 * purchaseOrderMapper.toItemsPojos(updateRequest.items) >> [new PurchaseOrderItems()]
            1 * purchaseOrderRepository.update(record, _, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> updatedPO
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned with new displayId"
            result.displayId == "PO-002"
    }

    def "updatePurchaseOrder tracks orderDate changes"() {
        given: "an existing purchase order with orderDate"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"
            def oldOrderDate = java.time.LocalDate.now().minusDays(5)
            def newOrderDate = java.time.LocalDate.now()

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.PENDING
            existingPO.orderDate = oldOrderDate
            existingPO.items = []

            def updateRequest = new PurchaseOrderUpdate()
            updateRequest.orderDate = newOrderDate
            updateRequest.items = [createItem(100.0, 1)]

            def record = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.orderDate = newOrderDate
            def company = createCompanyRecord(companyId)

        when: "updating orderDate"
            def result = service.updatePurchaseOrder(companyId, poId, updateRequest, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "record is updated"
            1 * purchaseOrderMapper.toRecord(existingPO) >> record
            1 * purchaseOrderMapper.updateRecordFromDto(updateRequest, record)
            1 * purchaseOrderMapper.toItemsPojos(updateRequest.items) >> [new PurchaseOrderItems()]
            1 * purchaseOrderRepository.update(record, _, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> updatedPO
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.orderDate == newOrderDate
    }

    def "updatePurchaseOrder publishes integration push event when active connections exist"() {
        given: "an existing purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.PENDING
            existingPO.items = []

            def updateRequest = new PurchaseOrderUpdate()
            updateRequest.items = [createItem(150.0, 3)]

            def record = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            def company = createCompanyRecord(companyId)

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> IntegrationProvider.QUICKBOOKS
                getStatus() >> IntegrationConnectionStatus.ENABLED
            }

        when: "updating purchase order"
            def result = service.updatePurchaseOrder(companyId, poId, updateRequest, authorId)

        then: "existing PO is fetched and updated"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]
            1 * purchaseOrderMapper.toRecord(existingPO) >> record
            1 * purchaseOrderMapper.updateRecordFromDto(updateRequest, record)
            1 * purchaseOrderMapper.toItemsPojos(updateRequest.items) >> [new PurchaseOrderItems()]
            1 * purchaseOrderRepository.update(record, _, _) >> record
            1 * purchaseOrderMapper.toDto(record, _) >> updatedPO
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)

        and: "active integration connections exist"
            1 * integrationConnectionService.listByCompany(companyId) >> [connection]

        and: "integration push event is published"
            1 * integrationPushStreamPublisher.publish(_)

        and: "result is returned"
            result.id == poId
    }

    // ==================== Coverage Gap Tests - updatePurchaseOrderStatus ====================

    def "updatePurchaseOrderStatus handles exception when fetching company for vector ingestion"() {
        given: "a pending purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.PENDING

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.IN_PROGRESS

            def updatedRecord = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.status = PurchaseOrderStatus.IN_PROGRESS

        when: "updating status"
            def result = service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "status is updated"
            1 * purchaseOrderRepository.updateStatus(poId, "in_progress", _) >> updatedRecord
            1 * purchaseOrderMapper.toDto(updatedRecord, _) >> updatedPO

        and: "company fetch throws exception (logged as warning)"
            1 * companyRepository.findById(companyId) >> { throw new RuntimeException("DB failure") }

        and: "vector store event still published with null assignedEmail"
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(companyId, _, updatedPO, null, _, _)

        and: "integration connections checked"
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is returned"
            result.status == PurchaseOrderStatus.IN_PROGRESS
    }

    def "updatePurchaseOrderStatus publishes integration push event when active connections exist"() {
        given: "a pending purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.PENDING

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.IN_PROGRESS

            def updatedRecord = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.status = PurchaseOrderStatus.IN_PROGRESS
            def company = createCompanyRecord(companyId)

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> IntegrationProvider.QUICKBOOKS
                getStatus() >> IntegrationConnectionStatus.ENABLED
            }

        when: "updating status"
            def result = service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, authorId)

        then: "existing PO is fetched and status updated"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]
            1 * purchaseOrderRepository.updateStatus(poId, "in_progress", _) >> updatedRecord
            1 * purchaseOrderMapper.toDto(updatedRecord, _) >> updatedPO
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)

        and: "active integration connections exist"
            1 * integrationConnectionService.listByCompany(companyId) >> [connection]

        and: "integration push event is published"
            1 * integrationPushStreamPublisher.publish(_)

        and: "result is returned"
            result.status == PurchaseOrderStatus.IN_PROGRESS
    }

    def "updatePurchaseOrderStatus transitions from IN_PROGRESS to COMPLETED"() {
        given: "an in-progress purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.IN_PROGRESS

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.COMPLETED

            def updatedRecord = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.status = PurchaseOrderStatus.COMPLETED
            def company = createCompanyRecord(companyId)

        when: "completing the PO"
            def result = service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "status is updated to completed"
            1 * purchaseOrderRepository.updateStatus(poId, "completed", _) >> updatedRecord
            1 * purchaseOrderMapper.toDto(updatedRecord, _) >> updatedPO
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result has completed status"
            result.status == PurchaseOrderStatus.COMPLETED
    }

    def "updatePurchaseOrderStatus transitions from IN_PROGRESS to CLOSED"() {
        given: "an in-progress purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.IN_PROGRESS

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.CLOSED

            def updatedRecord = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.status = PurchaseOrderStatus.CLOSED
            def company = createCompanyRecord(companyId)

        when: "closing the PO"
            def result = service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "status is updated to closed"
            1 * purchaseOrderRepository.updateStatus(poId, "closed", _) >> updatedRecord
            1 * purchaseOrderMapper.toDto(updatedRecord, _) >> updatedPO
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result has closed status"
            result.status == PurchaseOrderStatus.CLOSED
    }

    def "updatePurchaseOrderStatus throws BadRequestException for invalid transition from CLOSED"() {
        given: "a closed purchase order"
            def companyId = 1L
            def poId = "po-123"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.CLOSED

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.PENDING

        when: "attempting invalid transition"
            service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, "user@test.com")

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("closed")
    }

    def "updatePurchaseOrderStatus transitions from OPEN to IN_PROGRESS"() {
        given: "an open purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.OPEN

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.IN_PROGRESS

            def updatedRecord = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.status = PurchaseOrderStatus.IN_PROGRESS
            def company = createCompanyRecord(companyId)

        when: "starting work on open PO"
            def result = service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "status is updated"
            1 * purchaseOrderRepository.updateStatus(poId, "in_progress", _) >> updatedRecord
            1 * purchaseOrderMapper.toDto(updatedRecord, _) >> updatedPO
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result has in_progress status"
            result.status == PurchaseOrderStatus.IN_PROGRESS
    }

    def "updatePurchaseOrderStatus transitions from OPEN to CANCELLED"() {
        given: "an open purchase order"
            def companyId = 1L
            def poId = "po-123"
            def authorId = "user@test.com"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.OPEN

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.CANCELLED

            def updatedRecord = createPurchaseOrderRecord(poId, companyId)
            def updatedPO = createPurchaseOrder(poId, companyId)
            updatedPO.status = PurchaseOrderStatus.CANCELLED
            def company = createCompanyRecord(companyId)

        when: "cancelling open PO"
            def result = service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, authorId)

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "status is updated to cancelled"
            1 * purchaseOrderRepository.updateStatus(poId, "cancelled", _) >> updatedRecord
            1 * purchaseOrderMapper.toDto(updatedRecord, _) >> updatedPO
            1 * companyRepository.findById(companyId) >> company
            1 * vectorStoreIngestionPublisher.publishPurchaseOrderEvent(_, _, _, _, _, _)
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result has cancelled status"
            result.status == PurchaseOrderStatus.CANCELLED
    }

    def "updatePurchaseOrderStatus throws BadRequestException for invalid transition from CANCELLED to IN_PROGRESS"() {
        given: "a cancelled purchase order"
            def companyId = 1L
            def poId = "po-123"

            def flatRecords = [createFlatRecord(poId, companyId)]
            def existingPO = createPurchaseOrder(poId, companyId)
            existingPO.status = PurchaseOrderStatus.CANCELLED

            def statusUpdate = new PurchaseOrderStatusUpdate()
            statusUpdate.status = PurchaseOrderStatus.IN_PROGRESS  // Invalid - must go to PENDING first

        when: "attempting invalid transition"
            service.updatePurchaseOrderStatus(companyId, poId, statusUpdate, "user@test.com")

        then: "existing PO is fetched"
            1 * purchaseOrderRepository.findById(poId) >> flatRecords
            1 * purchaseOrderMapper.fromFlatRecords(flatRecords) >> [existingPO]

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("cancelled")
    }

    // ==================== Helper Methods ====================

    private PurchaseOrderFlatItemsRecord createFlatRecord(String id, Long companyId) {
        def record = new PurchaseOrderFlatItemsRecord()
        record.purchaseOrderId = id
        record.companyId = companyId
        return record
    }

    private static PurchaseOrdersRecord createPurchaseOrderRecord(String id, Long companyId) {
        def record = new PurchaseOrdersRecord()
        record.id = id
        record.companyId = companyId
        record.createdAt = OffsetDateTime.now()
        return record
    }

    private static PurchaseOrder createPurchaseOrder(String id, Long companyId) {
        def po = new PurchaseOrder()
        po.id = id
        po.companyId = companyId
        po.status = PurchaseOrderStatus.PENDING
        return po
    }

    private static ProjectsRecord createProjectRecord(String id) {
        def record = new ProjectsRecord()
        record.id = id
        record.companyId = 1L
        return record
    }

    private static CompaniesRecord createCompanyRecord(Long id) {
        def record = new CompaniesRecord()
        record.id = id
        record.assignedEmail = "inbox@test.com"
        return record
    }

    private static PurchaseOrderItem createItem(BigDecimal unitPrice, Integer quantity) {
        def item = new PurchaseOrderItem()
        item.unitPrice = unitPrice
        item.quantity = quantity
        return item
    }

    private static Contact createContactDto(String id) {
        def contact = new Contact()
        contact.id = id
        contact.name = "Test Contact"
        return contact
    }
}

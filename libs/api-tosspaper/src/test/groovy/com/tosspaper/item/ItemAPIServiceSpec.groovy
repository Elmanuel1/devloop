package com.tosspaper.item

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.ForbiddenException
import com.tosspaper.common.security.SecurityUtils
import com.tosspaper.generated.model.Item as GeneratedItem
import com.tosspaper.generated.model.ItemCreate
import com.tosspaper.generated.model.ItemUpdate
import com.tosspaper.integrations.push.IntegrationPushStreamPublisher
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.domain.integration.IntegrationCategory
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.Item
import org.mockito.MockedStatic
import org.mockito.Mockito
import spock.lang.Specification

import java.time.OffsetDateTime

class ItemAPIServiceSpec extends Specification {

    ItemRepository itemRepository
    ItemMapper itemMapper  // REAL mapper instance
    IntegrationConnectionService integrationConnectionService
    IntegrationPushStreamPublisher integrationPushStreamPublisher
    ObjectMapper objectMapper
    ItemAPIServiceImpl service
    MockedStatic<SecurityUtils> securityUtilsMock

    def setup() {
        itemRepository = Mock()
        itemMapper = new ItemMapper()  // Use REAL mapper to test field mapping
        integrationConnectionService = Mock()
        integrationPushStreamPublisher = Mock()
        objectMapper = new ObjectMapper()
        objectMapper.findAndRegisterModules()

        service = new ItemAPIServiceImpl(
            itemRepository,
            itemMapper,
            integrationConnectionService,
            integrationPushStreamPublisher,
            objectMapper
        )

        // Mock SecurityUtils static method
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class)
        securityUtilsMock.when({ SecurityUtils.getSubjectFromJwt() }).thenReturn("test@example.com")
    }

    def cleanup() {
        securityUtilsMock?.close()
    }

    // ==================== getItems ====================

    def "getItems returns all non-category items for company with all fields mapped"() {
        given: "items exist for company"
            def companyId = 1L
            def createdAt = OffsetDateTime.now()
            def item1 = Item.builder()
                    .id("item-1")
                    .companyId(companyId)
                    .connectionId("conn-1")
                    .name("Product A")
                    .code("PROD-A")
                    .description("A great product")
                    .type("Service")
                    .unitPrice(new BigDecimal("99.99"))
                    .purchaseCost(new BigDecimal("50.00"))
                    .active(true)
                    .taxable(true)
                    .quantityOnHand(100)
                    .createdAt(createdAt)
                    .build()
            item1.externalId = "ext-1"  // Set inherited field after build

            def item2 = Item.builder()
                    .id("item-2")
                    .companyId(companyId)
                    .name("Product B")
                    .type("Inventory")
                    .active(true)
                    .createdAt(createdAt)
                    .build()

            def item3 = Item.builder()
                    .id("item-3")
                    .companyId(companyId)
                    .name("Category X")
                    .type("Category")  // Should be filtered out
                    .active(true)
                    .createdAt(createdAt)
                    .build()

            def items = [item1, item2, item3]

        when: "fetching items"
            def result = service.getItems(companyId, null)

        then: "repository returns all items"
            1 * itemRepository.findByCompanyId(companyId) >> items

        and: "category items are filtered out"
            result.data.size() == 2

        and: "first item has all fields mapped correctly"
            with(result.data[0]) {
                id == "item-1"
                companyId == 1L
                connectionId == "conn-1"
                externalId == "ext-1"
                name == "Product A"
                code == "PROD-A"
                description == "A great product"
                type == "Service"
                unitPrice == new BigDecimal("99.99")
                purchaseCost == new BigDecimal("50.00")
                active == true
                taxable == true
                quantityOnHand == 100
                createdAt == createdAt
            }

        and: "second item also has fields mapped"
            with(result.data[1]) {
                id == "item-2"
                name == "Product B"
                type == "Inventory"
            }
    }

    def "getItems filters by active status when provided"() {
        given: "active and inactive items"
            def companyId = 1L
            def items = [
                createDomainItem("item-1", companyId, "Active Item", "Service", true),
                createDomainItem("item-2", companyId, "Inactive Item", "Service", false)
            ]

        when: "fetching only active items"
            def result = service.getItems(companyId, true)

        then: "repository returns all items"
            1 * itemRepository.findByCompanyId(companyId) >> items

        and: "result contains only active items"
            result.data.size() == 1
            result.data[0].id == "item-1"
            result.data[0].name == "Active Item"
            result.data[0].active == true
    }

    def "getItems filters inactive items when active=false"() {
        given: "active and inactive items"
            def companyId = 1L
            def items = [
                createDomainItem("item-1", companyId, "Active Item", "Service", true),
                createDomainItem("item-2", companyId, "Inactive Item", "Service", false)
            ]

        when: "fetching only inactive items"
            def result = service.getItems(companyId, false)

        then: "repository returns all items"
            1 * itemRepository.findByCompanyId(companyId) >> items

        and: "result contains only inactive items"
            result.data.size() == 1
            result.data[0].id == "item-2"
            result.data[0].name == "Inactive Item"
            result.data[0].active == false
    }

    def "getItems returns empty list when no items exist"() {
        given: "no items for company"
            def companyId = 1L

        when: "fetching items"
            def result = service.getItems(companyId, null)

        then: "repository returns empty list"
            1 * itemRepository.findByCompanyId(companyId) >> []

        and: "result is empty"
            result.data.isEmpty()
    }

    // ==================== getItemById ====================

    def "getItemById returns item with all fields when company matches"() {
        given: "an existing item"
            def companyId = 1L
            def itemId = "item-123"
            def createdAt = OffsetDateTime.now()
            def item = Item.builder()
                .id(itemId)
                .companyId(companyId)
                .connectionId("conn-1")
                .name("Test Product")
                .code("TEST-001")
                .description("Test description")
                .type("Service")
                .unitPrice(new BigDecimal("199.99"))
                .purchaseCost(new BigDecimal("100.00"))
                .active(true)
                .taxable(false)
                .quantityOnHand(50)
                .createdAt(createdAt)
                .build()
            item.externalId = "ext-1"  // Set inherited field after build

        when: "fetching item by id"
            def result = service.getItemById(companyId, itemId)

        then: "repository returns the item"
            1 * itemRepository.findById(itemId) >> item

        and: "all fields are mapped correctly"
            with(result) {
                id == itemId
                getCompanyId() == companyId
                connectionId == "conn-1"
                externalId == "ext-1"
                name == "Test Product"
                code == "TEST-001"
                description == "Test description"
                type == "Service"
                unitPrice == new BigDecimal("199.99")
                purchaseCost == new BigDecimal("100.00")
                active == true
                taxable == false
                quantityOnHand == 50
                getCreatedAt() == createdAt
            }
    }

    def "getItemById throws ForbiddenException when company does not match"() {
        given: "an item from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def itemId = "item-123"
            def item = createDomainItem(itemId, differentCompanyId, "Item", "Service", true)

        when: "fetching item"
            service.getItemById(companyId, itemId)

        then: "repository returns the item"
            1 * itemRepository.findById(itemId) >> item

        and: "ForbiddenException is thrown"
            thrown(ForbiddenException)
    }

    // ==================== createItem ====================

    def "createItem creates item with all fields mapped and publishes integration event"() {
        given: "a create request"
            def companyId = 1L
            def createRequest = new ItemCreate()
            createRequest.name = "New Item"
            createRequest.code = "NEW-001"
            createRequest.description = "A new item"
            createRequest.purchaseCost = new BigDecimal("75.00")

            def createdAt = OffsetDateTime.now()
            def createdItem = Item.builder()
                .id("created-id")
                .companyId(companyId)
                .name("New Item")
                .code("NEW-001")
                .description("A new item")
                .type("Service")  // Default from mapper
                .purchaseCost(new BigDecimal("75.00"))
                .active(true)     // Default from mapper
                .createdAt(createdAt)
                .build()

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> "QUICKBOOKS"
            }

        when: "creating item"
            def result = service.createItem(companyId, createRequest)

        then: "item is created in repository"
            1 * itemRepository.create(companyId, { Item item ->
                item.companyId == companyId
                item.name == "New Item"
                item.code == "NEW-001"
                item.description == "A new item"
                item.type == "Service"
                item.purchaseCost == new BigDecimal("75.00")
                item.active == true
            }) >> createdItem

        and: "integration push event published"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)
            1 * integrationPushStreamPublisher.publish(_)

        and: "returned item has all fields"
            with(result) {
                id == "created-id"
                name == "New Item"
                code == "NEW-001"
                description == "A new item"
                type == "Service"
                purchaseCost == new BigDecimal("75.00")
                active == true
            }
    }

    def "createItem skips integration push when no connection exists"() {
        given: "a create request"
            def companyId = 1L
            def createRequest = new ItemCreate()
            createRequest.name = "New Item"

            def createdItem = Item.builder()
                .id("created-id")
                .companyId(companyId)
                .name("New Item")
                .type("Service")
                .active(true)
                .createdAt(OffsetDateTime.now())
                .build()

        when: "creating item"
            def result = service.createItem(companyId, createRequest)

        then: "item is created"
            1 * itemRepository.create(companyId, _) >> createdItem

        and: "no connection found"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.empty()

        and: "no push event published"
            0 * integrationPushStreamPublisher.publish(_)

        and: "result is correct"
            result.id == "created-id"
            result.name == "New Item"
    }

    // ==================== updateItem ====================

    def "updateItem updates item and publishes integration event"() {
        given: "an existing item"
            def companyId = 1L
            def itemId = "item-123"
            def existingItem = Item.builder()
                .id(itemId)
                .companyId(companyId)
                .name("Old Name")
                .code("OLD-001")
                .type("Service")
                .active(true)
                .createdAt(OffsetDateTime.now())
                .build()

            def updateRequest = new ItemUpdate()
            updateRequest.name = "New Name"
            updateRequest.code = "NEW-001"
            updateRequest.description = "Updated description"
            updateRequest.purchaseCost = new BigDecimal("150.00")
            updateRequest.active = false

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> "QUICKBOOKS"
            }

        when: "updating item"
            service.updateItem(companyId, itemId, updateRequest)

        then: "existing item is fetched"
            1 * itemRepository.findById(itemId) >> existingItem

        and: "item is saved with updated fields"
            1 * itemRepository.update({ Item item ->
                // Verify mapper updated the fields
                item.name == "New Name"
                item.code == "NEW-001"
                item.description == "Updated description"
                item.purchaseCost == new BigDecimal("150.00")
                item.active == false
            }) >> existingItem

        and: "integration push event published"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)
            1 * integrationPushStreamPublisher.publish(_)
    }

    def "updateItem throws ForbiddenException when company does not match"() {
        given: "an item from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def itemId = "item-123"
            def existingItem = createDomainItem(itemId, differentCompanyId, "Item", "Service", true)
            def updateRequest = new ItemUpdate()

        when: "updating item"
            service.updateItem(companyId, itemId, updateRequest)

        then: "existing item is fetched"
            1 * itemRepository.findById(itemId) >> existingItem

        and: "ForbiddenException is thrown"
            thrown(ForbiddenException)

        and: "no update occurs"
            0 * itemRepository.update(_)
    }

    def "createItem continues when integration push fails"() {
        given: "a create request"
            def companyId = 1L
            def createRequest = new ItemCreate()
            createRequest.name = "New Item"

            def createdItem = Item.builder()
                .id("created-id")
                .companyId(companyId)
                .name("New Item")
                .type("Service")
                .active(true)
                .createdAt(OffsetDateTime.now())
                .build()

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> "QUICKBOOKS"
            }

        when: "creating item"
            def result = service.createItem(companyId, createRequest)

        then: "item is created"
            1 * itemRepository.create(companyId, _) >> createdItem

        and: "connection is found but publish throws exception"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)
            1 * integrationPushStreamPublisher.publish(_) >> { throw new RuntimeException("Publish failed") }

        and: "item creation still succeeds (exception is caught and logged)"
            result.id == "created-id"
            result.name == "New Item"
    }

    def "updateItem continues when integration push fails"() {
        given: "an existing item"
            def companyId = 1L
            def itemId = "item-123"
            def existingItem = Item.builder()
                .id(itemId)
                .companyId(companyId)
                .name("Old Name")
                .type("Service")
                .active(true)
                .createdAt(OffsetDateTime.now())
                .build()

            def updateRequest = new ItemUpdate()
            updateRequest.name = "New Name"

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> "QUICKBOOKS"
            }

        when: "updating item"
            service.updateItem(companyId, itemId, updateRequest)

        then: "existing item is fetched"
            1 * itemRepository.findById(itemId) >> existingItem

        and: "item is saved"
            1 * itemRepository.update(_) >> existingItem

        and: "connection is found but publish throws exception"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)
            1 * integrationPushStreamPublisher.publish(_) >> { throw new RuntimeException("Publish failed") }

        and: "no exception is thrown (update still succeeds)"
            noExceptionThrown()
    }

    // ==================== Helper Methods ====================

    private static Item createDomainItem(String id, Long companyId, String name, String type, Boolean active) {
        Item.builder()
            .id(id)
            .companyId(companyId)
            .name(name)
            .type(type)
            .active(active)
            .createdAt(OffsetDateTime.now())
            .build()
    }
}

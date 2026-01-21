package com.tosspaper.item

import com.tosspaper.generated.model.ItemCreate
import com.tosspaper.generated.model.ItemUpdate
import com.tosspaper.models.domain.integration.Item
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

class ItemMapperSpec extends Specification {

    ItemMapper mapper

    def setup() {
        mapper = new ItemMapper()
    }

    def "toApi should map all fields from domain Item to API Item"() {
        given: "a domain Item with all fields"
        def domainItem = Item.builder()
                .id("item-123")
                .companyId(1L)
                .connectionId("conn-456")
                .name("Test Item")
                .code("ITEM-001")
                .description("Test item description")
                .type("Service")
                .unitPrice(new BigDecimal("50.00"))
                .purchaseCost(new BigDecimal("30.00"))
                .active(true)
                .taxable(true)
                .quantityOnHand(new BigDecimal("100"))
                .createdAt(OffsetDateTime.now())
                .build()
        domainItem.externalId = "ext-789"

        when: "mapping to API"
        def result = mapper.toApi(domainItem)

        then: "all fields are mapped correctly"
        result.id == "item-123"
        result.companyId == 1L
        result.connectionId == "conn-456"
        result.externalId == "ext-789"
        result.name == "Test Item"
        result.code == "ITEM-001"
        result.description == "Test item description"
        result.type == "Service"
        result.unitPrice == new BigDecimal("50.00")
        result.purchaseCost == new BigDecimal("30.00")
        result.active == true
        result.taxable == true
        result.quantityOnHand == new BigDecimal("100")
        result.createdAt != null
    }

    def "toApi should handle null domain Item"() {
        when: "mapping null domain Item to API"
        def result = mapper.toApi(null)

        then: "result is null"
        result == null
    }

    def "toApi should handle null createdAt"() {
        given: "a domain Item with null createdAt"
        def domainItem = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Test Item")
                .createdAt(null)
                .build()

        when: "mapping to API"
        def result = mapper.toApi(domainItem)

        then: "createdAt is not set"
        result.createdAt == null
    }

    def "toApi should handle null optional fields"() {
        given: "a domain Item with minimal fields"
        def domainItem = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Test Item")
                .connectionId(null)
                .code(null)
                .description(null)
                .type(null)
                .unitPrice(null)
                .purchaseCost(null)
                .active(null)
                .taxable(null)
                .quantityOnHand(null)
                .build()
        domainItem.externalId = null

        when: "mapping to API"
        def result = mapper.toApi(domainItem)

        then: "null fields remain null"
        result.id == "item-1"
        result.companyId == 1L
        result.name == "Test Item"
        result.connectionId == null
        result.externalId == null
        result.code == null
        result.description == null
        result.type == null
        result.unitPrice == null
        result.purchaseCost == null
        result.active == null
        result.taxable == null
        result.quantityOnHand == null
    }

    def "toDomain should map ItemCreate to domain Item with defaults"() {
        given: "an ItemCreate DTO"
        def itemCreate = new ItemCreate(
                name: "New Item",
                code: "NEW-001",
                description: "New item description",
                purchaseCost: new BigDecimal("25.00")
        )

        when: "mapping to domain"
        def result = mapper.toDomain(1L, itemCreate)

        then: "fields are mapped with defaults"
        result.companyId == 1L
        result.name == "New Item"
        result.code == "NEW-001"
        result.description == "New item description"
        result.type == "Service"  // default value
        result.purchaseCost == new BigDecimal("25.00")
        result.active == true  // default value
    }

    def "toDomain should handle null ItemCreate"() {
        when: "mapping null ItemCreate to domain"
        def result = mapper.toDomain(1L, null)

        then: "result is null"
        result == null
    }

    def "toDomain should handle ItemCreate with minimal fields"() {
        given: "an ItemCreate with only name"
        def itemCreate = new ItemCreate(name: "Minimal Item")

        when: "mapping to domain"
        def result = mapper.toDomain(1L, itemCreate)

        then: "required fields are set with defaults"
        result.companyId == 1L
        result.name == "Minimal Item"
        result.type == "Service"
        result.active == true
        result.code == null
        result.description == null
        result.purchaseCost == null
    }

    def "toDomain should handle ItemCreate with all null optional fields"() {
        given: "an ItemCreate with null optional fields"
        def itemCreate = new ItemCreate(
                name: "Test Item",
                code: null,
                description: null,
                purchaseCost: null
        )

        when: "mapping to domain"
        def result = mapper.toDomain(1L, itemCreate)

        then: "null fields remain null but defaults are applied"
        result.companyId == 1L
        result.name == "Test Item"
        result.code == null
        result.description == null
        result.purchaseCost == null
        result.type == "Service"
        result.active == true
    }

    def "updateDomain should update only non-null fields from ItemUpdate"() {
        given: "an existing domain Item"
        def existing = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Original Name")
                .code("ORIG-001")
                .description("Original description")
                .purchaseCost(new BigDecimal("20.00"))
                .active(true)
                .build()

        and: "an ItemUpdate with some fields"
        def update = new ItemUpdate(
                name: "Updated Name",
                purchaseCost: new BigDecimal("30.00")
        )

        when: "updating the domain"
        mapper.updateDomain(update, existing)

        then: "only updated fields are changed"
        existing.name == "Updated Name"
        existing.purchaseCost == new BigDecimal("30.00")
        existing.code == "ORIG-001"  // unchanged
        existing.description == "Original description"  // unchanged
        existing.active == true  // unchanged
    }

    def "updateDomain should update all fields when all are provided in ItemUpdate"() {
        given: "an existing domain Item"
        def existing = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Original Name")
                .code("ORIG-001")
                .description("Original description")
                .purchaseCost(new BigDecimal("20.00"))
                .active(true)
                .build()

        and: "an ItemUpdate with all fields"
        def update = new ItemUpdate(
                name: "Updated Name",
                code: "UPD-001",
                description: "Updated description",
                purchaseCost: new BigDecimal("35.00"),
                active: false
        )

        when: "updating the domain"
        mapper.updateDomain(update, existing)

        then: "all fields are updated"
        existing.name == "Updated Name"
        existing.code == "UPD-001"
        existing.description == "Updated description"
        existing.purchaseCost == new BigDecimal("35.00")
        existing.active == false
    }

    def "updateDomain should handle null ItemUpdate"() {
        given: "an existing domain Item"
        def existing = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Original Name")
                .build()

        when: "updating with null ItemUpdate"
        mapper.updateDomain(null, existing)

        then: "existing item is unchanged"
        existing.name == "Original Name"
    }

    def "updateDomain should handle null existing Item"() {
        given: "an ItemUpdate"
        def update = new ItemUpdate(name: "Updated Name")

        when: "updating null existing item"
        mapper.updateDomain(update, null)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "updateDomain should handle both null parameters"() {
        when: "updating with both null parameters"
        mapper.updateDomain(null, null)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    @Unroll
    def "updateDomain should update individual field: #fieldName"() {
        given: "an existing domain Item with all fields set"
        def existing = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Original Name")
                .code("ORIG-001")
                .description("Original description")
                .purchaseCost(new BigDecimal("20.00"))
                .active(true)
                .build()

        and: "an ItemUpdate with only one field set"
        def update = new ItemUpdate()
        update."${fieldName}" = newValue

        when: "updating the domain"
        mapper.updateDomain(update, existing)

        then: "only the specified field is updated"
        existing."${fieldName}" == newValue

        where:
        fieldName       | newValue
        "name"          | "New Name"
        "code"          | "NEW-001"
        "description"   | "New description"
        "purchaseCost"  | new BigDecimal("50.00")
        "active"        | false
    }

    def "updateDomain should not update fields with null values in ItemUpdate"() {
        given: "an existing domain Item"
        def existing = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Original Name")
                .code("ORIG-001")
                .description("Original description")
                .purchaseCost(new BigDecimal("20.00"))
                .active(true)
                .build()

        and: "an ItemUpdate with all null fields"
        def update = new ItemUpdate(
                name: null,
                code: null,
                description: null,
                purchaseCost: null,
                active: null
        )

        when: "updating the domain"
        mapper.updateDomain(update, existing)

        then: "no fields are changed"
        existing.name == "Original Name"
        existing.code == "ORIG-001"
        existing.description == "Original description"
        existing.purchaseCost == new BigDecimal("20.00")
        existing.active == true
    }

    def "updateDomain should handle mixed null and non-null values"() {
        given: "an existing domain Item"
        def existing = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Original Name")
                .code("ORIG-001")
                .description("Original description")
                .purchaseCost(new BigDecimal("20.00"))
                .active(true)
                .build()

        and: "an ItemUpdate with mixed null and non-null values"
        def update = new ItemUpdate(
                name: "Updated Name",
                code: null,
                description: "Updated description",
                purchaseCost: null,
                active: false
        )

        when: "updating the domain"
        mapper.updateDomain(update, existing)

        then: "only non-null fields are updated"
        existing.name == "Updated Name"
        existing.code == "ORIG-001"  // unchanged (null in update)
        existing.description == "Updated description"
        existing.purchaseCost == new BigDecimal("20.00")  // unchanged (null in update)
        existing.active == false
    }

    def "toDomain should set correct default type value"() {
        given: "an ItemCreate DTO"
        def itemCreate = new ItemCreate(name: "Test Item")

        when: "mapping to domain"
        def result = mapper.toDomain(1L, itemCreate)

        then: "type is set to default 'Service'"
        result.type == "Service"
    }

    def "toDomain should set correct default active value"() {
        given: "an ItemCreate DTO"
        def itemCreate = new ItemCreate(name: "Test Item")

        when: "mapping to domain"
        def result = mapper.toDomain(1L, itemCreate)

        then: "active is set to default true"
        result.active == true
    }

    def "updateDomain should allow setting active to false"() {
        given: "an existing active item"
        def existing = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Test Item")
                .active(true)
                .build()

        and: "an update to deactivate"
        def update = new ItemUpdate(active: false)

        when: "updating the domain"
        mapper.updateDomain(update, existing)

        then: "active is set to false"
        existing.active == false
    }

    def "updateDomain should allow setting active to true"() {
        given: "an existing inactive item"
        def existing = Item.builder()
                .id("item-1")
                .companyId(1L)
                .name("Test Item")
                .active(false)
                .build()

        and: "an update to activate"
        def update = new ItemUpdate(active: true)

        when: "updating the domain"
        mapper.updateDomain(update, existing)

        then: "active is set to true"
        existing.active == true
    }
}

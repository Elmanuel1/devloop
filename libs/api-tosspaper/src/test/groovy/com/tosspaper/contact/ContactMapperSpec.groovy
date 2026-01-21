package com.tosspaper.contact

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.query.PaginatedResult
import com.tosspaper.generated.model.Address
import com.tosspaper.generated.model.Contact
import com.tosspaper.generated.model.ContactCreate
import com.tosspaper.generated.model.ContactList
import com.tosspaper.generated.model.ContactStatus
import com.tosspaper.generated.model.ContactTagEnum
import com.tosspaper.generated.model.ContactUpdate
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PartyTag
import com.tosspaper.models.jooq.tables.records.ContactsRecord
import org.jooq.JSONB
import org.mapstruct.factory.Mappers
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

class ContactMapperSpec extends Specification {

    ContactMapper mapper

    def setup() {
        mapper = Mappers.getMapper(ContactMapper)
    }

    // ==================== toDto (single record) ====================

    def "toDto maps all fields correctly"() {
        given: "a contact record with all fields populated"
            def record = new ContactsRecord()
            record.id = "contact-123"
            record.companyId = 1L
            record.name = "Test Contact"
            record.email = "test@test.com"
            record.phone = "+1234567890"
            record.tag = "supplier"
            record.status = "active"
            record.notes = "Test notes"
            record.provider = "QUICKBOOKS"
            record.currencyCode = "USD"
            record.address = createAddressJsonb("123 Main St", "New York", "NY", "10001", "USA")
            record.createdAt = OffsetDateTime.now()

        when: "mapping to DTO"
            def result = mapper.toDto(record)

        then: "all fields are mapped"
            with(result) {
                id == "contact-123"
                name == "Test Contact"
                email == "test@test.com"
                phone == "+1234567890"
                tag == ContactTagEnum.SUPPLIER
                status == ContactStatus.ACTIVE
                notes == "Test notes"
                provider == "QUICKBOOKS"
                currencyCode == "USD"
                address != null
            }
    }

    def "toDto handles null address gracefully"() {
        given: "a record with null address"
            def record = new ContactsRecord()
            record.id = "contact-123"
            record.status = "active"
            record.address = null

        when: "mapping to DTO"
            def result = mapper.toDto(record)

        then: "address is null"
            result.address == null
    }

    def "toDto handles empty JSONB address"() {
        given: "a record with empty JSONB address"
            def record = new ContactsRecord()
            record.id = "contact-123"
            record.status = "active"
            record.address = JSONB.valueOf('')

        when: "mapping to DTO"
            def result = mapper.toDto(record)

        then: "address is null"
            result.address == null
    }

    @Unroll
    def "toDto maps tag '#tagString' to #expectedTag"() {
        given: "a record with tag"
            def record = new ContactsRecord()
            record.id = "contact-1"
            record.tag = tagString
            record.status = "active"

        when: "mapping to DTO"
            def result = mapper.toDto(record)

        then: "tag is mapped correctly"
            result.tag == expectedTag

        where:
            tagString  | expectedTag
            "supplier" | ContactTagEnum.SUPPLIER
            "vendor"   | ContactTagEnum.VENDOR
            "ship_to"  | ContactTagEnum.SHIP_TO
            null       | null
            ""         | null
    }

    // ==================== toDto (list) ====================

    def "toDto maps list of records"() {
        given: "multiple contact records"
            def records = [
                createRecord("contact-1", 1L, "supplier"),
                createRecord("contact-2", 1L, "vendor"),
                createRecord("contact-3", 1L, "ship_to")
            ]

        when: "mapping list"
            def result = mapper.toDto(records)

        then: "all records are mapped"
            result.size() == 3
            result[0].id == "contact-1"
            result[1].id == "contact-2"
            result[2].id == "contact-3"
    }

    // ==================== toContactList ====================

    def "toContactList maps paginated result with pagination metadata"() {
        given: "a paginated result"
            def records = [
                createRecord("contact-1", 1L, "supplier"),
                createRecord("contact-2", 1L, "vendor")
            ]
            def paginatedResult = new PaginatedResult<ContactsRecord>(
                records,
                50,  // total
                2,   // page
                10   // pageSize
            )

        when: "mapping to contact list"
            def result = mapper.toContactList(paginatedResult)

        then: "data and pagination are set"
            with(result) {
                data.size() == 2
                data[0].id == "contact-1"
                data[1].id == "contact-2"
                pagination.page == 2
                pagination.pageSize == 10
                pagination.totalItems == 50
                pagination.totalPages == 5  // ceil(50/10)
            }
    }

    def "toContactList calculates total pages correctly"() {
        given: "paginated result with odd total"
            def paginatedResult = new PaginatedResult<ContactsRecord>(
                [],
                47,  // total
                1,
                10   // pageSize
            )

        when: "mapping to contact list"
            def result = mapper.toContactList(paginatedResult)

        then: "total pages is rounded up"
            result.pagination.totalPages == 5  // ceil(47/10)
    }

    // ==================== toRecord (from ContactCreate) ====================

    def "toRecord maps create request to record with defaults"() {
        given: "a contact create request"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Contact"
            createRequest.email = "new@test.com"
            createRequest.phone = "+1234567890"
            createRequest.tag = ContactTagEnum.SUPPLIER
            createRequest.notes = "Test notes"
            createRequest.currencyCode = Currency.USD
            createRequest.address = createAddressDto("123 Main St", "New York", "NY", "10001", "USA")

        when: "mapping to record"
            def result = mapper.toRecord(companyId, createRequest)

        then: "all fields are mapped"
            with(result) {
                it.companyId == 1L
                name == "New Contact"
                email == "new@test.com"
                phone == "+1234567890"
                tag == "supplier"
                status == "active"  // constant mapping
                notes == "Test notes"
                currencyCode == "USD"
                address != null
            }
    }

    def "toRecord handles null address in create request"() {
        given: "create request with null address"
            def createRequest = new ContactCreate()
            createRequest.name = "New Contact"
            createRequest.address = null

        when: "mapping to record"
            def result = mapper.toRecord(1L, createRequest)

        then: "address is null"
            result.address == null
    }

    @Unroll
    def "toRecord converts empty string email='#emailValue' phone='#phoneValue' to null via emptyToNull"() {
        given: "create request with empty strings"
            def createRequest = new ContactCreate()
            createRequest.name = "Test"
            createRequest.email = emailValue
            createRequest.phone = phoneValue
            createRequest.currencyCode = currencyValue

        when: "mapping to record"
            def result = mapper.toRecord(1L, createRequest)

        then: "empty strings are converted to null"
            result.email == expectedEmail
            result.phone == expectedPhone
            result.currencyCode == expectedCurrency

        where:
            emailValue | phoneValue | currencyValue | expectedEmail | expectedPhone | expectedCurrency
            ""         | ""         | null          | null          | null          | null
            "  "       | "  "       | null          | null          | null          | null
            "a@b.com"  | "123"      | Currency.USD  | "a@b.com"     | "123"         | "USD"
    }

    // ==================== updateRecord ====================

    def "updateRecord updates mutable fields with IGNORE null strategy"() {
        given: "an existing record and update request"
            def record = new ContactsRecord()
            record.id = "contact-123"
            record.companyId = 1L
            record.name = "Old Name"
            record.email = "old@test.com"
            record.tag = "supplier"
            record.status = "active"
            record.createdAt = OffsetDateTime.now()

            def updateRequest = new ContactUpdate()
            updateRequest.name = "New Name"
            updateRequest.email = "new@test.com"
            updateRequest.tag = ContactTagEnum.VENDOR
            updateRequest.status = ContactStatus.ARCHIVED
            updateRequest.notes = "Updated notes"
            updateRequest.currencyCode = Currency.EUR

        when: "updating record"
            mapper.updateRecord(updateRequest, record)

        then: "mutable fields are updated"
            with(record) {
                name == "New Name"
                email == "new@test.com"
                tag == "vendor"
                status == "archived"
                notes == "Updated notes"
                currencyCode == "EUR"
                // Immutable fields unchanged
                id == "contact-123"
                companyId == 1L
            }
    }

    def "updateRecord ignores null values"() {
        given: "existing record and update with nulls"
            def record = new ContactsRecord()
            record.name = "Original Name"
            record.email = "original@test.com"
            record.notes = "Original notes"

            def updateRequest = new ContactUpdate()
            updateRequest.name = "New Name"
            updateRequest.email = null
            updateRequest.notes = null

        when: "updating record"
            mapper.updateRecord(updateRequest, record)

        then: "null values are ignored"
            with(record) {
                name == "New Name"
                email == "original@test.com"  // unchanged
                notes == "Original notes"  // unchanged (IGNORE strategy)
            }
    }

    def "updateRecord handles address update"() {
        given: "existing record and update with new address"
            def record = new ContactsRecord()
            record.address = createAddressJsonb("Old St", "Old City", "OS", "00000", "Old Country")

            def updateRequest = new ContactUpdate()
            updateRequest.address = createAddressDto("New St", "New City", "NS", "99999", "New Country")

        when: "updating record"
            mapper.updateRecord(updateRequest, record)

        then: "address is updated"
            record.address != null
            record.address.data().contains("New St")
            record.address.data().contains("New City")
    }

    // ==================== stringToStatus ====================

    @Unroll
    def "stringToStatus converts '#statusString' to #expectedStatus"() {
        when: "mapping status string"
            def result = mapper.stringToStatus(statusString)

        then: "correct enum is returned"
            result == expectedStatus

        where:
            statusString | expectedStatus
            "active"     | ContactStatus.ACTIVE
            "archived"   | ContactStatus.ARCHIVED
    }

    // ==================== jsonbToAddress ====================

    def "jsonbToAddress deserializes Address from JSONB"() {
        given: "JSONB with address data"
            def jsonb = createAddressJsonb("123 Main St", "New York", "NY", "10001", "USA")

        when: "mapping to Address"
            def result = mapper.jsonbToAddress(jsonb)

        then: "Address is deserialized"
            with(result) {
                address == "123 Main St"
                city == "New York"
                stateOrProvince == "NY"
                postalCode == "10001"
                country == "USA"
            }
    }

    def "jsonbToAddress handles null JSONB"() {
        when: "mapping null JSONB"
            def result = mapper.jsonbToAddress(null)

        then: "null is returned"
            result == null
    }

    def "jsonbToAddress handles empty JSONB string"() {
        given: "empty JSONB"
            def jsonb = JSONB.valueOf('')

        when: "mapping to Address"
            def result = mapper.jsonbToAddress(jsonb)

        then: "null is returned"
            result == null
    }

    def "jsonbToAddress throws exception for malformed JSON"() {
        given: "malformed JSONB"
            def jsonb = JSONB.valueOf('{invalid json}')

        when: "mapping to Address"
            mapper.jsonbToAddress(jsonb)

        then: "RuntimeException is thrown"
            thrown(RuntimeException)
    }

    // ==================== addressToJsonb ====================

    def "addressToJsonb serializes Address to JSONB"() {
        given: "an Address object"
            def address = createAddressDto("123 Main St", "New York", "NY", "10001", "USA")

        when: "mapping to JSONB"
            def result = mapper.addressToJsonb(address)

        then: "JSONB contains serialized address"
            result != null
            result.data().contains("123 Main St")
            result.data().contains("New York")
    }

    def "addressToJsonb handles null Address"() {
        when: "mapping null Address"
            def result = mapper.addressToJsonb(null)

        then: "null is returned"
            result == null
    }

    // ==================== stringToTagEnum ====================

    @Unroll
    def "stringToTagEnum converts '#tagString' to #expectedTag"() {
        when: "mapping tag string"
            def result = mapper.stringToTagEnum(tagString)

        then: "correct enum is returned"
            result == expectedTag

        where:
            tagString  | expectedTag
            "supplier" | ContactTagEnum.SUPPLIER
            "vendor"   | ContactTagEnum.VENDOR
            "ship_to"  | ContactTagEnum.SHIP_TO
            null       | null
            ""         | null
    }

    def "stringToTagEnum throws exception for invalid tag"() {
        when: "mapping invalid tag"
            mapper.stringToTagEnum("invalid_tag")

        then: "RuntimeException is thrown"
            thrown(RuntimeException)
    }

    // ==================== tagEnumToString ====================

    @Unroll
    def "tagEnumToString converts #tag to '#expectedString'"() {
        when: "mapping tag enum to string"
            def result = mapper.tagEnumToString(tag)

        then: "correct string is returned"
            result == expectedString

        where:
            tag                      | expectedString
            ContactTagEnum.SUPPLIER  | "supplier"
            ContactTagEnum.VENDOR    | "vendor"
            ContactTagEnum.SHIP_TO   | "ship_to"
            null                     | null
    }

    // ==================== statusToString ====================

    @Unroll
    def "statusToString converts #status to '#expectedString'"() {
        when: "mapping status enum to string"
            def result = mapper.statusToString(status)

        then: "correct string is returned"
            result == expectedString

        where:
            status                   | expectedString
            ContactStatus.ACTIVE     | "active"
            ContactStatus.ARCHIVED   | "archived"
            null                     | null
    }

    // ==================== emptyToNull ====================

    @Unroll
    def "emptyToNull converts '#value' to #expected"() {
        when: "converting value"
            def result = mapper.emptyToNull(value)

        then: "correct result is returned"
            result == expected

        where:
            value       | expected
            "test"      | "test"
            "  test  "  | "  test  "  // trimmed check, but value preserved
            ""          | null
            "  "        | null
            "\t"        | null
            null        | null
    }

    // ==================== stringToCurrency ====================

    @Unroll
    def "stringToCurrency converts '#currencyCode' to #expectedCurrency"() {
        when: "mapping currency code"
            def result = mapper.stringToCurrency(currencyCode)

        then: "correct enum is returned"
            result == expectedCurrency

        where:
            currencyCode | expectedCurrency
            "USD"        | Currency.USD
            "EUR"        | Currency.EUR
            "GBP"        | Currency.GBP
            "CAD"        | Currency.CAD
            "usd"        | Currency.USD  // case insensitive
            null         | null
            ""           | null
            "  "         | null
    }

    // ==================== currencyToString ====================

    @Unroll
    def "currencyToString converts #currency to '#expectedCode'"() {
        when: "mapping currency to string"
            def result = mapper.currencyToString(currency)

        then: "correct code is returned"
            result == expectedCode

        where:
            currency      | expectedCode
            Currency.USD  | "USD"
            Currency.EUR  | "EUR"
            Currency.GBP  | "GBP"
            null          | null
    }

    // ==================== toParty ====================

    def "toParty maps all fields correctly"() {
        given: "a contact record with all fields"
            def record = new ContactsRecord()
            record.id = "contact-123"
            record.companyId = 1L
            record.name = "Test Contact"
            record.email = "test@test.com"
            record.phone = "+1234567890"
            record.notes = "Test notes"
            record.tag = "supplier"
            record.status = "active"
            record.currencyCode = "USD"
            record.provider = "QUICKBOOKS"
            record.externalId = "ext-123"
            record.providerVersion = "v1"
            record.address = createAddressJsonb("123 Main St", "New York", "NY", "10001", "USA")
            record.externalMetadata = JSONB.valueOf('{"qbo_id":"456"}')

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "all fields are mapped"
            with(result) {
                id == "contact-123"
                companyId == 1L
                name == "Test Contact"
                email == "test@test.com"
                phone == "+1234567890"
                notes == "Test notes"
                tag == PartyTag.SUPPLIER
                status == Party.PartyStatus.ACTIVE
                currencyCode == Currency.USD
                provider == "QUICKBOOKS"
                externalId == "ext-123"
                providerVersion == "v1"
                address != null
                address.address == "123 Main St"
                address.city == "New York"
                address.stateOrProvince == "NY"
                externalMetadata != null
                externalMetadata["qbo_id"] == "456"
            }
    }

    def "toParty handles null record"() {
        when: "mapping null record"
            def result = mapper.toParty(null)

        then: "null is returned"
            result == null
    }

    @Unroll
    def "toParty maps tag '#tagString' to #expectedPartyTag"() {
        given: "a record with tag"
            def record = new ContactsRecord()
            record.id = "contact-1"
            record.tag = tagString

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "tag is mapped correctly"
            result.tag == expectedPartyTag

        where:
            tagString  | expectedPartyTag
            "supplier" | PartyTag.SUPPLIER
            "ship_to"  | PartyTag.SHIP_TO
            "vendor"   | null  // vendor doesn't have PartyTag mapping
            null       | null
    }

    @Unroll
    def "toParty maps status '#statusString' to #expectedPartyStatus"() {
        given: "a record with status"
            def record = new ContactsRecord()
            record.id = "contact-1"
            record.status = statusString

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "status is mapped correctly"
            result.status == expectedPartyStatus

        where:
            statusString | expectedPartyStatus
            "active"     | Party.PartyStatus.ACTIVE
            "archived"   | Party.PartyStatus.ARCHIVED
            "ACTIVE"     | Party.PartyStatus.ACTIVE
            null         | null
    }

    def "toParty handles snake_case address fields from JSONB"() {
        given: "a record with snake_case address in JSONB"
            def record = new ContactsRecord()
            record.id = "contact-1"
            def addressJson = '''
            {
                "address": "123 Main St",
                "city": "New York",
                "state_or_province": "NY",
                "postal_code": "10001",
                "country": "USA"
            }
            '''
            record.address = JSONB.valueOf(addressJson)

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "address is mapped with snake_case conversion"
            with(result.address) {
                address == "123 Main St"
                city == "New York"
                stateOrProvince == "NY"
                postalCode == "10001"
                country == "USA"
            }
    }

    def "toParty handles null address gracefully"() {
        given: "a record with null address"
            def record = new ContactsRecord()
            record.id = "contact-1"
            record.address = null

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "address is null"
            result.address == null
    }

    def "toParty handles malformed address JSONB gracefully"() {
        given: "a record with malformed address"
            def record = new ContactsRecord()
            record.id = "contact-1"
            record.address = JSONB.valueOf('{invalid}')

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "address is null (exception caught)"
            result.address == null
    }

    def "toParty handles null external metadata"() {
        given: "a record with null external metadata"
            def record = new ContactsRecord()
            record.id = "contact-1"
            record.externalMetadata = null

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "external metadata is null"
            result.externalMetadata == null
    }

    def "toParty handles empty external metadata JSONB"() {
        given: "a record with empty external metadata"
            def record = new ContactsRecord()
            record.id = "contact-1"
            record.externalMetadata = JSONB.valueOf('  ')

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "external metadata is null"
            result.externalMetadata == null
    }

    def "toParty handles malformed external metadata gracefully"() {
        given: "a record with malformed metadata"
            def record = new ContactsRecord()
            record.id = "contact-1"
            record.externalMetadata = JSONB.valueOf('{invalid json}')

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "external metadata is null (exception caught)"
            result.externalMetadata == null
    }

    def "toParty handles invalid tag value gracefully"() {
        given: "a record with invalid tag"
            def record = new ContactsRecord()
            record.id = "contact-1"
            record.tag = "invalid_tag"

        when: "mapping to Party"
            def result = mapper.toParty(record)

        then: "tag is null (exception caught)"
            result.tag == null
    }

    // ==================== Helper Methods ====================

    private ContactsRecord createRecord(String id, Long companyId, String tag) {
        def record = new ContactsRecord()
        record.id = id
        record.companyId = companyId
        record.name = "Test Contact"
        record.tag = tag
        record.status = "active"
        record.createdAt = OffsetDateTime.now()
        return record
    }

    private Address createAddressDto(String address, String city, String stateOrProvince, String postalCode, String country) {
        def addr = new Address()
        addr.address = address
        addr.city = city
        addr.stateOrProvince = stateOrProvince
        addr.postalCode = postalCode
        addr.country = country
        return addr
    }

    private JSONB createAddressJsonb(String address, String city, String stateOrProvince, String postalCode, String country) {
        def objectMapper = new ObjectMapper()
        def addressMap = [
            "address": address,
            "city": city,
            "state_or_province": stateOrProvince,
            "postal_code": postalCode,
            "country": country
        ]
        return JSONB.valueOf(objectMapper.writeValueAsString(addressMap))
    }
}

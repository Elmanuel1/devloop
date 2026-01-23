package com.tosspaper.integrations.quickbooks.customer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.intuit.ipp.data.Customer
import com.tosspaper.integrations.fixtures.QBOTestFixtures
import com.tosspaper.models.domain.Address
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PartyTag
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification
import spock.lang.Subject

class CustomerMapperSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())

    @Subject
    CustomerMapper customerMapper = new CustomerMapper(objectMapper)

    // ==================== toDomain Tests ====================

    def "should map Customer with Job Location marker to Party"() {
        given:
        def qboCustomer = QBOTestFixtures.loadCustomer()

        when:
        def party = customerMapper.toDomain(qboCustomer)

        then:
        party != null

        and: "external ID and provider tracking fields are mapped"
        party.externalId == "123"
        party.providerVersion == "2"
        party.provider == IntegrationProvider.QUICKBOOKS.value

        and: "name has Job Location prefix stripped"
        party.name == "Downtown Office Complex"

        and: "contact info is mapped"
        party.phone == "(650) 555-7890"
        party.email == "sarah.johnson@johnsonlabs.com"
        party.notes == "Primary ship-to location for downtown deliveries. Loading dock access required."

        and: "address is mapped correctly"
        party.address != null
        party.address.address.contains("500 Innovation Way")
        party.address.city == "Mountain View"
        party.address.country == "USA"
        party.address.stateOrProvince == "CA"
        party.address.postalCode == "94043"

        and: "tag is SHIP_TO for job locations"
        party.tag == PartyTag.SHIP_TO
        party.status == Party.PartyStatus.ACTIVE

        and: "provider timestamps are mapped"
        party.providerCreatedAt != null
        party.providerLastUpdatedAt != null

        and: "external metadata contains original QBO entity that can be deserialized"
        party.externalMetadata != null
        party.externalMetadata.containsKey('qboEntity')
        
        and: "the stored QBO entity can be deserialized back to Customer"
        def storedJson = party.externalMetadata.get('qboEntity') as String
        def deserializedCustomer = objectMapper.readValue(storedJson, Customer)
        deserializedCustomer.id == "123"
        deserializedCustomer.syncToken == "2"
        deserializedCustomer.displayName == "[Job Location] Downtown Office Complex"
        
        and: "QBO-only fields are preserved in stored entity"
        deserializedCustomer.companyName == "Johnson Research Labs"
        deserializedCustomer.givenName == "Sarah"
        deserializedCustomer.familyName == "Johnson"
        deserializedCustomer.title == "Dr."
        deserializedCustomer.suffix == "PhD"
        deserializedCustomer.printOnCheckName == "Johnson Research Labs"
        deserializedCustomer.webAddr?.uri == "https://www.johnsonlabs.com"
        deserializedCustomer.balance == 15000.00
        deserializedCustomer.balanceWithJobs == 15000.00
        deserializedCustomer.taxable == true
        deserializedCustomer.preferredDeliveryMethod == "Email"
        deserializedCustomer.fax?.freeFormNumber == "(650) 555-7893"
        
        and: "ship address is preserved"
        deserializedCustomer.shipAddr != null
        deserializedCustomer.shipAddr.line1 == "500 Innovation Way"
        deserializedCustomer.shipAddr.line2 == "Loading Dock B"
    }

    def "should return null for Customer without Job Location marker"() {
        given: "a regular customer without the [Job Location] prefix"
        def qboCustomer = QBOTestFixtures.loadCustomerNotJobLocation()

        when:
        def party = customerMapper.toDomain(qboCustomer)

        then: "the mapper returns null (we only map job locations)"
        party == null
    }

    def "should map minimal Customer with Job Location marker"() {
        given:
        def qboCustomer = QBOTestFixtures.loadCustomerMinimal()

        when:
        def party = customerMapper.toDomain(qboCustomer)

        then:
        party != null
        party.externalId == "789"
        party.name == "Warehouse A" // [Job Location] prefix stripped
        party.tag == PartyTag.SHIP_TO
        party.status == Party.PartyStatus.ACTIVE

        and: "optional fields are null"
        party.phone == null
        party.email == null
        party.address == null
        party.notes == null
    }

    def "should map inactive Customer with ARCHIVED status"() {
        given:
        def qboCustomer = QBOTestFixtures.loadCustomer()
        qboCustomer.active = false

        when:
        def party = customerMapper.toDomain(qboCustomer)

        then:
        party.status == Party.PartyStatus.ARCHIVED
    }

    def "should use alternate phone when primary is null"() {
        given:
        def qboCustomer = QBOTestFixtures.loadCustomer()
        qboCustomer.primaryPhone = null

        when:
        def party = customerMapper.toDomain(qboCustomer)

        then:
        party.phone == "(650) 555-7891" // AlternatePhone
    }

    def "should use mobile phone when primary and alternate are null"() {
        given:
        def qboCustomer = QBOTestFixtures.loadCustomer()
        qboCustomer.primaryPhone = null
        qboCustomer.alternatePhone = null

        when:
        def party = customerMapper.toDomain(qboCustomer)

        then:
        party.phone == "(650) 555-7892" // Mobile
    }

    def "should return null when customer is null"() {
        expect:
        customerMapper.toDomain(null) == null
    }

    // ==================== toQboCustomer Tests ====================

    def "should convert Party to QBO Customer for CREATE with Job Location prefix"() {
        given:
        def party = Party.builder()
                .name("New Warehouse Location")
                .phone("(555) 123-4567")
                .email("warehouse@company.com")
                .notes("New warehouse location notes")
                .address(Address.builder()
                        .address("456 Warehouse Lane")
                        .city("Oakland")
                        .country("USA"                        )
                        .stateOrProvince("CA")
                        .postalCode("94612")
                        .build())
                .tag(PartyTag.SHIP_TO)
                .build()

        when:
        def qboCustomer = customerMapper.toQboCustomer(party)

        then:
        qboCustomer != null

        and: "name includes Job Location prefix"
        qboCustomer.displayName == "[Job Location] New Warehouse Location"

        and: "contact info is mapped"
        qboCustomer.primaryPhone?.freeFormNumber == "(555) 123-4567"
        qboCustomer.primaryEmailAddr?.address == "warehouse@company.com"
        qboCustomer.notes == "New warehouse location notes"

        and: "address is mapped"
        qboCustomer.billAddr != null
        qboCustomer.billAddr.line1 == "456 Warehouse Lane"
        qboCustomer.billAddr.city == "Oakland"
        qboCustomer.billAddr.country == "USA"
        qboCustomer.billAddr.countrySubDivisionCode == "CA"
        qboCustomer.billAddr.postalCode == "94612"

        and: "no Id or SyncToken for CREATE"
        qboCustomer.id == null
        qboCustomer.syncToken == null
    }

    def "should convert Party to QBO Customer for UPDATE (with external ID)"() {
        given:
        def party = Party.builder()
                .name("Updated Location Name")
                .phone("(555) 999-8888")
                .email("updated@location.com")
                .build()
        party.externalId = "123"
        party.providerVersion = "2"

        when:
        def qboCustomer = customerMapper.toQboCustomer(party)

        then:
        qboCustomer != null
        qboCustomer.displayName == "[Job Location] Updated Location Name"

        and: "Id and SyncToken are set for UPDATE"
        qboCustomer.id == "123"
        qboCustomer.syncToken == "2"
    }

    def "should not add duplicate Job Location prefix"() {
        given:
        def party = Party.builder()
                .name("[Job Location] Already Prefixed Name")
                .build()

        when:
        def qboCustomer = customerMapper.toQboCustomer(party)

        then: "should not double-prefix"
        qboCustomer.displayName == "[Job Location] Already Prefixed Name"
    }

    def "should preserve stored QBO entity fields during UPDATE - only override what we set"() {
        given: "a party that was previously synced from QBO"
        def originalCustomer = QBOTestFixtures.loadCustomer()
        def party = customerMapper.toDomain(originalCustomer)

        and: "only specific fields are modified"
        party.name = "Modified Location Name"
        party.phone = "(999) 000-1111"
        // Note: we are NOT modifying email, notes, address, etc.

        when:
        def qboCustomer = customerMapper.toQboCustomer(party)

        then: "modified fields are applied with prefix"
        qboCustomer.displayName == "[Job Location] Modified Location Name"
        qboCustomer.primaryPhone?.freeFormNumber == "(999) 000-1111"

        and: "Id and SyncToken are preserved for UPDATE"
        qboCustomer.id == "123"
        qboCustomer.syncToken == "2"

        and: "QBO-only fields that we don't map to domain are preserved"
        qboCustomer.companyName == "Johnson Research Labs"
        qboCustomer.givenName == "Sarah"
        qboCustomer.familyName == "Johnson"
        qboCustomer.title == "Dr."
        qboCustomer.suffix == "PhD"
        qboCustomer.printOnCheckName == "Johnson Research Labs"
        qboCustomer.webAddr?.uri == "https://www.johnsonlabs.com"
        qboCustomer.balance == 15000.00
        qboCustomer.balanceWithJobs == 15000.00
        qboCustomer.taxable == true

        and: "ship address is preserved (we only update billAddr)"
        qboCustomer.shipAddr != null
        qboCustomer.shipAddr.line1 == "500 Innovation Way"
        qboCustomer.shipAddr.line2 == "Loading Dock B"

        and: "address structure is preserved (Line2, Id, Lat, Long)"
        qboCustomer.billAddr != null
        qboCustomer.billAddr.id == "201"
        qboCustomer.billAddr.line2 == "Building A, Floor 3"
        qboCustomer.billAddr.lat == "37.3861"
        qboCustomer.billAddr._long == "-122.0839"
    }

    def "should preserve all QBO fields in complete round-trip"() {
        given: "original QBO customer with all fields"
        def originalCustomer = QBOTestFixtures.loadCustomer()

        when: "convert to domain and back without modifications"
        def party = customerMapper.toDomain(originalCustomer)
        def roundTripCustomer = customerMapper.toQboCustomer(party)

        then: "all critical fields are preserved"
        roundTripCustomer.id == originalCustomer.id
        roundTripCustomer.syncToken == originalCustomer.syncToken
        roundTripCustomer.displayName == originalCustomer.displayName
        roundTripCustomer.companyName == originalCustomer.companyName
        roundTripCustomer.givenName == originalCustomer.givenName
        roundTripCustomer.familyName == originalCustomer.familyName
        roundTripCustomer.title == originalCustomer.title
        roundTripCustomer.suffix == originalCustomer.suffix
        roundTripCustomer.printOnCheckName == originalCustomer.printOnCheckName
        roundTripCustomer.active == originalCustomer.active
        roundTripCustomer.balance == originalCustomer.balance
        roundTripCustomer.balanceWithJobs == originalCustomer.balanceWithJobs
        roundTripCustomer.taxable == originalCustomer.taxable

        and: "references are preserved"
        roundTripCustomer.currencyRef?.value == originalCustomer.currencyRef?.value
        roundTripCustomer.defaultTaxCodeRef?.value == originalCustomer.defaultTaxCodeRef?.value

        and: "all contact info is preserved"
        roundTripCustomer.primaryEmailAddr?.address == originalCustomer.primaryEmailAddr?.address
        roundTripCustomer.webAddr?.uri == originalCustomer.webAddr?.uri
        roundTripCustomer.fax?.freeFormNumber == originalCustomer.fax?.freeFormNumber
        roundTripCustomer.alternatePhone?.freeFormNumber == originalCustomer.alternatePhone?.freeFormNumber
        roundTripCustomer.mobile?.freeFormNumber == originalCustomer.mobile?.freeFormNumber

        and: "bill address structure is preserved (note: line1 contains combined address from domain)"
        roundTripCustomer.billAddr?.id == originalCustomer.billAddr?.id
        // line1 gets combined address from domain model
        roundTripCustomer.billAddr?.line1?.contains("500 Innovation Way")
        roundTripCustomer.billAddr?.line2 == originalCustomer.billAddr?.line2
        roundTripCustomer.billAddr?.city == originalCustomer.billAddr?.city
        roundTripCustomer.billAddr?.country == originalCustomer.billAddr?.country
        roundTripCustomer.billAddr?.countrySubDivisionCode == originalCustomer.billAddr?.countrySubDivisionCode
        roundTripCustomer.billAddr?.postalCode == originalCustomer.billAddr?.postalCode
        roundTripCustomer.billAddr?.lat == originalCustomer.billAddr?.lat
        roundTripCustomer.billAddr?._long == originalCustomer.billAddr?._long

        and: "ship address structure is preserved"
        roundTripCustomer.shipAddr?.id == originalCustomer.shipAddr?.id
        roundTripCustomer.shipAddr?.line1 == originalCustomer.shipAddr?.line1
        roundTripCustomer.shipAddr?.line2 == originalCustomer.shipAddr?.line2
    }

    def "should only override domain-mapped fields when updating"() {
        given: "a party synced from QBO"
        def originalCustomer = QBOTestFixtures.loadCustomer()
        def party = customerMapper.toDomain(originalCustomer)

        and: "we update only specific domain fields"
        def newName = "Completely New Location Name"
        def newEmail = "newemail@newdomain.com"
        def newAddress = Address.builder()
                .address("999 New Street")
                .city("New City")
                .country("Canada")
                .stateOrProvince("ON")
                .postalCode("M5V 1A1")
                .build()
        
        party.name = newName
        party.email = newEmail
        party.address = newAddress

        when:
        def qboCustomer = customerMapper.toQboCustomer(party)

        then: "updated fields are changed"
        qboCustomer.displayName == "[Job Location] " + newName
        qboCustomer.primaryEmailAddr?.address == newEmail
        qboCustomer.billAddr?.line1 == "999 New Street"
        qboCustomer.billAddr?.city == "New City"
        qboCustomer.billAddr?.country == "Canada"
        qboCustomer.billAddr?.countrySubDivisionCode == "ON"
        qboCustomer.billAddr?.postalCode == "M5V 1A1"

        and: "but QBO-only fields remain unchanged"
        qboCustomer.companyName == "Johnson Research Labs"
        qboCustomer.givenName == "Sarah"
        qboCustomer.familyName == "Johnson"
        qboCustomer.balance == 15000.00
        qboCustomer.taxable == true

        and: "address fields we don't map are preserved (Line2, Id, Lat, Long)"
        qboCustomer.billAddr?.id == "201"
        qboCustomer.billAddr?.line2 == "Building A, Floor 3"
        qboCustomer.billAddr?.lat == "37.3861"
        qboCustomer.billAddr?._long == "-122.0839"

        and: "ship address is completely preserved"
        qboCustomer.shipAddr?.line1 == "500 Innovation Way"
        qboCustomer.shipAddr?.line2 == "Loading Dock B"
    }

    def "should return null when party is null"() {
        expect:
        customerMapper.toQboCustomer(null) == null
    }

    // ==================== Address Mapping Tests ====================

    def "should combine address lines correctly"() {
        given:
        def qboCustomer = QBOTestFixtures.loadCustomer()

        when:
        def party = customerMapper.toDomain(qboCustomer)

        then: "address lines are combined"
        party.address.address.contains("500 Innovation Way")
        party.address.address.contains("Building A, Floor 3")
    }

    def "should handle null address gracefully"() {
        given:
        def qboCustomer = QBOTestFixtures.loadCustomerMinimal()
        qboCustomer.billAddr = null

        when:
        def party = customerMapper.toDomain(qboCustomer)

        then:
        party.address == null
    }
}

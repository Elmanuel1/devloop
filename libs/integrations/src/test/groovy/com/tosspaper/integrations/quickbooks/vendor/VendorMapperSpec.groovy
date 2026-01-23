package com.tosspaper.integrations.quickbooks.vendor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.intuit.ipp.data.Vendor
import com.tosspaper.integrations.fixtures.QBOTestFixtures
import com.tosspaper.models.domain.Address
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PartyTag
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification
import spock.lang.Subject

class VendorMapperSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())

    @Subject
    VendorMapper vendorMapper = new VendorMapper(objectMapper)

    // ==================== toDomain Tests ====================

    def "should map full Vendor to Party with all fields"() {
        given:
        def qboVendor = QBOTestFixtures.loadVendor()

        when:
        def party = vendorMapper.toDomain(qboVendor)

        then:
        party != null

        and: "external ID and provider tracking fields are mapped"
        party.externalId == "56"
        party.providerVersion == "3"
        party.provider == IntegrationProvider.QUICKBOOKS.value

        and: "name and contact info are mapped"
        party.name == "Thompson Industrial Supplies"
        party.phone == "(415) 555-1234"
        party.email == "robert.thompson@thompsonindustrial.com"
        party.notes == "Preferred vendor for industrial equipment. Volume discount available for orders over \$10,000."

        and: "address is mapped correctly"
        party.address != null
        party.address.address.contains("Thompson Industrial Supplies")
        party.address.address.contains("1500 Industrial Blvd, Suite 400")
        party.address.city == "San Francisco"
        party.address.country == "USA"
        party.address.stateOrProvince == "CA"
        party.address.postalCode == "94107"

        and: "tag and status are set correctly"
        party.tag == PartyTag.SUPPLIER
        party.status == Party.PartyStatus.ACTIVE

        and: "currency is mapped"
        party.currencyCode == Currency.USD

        and: "provider timestamps are mapped"
        party.providerCreatedAt != null
        party.providerLastUpdatedAt != null

        and: "external metadata contains original QBO entity that can be deserialized"
        party.externalMetadata != null
        party.externalMetadata.containsKey('qboEntity')
        
        and: "the stored QBO entity can be deserialized back to Vendor"
        def storedJson = party.externalMetadata.get('qboEntity') as String
        def deserializedVendor = objectMapper.readValue(storedJson, Vendor)
        deserializedVendor.id == "56"
        deserializedVendor.syncToken == "3"
        deserializedVendor.displayName == "Thompson Industrial Supplies"
        
        and: "QBO-only fields are preserved in stored entity"
        deserializedVendor.companyName == "Thompson Industrial Supplies Ltd."
        deserializedVendor.givenName == "Robert"
        deserializedVendor.familyName == "Thompson"
        deserializedVendor.title == "Mr."
        deserializedVendor.suffix == "Jr."
        deserializedVendor.printOnCheckName == "Thompson Industrial Supplies Ltd."
        deserializedVendor.webAddr?.uri == "https://www.thompsonindustrial.com"
        deserializedVendor.taxIdentifier == "12-3456789"
        deserializedVendor.acctNum == "VENDOR-2024-056"
        deserializedVendor.vendor1099 == true
        deserializedVendor.balance == 2500.00
        deserializedVendor.termRef?.value == "3"
        deserializedVendor.fax?.freeFormNumber == "(415) 555-3456"
    }

    def "should map minimal Vendor with only required fields"() {
        given:
        def qboVendor = QBOTestFixtures.loadVendorMinimal()

        when:
        def party = vendorMapper.toDomain(qboVendor)

        then:
        party != null
        party.externalId == "99"
        party.providerVersion == "0"
        party.name == "Quick Parts Inc"
        party.tag == PartyTag.SUPPLIER
        party.status == Party.PartyStatus.ACTIVE

        and: "optional fields are null"
        party.phone == null
        party.email == null
        party.address == null
        party.notes == null
        party.currencyCode == null
    }

    def "should map inactive Vendor with ARCHIVED status"() {
        given:
        def qboVendor = QBOTestFixtures.loadVendorInactive()

        when:
        def party = vendorMapper.toDomain(qboVendor)

        then:
        party != null
        party.externalId == "42"
        party.name == "Old Supplies Co (Inactive)"
        party.status == Party.PartyStatus.ARCHIVED
        party.notes == "Vendor no longer in business as of November 2024."
    }

    def "should use alternate phone when primary is null"() {
        given:
        def qboVendor = QBOTestFixtures.loadVendor()
        qboVendor.primaryPhone = null

        when:
        def party = vendorMapper.toDomain(qboVendor)

        then:
        party.phone == "(415) 555-5678" // AlternatePhone
    }

    def "should use mobile phone when primary and alternate are null"() {
        given:
        def qboVendor = QBOTestFixtures.loadVendor()
        qboVendor.primaryPhone = null
        qboVendor.alternatePhone = null

        when:
        def party = vendorMapper.toDomain(qboVendor)

        then:
        party.phone == "(415) 555-9012" // Mobile
    }

    def "should return null when vendor is null"() {
        expect:
        vendorMapper.toDomain(null) == null
    }

    // ==================== toQboVendor Tests ====================

    def "should convert Party to QBO Vendor for CREATE (no external ID)"() {
        given:
        def party = Party.builder()
                .name("New Test Vendor")
                .phone("(555) 123-4567")
                .email("test@vendor.com")
                .notes("Test vendor notes")
                .address(Address.builder()
                        .address("123 Test Street")
                        .city("Test City")
                        .country("USA")
                        .stateOrProvince("TX")
                        .postalCode("12345")
                        .build())
                .currencyCode(Currency.USD)
                .build()

        when:
        def qboVendor = vendorMapper.toQboVendor(party)

        then:
        qboVendor != null
        qboVendor.displayName == "New Test Vendor"
        qboVendor.primaryPhone?.freeFormNumber == "(555) 123-4567"
        qboVendor.primaryEmailAddr?.address == "test@vendor.com"
        qboVendor.notes == "Test vendor notes"

        and: "address is mapped"
        qboVendor.billAddr != null
        qboVendor.billAddr.line1 == "123 Test Street"
        qboVendor.billAddr.city == "Test City"
        qboVendor.billAddr.country == "USA"
        qboVendor.billAddr.countrySubDivisionCode == "TX"
        qboVendor.billAddr.postalCode == "12345"

        and: "currency is set for CREATE"
        qboVendor.currencyRef?.value == "USD"

        and: "no Id or SyncToken for CREATE"
        qboVendor.id == null
        qboVendor.syncToken == null
    }

    def "should convert Party to QBO Vendor for UPDATE (with external ID)"() {
        given:
        def party = Party.builder()
                .name("Updated Vendor Name")
                .phone("(555) 999-8888")
                .email("updated@vendor.com")
                .build()
        party.externalId = "56"
        party.providerVersion = "3"

        when:
        def qboVendor = vendorMapper.toQboVendor(party)

        then:
        qboVendor != null
        qboVendor.displayName == "Updated Vendor Name"

        and: "Id and SyncToken are set for UPDATE"
        qboVendor.id == "56"
        qboVendor.syncToken == "3"
    }

    def "should preserve stored QBO entity fields during UPDATE - only override what we set"() {
        given: "a party that was previously synced from QBO"
        def originalVendor = QBOTestFixtures.loadVendor()
        def party = vendorMapper.toDomain(originalVendor)

        and: "only specific fields are modified"
        party.name = "Modified Vendor Name"
        party.phone = "(999) 000-1111"
        // Note: we are NOT modifying email, notes, address, etc.

        when:
        def qboVendor = vendorMapper.toQboVendor(party)

        then: "modified fields are applied"
        qboVendor.displayName == "Modified Vendor Name"
        qboVendor.primaryPhone?.freeFormNumber == "(999) 000-1111"

        and: "Id and SyncToken are preserved for UPDATE"
        qboVendor.id == "56"
        qboVendor.syncToken == "3"

        and: "QBO-only fields that we don't map to domain are preserved"
        qboVendor.companyName == "Thompson Industrial Supplies Ltd."
        qboVendor.givenName == "Robert"
        qboVendor.familyName == "Thompson"
        qboVendor.title == "Mr."
        qboVendor.suffix == "Jr."
        qboVendor.printOnCheckName == "Thompson Industrial Supplies Ltd."
        qboVendor.webAddr?.uri == "https://www.thompsonindustrial.com"
        qboVendor.taxIdentifier == "12-3456789"
        qboVendor.acctNum == "VENDOR-2024-056"
        qboVendor.vendor1099 == true
        qboVendor.balance == 2500.00
        qboVendor.termRef?.value == "3"
        qboVendor.fax?.freeFormNumber == "(415) 555-3456"

        and: "address structure is preserved (Line2, Line3, Id, Lat, Long)"
        qboVendor.billAddr != null
        qboVendor.billAddr.id == "101"
        qboVendor.billAddr.line2 == "Robert Thompson"
        qboVendor.billAddr.line3 == "1500 Industrial Blvd, Suite 400"
        qboVendor.billAddr.lat == "37.7749"
        qboVendor.billAddr._long == "-122.4194"

        and: "currency is preserved from original (not overwritten for UPDATE)"
        qboVendor.currencyRef?.value == "USD"
    }

    def "should preserve all QBO fields in complete round-trip"() {
        given: "original QBO vendor with all fields"
        def originalVendor = QBOTestFixtures.loadVendor()

        when: "convert to domain and back without modifications"
        def party = vendorMapper.toDomain(originalVendor)
        def roundTripVendor = vendorMapper.toQboVendor(party)

        then: "all critical fields are preserved"
        roundTripVendor.id == originalVendor.id
        roundTripVendor.syncToken == originalVendor.syncToken
        roundTripVendor.displayName == originalVendor.displayName
        roundTripVendor.companyName == originalVendor.companyName
        roundTripVendor.givenName == originalVendor.givenName
        roundTripVendor.familyName == originalVendor.familyName
        roundTripVendor.title == originalVendor.title
        roundTripVendor.suffix == originalVendor.suffix
        roundTripVendor.printOnCheckName == originalVendor.printOnCheckName
        roundTripVendor.active == originalVendor.active
        roundTripVendor.balance == originalVendor.balance
        roundTripVendor.vendor1099 == originalVendor.vendor1099
        roundTripVendor.taxIdentifier == originalVendor.taxIdentifier
        roundTripVendor.acctNum == originalVendor.acctNum

        and: "references are preserved"
        roundTripVendor.termRef?.value == originalVendor.termRef?.value
        roundTripVendor.termRef?.name == originalVendor.termRef?.name
        roundTripVendor.currencyRef?.value == originalVendor.currencyRef?.value

        and: "all contact info is preserved"
        roundTripVendor.primaryEmailAddr?.address == originalVendor.primaryEmailAddr?.address
        roundTripVendor.webAddr?.uri == originalVendor.webAddr?.uri
        roundTripVendor.fax?.freeFormNumber == originalVendor.fax?.freeFormNumber
        roundTripVendor.alternatePhone?.freeFormNumber == originalVendor.alternatePhone?.freeFormNumber
        roundTripVendor.mobile?.freeFormNumber == originalVendor.mobile?.freeFormNumber

        and: "address structure is preserved (note: line1 contains combined address from domain)"
        roundTripVendor.billAddr?.id == originalVendor.billAddr?.id
        // line1 gets combined address (line1 + line2 + line3) from domain model
        roundTripVendor.billAddr?.line1?.contains("Thompson Industrial Supplies")
        roundTripVendor.billAddr?.line1?.contains("Robert Thompson")
        roundTripVendor.billAddr?.line1?.contains("1500 Industrial Blvd")
        // line2 and line3 are preserved from externalMetadata
        roundTripVendor.billAddr?.line2 == originalVendor.billAddr?.line2
        roundTripVendor.billAddr?.line3 == originalVendor.billAddr?.line3
        roundTripVendor.billAddr?.city == originalVendor.billAddr?.city
        roundTripVendor.billAddr?.country == originalVendor.billAddr?.country
        roundTripVendor.billAddr?.countrySubDivisionCode == originalVendor.billAddr?.countrySubDivisionCode
        roundTripVendor.billAddr?.postalCode == originalVendor.billAddr?.postalCode
        roundTripVendor.billAddr?.lat == originalVendor.billAddr?.lat
        roundTripVendor.billAddr?._long == originalVendor.billAddr?._long
    }

    def "should only override domain-mapped fields when updating"() {
        given: "a party synced from QBO"
        def originalVendor = QBOTestFixtures.loadVendor()
        def party = vendorMapper.toDomain(originalVendor)

        and: "we update only specific domain fields"
        def newName = "Completely New Vendor Name"
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
        def qboVendor = vendorMapper.toQboVendor(party)

        then: "updated fields are changed"
        qboVendor.displayName == newName
        qboVendor.primaryEmailAddr?.address == newEmail
        qboVendor.billAddr?.line1 == "999 New Street"
        qboVendor.billAddr?.city == "New City"
        qboVendor.billAddr?.country == "Canada"
        qboVendor.billAddr?.countrySubDivisionCode == "ON"
        qboVendor.billAddr?.postalCode == "M5V 1A1"

        and: "but QBO-only fields remain unchanged"
        qboVendor.companyName == "Thompson Industrial Supplies Ltd."
        qboVendor.givenName == "Robert"
        qboVendor.familyName == "Thompson"
        qboVendor.taxIdentifier == "12-3456789"
        qboVendor.acctNum == "VENDOR-2024-056"
        qboVendor.vendor1099 == true
        qboVendor.balance == 2500.00

        and: "address fields we don't map from domain are preserved (Line2, Line3, Id, Lat, Long)"
        qboVendor.billAddr?.id == "101"
        qboVendor.billAddr?.line2 == "Robert Thompson"
        qboVendor.billAddr?.line3 == "1500 Industrial Blvd, Suite 400"
        qboVendor.billAddr?.lat == "37.7749"
        qboVendor.billAddr?._long == "-122.4194"
    }

    def "should return null when party is null"() {
        expect:
        vendorMapper.toQboVendor(null) == null
    }

    // ==================== Address Mapping Tests ====================

    def "should combine address lines correctly"() {
        given:
        def qboVendor = QBOTestFixtures.loadVendor()

        when:
        def party = vendorMapper.toDomain(qboVendor)

        then: "all address lines are combined"
        party.address.address.contains("Thompson Industrial Supplies")
        party.address.address.contains("Robert Thompson")
        party.address.address.contains("1500 Industrial Blvd, Suite 400")
    }

    def "should handle null address gracefully"() {
        given:
        def qboVendor = QBOTestFixtures.loadVendorMinimal()
        qboVendor.billAddr = null

        when:
        def party = vendorMapper.toDomain(qboVendor)

        then:
        party.address == null
    }

    // ==================== Currency Mapping Tests ====================

    def "should not set currency for UPDATE (QBO limitation)"() {
        given:
        def party = Party.builder()
                .name("Existing Vendor")
                .currencyCode(Currency.CAD)
                .build()
        party.externalId = "123"
        party.providerVersion = "1"

        when:
        def qboVendor = vendorMapper.toQboVendor(party)

        then: "currency is not set for UPDATE (QBO doesn't allow changing currency on existing vendors)"
        qboVendor.currencyRef == null
    }
}

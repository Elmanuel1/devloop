package com.tosspaper.contact

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.generated.model.Contact
import com.tosspaper.generated.model.ContactCreate
import com.tosspaper.generated.model.ContactUpdate
import com.tosspaper.generated.model.ContactTagEnum
import com.tosspaper.generated.model.ContactStatus
import com.tosspaper.generated.model.Address
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.jooq.DSLContext
import com.tosspaper.models.jooq.Tables

class ContactControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    @Autowired
    DSLContext dsl

    def setup() {
        // Create company with assigned_email - CompanyLookupService will query this from DB
        dsl.insertInto(Tables.COMPANIES)
            .set([id: 1L, name: "Test Company", email: "aribooluwatoba@gmail.com", assigned_email: "test@dev-clientdocs.useassetiq.com"])
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.APPROVED_SENDERS).execute()
        dsl.deleteFrom(Tables.CONTACTS).execute()
        dsl.deleteFrom(Tables.COMPANIES).execute()
    }

    def "GET /v1/contacts should return a list of contacts"() {
        given: "a contact exists"
            dsl.insertInto(Tables.CONTACTS)
                .set([company_id: 1L, name: "John Doe", email: "aribooluwatoba@gmail.com", status: "active", tag: "supplier"])
                .execute()

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "1")

        when: "fetching contacts"
            def uri = "/v1/contacts?tag=supplier"
            def response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "returns 200 with contact list"
            response.statusCode == HttpStatus.OK
            def contactList = objectMapper.readValue(response.body, Map)
            contactList.data.size() == 1
            contactList.data[0].name == "John Doe"
    }

    def "POST /v1/contacts should create a contact"() {
        given: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "1")

        and: "a valid request"
            def createDto = new ContactCreate(name: "John Doe", email: "aribooluwatoba@gmail.com", tag: ContactTagEnum.SUPPLIER)
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating the contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 201 CREATED with all fields"
            response.statusCode == HttpStatus.CREATED
            def contact = objectMapper.readValue(response.body, Contact)
            with(contact) {
                id != null
                name == "John Doe"
                email == "aribooluwatoba@gmail.com"
                tag == ContactTagEnum.SUPPLIER
                status == ContactStatus.ACTIVE
                createdAt != null
                updatedAt == null  // Not set on creation
            }
    }

    def "POST /v1/contacts should create a contact with all fields"() {
        given: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "1")

        and: "a request with all fields"
            def address = new Address()
            address.setAddress("123 Main St")
            address.setCity("Toronto")
            address.setStateOrProvince("ON")
            address.setPostalCode("M5H 2N2")
            address.setCountry("CA")
            
            def createDto = new ContactCreate(
                name: "Jane Smith",
                email: "jane.smith@supplier.com",
                phone: "+1-555-987-6543",
                tag: ContactTagEnum.VENDOR,
                notes: "Primary contact for billing",
                address: address,
                currencyCode: "USD"
            )
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating the contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 201 CREATED with all fields set"
            response.statusCode == HttpStatus.CREATED
            def contact = objectMapper.readValue(response.body, Contact)
            with(contact) {
                id != null
                name == "Jane Smith"
                email == "jane.smith@supplier.com"
                phone == "+1-555-987-6543"
                tag == ContactTagEnum.VENDOR
                notes == "Primary contact for billing"
                status == ContactStatus.ACTIVE
                currencyCode == "USD"
                createdAt != null
                updatedAt == null  // Not set on creation
                provider == null  // Platform-created contact
                
                // Verify address fields
                address != null
                address.address == "123 Main St"
                address.city == "Toronto"
                address.stateOrProvince == "ON"
                address.postalCode == "M5H 2N2"
                address.country == "CA"
            }
    }

    def "GET /v1/contacts/{id} should return a single contact"() {
        given: "a contact exists"
            def contactId = dsl.insertInto(Tables.CONTACTS)
                .set([company_id: 1L, name: "John Doe", email: "aribooluwatoba@gmail.com", status: "active", tag: "supplier"])
                .returning(Tables.CONTACTS.ID)
                .fetchOne()
                .id

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "1")

        when: "fetching the contact"
            def response = restTemplate.exchange("/v1/contacts/${contactId}", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "returns 200 with all fields"
            response.statusCode == HttpStatus.OK
            def contact = objectMapper.readValue(response.body, Contact)
            with(contact) {
                id == contactId
                name == "John Doe"
                email == "aribooluwatoba@gmail.com"
                tag == ContactTagEnum.SUPPLIER
                status == ContactStatus.ACTIVE
                createdAt != null
            }
    }

    def "PUT /v1/contacts/{id} should update a contact"() {
        given: "an existing contact"
            def contactId = dsl.insertInto(Tables.CONTACTS)
                .set([company_id: 1L, name: "John Doe", email: "aribooluwatoba@gmail.com", status: "active"])
                .returning(Tables.CONTACTS.ID)
                .fetchOne()
                .id

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "1")

        and: "update request"
            def updateDto = new ContactUpdate(name: "John Updated")
            def entity = new HttpEntity<>(updateDto, headers)

        when: "updating the contact"
            def response = restTemplate.exchange("/v1/contacts/${contactId}", HttpMethod.PUT, entity, Void)

        then: "returns 204 NO CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT

        and: "contact is updated in database"
            def updated = dsl.selectFrom(Tables.CONTACTS).where(Tables.CONTACTS.ID.eq(contactId)).fetchOne()
            updated.name == "John Updated"
    }

    def "PUT /v1/contacts/{id} should return 404 for non-existent contact"() {
        given: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "1")

        and: "update request"
            def updateDto = new ContactUpdate(name: "John Updated")
            def entity = new HttpEntity<>(updateDto, headers)

        when: "updating non-existent contact"
            def response = restTemplate.exchange("/v1/contacts/non-existent-id", HttpMethod.PUT, entity, String)

        then: "returns 404"
            response.statusCode == HttpStatus.NOT_FOUND
    }

    def "DELETE /v1/contacts/{id} should delete a contact"() {
        given: "an existing contact"
            def contactId = dsl.insertInto(Tables.CONTACTS)
                .set([company_id: 1L, name: "John Doe", email: "aribooluwatoba@gmail.com", status: "active"])
                .returning(Tables.CONTACTS.ID)
                .fetchOne()
                .id

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "1")
            def entity = new HttpEntity<>(headers)

        when: "deleting the contact"
            def response = restTemplate.exchange("/v1/contacts/${contactId}", HttpMethod.DELETE, entity, Void)

        then: "returns 204 NO CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT

        and: "contact no longer exists"
            def deleted = dsl.selectFrom(Tables.CONTACTS).where(Tables.CONTACTS.ID.eq(contactId)).fetchOne()
            deleted == null
    }

    def "DELETE /v1/contacts/{id} should delete archived contact"() {
        given: "an archived contact"
            def contactId = dsl.insertInto(Tables.CONTACTS)
                .set([company_id: 1L, name: "John Doe", email: "aribooluwatoba@gmail.com", status: "archived", tag: "supplier"])
                .returning(Tables.CONTACTS.ID)
                .fetchOne()
                .id

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "1")
            def entity = new HttpEntity<>(headers)

        when: "the contact is deleted"
            def response = restTemplate.exchange("/v1/contacts/${contactId}", HttpMethod.DELETE, entity, Void)

        then: "returns 204 NO CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT

        and: "contact no longer exists"
            def deleted = dsl.selectFrom(Tables.CONTACTS).where(Tables.CONTACTS.ID.eq(contactId)).fetchOne()
            deleted == null
    }
} 
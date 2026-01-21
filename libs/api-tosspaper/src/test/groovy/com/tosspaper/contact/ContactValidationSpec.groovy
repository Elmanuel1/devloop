package com.tosspaper.contact

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.generated.model.ContactCreate
import com.tosspaper.generated.model.ContactTagEnum
import com.tosspaper.models.jooq.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class ContactValidationSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    @Autowired
    DSLContext dsl

    def setup() {
        // Set up real database data - services will query this
        dsl.insertInto(Tables.COMPANIES)
            .set([id: 1L, name: "Test Company", email: "aribooluwatoba@gmail.com", assigned_email: "test@dev-clientdocs.useassetiq.com"])
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        // Clean up in reverse order of foreign keys
        dsl.deleteFrom(Tables.APPROVED_SENDERS).execute()
        dsl.deleteFrom(Tables.CONTACTS).execute()
        dsl.deleteFrom(Tables.COMPANIES).execute()
    }

    def setupHeaders() {
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.add("X-Context-Id", "1")
        return headers
    }

    // OpenAPI: name is required
    def "POST /v1/contacts returns 400 when name is missing"() {
        given: "csrf and auth headers"
            def headers = setupHeaders()

        and: "request without name"
            def createDto = new ContactCreate(tag: ContactTagEnum.SUPPLIER, email: "test@test.com")
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 400 with validation error"
            response.statusCode == HttpStatus.BAD_REQUEST
            def error = objectMapper.readValue(response.body, Map)
            error.message != null
    }

    // OpenAPI: name has @NotNull validation (blank is allowed, only null is rejected)
    def "POST /v1/contacts returns 201 when name is blank but not null"() {
        given: "csrf and auth headers"
            def headers = setupHeaders()

        and: "request with blank name (allowed by @NotNull)"
            def createDto = new ContactCreate(name: "", tag: ContactTagEnum.SUPPLIER, email: "test@test.com")
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 201 since @NotNull allows blank strings"
            response.statusCode == HttpStatus.CREATED
    }

    // OpenAPI: tag is required
    def "POST /v1/contacts returns 400 when tag is missing"() {
        given: "csrf and auth headers"
            def headers = setupHeaders()

        and: "request without tag"
            def createDto = new ContactCreate(name: "Test Contact", email: "test@test.com")
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 400 with validation error"
            response.statusCode == HttpStatus.BAD_REQUEST
            def error = objectMapper.readValue(response.body, Map)
            error.message != null
    }

    // OpenAPI: email format: email
    def "POST /v1/contacts returns 400 when email is invalid format"() {
        given: "csrf and auth headers"
            def headers = setupHeaders()

        and: "request with invalid email"
            def createDto = new ContactCreate(name: "Test Contact", tag: ContactTagEnum.SUPPLIER, email: "not-an-email")
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 400 with validation error"
            response.statusCode == HttpStatus.BAD_REQUEST
            def error = objectMapper.readValue(response.body, Map)
            error.message != null
    }

    // OpenAPI: @AtLeastOneNotNull annotation (email or phone must be provided)
    def "POST /v1/contacts returns 400 when both email and phone are missing"() {
        given: "csrf and auth headers"
            def headers = setupHeaders()

        and: "request without email or phone"
            def createDto = new ContactCreate(name: "Test Contact", tag: ContactTagEnum.SUPPLIER)
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 400 with validation error"
            response.statusCode == HttpStatus.BAD_REQUEST
            def error = objectMapper.readValue(response.body, Map)
            error.message != null
    }

    // OpenAPI: tag enum validation
    def "POST /v1/contacts returns 400 when tag is invalid enum value"() {
        given: "csrf and auth headers"
            def headers = setupHeaders()

        and: "request with invalid tag"
            def requestBody = [name: "Test Contact", tag: "invalid_tag", email: "test@test.com"]
            def entity = new HttpEntity<>(requestBody, headers)

        when: "creating contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 400 with validation error"
            response.statusCode == HttpStatus.BAD_REQUEST
            def error = objectMapper.readValue(response.body, Map)
            error.message != null
    }
}

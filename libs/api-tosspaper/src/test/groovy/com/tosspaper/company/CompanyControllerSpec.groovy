package com.tosspaper.company

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.ApiError
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.generated.model.Company
import com.tosspaper.generated.model.CompanyCreate
import com.tosspaper.generated.model.CompanyInfoUpdate
import com.tosspaper.models.jooq.tables.Companies
import com.tosspaper.models.jooq.tables.AuthorizedUsers
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class CompanyControllerSpec extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate

    @Autowired
    private DSLContext dsl

    @Autowired
    private ObjectMapper objectMapper
    
    def cleanup() {
        dsl.deleteFrom(AuthorizedUsers.AUTHORIZED_USERS).execute()
        dsl.deleteFrom(Companies.COMPANIES).execute()
    }
    
    def "should create a company and then retrieve it"() {
        given: "a valid company creation request"
        def companyCreate = new CompanyCreate(
                name: "API Test Corp",
                assignedEmail: "api-test-corp@dev-clientdocs.useassetiq.com"
        )
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        def requestEntity = new HttpEntity<>(companyCreate, headers)

        when: "the create company endpoint is called"
        def response = restTemplate.postForEntity("/v1/companies", requestEntity, String)

        then: "the response is successful and returns the created company"
        response.statusCode == HttpStatus.CREATED
        def createdCompany = objectMapper.readValue(response.body, Company)
        createdCompany.id != null
        createdCompany.name == "API Test Corp"
        createdCompany.email == TestSecurityConfiguration.TEST_USER_EMAIL

        when: "the get company endpoint is called with the new ID, which should be protected"
        def getHeaders = new HttpHeaders()
        getHeaders.setBearerAuth(TestSecurityConfiguration.getTestToken())
        def getEntity = new HttpEntity<>(getHeaders)
        def getResponse = restTemplate.exchange("/v1/companies/${createdCompany.id}", HttpMethod.GET, getEntity, Company)

        then: "the correct company is retrieved"
        getResponse.statusCode == HttpStatus.OK
        getResponse.body.name == "API Test Corp"
    }

    def "should get current user's companies"() {
        given: "an existing company for the current user"
        def companyCreate = new CompanyCreate(
                name: "My Test Company",
                assignedEmail: "my-test-company@dev-clientdocs.useassetiq.com"
        )
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        def requestEntity = new HttpEntity<>(companyCreate, headers)
        def createResponse = restTemplate.postForEntity("/v1/companies", requestEntity, String)
        def createdCompany = objectMapper.readValue(createResponse.body, Company)

        and: "verify authorized_user record was created"
        def authorizedUsers = dsl.selectFrom(AuthorizedUsers.AUTHORIZED_USERS)
                .where(AuthorizedUsers.AUTHORIZED_USERS.EMAIL.eq(TestSecurityConfiguration.TEST_USER_EMAIL))
                .fetch()
        authorizedUsers.size() == 1
        authorizedUsers[0].companyId == createdCompany.id
        authorizedUsers[0].roleId == "owner"

        when: "the get my companies endpoint is called"
        def getHeaders = new HttpHeaders()
        getHeaders.setBearerAuth(TestSecurityConfiguration.getTestToken())
        def getEntity = new HttpEntity<>(getHeaders)
        def response = restTemplate.exchange("/v1/me/companies", HttpMethod.GET, getEntity, String)

        then: "the correct list of companies is retrieved with role=owner"
        response.statusCode == HttpStatus.OK
        def companiesJson = objectMapper.readValue(response.body, List)
        companiesJson.size() == 1
        companiesJson[0].name == "My Test Company"
        companiesJson[0].email == TestSecurityConfiguration.TEST_USER_EMAIL
        companiesJson[0].role == "owner"
    }

    def "should return 409 Conflict for duplicate company email"() {
        given: "an existing company"
        def companyCreate = new CompanyCreate(
                name: "Original",
                assignedEmail: "original@dev-clientdocs.useassetiq.com"
        )
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        restTemplate.postForEntity("/v1/companies", new HttpEntity<>(companyCreate, headers), String)

        when: "creating a new company with the same email"
        def duplicateRequest = new HttpEntity<>(new CompanyCreate(
                name: "Duplicate",
                assignedEmail: "duplicate@dev-clientdocs.useassetiq.com"
        ), headers)
        def response = restTemplate.postForEntity("/v1/companies", duplicateRequest, String)

        then: "a 409 Conflict status and the correct error code are returned"
        response.statusCode == HttpStatus.CONFLICT
        def apiError = objectMapper.readValue(response.body, ApiError)
        apiError.code() == "api.company.duplicate"
    }

    def "should return 404 Not Found for non-existent company"() {
        when: "requesting a non-existent company"
        def headers = new HttpHeaders()
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        def requestEntity = new HttpEntity<>(headers)
        def response = restTemplate.exchange("/v1/companies/999999", HttpMethod.GET, requestEntity, String)

        then: "a 404 Not Found status and the correct error code are returned"
        response.statusCode == HttpStatus.NOT_FOUND
        def apiError = objectMapper.readValue(response.body, ApiError)
        apiError.code() == "api.company.notFound"
    }

    def "should create a company with all fields"() {
        given: "a valid company creation request with all fields"
        def companyCreate = new CompanyCreate(
            name: "API Full Corp",
            assignedEmail: "api-full-corp@dev-clientdocs.useassetiq.com",
            description: "Full description",
            logoUrl: "http://logo.url/api",
            termsUrl: "http://terms.url/api",
            privacyUrl: "http://privacy.url/api"
        )
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        def requestEntity = new HttpEntity<>(companyCreate, headers)

        when: "the create company endpoint is called"
        def response = restTemplate.postForEntity("/v1/companies", requestEntity, Company)

        then: "the company is created with all fields populated"
        response.statusCode == HttpStatus.CREATED
        with(response.body) {
            name == "API Full Corp"
            description == "Full description"
            logoUrl == "http://logo.url/api"
        }
    }

    def "should create a company with only required fields"() {
        given: "a valid company creation request with only required fields"
        def companyCreate = new CompanyCreate(
                name: "API Minimal Corp",
                assignedEmail: "api-minimal-corp@dev-clientdocs.useassetiq.com"
        )
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        def requestEntity = new HttpEntity<>(companyCreate, headers)

        when: "the create company endpoint is called"
        def response = restTemplate.postForEntity("/v1/companies", requestEntity, String)

        then: "the response is successful and returns the created company with null optional fields"
        response.statusCode == HttpStatus.CREATED
        def createdCompany = objectMapper.readValue(response.body, Company)
        createdCompany.id != null
        createdCompany.name == "API Minimal Corp"
        createdCompany.description == null
    }

    def "should return 400 Bad Request for empty update"() {
        given: "an existing company"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        def createResponse = restTemplate.postForEntity("/v1/companies", new HttpEntity<>(new CompanyCreate(
                name: "To Update",
                assignedEmail: "to-update-1@dev-clientdocs.useassetiq.com"
        ), headers), Company)
        def companyId = createResponse.body.id

        and: "an empty update request"
        def emptyUpdate = new CompanyInfoUpdate()
        def requestEntity = new HttpEntity<>(emptyUpdate, headers)

        when: "the update endpoint is called with the empty request"
        def response = restTemplate.exchange("/v1/companies/${companyId}", HttpMethod.PUT, requestEntity, String)

        then: "a 400 Bad Request status is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "should successfully update a company's information"() {
        given: "an existing company"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        def createResponse = restTemplate.postForEntity("/v1/companies", new HttpEntity<>(new CompanyCreate(
                name: "To Update",
                assignedEmail: "to-update-2@dev-clientdocs.useassetiq.com"
        ), headers), Company)
        def createdCompanyId = createResponse.body.id

        and: "an update request for the company's info"
        def companyUpdate = new CompanyInfoUpdate(description: "New Description")
        def updateEntity = new HttpEntity<>(companyUpdate, headers)

        when: "the update company endpoint is called"
        def updateResponse = restTemplate.exchange("/v1/companies/${createdCompanyId}", HttpMethod.PUT, updateEntity, Void)

        then: "the update is successful"
        updateResponse.statusCode == HttpStatus.NO_CONTENT

        when: "the company is retrieved again"
        def getHeaders = new HttpHeaders()
        getHeaders.setBearerAuth(TestSecurityConfiguration.getTestToken())
        def getEntity = new HttpEntity<>(getHeaders)
        def getResponse = restTemplate.exchange("/v1/companies/${createdCompanyId}", HttpMethod.GET, getEntity, Company)

        then: "the company's description is updated"
        getResponse.statusCode == HttpStatus.OK
        getResponse.body.description == "New Description"
    }

    def "should return 401 Unauthorized without token"() {
        when: "calling an endpoint without an auth token"
        def response = restTemplate.getForEntity("/v1/companies/1", String)

        then: "an 401 Unauthorized status is returned"
        response.statusCode == HttpStatus.UNAUTHORIZED
    }

    def "should return 400 Bad Request for invalid URLs"() {
        given: "a company creation request with invalid URLs"
        def companyCreate = new CompanyCreate(
            name: "Invalid URL Corp",
            logoUrl: "invalid-url",
            termsUrl: "http://valid.url/terms",
            privacyUrl: "http://valid.url/privacy"
        )
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        def requestEntity = new HttpEntity<>(companyCreate, headers)

        when: "the create company endpoint is called"
        def response = restTemplate.postForEntity("/v1/companies", requestEntity, String)

        then: "a 400 Bad Request status is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "should save metadata to database but not return it in response"() {
        given: "a company creation request with metadata"
        def metadata = [
            compliance: [
                province: "BC",
                rules: ["DEPOSIT_SECURITY", "DEPOSIT_PET_DAMAGE"]
            ],
            settings: [
                allowPetDeposit: true,
                maxSecurityDeposit: 0.5
            ]
        ]
        def companyCreate = new CompanyCreate(
            name: "Metadata Test Corp",
            description: "Company with metadata",
            assignedEmail: "metadata-test-corp@dev-clientdocs.useassetiq.com",
            metadata: metadata
        )
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        def requestEntity = new HttpEntity<>(companyCreate, headers)

        when: "the create company endpoint is called"
        def response = restTemplate.postForEntity("/v1/companies", requestEntity, String)

        then: "the response is successful and does not contain metadata"
        response.statusCode == HttpStatus.CREATED
        def createdCompany = objectMapper.readValue(response.body, Company)
        createdCompany.id != null
        createdCompany.name == "Metadata Test Corp"
        createdCompany.description == "Company with metadata"
        // metadata is not exposed in the Company response model

        when: "the database is queried directly for the company"
        def dbRecord = dsl.selectFrom(Companies.COMPANIES)
                .where(Companies.COMPANIES.ID.eq(createdCompany.id))
                .fetchOne()

        then: "the metadata is saved in the database with correct values"
        dbRecord != null
        dbRecord.metadata != null
        def savedMetadata = objectMapper.readValue(dbRecord.metadata.data(), Map)
        savedMetadata.compliance.province == "BC"
        savedMetadata.compliance.rules == ["DEPOSIT_SECURITY", "DEPOSIT_PET_DAMAGE"]
        savedMetadata.settings.allowPetDeposit == true
        savedMetadata.settings.maxSecurityDeposit == 0.5
    }
} 
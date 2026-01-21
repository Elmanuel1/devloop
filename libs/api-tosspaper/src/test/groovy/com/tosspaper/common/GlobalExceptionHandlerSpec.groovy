package com.tosspaper.common

import com.tosspaper.common.ApiError
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.generated.model.*
import com.tosspaper.models.jooq.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerSpec extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate

    @Autowired
    private DSLContext dsl

    def setup() {
        dsl.insertInto(Tables.COMPANIES)
                .set([
                        id: TestSecurityConfiguration.TEST_COMPANY_ID,
                        name: "Test Company",
                        email: TestSecurityConfiguration.TEST_USER_EMAIL
                ])
                .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.PROJECTS).execute()
        dsl.deleteFrom(Tables.COMPANIES).execute()
    }

    def "should handle MissingRequestHeaderException when X-Context-Id header is missing"() {
        given: "auth headers without X-Context-Id"
        def headers = new HttpHeaders()
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        // Intentionally NOT adding X-Context-Id header

        and: "an http entity"
        def entity = new HttpEntity<>(headers)

        when: "calling an endpoint without the required X-Context-Id header"
        def response = restTemplate.exchange("/v1/projects", HttpMethod.GET, entity, ApiError)

        then: "a BAD_REQUEST response is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
        with(response.body) {
            code == "api.validation.error"
            message == "Required header 'X-Context-Id' is missing"
        }
    }

    def "should handle MissingRequestHeaderException for project creation without header"() {
        given: "a csrf token and auth headers without X-Context-Id"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        // Intentionally NOT adding X-Context-Id header

        and: "a valid project creation request"
        def createRequest = new ProjectCreate(key: "TEST", name: "Test Project")
        def createEntity = new HttpEntity<>(createRequest, headers)

        when: "calling create project endpoint without the required X-Context-Id header"
        def response = restTemplate.postForEntity("/v1/projects", createEntity, ApiError)

        then: "a BAD_REQUEST response is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
        with(response.body) {
            code == "api.validation.error"
            message == "Required header 'X-Context-Id' is missing"
        }
    }

    def "should handle FileUploadException for file size exceeding 3MB limit"() {
        given: "a csrf token and auth headers with X-Context-Id"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", TestSecurityConfiguration.TEST_COMPANY_ID.toString())

        and: "a request with file size exceeding 3MB"
        def createRequest = new CreatePresignedUrlRequest()
        createRequest.setKey("company-logos/large-file.png")
        createRequest.setSize(4 * 1024 * 1024L) // 4MB
        createRequest.setContentType(CreatePresignedUrlRequest.ContentTypeEnum.IMAGE_PNG)
        def createEntity = new HttpEntity<>(createRequest, headers)

        when: "calling create presigned URL endpoint with oversized file"
        def response = restTemplate.postForEntity("/v1/files/presigned-urls", createEntity, ApiError)

        then: "a BAD_REQUEST response is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
        with(response.body) {
            code == "file.upload.error"
            message == "Please upload a file less than 3 MB"
        }
    }

    def "should handle FileUploadException for content type mismatch"() {
        given: "a csrf token and auth headers with X-Context-Id"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", TestSecurityConfiguration.TEST_COMPANY_ID.toString())

        and: "a request with mismatched content type and file extension"
        def createRequest = new CreatePresignedUrlRequest()
        createRequest.setKey("company-logos/logo.png")
        createRequest.setSize(1024L)
        createRequest.setContentType(CreatePresignedUrlRequest.ContentTypeEnum.IMAGE_JPEG)
        def createEntity = new HttpEntity<>(createRequest, headers)

        when: "calling create presigned URL endpoint with mismatched content type"
        def response = restTemplate.postForEntity("/v1/files/presigned-urls", createEntity, ApiError)

        then: "a BAD_REQUEST response is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
        with(response.body) {
            code == "file.upload.error"
            message.contains("File extension is not supported")
        }
    }

    def "should handle FileUploadException for file without extension"() {
        given: "a csrf token and auth headers with X-Context-Id"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", TestSecurityConfiguration.TEST_COMPANY_ID.toString())

        and: "a request with key without extension"
        def createRequest = new CreatePresignedUrlRequest()
        createRequest.setKey("company-logos/logo")
        createRequest.setSize(1024L)
        createRequest.setContentType(CreatePresignedUrlRequest.ContentTypeEnum.IMAGE_PNG)
        def createEntity = new HttpEntity<>(createRequest, headers)

        when: "calling create presigned URL endpoint with file without extension"
        def response = restTemplate.postForEntity("/v1/files/presigned-urls", createEntity, ApiError)

        then: "a BAD_REQUEST response is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
        with(response.body) {
            code == "file.upload.error"
            message == "File must have a valid extension"
        }
    }

    def "should handle FileDeleteException"() {
        given: "a csrf token and auth headers with X-Context-Id"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", TestSecurityConfiguration.TEST_COMPANY_ID.toString())

        and: "a delete request that will cause an error"
        def deleteRequest = new DeletePresignedUrlRequest()
        deleteRequest.setKey("invalid-key")
        def deleteEntity = new HttpEntity<>(deleteRequest, headers)

        when: "calling delete presigned URL endpoint"
        def response = restTemplate.exchange("/v1/files/presigned-urls", HttpMethod.DELETE, deleteEntity, ApiError)

        then: "a BAD_REQUEST response is returned for delete errors"
        response.statusCode == HttpStatus.BAD_REQUEST
        with(response.body) {
            code == "file.delete.error"
            message.contains("Failed to generate presigned delete URL")
        }
    }

    def "should handle NotFoundException"() {
        given: "auth headers"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", TestSecurityConfiguration.TEST_COMPANY_ID.toString())

        when: "accessing a non-existent project"
        def response = restTemplate.exchange("/v1/projects/non-existent-id", HttpMethod.GET, new HttpEntity<>(headers), ApiError)

        then: "a NOT_FOUND response is returned"
        response.statusCode == HttpStatus.NOT_FOUND
        with(response.body) {
            code == "api.project.notFound"
        }
    }

    def "should handle ForbiddenException"() {
        given: "auth headers for a different company"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", "999") // Different company ID

        and: "a project that exists in company 1"
        def project = createProject("PROJ1", "Project 1")

        when: "accessing project from wrong company context"
        def response = restTemplate.exchange("/v1/projects/${project.id}", HttpMethod.GET, new HttpEntity<>(headers), ApiError)

        then: "a FORBIDDEN response is returned"
        response.statusCode == HttpStatus.FORBIDDEN
        with(response.body) {
            code == "api.forbidden"
        }
    }

    def "should handle DuplicateException"() {
        given: "a project exists"
        createProject("DUP", "Duplicate Project")

        and: "a request to create another project with same key"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", TestSecurityConfiguration.TEST_COMPANY_ID.toString())
        def createRequest = new ProjectCreate(key: "DUP", name: "Another Project")

        when: "creating duplicate project"
        def response = restTemplate.postForEntity("/v1/projects", new HttpEntity<>(createRequest, headers), ApiError)

        then: "a CONFLICT response is returned"
        response.statusCode == HttpStatus.CONFLICT
        with(response.body) {
            code == "api.project.duplicate"
        }
    }

    def "should handle BadRequestException"() {
        given: "an invalid project creation request (validation error via controller manually)"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", TestSecurityConfiguration.TEST_COMPANY_ID.toString())
        
        // Use an empty key which should trigger validation error if the controller validates it 
        // or a manual BadRequestException if we can trigger one.
        // Let's use a very long key that might exceed limits or trigger a specific error.
        def createRequest = new ProjectCreate(key: "", name: "Test") 

        when: "creating project with invalid data"
        def response = restTemplate.postForEntity("/v1/projects", new HttpEntity<>(createRequest, headers), ApiError)

        then: "a BAD_REQUEST response is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
        with(response.body) {
            code == "api.validation.error"
        }
    }

    private Project createProject(String key, String name) {
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", TestSecurityConfiguration.TEST_COMPANY_ID.toString())
        def createRequest = new ProjectCreate(key: key, name: name)
        def response = restTemplate.postForEntity("/v1/projects", new HttpEntity<>(createRequest, headers), Project)
        return response.body
    }
}
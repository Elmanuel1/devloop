package com.tosspaper.project

import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.generated.model.Project
import com.tosspaper.generated.model.ProjectCreate
import com.tosspaper.generated.model.ProjectUpdate
import com.tosspaper.models.jooq.tables.records.ProjectsRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import spock.lang.Shared
import com.tosspaper.models.jooq.Tables
import org.jooq.DSLContext

class ProjectControllerSpec extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate

    @Autowired
    private DSLContext dsl

    @Shared
    private Long companyId

    @Shared
    private ProjectsRecord projectRecord

    def setup() {
        dsl.insertInto(Tables.COMPANIES)
                .set([
                        id: TestSecurityConfiguration.TEST_COMPANY_ID,
                        name: "Test Company",
                        email: TestSecurityConfiguration.TEST_USER_EMAIL
                ])
                .execute()
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID
        projectRecord = dsl.insertInto(Tables.PROJECTS)
                .set([
                        id: "01HWCJ8B9B1T6G8E7C5D4F3A2E",
                        company_id: companyId,
                        key: "TESTKEY",
                        name: "Test Project",
                        status: "active"
                ])
                .returning()
                .fetchOne()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.PURCHASE_ORDER_ITEMS).execute()
        dsl.delete(Tables.PURCHASE_ORDERS).execute()
        dsl.deleteFrom(Tables.PROJECTS).execute()
        dsl.deleteFrom(Tables.CONTACTS).execute()
        dsl.deleteFrom(Tables.COMPANIES).execute()
    }

    def "should get project by id without csrf"() {
        given: "auth headers without csrf"
        def headers = new HttpHeaders()
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        headers.add("X-Context-Id", companyId.toString())

        and: "an http entity"
        def entity = new HttpEntity<>(headers)

        when: "the get project endpoint is called"
        def response = restTemplate.exchange("/v1/projects/${projectRecord.id}", HttpMethod.GET, entity, Project)

        then: "the correct project is retrieved"
        response.statusCode == HttpStatus.OK
        with(response.body) {
            id == projectRecord.id
            key == "TESTKEY"
            name == "Test Project"
        }
    }

    def "should create and update a project"() {
        given: "a csrf token and auth headers"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.add("X-Context-Id", companyId.toString())

        and: "a valid project creation request"
        def createRequest = new ProjectCreate(key: "CRUD", name: "CRUD Project")
        def createEntity = new HttpEntity<>(createRequest, headers)

        when: "the create project endpoint is called"
        def createResponse = restTemplate.postForEntity("/v1/projects", createEntity, Project)

        then: "the project is created successfully"
        createResponse.statusCode == HttpStatus.CREATED
        def createdProject = createResponse.body
        createdProject.id != null
        createdProject.key == "CRUD"

        when: "the project is updated"
        def updateRequest = new ProjectUpdate(name: "Updated CRUD Project")
        def updateEntity = new HttpEntity<>(updateRequest, headers)
        def updateResponse = restTemplate.exchange("/v1/projects/${createdProject.id}", HttpMethod.PUT, updateEntity, Void)

        then: "the project is updated successfully"
        updateResponse.statusCode == HttpStatus.NO_CONTENT

        when: "the get project endpoint is called again"
        def getHeaders = new HttpHeaders()
        getHeaders.setBearerAuth(TestSecurityConfiguration.getTestToken())
        getHeaders.add("X-Context-Id", companyId.toString())
        def getEntity = new HttpEntity<>(getHeaders)
        def getResponse = restTemplate.exchange("/v1/projects/${createdProject.id}", HttpMethod.GET, getEntity, Project)

        then: "the updated project is retrieved"
        getResponse.statusCode == HttpStatus.OK
        getResponse.body.name == "Updated CRUD Project"
    }
} 
package com.tosspaper.delivery_slips

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.domain.DeliverySlip
import com.tosspaper.models.domain.LineItem
import com.tosspaper.models.extraction.dto.Party
import com.tosspaper.models.jooq.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

import java.time.LocalDate

class DeliverySlipControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    @Autowired
    DSLContext dsl

    @Autowired
    DeliverySlipRepository deliverySlipRepository

    Long companyId

    def setup() {
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID

        dsl.insertInto(Tables.COMPANIES)
            .set(Tables.COMPANIES.ID, companyId)
            .set(Tables.COMPANIES.NAME, "Test Company")
            .set(Tables.COMPANIES.EMAIL, "test@test.com")
            .set(Tables.COMPANIES.ASSIGNED_EMAIL, "test@dev-clientdocs.useassetiq.com")
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.DELIVERY_SLIPS).where(Tables.DELIVERY_SLIPS.COMPANY_ID.eq(companyId)).execute()
        dsl.deleteFrom(Tables.EXTRACTION_TASK).execute()
        dsl.deleteFrom(Tables.COMPANIES).where(Tables.COMPANIES.ID.eq(companyId)).execute()
    }

    // ==================== getDeliverySlips ====================

    def "getDeliverySlips returns OK with delivery slip list"() {
        given: "delivery slips exist in database"
            insertExtractionTask("task-1")
            insertExtractionTask("task-2")
            deliverySlipRepository.create(buildDeliverySlip("task-1", "PO-001", "proj-1"))
            deliverySlipRepository.create(buildDeliverySlip("task-2", "PO-001", "proj-1"))

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliverySlips with filters"
            def response = restTemplate.exchange(
                "/v1/delivery-slips?poNumber=PO-001&limit=10",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains delivery slips with all fields"
            def body = objectMapper.readValue(response.body, Map)
            body.data.size() == 2
            body.pagination != null
            with(body.data[0]) {
                id != null
                extractionTaskId != null
                companyId == this.companyId.intValue()
                documentNumber == "DS-001"
                documentDate != null
                poNumber == "PO-001"
                jobNumber == "JOB-001"
                status != null
                createdAt != null
                createdBy == null  // set by repository default
                sellerInfo != null
                sellerInfo.name == "Seller Corp"
                buyerInfo != null
                buyerInfo.name == "Buyer Inc"
                lineItems != null
                lineItems.size() == 1
            }
    }

    def "getDeliverySlips handles null optional parameters"() {
        given: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliverySlips with no optional parameters"
            def response = restTemplate.exchange(
                "/v1/delivery-slips",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK with empty data"
            response.statusCode == HttpStatus.OK
            def body = objectMapper.readValue(response.body, Map)
            body.data != null
    }

    def "getDeliverySlips returns empty list when no slips found"() {
        given: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliverySlips"
            def response = restTemplate.exchange(
                "/v1/delivery-slips",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response contains empty list"
            response.statusCode == HttpStatus.OK
            def body = objectMapper.readValue(response.body, Map)
            body.data.isEmpty()
    }

    // ==================== getDeliverySlipById ====================

    def "getDeliverySlipById returns OK with all fields"() {
        given: "a delivery slip exists via repository"
            insertExtractionTask("task-123")
            def record = deliverySlipRepository.create(buildDeliverySlip("task-123", "PO-100", "proj-A"))

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliverySlipById"
            def response = restTemplate.exchange(
                "/v1/delivery-slips/${record.id}",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains all delivery slip fields"
            def slip = objectMapper.readValue(response.body, Map)
            slip.id == record.id
            slip.extractionTaskId == "task-123"
            slip.companyId == companyId.intValue()
            slip.documentNumber == "DS-001"
            slip.documentDate != null
            slip.projectId == "proj-A"
            slip.projectName == "Test Project"
            slip.jobNumber == "JOB-001"
            slip.poNumber == "PO-100"
            slip.deliveryMethodNote == "Flatbed truck"
            slip.status == "delivered"
            slip.createdAt != null
            slip.sellerInfo != null
            slip.sellerInfo.name == "Seller Corp"
            slip.sellerInfo.role == "Seller"
            slip.buyerInfo != null
            slip.buyerInfo.name == "Buyer Inc"
            slip.buyerInfo.role == "Buyer"
            slip.lineItems != null
            slip.lineItems.size() == 1
            slip.lineItems[0].description == "Concrete Mix"
            slip.lineItems[0].quantity == 10.0
            slip.lineItems[0].unitCost == 50.0
            slip.lineItems[0].total == 500.0
            slip.lineItems[0].ticketNumber == "TK-001"
    }

    def "getDeliverySlipById returns 404 when slip not found"() {
        given: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliverySlipById with non-existent ID"
            def response = restTemplate.exchange(
                "/v1/delivery-slips/non-existent",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is 404"
            response.statusCode == HttpStatus.NOT_FOUND
    }

    // ==================== Helper Methods ====================

    private void insertExtractionTask(String assignedId) {
        dsl.insertInto(Tables.EXTRACTION_TASK)
            .set(Tables.EXTRACTION_TASK.ASSIGNED_ID, assignedId)
            .set(Tables.EXTRACTION_TASK.STORAGE_KEY, "test/storage/${assignedId}" as String)
            .set(Tables.EXTRACTION_TASK.COMPANY_ID, companyId)
            .set(Tables.EXTRACTION_TASK.EMAIL_MESSAGE_ID, UUID.randomUUID())
            .set(Tables.EXTRACTION_TASK.EMAIL_THREAD_ID, UUID.randomUUID())
            .onDuplicateKeyIgnore()
            .execute()
    }

    private DeliverySlip buildDeliverySlip(String extractionTaskId, String poNumber, String projectId) {
        DeliverySlip.builder()
            .assignedId(extractionTaskId)
            .companyId(companyId)
            .documentNumber("DS-001")
            .documentDate(LocalDate.of(2025, 6, 15))
            .poNumber(poNumber)
            .jobNumber("JOB-001")
            .projectId(projectId)
            .projectName("Test Project")
            .deliveryMethodNote("Flatbed truck")
            .sellerInfo(new Party(Party.Role.SELLER, "Seller Corp", null, "REF-001", null))
            .buyerInfo(new Party(Party.Role.BUYER, "Buyer Inc", null, "REF-002", null))
            .lineItems([
                LineItem.builder()
                    .lineNumber("1")
                    .description("Concrete Mix")
                    .unitOfMeasure("bags")
                    .quantity(10.0)
                    .unitPrice(50.0)
                    .total(500.0)
                    .ticketNumber("TK-001")
                    .build()
            ])
            .build()
    }
}

package com.tosspaper.delivery_notes

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.domain.DeliveryNote
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

class DeliveryNoteControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    @Autowired
    DSLContext dsl

    @Autowired
    DeliveryNoteRepository deliveryNoteRepository

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
        dsl.deleteFrom(Tables.DELIVERY_NOTES).where(Tables.DELIVERY_NOTES.COMPANY_ID.eq(companyId)).execute()
        dsl.deleteFrom(Tables.EXTRACTION_TASK).execute()
        dsl.deleteFrom(Tables.COMPANIES).where(Tables.COMPANIES.ID.eq(companyId)).execute()
    }

    // ==================== getDeliveryNotes ====================

    def "getDeliveryNotes returns OK with delivery note list"() {
        given: "delivery notes exist in database"
            insertExtractionTask("task-1")
            insertExtractionTask("task-2")
            deliveryNoteRepository.create(dsl, buildDeliveryNote("task-1", "PO-001", "proj-1"))
            deliveryNoteRepository.create(dsl, buildDeliveryNote("task-2", "PO-001", "proj-1"))

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliveryNotes with filters"
            def response = restTemplate.exchange(
                "/v1/delivery-notes?poNumber=PO-001&limit=10",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains delivery notes with all fields"
            def body = objectMapper.readValue(response.body, Map)
            body.data.size() == 2
            body.pagination != null
            with(body.data[0]) {
                id != null
                extractionTaskId != null
                companyId == this.companyId.intValue()
                documentNumber == "DN-001"
                documentDate != null
                poNumber == "PO-001"
                jobNumber == "JOB-100"
                status != null
                createdAt != null
                sellerInfo != null
                sellerInfo.name == "Supplier Ltd"
                buyerInfo != null
                buyerInfo.name == "Receiver Co"
                lineItems != null
                lineItems.size() == 1
            }
    }

    def "getDeliveryNotes handles null optional parameters"() {
        given: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliveryNotes with no optional parameters"
            def response = restTemplate.exchange(
                "/v1/delivery-notes",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK with empty data"
            response.statusCode == HttpStatus.OK
            def body = objectMapper.readValue(response.body, Map)
            body.data != null
    }

    def "getDeliveryNotes returns empty list when no notes found"() {
        given: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliveryNotes"
            def response = restTemplate.exchange(
                "/v1/delivery-notes",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response contains empty list"
            response.statusCode == HttpStatus.OK
            def body = objectMapper.readValue(response.body, Map)
            body.data.isEmpty()
    }

    // ==================== getDeliveryNoteById ====================

    def "getDeliveryNoteById returns OK with all fields"() {
        given: "a delivery note exists via repository"
            insertExtractionTask("task-123")
            def record = deliveryNoteRepository.create(dsl, buildDeliveryNote("task-123", "PO-200", "proj-B"))

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliveryNoteById"
            def response = restTemplate.exchange(
                "/v1/delivery-notes/${record.id}",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains all delivery note fields"
            def note = objectMapper.readValue(response.body, Map)
            note.id == record.id
            note.extractionTaskId == "task-123"
            note.companyId == companyId.intValue()
            note.documentNumber == "DN-001"
            note.documentDate != null
            note.projectId == "proj-B"
            note.jobNumber == "JOB-100"
            note.poNumber == "PO-200"
            note.status == "delivered"
            note.createdAt != null
            note.sellerInfo != null
            note.sellerInfo.name == "Supplier Ltd"
            note.sellerInfo.role == "Seller"
            note.buyerInfo != null
            note.buyerInfo.name == "Receiver Co"
            note.buyerInfo.role == "Buyer"
            note.lineItems != null
            note.lineItems.size() == 1
            note.lineItems[0].description == "Steel Beams"
            note.lineItems[0].quantity == 5.0
            note.lineItems[0].unitCost == 200.0
            note.lineItems[0].total == 1000.0
            note.lineItems[0].ticketNumber == "TK-100"
    }

    def "getDeliveryNoteById returns 404 when note not found"() {
        given: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getDeliveryNoteById with non-existent ID"
            def response = restTemplate.exchange(
                "/v1/delivery-notes/non-existent",
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

    private DeliveryNote buildDeliveryNote(String extractionTaskId, String poNumber, String projectId) {
        DeliveryNote.builder()
            .assignedId(extractionTaskId)
            .companyId(companyId)
            .documentNumber("DN-001")
            .documentDate(LocalDate.of(2025, 7, 20))
            .poNumber(poNumber)
            .jobNumber("JOB-100")
            .projectId(projectId)
            .projectName("Test Project B")
            .deliveryMethodNote("Box truck")
            .sellerInfo(new Party(Party.Role.SELLER, "Supplier Ltd", null, "REF-S01", null))
            .buyerInfo(new Party(Party.Role.BUYER, "Receiver Co", null, "REF-B01", null))
            .lineItems([
                LineItem.builder()
                    .lineNumber("1")
                    .description("Steel Beams")
                    .unitOfMeasure("units")
                    .quantity(5.0)
                    .unitPrice(200.0)
                    .total(1000.0)
                    .ticketNumber("TK-100")
                    .build()
            ])
            .build()
    }
}

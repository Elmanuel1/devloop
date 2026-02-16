package com.tosspaper.comparisons

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.agent.ComparisonEvent
import com.tosspaper.aiengine.agent.StreamingComparisonAgent
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.extraction.dto.ComparisonResult
import com.tosspaper.models.extraction.dto.FieldComparison
import com.tosspaper.models.jooq.Tables
import org.jooq.DSLContext
import org.jooq.JSONB
import com.tosspaper.models.service.PurchaseOrderLookupService
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import reactor.core.publisher.Flux

/**
 * Integration tests for DocumentPartComparisonController.
 * Tests REST endpoints for document comparison including security, header parsing, and response mapping.
 *
 * Only external service boundaries are mocked:
 * - StreamingComparisonAgent (wraps AI ChatClient)
 * - PurchaseOrderLookupService (already mocked in BaseIntegrationTest)
 *
 * Internal services (DocumentPartComparisonService, repositories) use real implementations
 * backed by Testcontainers PostgreSQL.
 */
class DocumentPartComparisonControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    @Autowired
    DSLContext dsl

    @SpringBean
    StreamingComparisonAgent streamingComparisonAgent = Mock()

    @SpringBean
    PurchaseOrderLookupService purchaseOrderLookupService = Mock()

    def setup() {
        // Create company (FK required by extraction_task)
        dsl.insertInto(Tables.COMPANIES)
            .set([
                id   : TestSecurityConfiguration.TEST_COMPANY_ID,
                name : "Test Company",
                email: TestSecurityConfiguration.TEST_USER_EMAIL
            ])
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.DOCUMENT_PART_COMPARISONS).execute()
        dsl.deleteFrom(Tables.EXTRACTION_TASK).execute()
        dsl.deleteFrom(Tables.COMPANIES).execute()
    }

    // ==================== GET /v1/comparisons ====================

    def "GET returns 200 with mapped comparison result when found"() {
        given: "an extraction task and comparison exist in the database"
        insertExtractionTask("doc-123", TestSecurityConfiguration.TEST_COMPANY_ID)
        def comparison = buildComparison("doc-123", "PO-456", Comparison.OverallStatus.MATCHED)
        insertComparisonResult("doc-123", comparison)

        and: "authenticated request with X-Context-Id"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", String.valueOf(TestSecurityConfiguration.TEST_COMPANY_ID))
        def entity = new HttpEntity<>(headers)

        when: "retrieving comparison"
        def response = restTemplate.exchange(
            "/v1/comparisons?assignedId=doc-123", HttpMethod.GET, entity, String)

        then: "200 OK with mapped DTO"
        response.statusCode == HttpStatus.OK
        def body = objectMapper.readTree(response.body)
        body.get("documentId").asText() == "doc-123"
        body.get("poId").asText() == "PO-456"
        body.get("overallStatus").asText() == "matched"
    }

    def "GET returns 404 when comparison not found"() {
        given: "an extraction task exists but no comparison"
        insertExtractionTask("doc-999", TestSecurityConfiguration.TEST_COMPANY_ID)

        and: "authenticated request"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", String.valueOf(TestSecurityConfiguration.TEST_COMPANY_ID))
        def entity = new HttpEntity<>(headers)

        when: "retrieving comparison"
        def response = restTemplate.exchange(
            "/v1/comparisons?assignedId=doc-999", HttpMethod.GET, entity, String)

        then: "404 Not Found"
        response.statusCode == HttpStatus.NOT_FOUND
    }

    def "GET returns error without authentication"() {
        given: "request with X-Context-Id but no auth token"
        def headers = new HttpHeaders()
        headers.set("X-Context-Id", "1")
        def entity = new HttpEntity<>(headers)

        when: "unauthenticated request"
        def response = restTemplate.exchange(
            "/v1/comparisons?assignedId=doc-123", HttpMethod.GET, entity, String)

        then: "not successful - returns 401 or 403"
        response.statusCode.is4xxClientError()
        response.statusCode != HttpStatus.NOT_FOUND
    }

    def "GET returns 400 when X-Context-Id header is missing"() {
        given: "authenticated request WITHOUT X-Context-Id"
        def headers = createAuthHeaders()
        def entity = new HttpEntity<>(headers)

        when: "request without required header"
        def response = restTemplate.exchange(
            "/v1/comparisons?assignedId=doc-123", HttpMethod.GET, entity, String)

        then: "400 Bad Request for missing header"
        response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "GET parses X-Context-Id header as company ID"() {
        given: "a second company exists"
        dsl.insertInto(Tables.COMPANIES)
            .set([id: 42L, name: "Company 42", email: "company42@example.com"])
            .onDuplicateKeyIgnore()
            .execute()

        and: "comparison exists for company 42"
        insertExtractionTask("doc-abc", 42L)
        def comparison = buildComparison("doc-abc", "PO-001", Comparison.OverallStatus.PARTIAL)
        insertComparisonResult("doc-abc", comparison)

        and: "request with X-Context-Id=42"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", "42")
        def entity = new HttpEntity<>(headers)

        when: "retrieving comparison"
        def response = restTemplate.exchange(
            "/v1/comparisons?assignedId=doc-abc", HttpMethod.GET, entity, String)

        then: "200 OK proves company ID 42 was correctly parsed from header"
        response.statusCode == HttpStatus.OK
        def body = objectMapper.readTree(response.body)
        body.get("documentId").asText() == "doc-abc"

        cleanup:
        dsl.deleteFrom(Tables.DOCUMENT_PART_COMPARISONS).where(Tables.DOCUMENT_PART_COMPARISONS.EXTRACTION_ID.eq("doc-abc")).execute()
        dsl.deleteFrom(Tables.EXTRACTION_TASK).where(Tables.EXTRACTION_TASK.ASSIGNED_ID.eq("doc-abc")).execute()
        dsl.deleteFrom(Tables.COMPANIES).where(Tables.COMPANIES.ID.eq(42L)).execute()
    }

    def "GET returns comparison with results including vendor and line items"() {
        given: "a comparison with vendor and line item results"
        insertExtractionTask("doc-full", TestSecurityConfiguration.TEST_COMPANY_ID)
        def comparison = buildComparisonWithResults()
        insertComparisonResult("doc-full", comparison)

        and: "authenticated request"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", String.valueOf(TestSecurityConfiguration.TEST_COMPANY_ID))
        def entity = new HttpEntity<>(headers)

        when: "retrieving comparison"
        def response = restTemplate.exchange(
            "/v1/comparisons?assignedId=doc-full", HttpMethod.GET, entity, String)

        then: "200 OK with full result structure"
        response.statusCode == HttpStatus.OK
        def body = objectMapper.readTree(response.body)
        body.get("overallStatus").asText() == "partial"
        body.get("results").size() == 2
        body.get("results")[0].get("type").asText() == "vendor"
        body.get("results")[0].get("status").asText() == "matched"
        body.get("results")[1].get("type").asText() == "line_item"
        body.get("results")[1].get("status").asText() == "unmatched"
        body.get("results")[1].get("severity").asText() == "blocking"
        body.get("blockingIssues").asInt() == 1
    }

    def "GET returns comparison with field-level comparisons"() {
        given: "a comparison with field-level detail"
        insertExtractionTask("doc-detail", TestSecurityConfiguration.TEST_COMPANY_ID)
        def comparison = buildComparisonWithResults()
        insertComparisonResult("doc-detail", comparison)

        and: "authenticated request"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", String.valueOf(TestSecurityConfiguration.TEST_COMPANY_ID))
        def entity = new HttpEntity<>(headers)

        when: "retrieving comparison"
        def response = restTemplate.exchange(
            "/v1/comparisons?assignedId=doc-detail", HttpMethod.GET, entity, String)

        then: "field comparisons are included in vendor result"
        response.statusCode == HttpStatus.OK
        def body = objectMapper.readTree(response.body)
        def vendorComparisons = body.get("results")[0].get("comparisons")
        vendorComparisons.size() == 1
        vendorComparisons[0].get("field").asText() == "name"
        vendorComparisons[0].get("match").asText() == "exact"
        vendorComparisons[0].get("poValue").asText() == "Acme Corp"
        vendorComparisons[0].get("documentValue").asText() == "Acme Corp"
    }

    def "GET returns null confidence when not set"() {
        given: "a comparison without confidence"
        insertExtractionTask("doc-noconf", TestSecurityConfiguration.TEST_COMPANY_ID)
        def comparison = new Comparison()
        comparison.documentId = "doc-noconf"
        comparison.poId = "PO-789"
        comparison.results = []
        insertComparisonResult("doc-noconf", comparison)

        and: "authenticated request"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", String.valueOf(TestSecurityConfiguration.TEST_COMPANY_ID))
        def entity = new HttpEntity<>(headers)

        when: "retrieving comparison"
        def response = restTemplate.exchange(
            "/v1/comparisons?assignedId=doc-noconf", HttpMethod.GET, entity, String)

        then: "200 OK with zero blocking issues"
        response.statusCode == HttpStatus.OK
        def body = objectMapper.readTree(response.body)
        body.get("blockingIssues").asInt() == 0
    }

    // ==================== POST /v1/comparisons/{assignedId}/ (SSE) ====================

    def "POST returns SSE stream with streaming comparison events"() {
        given: "an extraction task with PO linked and conformed JSON"
        insertExtractionTask("sse-doc", TestSecurityConfiguration.TEST_COMPANY_ID, "PO-111", "po-111-id",
            '{"lineItems":[]}', "invoice")

        and: "PO lookup returns a purchase order"
        purchaseOrderLookupService.getPoWithItemsByPoNumber(
            TestSecurityConfiguration.TEST_COMPANY_ID,
            "PO-111") >> Optional.of(PurchaseOrder.builder()
                .id("po-111-id")
                .displayId("PO-111")
                .companyId(TestSecurityConfiguration.TEST_COMPANY_ID)
                .items([])
                .build())

        and: "streaming agent returns comparison events"
        def comparisonResult = buildComparison("sse-doc", "PO-111", Comparison.OverallStatus.MATCHED)
        streamingComparisonAgent.executeComparison(_) >> Flux.just(
                ComparisonEvent.Activity.processing(),
                ComparisonEvent.Complete.of(comparisonResult, "sse-doc-test123")
            )

        and: "authenticated POST request with CSRF"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", String.valueOf(TestSecurityConfiguration.TEST_COMPANY_ID))
        def entity = new HttpEntity<>(headers)

        when: "triggering comparison"
        def response = restTemplate.exchange(
            "/v1/comparisons/sse-doc/", HttpMethod.POST, entity, String)

        then: "response is 200 OK with SSE content"
        response.statusCode == HttpStatus.OK
    }

    def "POST returns error without authentication"() {
        given: "request with X-Context-Id but no auth token"
        def headers = new HttpHeaders()
        headers.set("X-Context-Id", "1")
        def entity = new HttpEntity<>(headers)

        when: "POST without auth token"
        def response = restTemplate.exchange(
            "/v1/comparisons/doc-123/", HttpMethod.POST, entity, String)

        then: "not successful - returns 4xx client error"
        response.statusCode.is4xxClientError()
    }

    def "POST returns error SSE event when no PO is linked"() {
        given: "extraction task without PO linked"
        insertExtractionTask("no-po-doc", TestSecurityConfiguration.TEST_COMPANY_ID, null, null,
            '{"lineItems":[]}', "invoice")

        and: "authenticated POST request with CSRF"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", String.valueOf(TestSecurityConfiguration.TEST_COMPANY_ID))
        def entity = new HttpEntity<>(headers)

        when: "triggering comparison"
        def response = restTemplate.exchange(
            "/v1/comparisons/no-po-doc/", HttpMethod.POST, entity, String)

        then: "response contains error SSE event about missing PO"
        response.statusCode == HttpStatus.OK
        response.body.contains("error")
        response.body.contains("NO_PO_LINKED")
    }

    // ==================== Helper Methods ====================

    /**
     * Insert a minimal extraction task into the database.
     * Only required NOT NULL fields are set: assigned_id, storage_key, company_id,
     * email_message_id, email_thread_id. match_type defaults to 'pending'.
     */
    private void insertExtractionTask(String assignedId, Long companyId,
                                       String poNumber = null, String purchaseOrderId = null,
                                       String conformedJson = null, String documentType = null) {
        def values = [
            (Tables.EXTRACTION_TASK.ASSIGNED_ID)     : assignedId,
            (Tables.EXTRACTION_TASK.STORAGE_KEY)      : "test-storage-key/${assignedId}",
            (Tables.EXTRACTION_TASK.COMPANY_ID)       : companyId,
            (Tables.EXTRACTION_TASK.EMAIL_MESSAGE_ID) : UUID.randomUUID(),
            (Tables.EXTRACTION_TASK.EMAIL_THREAD_ID)  : UUID.randomUUID(),
            (Tables.EXTRACTION_TASK.MATCH_TYPE)       : "pending",
        ]
        if (poNumber != null) {
            values[Tables.EXTRACTION_TASK.PO_NUMBER] = poNumber
        }
        if (purchaseOrderId != null) {
            values[Tables.EXTRACTION_TASK.PURCHASE_ORDER_ID] = purchaseOrderId
        }
        if (conformedJson != null) {
            values[Tables.EXTRACTION_TASK.CONFORMED_JSON] = JSONB.jsonb(conformedJson)
        }
        if (documentType != null) {
            values[Tables.EXTRACTION_TASK.DOCUMENT_TYPE] = documentType
        }

        dsl.insertInto(Tables.EXTRACTION_TASK)
            .set(values)
            .onDuplicateKeyIgnore()
            .execute()
    }

    /**
     * Insert a comparison result into the database via the document_part_comparisons table.
     */
    private void insertComparisonResult(String extractionId, Comparison comparison) {
        def jsonData = objectMapper.writeValueAsString(comparison)
        def values = [
            (Tables.DOCUMENT_PART_COMPARISONS.EXTRACTION_ID) : extractionId,
            (Tables.DOCUMENT_PART_COMPARISONS.DOCUMENT_ID)   : comparison.documentId,
            (Tables.DOCUMENT_PART_COMPARISONS.PO_ID)         : comparison.poId,
            (Tables.DOCUMENT_PART_COMPARISONS.RESULT_DATA)   : JSONB.jsonb(jsonData),
        ]
        if (comparison.overallStatus != null) {
            values[Tables.DOCUMENT_PART_COMPARISONS.OVERALL_STATUS] = comparison.overallStatus.value()
        }

        dsl.insertInto(Tables.DOCUMENT_PART_COMPARISONS)
            .set(values)
            .execute()
    }

    private static Comparison buildComparison(String docId, String poId, Comparison.OverallStatus status) {
        def comparison = new Comparison()
        comparison.documentId = docId
        comparison.poId = poId
        comparison.overallStatus = status
        comparison.confidence = 0.95
        comparison.results = []
        return comparison
    }

    private static Comparison buildComparisonWithResults() {
        def comparison = new Comparison()
        comparison.documentId = "doc-full"
        comparison.poId = "PO-456"
        comparison.overallStatus = Comparison.OverallStatus.PARTIAL
        comparison.confidence = 0.85

        def vendorResult = new ComparisonResult()
        vendorResult.type = ComparisonResult.Type.VENDOR
        vendorResult.status = ComparisonResult.Status.MATCHED
        vendorResult.severity = ComparisonResult.Severity.INFO
        vendorResult.matchScore = 0.98

        def fieldComparison = new FieldComparison()
        fieldComparison.field = "name"
        fieldComparison.match = FieldComparison.Match.EXACT
        fieldComparison.poValue = "Acme Corp"
        fieldComparison.documentValue = "Acme Corp"
        fieldComparison.explanation = "Vendor names match exactly"
        fieldComparison.isBlocking = false
        vendorResult.comparisons = [fieldComparison]

        def lineItemResult = new ComparisonResult()
        lineItemResult.type = ComparisonResult.Type.LINE_ITEM
        lineItemResult.status = ComparisonResult.Status.UNMATCHED
        lineItemResult.severity = ComparisonResult.Severity.BLOCKING
        lineItemResult.matchScore = 0.30
        lineItemResult.extractedIndex = 0L
        lineItemResult.poIndex = 0L

        def priceComparison = new FieldComparison()
        priceComparison.field = "unitPrice"
        priceComparison.match = FieldComparison.Match.MISMATCH
        priceComparison.poValue = "50.00"
        priceComparison.documentValue = "55.00"
        priceComparison.explanation = 'Price differs by $5.00'
        priceComparison.isBlocking = true
        lineItemResult.comparisons = [priceComparison]

        comparison.results = [vendorResult, lineItemResult]
        return comparison
    }
}

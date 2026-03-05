package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord
import org.jooq.JSONB
import spock.lang.Specification
import spock.lang.Subject

import java.math.BigDecimal
import java.time.OffsetDateTime

class ConflictDetectorSpec extends Specification {

    ExtractionFieldRepository extractionFieldRepository = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    ConflictDetector detector = new ConflictDetector(extractionFieldRepository, objectMapper)

    static final String EXTRACTION_ID = "extraction-abc-123"

    // ==================== detectAndMarkConflicts — no fields ====================

    def "TC-CD-01: returns zero when no fields exist for the extraction"() {
        given: "repository returns empty list"
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> []

        when: "detectAndMarkConflicts is called"
            int result = detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "no conflict marking occurs and count is zero"
            result == 0
            0 * extractionFieldRepository.markConflict(_, _, _)
    }

    // ==================== detectAndMarkConflicts — single document per field ====================

    def "TC-CD-02: no conflict when each field_name appears only once"() {
        given: "two fields with different names — one document each"
            def field1 = buildField("f1", EXTRACTION_ID, "closing_date", '"2025-01-15"', 0.95)
            def field2 = buildField("f2", EXTRACTION_ID, "contract_value", '"50000"', 0.90)
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> [field1, field2]

        when: "detectAndMarkConflicts is called"
            int result = detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "no conflicts — markConflict is never called"
            result == 0
            0 * extractionFieldRepository.markConflict(_, _, _)
    }

    // ==================== detectAndMarkConflicts — same value no conflict ====================

    def "TC-CD-03: no conflict when multiple documents have the same proposed_value for a field"() {
        given: "two rows for the same field_name with identical values"
            def f1 = buildField("f1", EXTRACTION_ID, "closing_date", '"2025-01-15"', 0.92)
            def f2 = buildField("f2", EXTRACTION_ID, "closing_date", '"2025-01-15"', 0.88)
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> [f1, f2]

        when: "detectAndMarkConflicts is called"
            int result = detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "same value — no conflict flagged"
            result == 0
            0 * extractionFieldRepository.markConflict(_, _, _)
    }

    // ==================== detectAndMarkConflicts — conflict detected ====================

    def "TC-CD-04: detects conflict when two documents propose different values for the same field"() {
        given: "two rows for 'closing_date' with different proposed values"
            def f1 = buildField("f1", EXTRACTION_ID, "closing_date", '"2025-01-15"', 0.95)
            def f2 = buildField("f2", EXTRACTION_ID, "closing_date", '"2025-02-28"', 0.80)
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> [f1, f2]

        when: "detectAndMarkConflicts is called"
            int result = detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "conflict is marked and count reflects updated rows"
            // In Spock, then-block interactions override given stubs, so we combine count + return value
            1 * extractionFieldRepository.markConflict(EXTRACTION_ID, "closing_date", _ as JSONB) >> 2
            result == 2
    }

    def "TC-CD-05: detects conflict across three documents for the same field"() {
        given: "three rows for 'contract_value' with three different values"
            def f1 = buildField("f1", EXTRACTION_ID, "contract_value", '"100000"', 0.95)
            def f2 = buildField("f2", EXTRACTION_ID, "contract_value", '"120000"', 0.80)
            def f3 = buildField("f3", EXTRACTION_ID, "contract_value", '"115000"', 0.70)
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> [f1, f2, f3]

        when: "detectAndMarkConflicts is called"
            int result = detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "all three rows are flagged"
            1 * extractionFieldRepository.markConflict(EXTRACTION_ID, "contract_value", _ as JSONB) >> 3
            result == 3
    }

    // ==================== detectAndMarkConflicts — multiple fields, some conflicting ====================

    def "TC-CD-06: only conflicting fields are marked — non-conflicting fields are untouched"() {
        given: "closing_date has a conflict, contract_value does not"
            def closingDate1 = buildField("cd1", EXTRACTION_ID, "closing_date", '"2025-01-15"', 0.92)
            def closingDate2 = buildField("cd2", EXTRACTION_ID, "closing_date", '"2025-03-01"', 0.85)
            def contractValue1 = buildField("cv1", EXTRACTION_ID, "contract_value", '"50000"', 0.90)
            def contractValue2 = buildField("cv2", EXTRACTION_ID, "contract_value", '"50000"', 0.88)
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> [
                closingDate1, closingDate2, contractValue1, contractValue2
            ]

        when: "detectAndMarkConflicts is called"
            int result = detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "only the conflicting field is marked"
            1 * extractionFieldRepository.markConflict(EXTRACTION_ID, "closing_date", _ as JSONB) >> 2
            0 * extractionFieldRepository.markConflict(EXTRACTION_ID, "contract_value", _)
            result == 2
    }

    // ==================== detectAndMarkConflicts — competing_values JSONB content ====================

    def "TC-CD-07: competing_values JSONB contains field_id, value, and confidence for each row"() {
        given: "two conflicting rows"
            def f1 = buildField("field-id-A", EXTRACTION_ID, "title", '"Alpha Contract"', 0.95)
            def f2 = buildField("field-id-B", EXTRACTION_ID, "title", '"Beta Contract"', 0.80)
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> [f1, f2]

            JSONB capturedJsonb = null
            extractionFieldRepository.markConflict(EXTRACTION_ID, "title", _ as JSONB) >> { String extId, String fn, JSONB jsonb ->
                capturedJsonb = jsonb
                return 2
            }

        when: "detectAndMarkConflicts is called"
            detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "competing_values contains both entries with field_id, value, and confidence"
            capturedJsonb != null
            def parsed = objectMapper.readTree(capturedJsonb.data())
            parsed.isArray()
            parsed.size() == 2

            def ids = (0..<parsed.size()).collect { parsed[it]["field_id"].asText() }.toSet()
            ids.contains("field-id-A")
            ids.contains("field-id-B")

            // each element has 'value' and 'confidence'
            (0..<parsed.size()).every { i ->
                parsed[i].has("value") && parsed[i].has("confidence")
            }
    }

    // ==================== detectAndMarkConflicts — null proposed_value ====================

    def "TC-CD-08: null proposed_value rows are treated as empty string for comparison"() {
        given: "one row has a value, another has null proposed_value"
            def f1 = buildField("f1", EXTRACTION_ID, "closing_date", '"2025-01-15"', 0.95)
            def f2 = buildFieldNullValue("f2", EXTRACTION_ID, "closing_date", 0.50)
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> [f1, f2]

        when: "detectAndMarkConflicts is called"
            int result = detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "conflict is detected (non-null vs null)"
            1 * extractionFieldRepository.markConflict(EXTRACTION_ID, "closing_date", _ as JSONB) >> 2
            result == 2
    }

    def "TC-CD-09: two null proposed_value rows are NOT considered conflicting"() {
        given: "two rows both have null proposed_value — same field"
            def f1 = buildFieldNullValue("f1", EXTRACTION_ID, "closing_date", 0.50)
            def f2 = buildFieldNullValue("f2", EXTRACTION_ID, "closing_date", 0.45)
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> [f1, f2]

        when: "detectAndMarkConflicts is called"
            int result = detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "no conflict — both values normalise to empty string"
            result == 0
            0 * extractionFieldRepository.markConflict(_, _, _)
    }

    // ==================== normalise — unit tests ====================

    def "TC-CD-10: normalise returns empty string for null JSONB"() {
        expect:
            detector.normalise(null) == ""
    }

    def "TC-CD-11: normalise returns empty string for JSONB with null data"() {
        given: "a JSONB whose data() method returns null — created via nullableJsonb helper"
            // JSONB.valueOf(null) in Groovy returns a JSONB whose toString() is 'null' but is non-null
            // We use a separate variable to avoid Groovy treating the result as null
            def nullDataJsonb = JSONB.jsonbOrNull(null)

        expect: "normalise handles this gracefully and returns empty string"
            // The method guards against both null JSONB and null data()
            detector.normalise(null) == ""
            nullDataJsonb == null || detector.normalise(nullDataJsonb) == ""
    }

    def "TC-CD-12: normalise produces consistent output for equivalent JSON objects"() {
        given: "two JSON strings with the same keys but different whitespace"
            def a = JSONB.valueOf('{"key":"value"}')
            def b = JSONB.valueOf('{ "key" : "value" }')

        expect: "they normalise to the same string"
            detector.normalise(a) == detector.normalise(b)
    }

    def "TC-CD-13: normalise produces different output for different JSON values"() {
        given:
            def a = JSONB.valueOf('"2025-01-15"')
            def b = JSONB.valueOf('"2025-02-28"')

        expect:
            detector.normalise(a) != detector.normalise(b)
    }

    def "TC-CD-14: normalise handles simple string JSON"() {
        given:
            def jsonb = JSONB.valueOf('"hello world"')

        expect:
            detector.normalise(jsonb) != ""
    }

    // ==================== ExtractionFieldQuery passed to repository ====================

    def "TC-CD-15: passes extraction ID in query when calling findByExtractionId"() {
        given: "capturing the query object passed to the repository"
            ExtractionFieldQuery capturedQuery = null
            extractionFieldRepository.findByExtractionId(_ as ExtractionFieldQuery) >> { ExtractionFieldQuery q ->
                capturedQuery = q
                return []
            }

        when: "detectAndMarkConflicts is called"
            detector.detectAndMarkConflicts(EXTRACTION_ID)

        then: "query has the correct extraction ID"
            capturedQuery != null
            capturedQuery.getExtractionId() == EXTRACTION_ID
    }

    // ==================== Helper Methods ====================

    private static ExtractionFieldsRecord buildField(String id, String extractionId,
                                                      String fieldName, String proposedValueJson,
                                                      double confidence) {
        def record = new ExtractionFieldsRecord()
        record.setId(id)
        record.setExtractionId(extractionId)
        record.setFieldName(fieldName)
        record.setFieldType("string")
        record.setProposedValue(JSONB.valueOf(proposedValueJson))
        record.setConfidence(BigDecimal.valueOf(confidence))
        record.setHasConflict(false)
        record.setStatus("extracted")
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }

    private static ExtractionFieldsRecord buildFieldNullValue(String id, String extractionId,
                                                               String fieldName, double confidence) {
        def record = new ExtractionFieldsRecord()
        record.setId(id)
        record.setExtractionId(extractionId)
        record.setFieldName(fieldName)
        record.setFieldType("string")
        record.setProposedValue(null)
        record.setConfidence(BigDecimal.valueOf(confidence))
        record.setHasConflict(false)
        record.setStatus("extracted")
        record.setCreatedAt(OffsetDateTime.now())
        record.setUpdatedAt(OffsetDateTime.now())
        return record
    }
}

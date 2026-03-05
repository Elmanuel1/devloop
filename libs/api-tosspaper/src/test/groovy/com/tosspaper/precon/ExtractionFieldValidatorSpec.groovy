package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import spock.lang.Specification
import spock.lang.Subject

class ExtractionFieldValidatorSpec extends Specification {

    @Subject
    ExtractionFieldValidator validator = new ExtractionFieldValidator()

    ObjectMapper mapper = new ObjectMapper()

    // ── null / null node ──────────────────────────────────────────────────────

    def "TC-FV-01: null payload is rejected"() {
        when:
            def result = validator.isValid("doc-1", null)
        then:
            !result
    }

    def "TC-FV-02: JSON null node is rejected"() {
        when:
            def result = validator.isValid("doc-null", NullNode.instance)
        then:
            !result
    }

    // ── non-object types ─────────────────────────────────────────────────────

    def "TC-FV-03: JSON array node is rejected"() {
        given: "a JSON array"
            def node = mapper.readTree('[{"field":"value"}]')
        when:
            def result = validator.isValid("doc-array", node)
        then:
            !result
    }

    def "TC-FV-04: JSON string node is rejected"() {
        given: "a JSON text node"
            def node = mapper.readTree('"hello"')
        when:
            def result = validator.isValid("doc-string", node)
        then:
            !result
    }

    def "TC-FV-05: JSON number node is rejected"() {
        given: "a JSON number node"
            def node = mapper.readTree("42")
        when:
            def result = validator.isValid("doc-number", node)
        then:
            !result
    }

    // ── empty object ─────────────────────────────────────────────────────────

    def "TC-FV-06: empty JSON object is rejected"() {
        given: "an empty JSON object"
            def node = mapper.readTree("{}")
        when:
            def result = validator.isValid("doc-empty", node)
        then:
            !result
    }

    // ── valid payloads ────────────────────────────────────────────────────────

    def "TC-FV-07: non-empty JSON object with one field is valid"() {
        given: "a payload with one extracted field"
            def node = mapper.readTree('{"tender_title": "Road Construction Project"}')
        when:
            def result = validator.isValid("doc-1", node)
        then:
            result
    }

    def "TC-FV-08: non-empty JSON object with multiple fields is valid"() {
        given: "a payload with several extracted fields"
            def node = mapper.readTree('''
                {
                    "tender_title": "Road Construction",
                    "closing_date": "2026-04-01",
                    "currency": "USD"
                }
            ''')
        when:
            def result = validator.isValid("doc-2", node)
        then:
            result
    }

    def "TC-FV-09: payload with nested objects is valid"() {
        given: "a payload with nested structure"
            def node = mapper.readTree('{"parties": {"buyer": "City Council"}}')
        when:
            def result = validator.isValid("doc-nested", node)
        then:
            result
    }

    // ── rejectionMessage ─────────────────────────────────────────────────────

    def "TC-FV-10: rejectionMessage contains the document ID"() {
        when:
            def message = validator.rejectionMessage("doc-abc-123")
        then:
            message.contains("doc-abc-123")
            !message.isBlank()
    }

    def "TC-FV-11: rejectionMessage does not throw for any document ID"() {
        expect:
            validator.rejectionMessage(docId) != null
        where:
            docId << ["doc-1", "a", "00000000-0000-0000-0000-000000000000", ""]
    }
}

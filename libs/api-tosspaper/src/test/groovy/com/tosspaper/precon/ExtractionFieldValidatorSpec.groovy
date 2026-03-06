package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import spock.lang.Specification
import spock.lang.Subject

class ExtractionFieldValidatorSpec extends Specification {

    @Subject
    ExtractionFieldValidator validator = new ExtractionFieldValidator()

    ObjectMapper mapper = new ObjectMapper()

    // ── isValid: pass-through stub ────────────────────────────────────────────

    def "TC-FV-01: isValid returns true for any payload (pass-through stub)"() {
        expect:
            validator.isValid("doc-1", null)
            validator.isValid("doc-1", NullNode.instance)
            validator.isValid("doc-1", mapper.readTree("{}"))
            validator.isValid("doc-1", mapper.readTree('{"field": "value"}'))
    }

    // ── rejectionMessage ──────────────────────────────────────────────────────

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

    // ── validateAndWriteFields: pass-through stub ─────────────────────────────

    def "TC-FV-12: validateAndWriteFields returns true for any payload (pass-through stub)"() {
        expect:
            validator.validateAndWriteFields("ext-1", "doc-1", mapper.readTree('{"tender_title": "Bridge"}'))
            validator.validateAndWriteFields("ext-1", "doc-1", NullNode.instance)
            validator.validateAndWriteFields("ext-1", "doc-1", mapper.readTree("{}"))
    }
}

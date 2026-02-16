package com.tosspaper.aiengine.extractors

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.Subject

class ReductoDocumentExtractorSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    ReductoDocumentExtractor extractor = new ReductoDocumentExtractor(objectMapper)

    def "extract should parse Reducto response and remove citations"() {
        given: "a Reducto response with nested result and citations"
            def rawResponse = '''
            {
                "result": {
                    "result": {
                        "documentType": {"value": "invoice", "citations": [{"page": 1}]},
                        "documentNumber": {"value": "INV-001", "citations": [{"page": 1}]},
                        "customerPONumber": {"value": "PO-123", "citations": []}
                    }
                }
            }
            '''

        when: "extracting"
            def result = extractor.extract(rawResponse)

        then: "citations are removed and values extracted"
            result != null
            def parsed = objectMapper.readValue(result, Map)
            parsed.documentType == "invoice"
            parsed.documentNumber == "INV-001"
            parsed.customerPONumber == "PO-123"
    }

    def "extract should handle null input"() {
        when: "extracting null"
            def result = extractor.extract(null)

        then: "null is returned"
            result == null
    }

    def "extract should handle empty input"() {
        when: "extracting empty string"
            def result = extractor.extract("")

        then: "null is returned"
            result == null
    }

    def "extract should handle whitespace-only input"() {
        when: "extracting whitespace"
            def result = extractor.extract("   ")

        then: "null is returned"
            result == null
    }

    def "extract should handle response without result field"() {
        given: "response without result"
            def rawResponse = '{"data": "something"}'

        when: "extracting"
            def result = extractor.extract(rawResponse)

        then: "null is returned"
            result == null
    }

    def "extract should handle response without inner result"() {
        given: "response with result but no inner result"
            def rawResponse = '{"result": {"status": "completed"}}'

        when: "extracting"
            def result = extractor.extract(rawResponse)

        then: "null is returned"
            result == null
    }

    def "extract should handle nested maps recursively"() {
        given: "a response with nested maps"
            def rawResponse = '''
            {
                "result": {
                    "result": {
                        "parties": {
                            "vendor": {"value": "Vendor Inc", "citations": [{"page": 1}]},
                            "buyer": {"value": "Buyer Corp", "citations": [{"page": 2}]}
                        }
                    }
                }
            }
            '''

        when: "extracting"
            def result = extractor.extract(rawResponse)

        then: "nested citations are removed"
            result != null
            def parsed = objectMapper.readValue(result, Map)
            parsed.parties.vendor == "Vendor Inc"
            parsed.parties.buyer == "Buyer Corp"
    }

    def "extract should handle lists in response"() {
        given: "a response with list values"
            def rawResponse = '''
            {
                "result": {
                    "result": {
                        "deliveryTransactions": [
                            {"value": {"poNumber": "PO-1"}, "citations": []},
                            {"value": {"poNumber": "PO-2"}, "citations": []}
                        ]
                    }
                }
            }
            '''

        when: "extracting"
            def result = extractor.extract(rawResponse)

        then: "lists are processed correctly"
            result != null
            def parsed = objectMapper.readValue(result, Map)
            parsed.deliveryTransactions.size() == 2
    }

    def "extract should handle malformed JSON"() {
        given: "invalid JSON"
            def rawResponse = "not valid json {"

        when: "extracting"
            def result = extractor.extract(rawResponse)

        then: "null is returned"
            result == null
    }

    def "extract should preserve simple values without citations"() {
        given: "a response with plain values"
            def rawResponse = '''
            {
                "result": {
                    "result": {
                        "simpleField": "plain value",
                        "numericField": 42,
                        "boolField": true
                    }
                }
            }
            '''

        when: "extracting"
            def result = extractor.extract(rawResponse)

        then: "plain values are preserved"
            result != null
            def parsed = objectMapper.readValue(result, Map)
            parsed.simpleField == "plain value"
            parsed.numericField == 42
            parsed.boolField == true
    }
}

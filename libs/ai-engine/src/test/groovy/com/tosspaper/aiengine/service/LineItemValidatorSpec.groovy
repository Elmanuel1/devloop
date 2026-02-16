package com.tosspaper.aiengine.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.tools.FileTools
import com.tosspaper.aiengine.tools.FileTools.PoItemInfo
import com.tosspaper.aiengine.tools.FileTools.ValidationResult
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.extraction.dto.ComparisonResult
import com.tosspaper.models.extraction.dto.FieldComparison
// ResultType is a nested enum in ComparisonResult
import org.springframework.ai.chat.client.ChatClient
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for LineItemValidator.
 * Tests post-hoc validation and batch correction of line item matches.
 */
class LineItemValidatorSpec extends Specification {

    FileTools fileTools = Mock()
    ChatClient comparisonChatClient = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    LineItemValidator validator

    def setup() {
        validator = new LineItemValidator(fileTools, comparisonChatClient, objectMapper)
    }

    // ==================== VALIDATION TESTS ====================

    def "validateLineItems should return empty batch when comparison has no results"() {
        given: "comparison with null results"
            def comparison = new Comparison()
            comparison.setResults(null)

        when: "validating line items"
            def result = validator.validateLineItems(comparison, 5)

        then: "empty batch is returned"
            result.validated().isEmpty()
            result.failed().isEmpty()
            result.usedPoIndices().isEmpty()
    }

    def "validateLineItems should strip phantom line items beyond document count"() {
        given: "comparison with phantom line items"
            def comparison = new Comparison()
            def results = [
                createLineItemResult(0L, 1),
                createLineItemResult(1L, 2),
                createLineItemResult(2L, 3),
                createLineItemResult(5L, 4) // Phantom - beyond document count
            ]
            comparison.setResults(results)

        and: "validations for non-phantom items"
            fileTools.validateLineItemMatch(_, _, _) >>
                new ValidationResult(true, 0, 1, "ITEM-001", "Widget", "ITEM-001", "Widget")

        when: "validating with docLineItemCount=3"
            def batch = validator.validateLineItems(comparison, 3)

        then: "phantom item is removed"
            comparison.getResults().size() == 3
            comparison.getResults().every { it.extractedIndex < 3 }
    }

    def "validateLineItems should deduplicate line items with same extractedIndex"() {
        given: "comparison with duplicate extractedIndex"
            def comparison = new Comparison()
            def results = [
                createLineItemResult(0L, 1),
                createLineItemResult(0L, 2), // Duplicate
                createLineItemResult(1L, 3)
            ]
            comparison.setResults(results)

        and: "validations for kept items"
            fileTools.validateLineItemMatch(_, _, _) >>
                new ValidationResult(true, 0, 1, "ITEM-001", "Widget", "ITEM-001", "Widget")

        when: "validating line items"
            def batch = validator.validateLineItems(comparison, 5)

        then: "duplicates are removed"
            comparison.getResults().size() == 2
    }

    def "validateLineItems should skip line items with null poIndex"() {
        given: "comparison with null poIndex"
            def comparison = new Comparison()
            def result = createLineItemResult(0L, null)
            comparison.setResults([result])

        when: "validating line items"
            def batch = validator.validateLineItems(comparison, 5)

        then: "no validation performed"
            batch.validated().isEmpty()
            batch.failed().isEmpty()
            0 * fileTools.validateLineItemMatch(_, _, _)
    }

    def "validateLineItems should mark valid items as validated"() {
        given: "comparison with valid line item"
            def comparison = new Comparison()
            def result = createLineItemResult(0L, 1, "ITEM-001", "Widget A")
            comparison.setResults([result])

        and: "validation succeeds"
            fileTools.validateLineItemMatch(1, "ITEM-001", "Widget A") >>
                new ValidationResult(true, 0, 1, "ITEM-001", "Widget A", "ITEM-001", "Widget A")

        when: "validating line items"
            def batch = validator.validateLineItems(comparison, 5)

        then: "item is validated"
            batch.validated().size() == 1
            batch.validated()[0].valid()
            batch.validated()[0].docIndex() == 0
            batch.validated()[0].poIndex() == 1
            batch.failed().isEmpty()
            batch.usedPoIndices().contains(1)
    }

    def "validateLineItems should mark invalid items as failed"() {
        given: "comparison with invalid line item"
            def comparison = new Comparison()
            def result = createLineItemResult(0L, 1, "ITEM-001", "Widget A")
            comparison.setResults([result])

        and: "validation fails"
            fileTools.validateLineItemMatch(1, "ITEM-001", "Widget A") >>
                new ValidationResult(false, 0, 1, "ITEM-001", "Widget A", "ITEM-999", "Different Item")

        when: "validating line items"
            def batch = validator.validateLineItems(comparison, 5)

        then: "item is marked as failed"
            batch.validated().isEmpty()
            batch.failed().size() == 1
            !batch.failed()[0].validation().valid()
            batch.failed()[0].validation().docIndex() == 0
            batch.usedPoIndices().isEmpty()
    }

    def "validateLineItems should mark duplicate poIndex usage as failed"() {
        given: "comparison with two items matching same poIndex"
            def comparison = new Comparison()
            def results = [
                createLineItemResult(0L, 1, "ITEM-001", "Widget A"),
                createLineItemResult(1L, 1, "ITEM-001", "Widget A") // Same poIndex
            ]
            comparison.setResults(results)

        and: "both validate correctly"
            fileTools.validateLineItemMatch(1, "ITEM-001", "Widget A") >>
                new ValidationResult(true, 0, 1, "ITEM-001", "Widget A", "ITEM-001", "Widget A") >>
                new ValidationResult(true, 1, 1, "ITEM-001", "Widget A", "ITEM-001", "Widget A")

        when: "validating line items"
            def batch = validator.validateLineItems(comparison, 5)

        then: "first succeeds, second fails due to duplicate"
            batch.validated().size() == 1
            batch.failed().size() == 1
            batch.validated()[0].docIndex() == 0
            batch.failed()[0].validation().docIndex() == 1
    }

    def "validateLineItems should skip non-line-item results"() {
        given: "comparison with mixed result types"
            def comparison = new Comparison()
            def lineItem = createLineItemResult(0L, 1, "ITEM-001", "Widget A")
            def headerField = new ComparisonResult()
            headerField.setType(ComparisonResult.Type.VENDOR)
            comparison.setResults([lineItem, headerField])

        and: "validation succeeds for line item"
            fileTools.validateLineItemMatch(1, "ITEM-001", "Widget A") >>
                new ValidationResult(true, 0, 1, "ITEM-001", "Widget A", "ITEM-001", "Widget A")

        when: "validating line items"
            def batch = validator.validateLineItems(comparison, 5)

        then: "only line item is validated"
            batch.validated().size() == 1
            comparison.getResults().size() == 2 // Both remain
    }

    // ==================== BATCH CORRECTION TESTS ====================

    def "correctFailedItemsBatch should return empty when no failed items"() {
        when: "correcting empty list"
            def result = validator.correctFailedItemsBatch([], [] as Set, [])

        then: "empty result returned"
            result.corrections().isEmpty()
            result.uncorrectable().isEmpty()
            0 * comparisonChatClient._
    }

    def "correctFailedItemsBatch should correct failed items in batch"() {
        given: "failed validations"
            def failedItems = [
                new LineItemValidator.FailedValidation(
                    new ValidationResult(false, 3, 10, "ITEM-003", "Widget C", "WRONG", "Wrong Item"),
                    createLineItemResult(3L, 10)
                ),
                new LineItemValidator.FailedValidation(
                    new ValidationResult(false, 5, 20, "ITEM-005", "Widget E", "WRONG", "Wrong Item"),
                    createLineItemResult(5L, 20)
                )
            ]

        and: "available PO items"
            def availableItems = [
                new PoItemInfo(15, "ITEM-003", "Widget C"),
                new PoItemInfo(25, "ITEM-005", "Widget E")
            ]

        and: "AI returns corrections"
            def promptSpec = Mock(ChatClient.ChatClientRequestSpec)
            def callResponseSpec = Mock(ChatClient.CallResponseSpec)
            comparisonChatClient.prompt() >> promptSpec
            promptSpec.user(_) >> promptSpec
            promptSpec.call() >> callResponseSpec
            callResponseSpec.content() >> """
            {
                "3": 15,
                "5": 25
            }
            """

        and: "validations succeed for corrected indices"
            fileTools.validateLineItemMatch(15, "ITEM-003", "Widget C") >>
                new ValidationResult(true, 3, 15, "ITEM-003", "Widget C", "ITEM-003", "Widget C")
            fileTools.validateLineItemMatch(25, "ITEM-005", "Widget E") >>
                new ValidationResult(true, 5, 25, "ITEM-005", "Widget E", "ITEM-005", "Widget E")

        when: "correcting failed items"
            def result = validator.correctFailedItemsBatch(failedItems, [] as Set, availableItems)

        then: "corrections are returned"
            result.corrections().size() == 2
            result.corrections()[3] == 15
            result.corrections()[5] == 25
            result.uncorrectable().isEmpty()
    }

    def "correctFailedItemsBatch should mark unmatched items as uncorrectable"() {
        given: "failed validation"
            def failedItems = [
                new LineItemValidator.FailedValidation(
                    new ValidationResult(false, 3, 10, "ITEM-003", "Widget C", "WRONG", "Wrong Item"),
                    createLineItemResult(3L, 10)
                )
            ]

        and: "AI returns null (no match)"
            def promptSpec = Mock(ChatClient.ChatClientRequestSpec)
            def callResponseSpec = Mock(ChatClient.CallResponseSpec)
            comparisonChatClient.prompt() >> promptSpec
            promptSpec.user(_) >> promptSpec
            promptSpec.call() >> callResponseSpec
            callResponseSpec.content() >> '{"3": null}'

        when: "correcting failed items"
            def result = validator.correctFailedItemsBatch(failedItems, [] as Set, [])

        then: "item marked as uncorrectable"
            result.corrections().isEmpty()
            result.uncorrectable().contains(3)
    }

    def "correctFailedItemsBatch should reject corrections to already-used indices"() {
        given: "failed validation"
            def failedItems = [
                new LineItemValidator.FailedValidation(
                    new ValidationResult(false, 3, 10, "ITEM-003", "Widget C", "WRONG", "Wrong Item"),
                    createLineItemResult(3L, 10)
                )
            ]

        and: "AI returns index that's already used"
            def promptSpec = Mock(ChatClient.ChatClientRequestSpec)
            def callResponseSpec = Mock(ChatClient.CallResponseSpec)
            comparisonChatClient.prompt() >> promptSpec
            promptSpec.user(_) >> promptSpec
            promptSpec.call() >> callResponseSpec
            callResponseSpec.content() >> '{"3": 15}' >> '{"3": 16}'

        and: "index 15 is already used, 16 is valid"
            fileTools.validateLineItemMatch(16, "ITEM-003", "Widget C") >>
                new ValidationResult(true, 3, 16, "ITEM-003", "Widget C", "ITEM-003", "Widget C")

        when: "correcting with retry"
            def result = validator.correctFailedItemsBatch(failedItems, [15] as Set, [])

        then: "retries and finds valid index"
            result.corrections().size() == 1
            result.corrections()[3] == 16
    }

    def "correctFailedItemsBatch should retry invalid corrections"() {
        given: "failed validation"
            def failedItems = [
                new LineItemValidator.FailedValidation(
                    new ValidationResult(false, 3, 10, "ITEM-003", "Widget C", "WRONG", "Wrong Item"),
                    createLineItemResult(3L, 10)
                )
            ]

        and: "first AI attempt returns wrong index, second attempt succeeds"
            def promptSpec = Mock(ChatClient.ChatClientRequestSpec)
            def callResponseSpec = Mock(ChatClient.CallResponseSpec)
            comparisonChatClient.prompt() >> promptSpec
            promptSpec.user(_) >> promptSpec
            promptSpec.call() >> callResponseSpec
            callResponseSpec.content() >> '{"3": 15}' >> '{"3": 16}'

        and: "first validation fails, second succeeds"
            fileTools.validateLineItemMatch(15, "ITEM-003", "Widget C") >>
                new ValidationResult(false, 3, 15, "ITEM-003", "Widget C", "OTHER", "Other Item")
            fileTools.validateLineItemMatch(16, "ITEM-003", "Widget C") >>
                new ValidationResult(true, 3, 16, "ITEM-003", "Widget C", "ITEM-003", "Widget C")

        when: "correcting with retry"
            def result = validator.correctFailedItemsBatch(failedItems, [] as Set, [])

        then: "eventually succeeds"
            result.corrections().size() == 1
            result.corrections()[3] == 16
    }

    def "correctFailedItemsBatch should handle AI errors gracefully"() {
        given: "failed validation"
            def failedItems = [
                new LineItemValidator.FailedValidation(
                    new ValidationResult(false, 3, 10, "ITEM-003", "Widget C", "WRONG", "Wrong Item"),
                    createLineItemResult(3L, 10)
                )
            ]

        and: "AI throws exception"
            comparisonChatClient.prompt() >> { throw new RuntimeException("AI error") }

        when: "correcting failed items"
            def result = validator.correctFailedItemsBatch(failedItems, [] as Set, [])

        then: "item marked as uncorrectable after retries"
            result.corrections().isEmpty()
            result.uncorrectable().contains(3)
    }

    def "correctFailedItemsBatch should limit retries to MAX_CORRECTION_RETRIES"() {
        given: "failed validation"
            def failedItems = [
                new LineItemValidator.FailedValidation(
                    new ValidationResult(false, 3, 10, "ITEM-003", "Widget C", "WRONG", "Wrong Item"),
                    createLineItemResult(3L, 10)
                )
            ]

        and: "AI always returns invalid corrections"
            def promptSpec = Mock(ChatClient.ChatClientRequestSpec)
            def callResponseSpec = Mock(ChatClient.CallResponseSpec)
            comparisonChatClient.prompt() >> promptSpec
            promptSpec.user(_) >> promptSpec
            promptSpec.call() >> callResponseSpec
            callResponseSpec.content() >>> ['{"3": 15}', '{"3": 16}']

        and: "all validations fail"
            fileTools.validateLineItemMatch(_, _, _) >>
                new ValidationResult(false, 3, 15, "ITEM-003", "Widget C", "OTHER", "Other Item")

        when: "correcting failed items"
            def result = validator.correctFailedItemsBatch(failedItems, [] as Set, [])

        then: "gives up after MAX_CORRECTION_RETRIES"
            result.corrections().isEmpty()
            result.uncorrectable().contains(3)
    }

    // ==================== HELPER METHOD TESTS ====================

    def "getAvailablePoItems should filter out used indices"() {
        given: "PO items list"
            def allItems = [
                new PoItemInfo(1, "ITEM-001", "Widget A"),
                new PoItemInfo(2, "ITEM-002", "Widget B"),
                new PoItemInfo(3, "ITEM-003", "Widget C")
            ]
            fileTools.getPoItemsList() >> allItems

        and: "some indices are used"
            def usedIndices = [1, 3] as Set

        when: "getting available items"
            def available = validator.getAvailablePoItems(usedIndices)

        then: "only unused items returned"
            available.size() == 1
            available[0].index() == 2
            available[0].itemCode() == "ITEM-002"
    }

    // ==================== EXTRACTION HELPER TESTS ====================

    def "should extract item code from various field names"() {
        given: "comparison result with item code field"
            def result = createLineItemResult(0L, 1)
            result.setComparisons([
                createFieldComparison(fieldName, "TEST-CODE", "value")
            ])

        when: "extracting item code (using reflection to test private method)"
            def itemCode = extractItemCodeReflection(result)

        then: "item code is extracted"
            itemCode == "TEST-CODE"

        where: "field names"
            fieldName << ["Item Code", "ItemCode", "SKU", "Unit Code", "item code"]
    }

    def "should extract description from various field names"() {
        given: "comparison result with description field"
            def result = createLineItemResult(0L, 1)
            result.setComparisons([
                createFieldComparison(fieldName, "Test Description", "value")
            ])

        when: "extracting description"
            def description = extractDescriptionReflection(result)

        then: "description is extracted"
            description == "Test Description"

        where: "field names"
            fieldName << ["Description", "Name", "Product", "description"]
    }

    // ==================== JSON PARSING TESTS ====================

    def "should parse batch corrections from JSON response"() {
        given: "AI response with corrections"
            def response = """
            {
                "3": 15,
                "5": 20,
                "7": null
            }
            """

        when: "parsing corrections"
            def corrections = parseBatchCorrectionsReflection(response)

        then: "corrections are parsed correctly"
            corrections[3] == 15
            corrections[5] == 20
            corrections[7] == null
            corrections.size() == 3
    }

    def "should extract JSON from markdown code blocks"() {
        given: "response with markdown"
            def response = """
            ```json
            {"3": 15}
            ```
            """

        when: "parsing corrections"
            def corrections = parseBatchCorrectionsReflection(response)

        then: "JSON is extracted and parsed"
            corrections[3] == 15
    }

    def "should handle malformed JSON gracefully"() {
        given: "malformed response"
            def response = "not json at all"

        when: "parsing corrections"
            def corrections = parseBatchCorrectionsReflection(response)

        then: "empty map returned"
            corrections.isEmpty()
    }

    // ==================== HELPER METHODS ====================

    private ComparisonResult createLineItemResult(Long extractedIndex, Integer poIndex,
                                                  String itemCode = "ITEM-001",
                                                  String description = "Widget") {
        def result = new ComparisonResult()
        result.setType(ComparisonResult.Type.LINE_ITEM)
        result.setExtractedIndex(extractedIndex)
        result.setPoIndex(poIndex?.longValue())

        def comparisons = []
        if (itemCode) {
            comparisons.add(createFieldComparison("Item Code", itemCode, itemCode))
        }
        if (description) {
            comparisons.add(createFieldComparison("Description", description, description))
        }
        result.setComparisons(comparisons)

        return result
    }

    private FieldComparison createFieldComparison(String field, String docValue, String poValue) {
        def fc = new FieldComparison()
        fc.setField(field)
        fc.setDocumentValue(docValue)
        fc.setPoValue(poValue)
        return fc
    }

    private String extractItemCodeReflection(ComparisonResult result) {
        def method = LineItemValidator.class.getDeclaredMethod("extractItemCode", ComparisonResult.class)
        method.setAccessible(true)
        return method.invoke(validator, result) as String
    }

    private String extractDescriptionReflection(ComparisonResult result) {
        def method = LineItemValidator.class.getDeclaredMethod("extractDescription", ComparisonResult.class)
        method.setAccessible(true)
        return method.invoke(validator, result) as String
    }

    private Map<Integer, Integer> parseBatchCorrectionsReflection(String response) {
        def method = LineItemValidator.class.getDeclaredMethod("parseBatchCorrections", String.class)
        method.setAccessible(true)
        return method.invoke(validator, response) as Map<Integer, Integer>
    }
}

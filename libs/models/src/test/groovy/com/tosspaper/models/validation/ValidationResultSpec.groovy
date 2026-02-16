package com.tosspaper.models.validation

import spock.lang.Specification

/**
 * Tests for ValidationResult.
 * Verifies validation result building and combination.
 */
class ValidationResultSpec extends Specification {

    def "valid should create valid result with no violations"() {
        when:
        def result = ValidationResult.valid()

        then:
        result.isValid()
        !result.isInvalid()
        result.violations.isEmpty()
        result.violationMessage == ""
    }

    def "invalid with single violation should create invalid result"() {
        when:
        def result = ValidationResult.invalid("Error message")

        then:
        !result.isValid()
        result.isInvalid()
        result.violations == ["Error message"]
        result.violationMessage == "Error message"
    }

    def "invalid with multiple violations should create invalid result"() {
        when:
        def result = ValidationResult.invalid("Error 1", "Error 2", "Error 3")

        then:
        result.isInvalid()
        result.violations.size() == 3
        result.violations.containsAll(["Error 1", "Error 2", "Error 3"])
    }

    def "invalid with list should create invalid result"() {
        when:
        def result = ValidationResult.invalid(["Error A", "Error B"])

        then:
        result.isInvalid()
        result.violations == ["Error A", "Error B"]
    }

    def "getViolationMessage should join violations with semicolon"() {
        when:
        def result = ValidationResult.invalid("Error 1", "Error 2", "Error 3")

        then:
        result.violationMessage == "Error 1; Error 2; Error 3"
    }

    def "getViolations should return unmodifiable list"() {
        given:
        def result = ValidationResult.invalid("Error 1")

        when:
        result.violations.add("Error 2")

        then:
        thrown(UnsupportedOperationException)
    }

    def "addViolation should create new result with added violation"() {
        given:
        def result = ValidationResult.invalid("Error 1")

        when:
        def newResult = result.addViolation("Error 2")

        then:
        result.violations == ["Error 1"] // Original unchanged
        newResult.violations == ["Error 1", "Error 2"]
        newResult.isInvalid()
    }

    def "addViolation to valid result should create invalid result"() {
        given:
        def result = ValidationResult.valid()

        when:
        def newResult = result.addViolation("New error")

        then:
        result.isValid() // Original unchanged
        newResult.isInvalid()
        newResult.violations == ["New error"]
    }

    def "combine should merge violations from two results"() {
        given:
        def result1 = ValidationResult.invalid("Error 1", "Error 2")
        def result2 = ValidationResult.invalid("Error 3", "Error 4")

        when:
        def combined = result1.combine(result2)

        then:
        combined.isInvalid()
        combined.violations == ["Error 1", "Error 2", "Error 3", "Error 4"]
    }

    def "combine valid with valid should return valid"() {
        given:
        def result1 = ValidationResult.valid()
        def result2 = ValidationResult.valid()

        when:
        def combined = result1.combine(result2)

        then:
        combined.isValid()
        combined.violations.isEmpty()
    }

    def "combine valid with invalid should return invalid"() {
        given:
        def valid = ValidationResult.valid()
        def invalid = ValidationResult.invalid("Error 1")

        when:
        def combined1 = valid.combine(invalid)
        def combined2 = invalid.combine(valid)

        then:
        combined1.isInvalid()
        combined1.violations == ["Error 1"]
        combined2.isInvalid()
        combined2.violations == ["Error 1"]
    }

    def "combine should not modify original results"() {
        given:
        def result1 = ValidationResult.invalid("Error 1")
        def result2 = ValidationResult.invalid("Error 2")

        when:
        def combined = result1.combine(result2)

        then:
        result1.violations == ["Error 1"]
        result2.violations == ["Error 2"]
        combined.violations == ["Error 1", "Error 2"]
    }

    def "combine should handle empty violations"() {
        given:
        def result1 = ValidationResult.invalid("Error 1")
        def result2 = ValidationResult.valid()
        def result3 = ValidationResult.invalid("Error 3")

        when:
        def combined = result1.combine(result2).combine(result3)

        then:
        combined.violations == ["Error 1", "Error 3"]
    }

    def "multiple addViolation calls should accumulate violations"() {
        given:
        def result = ValidationResult.valid()

        when:
        def result1 = result.addViolation("Error 1")
        def result2 = result1.addViolation("Error 2")
        def result3 = result2.addViolation("Error 3")

        then:
        result3.violations == ["Error 1", "Error 2", "Error 3"]
    }

    def "isValid and isInvalid should be opposite"() {
        given:
        def valid = ValidationResult.valid()
        def invalid = ValidationResult.invalid("Error")

        expect:
        valid.isValid() == !valid.isInvalid()
        invalid.isValid() == !invalid.isInvalid()
    }

    def "violations list should be defensive copy"() {
        given:
        def originalList = ["Error 1", "Error 2"]
        def result = ValidationResult.invalid(originalList)

        when:
        originalList.add("Error 3")

        then:
        result.violations == ["Error 1", "Error 2"] // Not affected by external modification
    }

    def "invalid with null list should handle gracefully"() {
        when:
        def result = ValidationResult.invalid((List<String>) null)

        then:
        result.isValid() // Empty list = valid
        result.violations.isEmpty()
    }

    def "getViolationMessage with empty violations should return empty string"() {
        when:
        def result = ValidationResult.valid()

        then:
        result.violationMessage == ""
    }

    def "combine multiple results in sequence"() {
        given:
        def result1 = ValidationResult.invalid("Error 1")
        def result2 = ValidationResult.invalid("Error 2")
        def result3 = ValidationResult.valid()
        def result4 = ValidationResult.invalid("Error 4")

        when:
        def combined = result1
            .combine(result2)
            .combine(result3)
            .combine(result4)

        then:
        combined.violations == ["Error 1", "Error 2", "Error 4"]
    }

    def "violations should maintain insertion order"() {
        when:
        def result = ValidationResult.invalid("Z", "A", "M", "B")

        then:
        result.violations == ["Z", "A", "M", "B"]
    }

    def "getViolationMessage should handle special characters"() {
        when:
        def result = ValidationResult.invalid("Error: file too large", "Error: invalid format", "Error: missing field")

        then:
        result.violationMessage == "Error: file too large; Error: invalid format; Error: missing field"
    }
}

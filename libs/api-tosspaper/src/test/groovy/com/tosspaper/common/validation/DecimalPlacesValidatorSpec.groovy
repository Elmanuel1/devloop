package com.tosspaper.common.validation

import spock.lang.Specification
import spock.lang.Unroll

class DecimalPlacesValidatorSpec extends Specification {

    DecimalPlacesValidator validator = new DecimalPlacesValidator()

    def setup() {
        // Initialize with default of 2 decimal places
        def annotation = Mock(DecimalPlaces) {
            value() >> 2
        }
        validator.initialize(annotation)
    }

    def "null value is valid"() {
        expect:
        validator.isValid(null, null)
    }

    @Unroll
    def "value #value with #scale decimal places is #expected when max is 2"() {
        expect:
        validator.isValid(value, null) == expected

        where:
        value                          | scale | expected
        new BigDecimal("100")          | 0     | true
        new BigDecimal("100.1")        | 1     | true
        new BigDecimal("100.12")       | 2     | true
        new BigDecimal("100.123")      | 3     | false
        new BigDecimal("100.1234")     | 4     | false
        new BigDecimal("0.00")         | 2     | true
        new BigDecimal("0.001")        | 3     | false
    }

    def "value with negative scale (trailing zeros) is valid"() {
        given: "a value like 1200 which has negative scale"
        def value = new BigDecimal("1200").setScale(-2)

        expect:
        validator.isValid(value, null)
    }

    def "integer values are valid"() {
        expect:
        validator.isValid(BigDecimal.valueOf(100), null)
        validator.isValid(BigDecimal.ZERO, null)
        validator.isValid(BigDecimal.ONE, null)
    }

    def "validator with different max decimal places"() {
        given: "validator initialized with 4 decimal places"
        def annotation = Mock(DecimalPlaces) {
            value() >> 4
        }
        validator.initialize(annotation)

        expect:
        validator.isValid(new BigDecimal("100.1234"), null)
        !validator.isValid(new BigDecimal("100.12345"), null)
    }
}

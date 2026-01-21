package com.tosspaper.common

import spock.lang.Specification
import spock.lang.Unroll

class HeaderUtilsSpec extends Specification {

    @Unroll
    def "parseCompanyId parses valid company ID '#input' to #expected"() {
        expect:
        HeaderUtils.parseCompanyId(input) == expected

        where:
        input        | expected
        "123"        | 123L
        "1"          | 1L
        "9999999999" | 9999999999L
        "0"          | 0L
        "-1"         | -1L
    }

    def "parseCompanyId throws BadRequestException for non-numeric input"() {
        when:
        HeaderUtils.parseCompanyId("not-a-number")

        then:
        thrown(BadRequestException)
    }

    def "parseCompanyId throws BadRequestException for empty string"() {
        when:
        HeaderUtils.parseCompanyId("")

        then:
        thrown(BadRequestException)
    }

    def "parseCompanyId throws BadRequestException for whitespace"() {
        when:
        HeaderUtils.parseCompanyId("  ")

        then:
        thrown(BadRequestException)
    }

    def "parseCompanyId throws BadRequestException for decimal number"() {
        when:
        HeaderUtils.parseCompanyId("123.45")

        then:
        thrown(BadRequestException)
    }

    def "parseCompanyId throws BadRequestException for null"() {
        when:
        HeaderUtils.parseCompanyId(null)

        then:
        thrown(BadRequestException)
    }

    def "parseCompanyId throws BadRequestException for mixed alphanumeric"() {
        when:
        HeaderUtils.parseCompanyId("123abc")

        then:
        thrown(BadRequestException)
    }
}

package com.tosspaper.common

import spock.lang.Specification
import spock.lang.Unroll

class PaginationUtilsSpec extends Specification {

    // ==================== clampLimit ====================

    @Unroll
    def "clampLimit(#input) should return #expected"() {
        expect:
            PaginationUtils.clampLimit(input) == expected

        where:
            input | expected
            null  | 20
            0     | 20
            -5    | 20
            1     | 1
            50    | 50
            100   | 100
            150   | 20
    }

    // ==================== hasMore ====================

    def "hasMore should return true when records exceed effectiveLimit"() {
        given: "4 items and limit 3"
            def records = [1, 2, 3, 4]

        expect:
            PaginationUtils.hasMore(records, 3) == true
    }

    def "hasMore should return false when records equal effectiveLimit"() {
        given: "3 items and limit 3"
            def records = [1, 2, 3]

        expect:
            PaginationUtils.hasMore(records, 3) == false
    }

    def "hasMore should return false when records are fewer than effectiveLimit"() {
        given: "2 items and limit 3"
            def records = [1, 2]

        expect:
            PaginationUtils.hasMore(records, 3) == false
    }

    // ==================== truncate ====================

    def "truncate should return first N items when records exceed effectiveLimit"() {
        given: "4 items and limit 3"
            def records = ["a", "b", "c", "d"]

        when:
            def result = PaginationUtils.truncate(records, 3)

        then:
            result == ["a", "b", "c"]
            result.size() == 3
    }

    def "truncate should return same list when records equal effectiveLimit"() {
        given: "3 items and limit 3"
            def records = ["a", "b", "c"]

        when:
            def result = PaginationUtils.truncate(records, 3)

        then:
            result == ["a", "b", "c"]
            result.size() == 3
    }

    def "truncate should return same list when records are fewer than effectiveLimit"() {
        given: "2 items and limit 3"
            def records = ["a", "b"]

        when:
            def result = PaginationUtils.truncate(records, 3)

        then:
            result == ["a", "b"]
            result.size() == 2
    }
}

package com.tosspaper.common.utils

import spock.lang.Specification

import java.time.LocalDate
import java.time.ZoneOffset

class DateRangeUtilsSpec extends Specification {

    def "toStartOfDay returns null for null input"() {
        expect:
        DateRangeUtils.toStartOfDay(null) == null
    }

    def "toStartOfDay returns start of day in UTC"() {
        given:
        def date = LocalDate.of(2024, 6, 15)

        when:
        def result = DateRangeUtils.toStartOfDay(date)

        then:
        result.year == 2024
        result.monthValue == 6
        result.dayOfMonth == 15
        result.hour == 0
        result.minute == 0
        result.second == 0
        result.nano == 0
        result.offset == ZoneOffset.UTC
    }

    def "toEndOfDay returns null for null input"() {
        expect:
        DateRangeUtils.toEndOfDay(null) == null
    }

    def "toEndOfDay returns end of day in UTC"() {
        given:
        def date = LocalDate.of(2024, 6, 15)

        when:
        def result = DateRangeUtils.toEndOfDay(date)

        then:
        result.year == 2024
        result.monthValue == 6
        result.dayOfMonth == 15
        result.hour == 23
        result.minute == 59
        result.second == 59
        result.nano == 999999999
        result.offset == ZoneOffset.UTC
    }

    def "toStartOfDay and toEndOfDay cover full day range"() {
        given:
        def date = LocalDate.of(2024, 1, 1)

        when:
        def start = DateRangeUtils.toStartOfDay(date)
        def end = DateRangeUtils.toEndOfDay(date)

        then:
        start.isBefore(end)
        start.toLocalDate() == end.toLocalDate()
    }
}

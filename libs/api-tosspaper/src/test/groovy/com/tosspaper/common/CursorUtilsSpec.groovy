package com.tosspaper.common

import spock.lang.Specification
import spock.lang.Unroll
import java.time.OffsetDateTime
import java.time.ZoneOffset

class CursorUtilsSpec extends Specification {

    def "encodeCursor encodes timestamp and id correctly"() {
        given:
        def createdAt = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)
        def id = "01HQXYZ123456789"

        when:
        def cursor = CursorUtils.encodeCursor(createdAt, id)

        then:
        cursor != null
        !cursor.contains("+")  // URL-safe encoding
        !cursor.contains("/")
        !cursor.endsWith("=")  // No padding
    }

    def "decodeCursor decodes correctly"() {
        given:
        def createdAt = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)
        def id = "01HQXYZ123456789"
        def cursor = CursorUtils.encodeCursor(createdAt, id)

        when:
        def result = CursorUtils.decodeCursor(cursor)

        then:
        result.createdAt() == createdAt
        result.id() == id
    }

    def "encodeCursor throws exception for null createdAt"() {
        when:
        CursorUtils.encodeCursor(null, "id")

        then:
        thrown(IllegalArgumentException)
    }

    def "encodeCursor throws exception for null id"() {
        given:
        def createdAt = OffsetDateTime.now()

        when:
        CursorUtils.encodeCursor(createdAt, null)

        then:
        thrown(IllegalArgumentException)
    }

    def "encodeCursor throws exception for blank id"() {
        given:
        def createdAt = OffsetDateTime.now()

        when:
        CursorUtils.encodeCursor(createdAt, "  ")

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor throws exception for null cursor"() {
        when:
        CursorUtils.decodeCursor(null)

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor throws exception for blank cursor"() {
        when:
        CursorUtils.decodeCursor("  ")

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor throws exception for invalid base64"() {
        when:
        CursorUtils.decodeCursor("not-valid-base64!!!")

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor throws exception for cursor without delimiter"() {
        given:
        def invalidPayload = Base64.getUrlEncoder().withoutPadding().encodeToString("no-delimiter".getBytes())

        when:
        CursorUtils.decodeCursor(invalidPayload)

        then:
        thrown(IllegalArgumentException)
    }

    def "encodeEmailCursor encodes email correctly"() {
        given:
        def email = "test@example.com"

        when:
        def cursor = CursorUtils.encodeEmailCursor(email)

        then:
        cursor != null
        !cursor.contains("+")
        !cursor.contains("/")
    }

    def "decodeEmailCursor decodes correctly"() {
        given:
        def email = "test@example.com"
        def cursor = CursorUtils.encodeEmailCursor(email)

        when:
        def result = CursorUtils.decodeEmailCursor(cursor)

        then:
        result == email
    }

    def "encodeEmailCursor throws exception for null email"() {
        when:
        CursorUtils.encodeEmailCursor(null)

        then:
        thrown(IllegalArgumentException)
    }

    def "encodeEmailCursor throws exception for blank email"() {
        when:
        CursorUtils.encodeEmailCursor("  ")

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeEmailCursor throws exception for null cursor"() {
        when:
        CursorUtils.decodeEmailCursor(null)

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeEmailCursor throws exception for blank cursor"() {
        when:
        CursorUtils.decodeEmailCursor("  ")

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeEmailCursor throws exception for invalid base64"() {
        when:
        CursorUtils.decodeEmailCursor("invalid!!!base64")

        then:
        thrown(IllegalArgumentException)
    }

    def "roundtrip encode and decode preserves data"() {
        given:
        def createdAt = OffsetDateTime.of(2024, 6, 15, 14, 30, 45, 123000000, ZoneOffset.ofHours(-5))
        def id = "01HW7ABCDEFGHIJK"

        when:
        def cursor = CursorUtils.encodeCursor(createdAt, id)
        def result = CursorUtils.decodeCursor(cursor)

        then:
        result.createdAt() == createdAt
        result.id() == id
    }
}

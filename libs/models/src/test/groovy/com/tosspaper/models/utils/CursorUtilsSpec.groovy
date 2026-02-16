package com.tosspaper.models.utils

import spock.lang.Specification

import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Tests for CursorUtils.
 * Verifies cursor encoding and decoding for pagination.
 */
class CursorUtilsSpec extends Specification {

    def "encodeCursor should encode timestamp and ID to base64"() {
        given:
        def createdAt = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)
        def id = "01HQXYZ123ABC"

        when:
        def cursor = CursorUtils.encodeCursor(createdAt, id)

        then:
        cursor != null
        cursor.length() > 0
        !cursor.contains("=") // URL-safe, no padding
        !cursor.contains("+")
        !cursor.contains("/")
    }

    def "decodeCursor should decode cursor to timestamp and ID"() {
        given:
        def createdAt = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)
        def id = "01HQXYZ123ABC"
        def cursor = CursorUtils.encodeCursor(createdAt, id)

        when:
        def decoded = CursorUtils.decodeCursor(cursor)

        then:
        decoded.createdAt() == createdAt
        decoded.id() == id
    }

    def "encodeCursor and decodeCursor should be reversible"() {
        given:
        def createdAt = OffsetDateTime.of(2024, 3, 20, 15, 45, 30, 500000000, ZoneOffset.ofHours(-5))
        def id = "01HQTEST123XYZ"

        when:
        def encoded = CursorUtils.encodeCursor(createdAt, id)
        def decoded = CursorUtils.decodeCursor(encoded)

        then:
        decoded.createdAt() == createdAt
        decoded.id() == id
    }

    def "encodeCursor should handle various timestamps"() {
        given:
        def id = "TEST123"

        when:
        def cursor1 = CursorUtils.encodeCursor(timestamp, id)
        def decoded1 = CursorUtils.decodeCursor(cursor1)

        then:
        decoded1.createdAt() == timestamp

        where:
        timestamp << [
            OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 999999999, ZoneOffset.UTC),
            OffsetDateTime.now(),
            OffsetDateTime.of(2020, 6, 15, 12, 0, 0, 0, ZoneOffset.ofHours(8))
        ]
    }

    def "encodeCursor should handle various ID formats"() {
        given:
        def createdAt = OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC)

        when:
        def cursor = CursorUtils.encodeCursor(createdAt, id)
        def decoded = CursorUtils.decodeCursor(cursor)

        then:
        decoded.id() == id

        where:
        id << [
            "01HQXYZ123ABC",
            "short",
            "very-long-id-with-many-characters-123456789",
            "ID_WITH_UNDERSCORES",
            "id-with-dashes",
            "123456"
        ]
    }

    def "encodeCursor should throw exception for null timestamp"() {
        when:
        CursorUtils.encodeCursor(null, "id123")

        then:
        thrown(IllegalArgumentException)
    }

    def "encodeCursor should throw exception for null ID"() {
        given:
        def createdAt = OffsetDateTime.now()

        when:
        CursorUtils.encodeCursor(createdAt, null)

        then:
        thrown(IllegalArgumentException)
    }

    def "encodeCursor should throw exception for blank ID"() {
        given:
        def createdAt = OffsetDateTime.now()

        when:
        CursorUtils.encodeCursor(createdAt, "   ")

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor should throw exception for null cursor"() {
        when:
        CursorUtils.decodeCursor(null)

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor should throw exception for blank cursor"() {
        when:
        CursorUtils.decodeCursor("   ")

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor should throw exception for invalid base64"() {
        when:
        CursorUtils.decodeCursor("not-valid-base64!!!")

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor should throw exception for malformed cursor content"() {
        given:
        def invalidCursor = Base64.urlEncoder.withoutPadding().encodeToString("no-delimiter-here".bytes)

        when:
        CursorUtils.decodeCursor(invalidCursor)

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor should throw exception for cursor with empty ID"() {
        given:
        def timestamp = OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        def invalidContent = "${timestamp}|" // Delimiter at end
        def invalidCursor = Base64.urlEncoder.withoutPadding().encodeToString(invalidContent.bytes)

        when:
        CursorUtils.decodeCursor(invalidCursor)

        then:
        thrown(IllegalArgumentException)
    }

    def "decodeCursor should throw exception for invalid timestamp format"() {
        given:
        def invalidContent = "not-a-timestamp|id123"
        def invalidCursor = Base64.urlEncoder.withoutPadding().encodeToString(invalidContent.bytes)

        when:
        CursorUtils.decodeCursor(invalidCursor)

        then:
        thrown(IllegalArgumentException)
    }

    def "encodeCursor should use URL-safe base64 encoding"() {
        given:
        def createdAt = OffsetDateTime.now()
        def id = "test-id-123"

        when:
        def cursor = CursorUtils.encodeCursor(createdAt, id)

        then:
        !cursor.contains("+") // Standard base64 char
        !cursor.contains("/") // Standard base64 char
        !cursor.contains("=") // Padding
    }

    def "decodeCursor should fail for cursor with ID containing delimiter"() {
        given:
        def createdAt = OffsetDateTime.of(2024, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC)
        def id = "id|with|pipes" // Contains delimiter characters

        when:
        def cursor = CursorUtils.encodeCursor(createdAt, id)
        CursorUtils.decodeCursor(cursor)

        then:
        // lastIndexOf splits at last "|", corrupting the timestamp portion
        // which causes a parse failure
        thrown(IllegalArgumentException)
    }

    def "CursorPair should be a record with proper accessors"() {
        given:
        def createdAt = OffsetDateTime.now()
        def id = "test123"

        when:
        def pair = new CursorUtils.CursorPair(createdAt, id)

        then:
        pair.createdAt() == createdAt
        pair.id() == id
    }

    def "encodeCursor should handle timestamps with different time zones"() {
        given:
        def id = "tz-test"
        def utc = OffsetDateTime.of(2024, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC)
        def est = OffsetDateTime.of(2024, 1, 15, 7, 0, 0, 0, ZoneOffset.ofHours(-5))
        def jst = OffsetDateTime.of(2024, 1, 15, 21, 0, 0, 0, ZoneOffset.ofHours(9))

        when:
        def cursor1 = CursorUtils.encodeCursor(utc, id)
        def cursor2 = CursorUtils.encodeCursor(est, id)
        def cursor3 = CursorUtils.encodeCursor(jst, id)

        def decoded1 = CursorUtils.decodeCursor(cursor1)
        def decoded2 = CursorUtils.decodeCursor(cursor2)
        def decoded3 = CursorUtils.decodeCursor(cursor3)

        then:
        decoded1.createdAt() == utc
        decoded2.createdAt() == est
        decoded3.createdAt() == jst
        // All represent the same instant
        decoded1.createdAt().toInstant() == decoded2.createdAt().toInstant()
        decoded2.createdAt().toInstant() == decoded3.createdAt().toInstant()
    }

    def "encodeCursor should handle IDs with special characters"() {
        given:
        def createdAt = OffsetDateTime.now()

        when:
        def cursor = CursorUtils.encodeCursor(createdAt, id)
        def decoded = CursorUtils.decodeCursor(cursor)

        then:
        decoded.id() == id

        where:
        id << [
            "id-with-dashes",
            "id_with_underscores",
            "id.with.dots",
            "ID123ABC",
            "01HQXYZ",
            "mixed_Case-ID.123"
        ]
    }
}

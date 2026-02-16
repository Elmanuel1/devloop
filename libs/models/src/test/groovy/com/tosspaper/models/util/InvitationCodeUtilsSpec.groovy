package com.tosspaper.models.util

import spock.lang.Specification

/**
 * Tests for InvitationCodeUtils.
 * Verifies invitation code encoding and decoding.
 */
class InvitationCodeUtilsSpec extends Specification {

    def "encode should create base64 URL-safe code from companyId and email"() {
        given:
        def companyId = 12345L
        def email = "user@example.com"

        when:
        def code = InvitationCodeUtils.encode(companyId, email)

        then:
        code != null
        code.length() > 0
        !code.contains("=") // No padding
        !code.contains("+")
        !code.contains("/")
    }

    def "decode should extract companyId and email from code"() {
        given:
        def companyId = 12345L
        def email = "user@example.com"
        def code = InvitationCodeUtils.encode(companyId, email)

        when:
        def data = InvitationCodeUtils.decode(code)

        then:
        data.companyId() == companyId
        data.email() == email
    }

    def "encode and decode should be reversible"() {
        given:
        def companyId = 98765L
        def email = "test@company.com"

        when:
        def encoded = InvitationCodeUtils.encode(companyId, email)
        def decoded = InvitationCodeUtils.decode(encoded)

        then:
        decoded.companyId() == companyId
        decoded.email() == email
    }

    def "encode should handle various company IDs"() {
        given:
        def email = "user@test.com"

        when:
        def code = InvitationCodeUtils.encode(companyId, email)
        def decoded = InvitationCodeUtils.decode(code)

        then:
        decoded.companyId() == companyId

        where:
        companyId << [1L, 100L, 999999L, Long.MAX_VALUE]
    }

    def "encode should handle various email formats"() {
        given:
        def companyId = 123L

        when:
        def code = InvitationCodeUtils.encode(companyId, email)
        def decoded = InvitationCodeUtils.decode(code)

        then:
        decoded.email() == email

        where:
        email << [
            "simple@example.com",
            "user.name@example.com",
            "user+tag@example.com",
            "user_name@sub.example.com",
            "123@example.com",
            "a@b.co"
        ]
    }

    def "decode should throw exception for invalid base64"() {
        when:
        InvitationCodeUtils.decode("not-valid-base64!!!")

        then:
        thrown(IllegalArgumentException)
    }

    def "decode should throw exception for malformed payload"() {
        given:
        def invalidPayload = "no-separator-here"
        def code = Base64.urlEncoder.withoutPadding().encodeToString(invalidPayload.bytes)

        when:
        InvitationCodeUtils.decode(code)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Invalid invitation code")
    }

    def "decode should throw exception for non-numeric company ID"() {
        given:
        def invalidPayload = "abc:user@example.com"
        def code = Base64.urlEncoder.withoutPadding().encodeToString(invalidPayload.bytes)

        when:
        InvitationCodeUtils.decode(code)

        then:
        thrown(IllegalArgumentException)
    }

    def "decode should handle payload with colon in email"() {
        given:
        // Emails don't typically have colons, but the split should handle it
        def companyId = 123L
        def email = "user:special@example.com"
        def code = InvitationCodeUtils.encode(companyId, email)

        when:
        def decoded = InvitationCodeUtils.decode(code)

        then:
        decoded.email() == email // Uses split with limit 2
    }

    def "encode should use URL-safe base64"() {
        given:
        def companyId = 123L
        def email = "user@example.com"

        when:
        def code = InvitationCodeUtils.encode(companyId, email)

        then:
        !code.contains("+")
        !code.contains("/")
        !code.contains("=")
    }

    def "encode should handle long emails"() {
        given:
        def companyId = 123L
        def email = "very.long.email.address.with.many.dots@subdomain.example.company.com"

        when:
        def code = InvitationCodeUtils.encode(companyId, email)
        def decoded = InvitationCodeUtils.decode(code)

        then:
        decoded.email() == email
    }

    def "encode should handle zero company ID"() {
        given:
        def companyId = 0L
        def email = "user@example.com"

        when:
        def code = InvitationCodeUtils.encode(companyId, email)
        def decoded = InvitationCodeUtils.decode(code)

        then:
        decoded.companyId() == 0L
    }

    def "encode should handle negative company ID"() {
        given:
        def companyId = -123L
        def email = "user@example.com"

        when:
        def code = InvitationCodeUtils.encode(companyId, email)
        def decoded = InvitationCodeUtils.decode(code)

        then:
        decoded.companyId() == -123L
    }

    def "different inputs should produce different codes"() {
        when:
        def code1 = InvitationCodeUtils.encode(123L, "user1@example.com")
        def code2 = InvitationCodeUtils.encode(123L, "user2@example.com")
        def code3 = InvitationCodeUtils.encode(456L, "user1@example.com")

        then:
        code1 != code2
        code1 != code3
        code2 != code3
    }

    def "InvitationData should be a record with proper accessors"() {
        when:
        def data = new InvitationCodeUtils.InvitationData(123L, "user@example.com")

        then:
        data.companyId() == 123L
        data.email() == "user@example.com"
    }

    def "encode should handle email with special characters"() {
        given:
        def companyId = 123L
        def email = "user+tag_name.test@example.com"

        when:
        def code = InvitationCodeUtils.encode(companyId, email)
        def decoded = InvitationCodeUtils.decode(code)

        then:
        decoded.email() == email
    }

    def "decode should handle empty payload parts gracefully"() {
        given:
        def invalidPayload = ":email@example.com" // Empty company ID
        def code = Base64.urlEncoder.withoutPadding().encodeToString(invalidPayload.bytes)

        when:
        InvitationCodeUtils.decode(code)

        then:
        thrown(IllegalArgumentException)
    }
}

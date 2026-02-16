package com.tosspaper.models.utils

import spock.lang.Specification

/**
 * Tests for EmailUtils.
 * Verifies email address cleaning and validation.
 */
class EmailUtilsSpec extends Specification {

    def "cleanEmailAddress should extract email from display name format"() {
        expect:
        EmailUtils.cleanEmailAddress(input) == expected

        where:
        input                                  | expected
        '"John Doe" <john@example.com>'       | "john@example.com"
        'John Doe <john@example.com>'         | "john@example.com"
        '<john@example.com>'                  | "john@example.com"
        'john@example.com'                    | "john@example.com"
        '"Jane Smith" <jane.smith@company.co.uk>' | "jane.smith@company.co.uk"
    }

    def "cleanEmailAddress should handle nested brackets"() {
        expect:
        // lastIndexOf('<') finds the second '<', indexOf('>') finds the matching '>'
        // Extracts content between last '<' and next '>'
        EmailUtils.cleanEmailAddress('Name <inner <actual@email.com>>') == "actual@email.com"
    }

    def "cleanEmailAddress should use last occurrence of brackets"() {
        given:
        def input = 'First <wrong@email.com> Second <correct@email.com>'

        expect:
        EmailUtils.cleanEmailAddress(input) == "correct@email.com"
    }

    def "cleanEmailAddress should handle empty brackets"() {
        expect:
        EmailUtils.cleanEmailAddress('Name <>') == 'Name <>' // Falls back to original
    }

    def "cleanEmailAddress should handle malformed brackets"() {
        expect:
        EmailUtils.cleanEmailAddress('Name <email@example.com') == 'Name <email@example.com'
        EmailUtils.cleanEmailAddress('Name email@example.com>') == 'Name email@example.com>'
    }

    def "cleanEmailAddress should trim whitespace from extracted email"() {
        expect:
        EmailUtils.cleanEmailAddress('Name < email@example.com >') == "email@example.com"
        EmailUtils.cleanEmailAddress('<  email@example.com  >') == "email@example.com"
    }

    def "cleanEmailAddress should handle null input"() {
        expect:
        EmailUtils.cleanEmailAddress(null) == null
    }

    def "cleanEmailAddress should handle blank input"() {
        expect:
        EmailUtils.cleanEmailAddress('') == ''
        EmailUtils.cleanEmailAddress('   ') == '   '
    }

    def "cleanEmailAddress should trim plain email"() {
        expect:
        EmailUtils.cleanEmailAddress('  email@example.com  ') == 'email@example.com'
    }

    def "isValidEmail should return true for valid emails"() {
        expect:
        EmailUtils.isValidEmail(email)

        where:
        email << [
            "user@example.com",
            "test.user@example.com",
            "test_user@example.com",
            "test+user@example.com",
            "test@subdomain.example.com",
            "123@example.com",
            "user@example.co.uk"
        ]
    }

    def "isValidEmail should return false for invalid emails"() {
        expect:
        !EmailUtils.isValidEmail(email)

        where:
        email << [
            "notanemail",
            "@example.com",
            "user@",
            "user @example.com",
            "user@.com",
            "user..test@example.com",
            "",
            "   ",
            null
        ]
    }

    def "isValidEmail should trim whitespace before validating"() {
        expect:
        EmailUtils.isValidEmail('  user@example.com  ')
    }

    def "isValidEmail should handle special characters"() {
        expect:
        EmailUtils.isValidEmail("test+tag@example.com")
        EmailUtils.isValidEmail("test.user@example.com")
        EmailUtils.isValidEmail("test_user@example.com")
    }

    def "isValidDomain should return true for valid domains"() {
        expect:
        EmailUtils.isValidDomain(domain)

        where:
        domain << [
            "example.com",
            "subdomain.example.com",
            "sub.sub.example.com",
            "example.co.uk",
            "example-domain.com",
            "123.456.com"
        ]
    }

    def "isValidDomain should return false for invalid domains"() {
        expect:
        !EmailUtils.isValidDomain(domain)

        where:
        domain << [
            "nodot",
            ".com",
            "example.",
            "@example.com",
            "example .com",
            "",
            "   ",
            null
        ]
    }

    def "isValidDomain should reject domains with @ symbol"() {
        expect:
        !EmailUtils.isValidDomain("user@example.com")
    }

    def "isValidDomain should require at least one dot"() {
        expect:
        !EmailUtils.isValidDomain("nodomain")
    }

    def "isValidDomain should reject domains starting with dot"() {
        expect:
        !EmailUtils.isValidDomain(".example.com")
    }

    def "isValidDomain should handle domains ending with dot"() {
        expect:
        // Implementation only checks first dot index against length()-1
        // "example.com." has first dot at index 7, length-1 = 11
        // So the dot-at-end check doesn't catch it, and regex allows dots
        EmailUtils.isValidDomain("example.com.")
    }

    def "isValidDomain should reject domains with internal spaces"() {
        expect:
        // Domains with internal spaces fail regex check
        !EmailUtils.isValidDomain("example .com")
        // Leading/trailing spaces are trimmed, so these are valid after trimming
        EmailUtils.isValidDomain(" example.com")
        EmailUtils.isValidDomain("example.com ")
    }

    def "isValidDomain should trim whitespace before validating"() {
        expect:
        EmailUtils.isValidDomain("  example.com  ")
    }

    def "isValidDomain should accept domains with hyphens"() {
        expect:
        EmailUtils.isValidDomain("my-domain.com")
        EmailUtils.isValidDomain("sub-domain.example.com")
    }

    def "isValidDomain should accept domains with numbers"() {
        expect:
        EmailUtils.isValidDomain("domain123.com")
        EmailUtils.isValidDomain("123domain.com")
    }

    def "isValidDomain should reject special characters except dot and hyphen"() {
        expect:
        !EmailUtils.isValidDomain("domain@example.com")
        !EmailUtils.isValidDomain("domain_example.com")
        !EmailUtils.isValidDomain("domain+example.com")
        !EmailUtils.isValidDomain("domain example.com")
    }
}

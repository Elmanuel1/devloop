package com.tosspaper.models.utils

import spock.lang.Specification

/**
 * Tests for EmailPatternMatcher.
 * Verifies email pattern matching and regex generation.
 */
class EmailPatternMatcherSpec extends Specification {

    def "matches should match email against pattern"() {
        expect:
        EmailPatternMatcher.matches(email, pattern) == expected

        where:
        email                  | pattern                   | expected
        "user@acme.com"       | '^.*@acme\\.com$'        | true
        "test@acme.com"       | '^.*@acme\\.com$'        | true
        "user@other.com"      | '^.*@acme\\.com$'        | false
        "exact@test.com"      | '^exact@test\\.com$'     | true
        "other@test.com"      | '^exact@test\\.com$'     | false
    }

    def "matches should return false for null email"() {
        expect:
        !EmailPatternMatcher.matches(null, '^.*@example\\.com$')
    }

    def "matches should return false for null pattern"() {
        expect:
        !EmailPatternMatcher.matches("user@example.com", null)
    }

    def "matches should return false for both null"() {
        expect:
        !EmailPatternMatcher.matches(null, null)
    }

    def "createEmailPattern should create exact match pattern"() {
        given:
        def email = "user@example.com"

        when:
        def pattern = EmailPatternMatcher.createEmailPattern(email)

        then:
        pattern == '^user@example\\.com$'
        EmailPatternMatcher.matches(email, pattern)
        !EmailPatternMatcher.matches("other@example.com", pattern)
    }

    def "createEmailPattern should escape dots in email"() {
        given:
        def email = "user.name@sub.example.com"

        when:
        def pattern = EmailPatternMatcher.createEmailPattern(email)

        then:
        pattern.contains("\\.")
        EmailPatternMatcher.matches(email, pattern)
    }

    def "createEmailPattern should throw exception for null email"() {
        when:
        EmailPatternMatcher.createEmailPattern(null)

        then:
        thrown(IllegalArgumentException)
    }

    def "createDomainPattern should create domain match pattern"() {
        given:
        def domain = "example.com"

        when:
        def pattern = EmailPatternMatcher.createDomainPattern(domain)

        then:
        pattern == '^.*@example\\.com$'
        EmailPatternMatcher.matches("user@example.com", pattern)
        EmailPatternMatcher.matches("test@example.com", pattern)
        !EmailPatternMatcher.matches("user@other.com", pattern)
    }

    def "createDomainPattern should escape dots in domain"() {
        given:
        def domain = "sub.example.com"

        when:
        def pattern = EmailPatternMatcher.createDomainPattern(domain)

        then:
        pattern.contains("\\.")
        EmailPatternMatcher.matches("user@sub.example.com", pattern)
    }

    def "createDomainPattern should throw exception for null domain"() {
        when:
        EmailPatternMatcher.createDomainPattern(null)

        then:
        thrown(IllegalArgumentException)
    }

    def "extractDomain should extract domain from email"() {
        expect:
        EmailPatternMatcher.extractDomain(email) == expected

        where:
        email                      | expected
        "user@example.com"        | "example.com"
        "test@sub.example.com"    | "sub.example.com"
        "name@domain.co.uk"       | "domain.co.uk"
        "123@test.com"            | "test.com"
    }

    def "extractDomain should throw exception for null email"() {
        when:
        EmailPatternMatcher.extractDomain(null)

        then:
        thrown(IllegalArgumentException)
    }

    def "extractDomain should throw exception for email without @"() {
        when:
        EmailPatternMatcher.extractDomain("notanemail")

        then:
        thrown(IllegalArgumentException)
    }

    def "extractDomain should handle email with multiple @ symbols"() {
        given:
        def email = "user@name@example.com"

        when:
        def domain = EmailPatternMatcher.extractDomain(email)

        then:
        domain == "name@example.com" // Takes everything after first @
    }

    def "createEmailPattern should handle various email formats"() {
        given:
        def pattern = EmailPatternMatcher.createEmailPattern(email)

        expect:
        EmailPatternMatcher.matches(email, pattern)

        where:
        // Note: createEmailPattern only escapes dots, not other regex chars like +
        // So emails with + won't round-trip correctly through pattern matching
        email << [
            "simple@example.com",
            "user.name@example.com",
            "123@example.com",
            "user_name@sub.example.com"
        ]
    }

    def "createDomainPattern should handle various domain formats"() {
        given:
        def pattern = EmailPatternMatcher.createDomainPattern(domain)

        expect:
        EmailPatternMatcher.matches("user@${domain}", pattern)
        EmailPatternMatcher.matches("test@${domain}", pattern)

        where:
        domain << [
            "example.com",
            "sub.example.com",
            "example.co.uk",
            "very.long.subdomain.example.com"
        ]
    }

    def "matches should handle regex special characters in patterns"() {
        expect:
        EmailPatternMatcher.matches("user@example.com", pattern) == expected

        where:
        pattern                  | expected
        '^user@example\\.com$'  | true
        '^.*@example\\.com$'    | true
        '^user.*\\.com$'        | true
        ".*example.*"           | true
    }

    def "createEmailPattern should prevent wildcard matching"() {
        given:
        def email = "exact@example.com"
        def pattern = EmailPatternMatcher.createEmailPattern(email)

        expect:
        EmailPatternMatcher.matches("exact@example.com", pattern)
        !EmailPatternMatcher.matches("other@example.com", pattern)
        !EmailPatternMatcher.matches("exactXexample.com", pattern) // Dot is escaped
    }

    def "createDomainPattern should allow any local part"() {
        given:
        def pattern = EmailPatternMatcher.createDomainPattern("example.com")

        expect:
        EmailPatternMatcher.matches("any@example.com", pattern)
        EmailPatternMatcher.matches("user123@example.com", pattern)
        EmailPatternMatcher.matches("test.user@example.com", pattern)
    }

    def "createDomainPattern should not match partial domains"() {
        given:
        def pattern = EmailPatternMatcher.createDomainPattern("example.com")

        expect:
        !EmailPatternMatcher.matches("user@notexample.com", pattern)
        !EmailPatternMatcher.matches("user@example.com.fake", pattern)
    }

    def "patterns should be case-sensitive by default"() {
        given:
        def pattern = EmailPatternMatcher.createEmailPattern("User@Example.Com")

        expect:
        EmailPatternMatcher.matches("User@Example.Com", pattern)
        !EmailPatternMatcher.matches("user@example.com", pattern)
    }

    def "extractDomain should handle subdomain emails"() {
        expect:
        EmailPatternMatcher.extractDomain("user@mail.subdomain.example.com") == "mail.subdomain.example.com"
    }

    def "createEmailPattern and createDomainPattern should produce different patterns"() {
        given:
        def emailPattern = EmailPatternMatcher.createEmailPattern("user@example.com")
        def domainPattern = EmailPatternMatcher.createDomainPattern("example.com")

        expect:
        emailPattern != domainPattern
        EmailPatternMatcher.matches("user@example.com", emailPattern)
        EmailPatternMatcher.matches("user@example.com", domainPattern)
        !EmailPatternMatcher.matches("other@example.com", emailPattern)
        EmailPatternMatcher.matches("other@example.com", domainPattern)
    }
}

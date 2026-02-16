package com.tosspaper.models.utils

import spock.lang.Specification

/**
 * Tests for PostgresSearchUtils.
 * Verifies PostgreSQL full-text search query building.
 */
class PostgresSearchUtilsSpec extends Specification {

    def "buildPrefixQuery should create tsquery with prefix matching"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery(input) == expected

        where:
        input            | expected
        "hello"         | "hello:*"
        "hello world"   | "hello:* & world:*"
        "test query"    | "test:* & query:*"
    }

    def "buildPrefixQuery should escape PostgreSQL special characters"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery(input) == expected

        where:
        input           | expected
        "test&query"   | "test\\&query:*"
        "test|query"   | "test\\|query:*"
        "test!query"   | "test\\!query:*"
        "test(query"   | "test\\(query:*"
        "test)query"   | "test\\)query:*"
        "test:query"   | "test\\:query:*"
        "test<query"   | "test\\<query:*"
        "test>query"   | "test\\>query:*"
    }

    def "buildPrefixQuery should preserve hyphens and underscores"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("ABC-123") == "ABC-123:*"
        PostgresSearchUtils.buildPrefixQuery("test_value") == "test_value:*"
    }

    def "buildPrefixQuery should include all words after prefix append"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery(input) == expected

        where:
        // The filter is word.length() > 2 AFTER appending ":*"
        // So even single-char words ("a:*" = 3 chars) pass the filter
        input           | expected
        "a b"          | "a:* & b:*"
        "ab cd"        | "ab:* & cd:*"
        "a test"       | "a:* & test:*"
        "ab testing"   | "ab:* & testing:*"
        "hello a b"    | "hello:* & a:* & b:*"
    }

    def "buildPrefixQuery should handle null input"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery(null) == ""
    }

    def "buildPrefixQuery should handle empty input"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("") == ""
        PostgresSearchUtils.buildPrefixQuery("   ") == ""
    }

    def "buildPrefixQuery should trim whitespace"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("  hello  world  ") == "hello:* & world:*"
    }

    def "buildPrefixQuery should handle multiple spaces between words"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("hello    world") == "hello:* & world:*"
    }

    def "buildPrefixQuery should join words with AND operator"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("one two three") == "one:* & two:* & three:*"
    }

    def "buildPrefixQuery should handle real-world search terms"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery(input) == expected

        where:
        input                  | expected
        "ABC-123"             | "ABC-123:*"
        "invoice 2024"        | "invoice:* & 2024:*"
        "test & development"  | "test:* & \\&:* & development:*"
        "john@example.com"    | "john@example.com:*"
        "file.pdf"            | "file.pdf:*"
    }

    def "buildPrefixQuery should handle all special characters correctly"() {
        given:
        def input = "test&value|other!item(one)two:three<four>five"

        when:
        def result = PostgresSearchUtils.buildPrefixQuery(input)

        then:
        result.contains("\\&")
        result.contains("\\|")
        result.contains("\\!")
        result.contains("\\(")
        result.contains("\\)")
        result.contains("\\:")
        result.contains("\\<")
        result.contains("\\>")
    }

    def "buildPrefixQuery should preserve numeric values"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("12345") == "12345:*"
        PostgresSearchUtils.buildPrefixQuery("2024") == "2024:*"
    }

    def "buildPrefixQuery should handle alphanumeric combinations"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("ABC123XYZ") == "ABC123XYZ:*"
        PostgresSearchUtils.buildPrefixQuery("test123") == "test123:*"
    }

    def "buildPrefixQuery should handle email addresses"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("user@example.com") == "user@example.com:*"
    }

    def "buildPrefixQuery should handle file paths"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("path/to/file.pdf").contains("path/to/file.pdf:*")
    }

    def "buildPrefixQuery should handle document numbers"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("INV-2024-001") == "INV-2024-001:*"
        PostgresSearchUtils.buildPrefixQuery("DOC_123_456") == "DOC_123_456:*"
    }

    def "buildPrefixQuery should filter correctly based on character count"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery(input).isEmpty() == expected

        where:
        // Filter is word.length() > 2 AFTER appending ":*"
        // "a:*" = 3 chars, 3 > 2 = true -> passes filter (not empty)
        input    | expected
        "a"     | false  // "a:*" = 3 chars, passes filter
        "ab"    | false  // "ab:*" = 4 chars, passes filter
        "abc"   | false  // "abc:*" = 5 chars, passes filter
        "abcd"  | false  // "abcd:*" = 6 chars, passes filter
    }

    def "buildPrefixQuery should handle Unicode characters"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("文档") == "文档:*"
        PostgresSearchUtils.buildPrefixQuery("résumé") == "résumé:*"
    }

    def "buildPrefixQuery should handle mixed content"() {
        given:
        def input = "invoice ABC-123 2024 test"

        when:
        def result = PostgresSearchUtils.buildPrefixQuery(input)

        then:
        result == "invoice:* & ABC-123:* & 2024:* & test:*"
    }

    def "buildPrefixQuery should handle strings with only short words"() {
        expect:
        // All single-char words still pass the > 2 filter after ":*" is appended
        PostgresSearchUtils.buildPrefixQuery("a b c d e f") == "a:* & b:* & c:* & d:* & e:* & f:*"
    }

    def "buildPrefixQuery should handle single long word"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("verylongword") == "verylongword:*"
    }

    def "buildPrefixQuery result should be usable in PostgreSQL tsquery"() {
        given:
        def searchTerm = "invoice 2024"

        when:
        def query = PostgresSearchUtils.buildPrefixQuery(searchTerm)

        then:
        query == "invoice:* & 2024:*"
        // This format is valid for: to_tsquery('english', 'invoice:* & 2024:*')
    }

    def "buildPrefixQuery should handle tab and newline characters"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("hello\tworld\ntest") == "hello:* & world:* & test:*"
    }

    def "buildPrefixQuery should preserve dots in search terms"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery("file.pdf") == "file.pdf:*"
        PostgresSearchUtils.buildPrefixQuery("v1.0.0") == "v1.0.0:*"
    }

    def "buildPrefixQuery should handle compound terms with special chars"() {
        expect:
        PostgresSearchUtils.buildPrefixQuery(input).contains(":*")

        where:
        input << [
            "test@example.com",
            "2024-01-15",
            "file_name_123",
            "v1.2.3"
        ]
    }
}

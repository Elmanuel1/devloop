package com.tosspaper.models.utils

import spock.lang.Specification

/**
 * Tests for UrlSafeUtils.
 * Verifies URL-safe string transformation.
 */
class UrlSafeUtilsSpec extends Specification {

    def "makeUrlSafe should replace spaces with underscores"() {
        expect:
        UrlSafeUtils.makeUrlSafe("file name.pdf") == "file_name.pdf"
    }

    def "makeUrlSafe should replace hyphens with underscores"() {
        expect:
        UrlSafeUtils.makeUrlSafe("file-name.pdf") == "file_name.pdf"
    }

    def "makeUrlSafe should replace special characters"() {
        given:
        def input = 'file"name<test>|file.pdf'

        when:
        def result = UrlSafeUtils.makeUrlSafe(input)

        then:
        !result.contains('"')
        !result.contains('<')
        !result.contains('>')
        !result.contains('|')
    }

    def "makeUrlSafe should replace various problematic characters"() {
        expect:
        !UrlSafeUtils.makeUrlSafe(input).contains(badChar)

        where:
        input                  | badChar
        "file name.pdf"       | " "
        "file-name.pdf"       | "-"
        'file"name.pdf'       | '"'
        "file<name.pdf"       | "<"
        "file>name.pdf"       | ">"
        "file|name.pdf"       | "|"
        "file\\name.pdf"      | "\\"
        "file:name.pdf"       | ":"
        "file*name.pdf"       | "*"
        "file?name.pdf"       | "?"
        "file..name.pdf"      | ".."
    }

    def "makeUrlSafe should replace Unicode minus signs and dashes"() {
        expect:
        !UrlSafeUtils.makeUrlSafe(input).contains(badChar)

        where:
        input                        | badChar
        "file\u2212name.pdf"        | "\u2212" // MINUS SIGN
        "file\u2010name.pdf"        | "\u2010" // HYPHEN
        "file\u2011name.pdf"        | "\u2011" // NON-BREAKING HYPHEN
        "file\u2012name.pdf"        | "\u2012" // FIGURE DASH
        "file\u2013name.pdf"        | "\u2013" // EN DASH
        "file\u2014name.pdf"        | "\u2014" // EM DASH
        "file\u2015name.pdf"        | "\u2015" // HORIZONTAL BAR
    }

    def "makeUrlSafe should preserve forward slashes"() {
        expect:
        UrlSafeUtils.makeUrlSafe("path/to/file.pdf") == "path/to/file.pdf"
    }

    def "makeUrlSafe should preserve @ symbols"() {
        expect:
        UrlSafeUtils.makeUrlSafe("user@example.com/file.pdf").contains("@")
    }

    def "makeUrlSafe should preserve dots"() {
        expect:
        UrlSafeUtils.makeUrlSafe("file.name.pdf").contains(".")
    }

    def "makeUrlSafe should replace spaces with underscores"() {
        expect:
        // Spaces are replaced with underscores before trim() is called
        UrlSafeUtils.makeUrlSafe("  file.pdf  ") == "__file.pdf__"
    }

    def "makeUrlSafe should return null for null input"() {
        expect:
        UrlSafeUtils.makeUrlSafe(null) == null
    }

    def "makeUrlSafe should return blank input as-is"() {
        expect:
        // isBlank() returns true, so input is returned as-is (null/blank guard)
        UrlSafeUtils.makeUrlSafe("   ") == "   "
    }

    def "makeUrlSafe should handle empty string"() {
        expect:
        UrlSafeUtils.makeUrlSafe("") == ""
    }

    def "makeUrlSafe should handle strings with multiple problematic characters"() {
        given:
        def input = 'file name-with*special?chars<and>more|stuff.pdf'

        when:
        def result = UrlSafeUtils.makeUrlSafe(input)

        then:
        result == "file_name_with_special_chars_and_more_stuff.pdf"
    }

    def "makeUrlSafe should handle email-like paths"() {
        given:
        def input = "recipient@example.com/sender@example.com/file name.pdf"

        when:
        def result = UrlSafeUtils.makeUrlSafe(input)

        then:
        result.contains("@")
        result.contains("/")
        !result.contains(" ")
    }

    def "makeUrlSafe should handle consecutive special characters"() {
        expect:
        UrlSafeUtils.makeUrlSafe("file***name.pdf") == "file___name.pdf"
        UrlSafeUtils.makeUrlSafe("file   name.pdf") == "file___name.pdf"
        UrlSafeUtils.makeUrlSafe("file---name.pdf") == "file___name.pdf"
    }

    def "makeUrlSafe should preserve alphanumeric characters"() {
        expect:
        UrlSafeUtils.makeUrlSafe("file123ABC.pdf") == "file123ABC.pdf"
    }

    def "makeUrlSafe should handle Unicode characters in filenames"() {
        given:
        def input = "文档-résumé.pdf"

        when:
        def result = UrlSafeUtils.makeUrlSafe(input)

        then:
        result.contains("文档")
        result.contains("résumé")
        !result.contains("-")
    }

    def "makeUrlSafe should handle S3 signature compatibility issues"() {
        given:
        def input = "file-with-regular-hyphen.pdf"
        def inputWithUnicode = "file\u2013with\u2013en\u2013dash.pdf"

        when:
        def result1 = UrlSafeUtils.makeUrlSafe(input)
        def result2 = UrlSafeUtils.makeUrlSafe(inputWithUnicode)

        then:
        result1 == "file_with_regular_hyphen.pdf"
        result2 == "file_with_en_dash.pdf"
    }

    def "makeUrlSafe should handle complex real-world scenarios"() {
        expect:
        UrlSafeUtils.makeUrlSafe(input) == expected

        where:
        input                                           | expected
        "Invoice #123.pdf"                             | "Invoice_#123.pdf"
        "Document (final).pdf"                         | "Document_(final).pdf"
        "2024-01-15 Report.pdf"                        | "2024_01_15_Report.pdf"
        "user@company.com/sender@domain.com/file.pdf"  | "user@company.com/sender@domain.com/file.pdf"
    }

    def "makeUrlSafe should be idempotent for already safe strings"() {
        given:
        def safeInput = "already_safe_file.pdf"

        when:
        def result1 = UrlSafeUtils.makeUrlSafe(safeInput)
        def result2 = UrlSafeUtils.makeUrlSafe(result1)

        then:
        result1 == result2
    }
}

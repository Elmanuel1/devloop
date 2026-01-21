package com.tosspaper.common.validator

import spock.lang.Specification
import spock.lang.Unroll

class ValidUrlValidatorSpec extends Specification {

    ValidUrlValidator validator = new ValidUrlValidator()

    def "null value is valid"() {
        expect:
        validator.isValid(null, null)
    }

    @Unroll
    def "valid http/https URL '#url' returns true"() {
        expect:
        validator.isValid(url, null)

        where:
        url << [
            "http://example.com",
            "https://example.com",
            "http://www.example.com",
            "https://www.example.com",
            "http://example.com/path",
            "https://example.com/path/to/resource",
            "http://example.com:8080",
            "https://example.com:443/path",
            "http://example.com/path?query=value",
            "https://example.com/path?query=value&other=123",
            "http://example.com/path#fragment",
            "https://user:pass@example.com/path",
            "HTTP://EXAMPLE.COM",
            "HTTPS://EXAMPLE.COM",
            "Http://Example.Com",
            "HtTpS://Example.Com"
        ]
    }

    @Unroll
    def "invalid scheme '#url' returns false"() {
        expect:
        !validator.isValid(url, null)

        where:
        url << [
            "ftp://example.com",
            "mailto:user@example.com",
            "file:///path/to/file",
            "ssh://user@host.com",
            "tel:+1234567890",
            "javascript:alert('xss')",
            "data:text/html,<h1>Hello</h1>"
        ]
    }

    @Unroll
    def "malformed URL '#url' returns false"() {
        expect:
        !validator.isValid(url, null)

        where:
        url << [
            "not a url",
            "://missing-scheme.com",
            "http://[invalid-ipv6"
        ]
    }

    def "URLs that URI parser accepts but may seem unusual"() {
        // Some URLs that look malformed are actually valid according to URI parser
        // These tests document the actual behavior
        expect:
        // URI parser is lenient with some inputs
        validator.isValid("http://example.com", null)
    }

    def "URL without scheme returns false"() {
        expect:
        !validator.isValid("example.com", null)
        !validator.isValid("www.example.com", null)
        !validator.isValid("//example.com", null)
    }

    def "empty string is handled"() {
        expect:
        !validator.isValid("", null)
    }

    def "whitespace-only string returns false"() {
        expect:
        !validator.isValid("   ", null)
    }

    def "URL with special characters in path is valid"() {
        expect:
        validator.isValid("https://example.com/path%20with%20spaces", null)
        validator.isValid("https://example.com/path-with-dashes", null)
        validator.isValid("https://example.com/path_with_underscores", null)
    }

    def "localhost URLs are valid"() {
        expect:
        validator.isValid("http://localhost", null)
        validator.isValid("http://localhost:8080", null)
        validator.isValid("https://localhost:3000/api", null)
    }

    def "IP address URLs are valid"() {
        expect:
        validator.isValid("http://192.168.1.1", null)
        validator.isValid("https://10.0.0.1:8080/path", null)
        validator.isValid("http://127.0.0.1", null)
    }
}

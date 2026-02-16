package com.tosspaper.models.validation

import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.properties.FileProperties
import spock.lang.Specification
import spock.lang.Subject

/**
 * Tests for FileSizeValidation.
 * Verifies file size validation against configured limits.
 */
class FileSizeValidationSpec extends Specification {

    FileProperties fileProperties

    @Subject
    FileSizeValidation validation

    def setup() {
        fileProperties = new FileProperties()
        fileProperties.minFileSizeBytes = 5 * 1024L // 5KB
        fileProperties.maxFileSizeBytes = 3 * 1024 * 1024L // 3MB
        validation = new FileSizeValidation(fileProperties)
    }

    def "validate should pass for file within size limits"() {
        given:
        def content = new byte[100 * 1024] // 100KB
        def fileObject = FileObject.builder()
            .fileName("valid.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should pass for file at minimum size"() {
        given:
        def content = new byte[5 * 1024] // Exactly 5KB
        def fileObject = FileObject.builder()
            .fileName("minimum.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should pass for file at maximum size"() {
        given:
        def content = new byte[3 * 1024 * 1024] // Exactly 3MB
        def fileObject = FileObject.builder()
            .fileName("maximum.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should reject file below minimum size"() {
        given:
        def content = new byte[1024] // 1KB, below 5KB minimum
        def fileObject = FileObject.builder()
            .fileName("tiny.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("below minimum")
        result.violations[0].contains("1024")
        result.violations[0].contains("5120")
    }

    def "validate should reject file above maximum size"() {
        given:
        def content = new byte[4 * 1024 * 1024] // 4MB, above 3MB maximum
        def fileObject = FileObject.builder()
            .fileName("huge.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("exceeds maximum")
        result.violations[0].contains("4194304")
        result.violations[0].contains("3145728")
    }

    def "validate should reject empty files"() {
        given:
        def content = new byte[0]
        def fileObject = FileObject.builder()
            .fileName("empty.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("below minimum")
    }

    def "validate should reject very small files likely to be signature icons"() {
        given:
        def content = new byte[512] // 512 bytes
        def fileObject = FileObject.builder()
            .fileName("icon.png")
            .contentType("image/png")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("signature icon")
    }

    def "validate should handle various file sizes"() {
        given:
        def content = new byte[size]
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid() == expected

        where:
        size                  | expected
        6 * 1024              | true  // 6KB - valid
        50 * 1024             | true  // 50KB - valid
        500 * 1024            | true  // 500KB - valid
        1 * 1024 * 1024       | true  // 1MB - valid
        2 * 1024 * 1024       | true  // 2MB - valid
        3 * 1024 * 1024 - 1   | true  // Just under 3MB - valid
        4 * 1024              | false // 4KB - too small
        3 * 1024 * 1024 + 1   | false // Just over 3MB - too large
    }

    def "validate should respect custom size limits"() {
        given:
        fileProperties.minFileSizeBytes = 10 * 1024L // 10KB
        fileProperties.maxFileSizeBytes = 5 * 1024 * 1024L // 5MB
        validation = new FileSizeValidation(fileProperties)

        def content = new byte[8 * 1024] // 8KB
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid() // Below new 10KB minimum
    }

    def "validate should work with very small minimum size"() {
        given:
        fileProperties.minFileSizeBytes = 1L // 1 byte
        validation = new FileSizeValidation(fileProperties)

        def content = new byte[10]
        def fileObject = FileObject.builder()
            .fileName("tiny.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should work with very large maximum size"() {
        given:
        fileProperties.maxFileSizeBytes = 100 * 1024 * 1024L // 100MB
        validation = new FileSizeValidation(fileProperties)

        def content = new byte[10 * 1024 * 1024] // 10MB
        def fileObject = FileObject.builder()
            .fileName("large.pdf")
            .contentType("application/pdf")
            .content(content)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }
}

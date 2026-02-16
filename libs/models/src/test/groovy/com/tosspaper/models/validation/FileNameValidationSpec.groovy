package com.tosspaper.models.validation

import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.properties.FileProperties
import spock.lang.Specification
import spock.lang.Subject

/**
 * Tests for FileNameValidation.
 * Verifies file name length validation.
 */
class FileNameValidationSpec extends Specification {

    FileProperties fileProperties

    @Subject
    FileNameValidation validation

    def setup() {
        fileProperties = new FileProperties()
        fileProperties.maxFilenameLength = 255
        validation = new FileNameValidation(fileProperties)
    }

    def "validate should pass for valid filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("valid-document.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should pass for filename at maximum length"() {
        given:
        def fileName = "a" * 251 + ".pdf" // Exactly 255 characters
        def fileObject = FileObject.builder()
            .fileName(fileName)
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should reject filename exceeding maximum length"() {
        given:
        def fileName = "a" * 252 + ".pdf" // 256 characters
        def fileObject = FileObject.builder()
            .fileName(fileName)
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("exceeds maximum")
        result.violations[0].contains("256")
        result.violations[0].contains("255")
    }

    def "validate should reject null filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName(null)
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("required")
    }

    def "validate should reject blank filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("   ")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("required")
    }

    def "validate should reject empty filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("required")
    }

    def "validate should pass for short filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("a.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should pass for filename with special characters"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("my-doc_v2.1[final].pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should pass for filename with spaces"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("My Document Name.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should pass for filename with Unicode characters"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("文档-résumé-файл.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should respect custom maximum length"() {
        given:
        fileProperties.maxFilenameLength = 50
        validation = new FileNameValidation(fileProperties)

        def fileName = "a" * 47 + ".pdf" // 51 characters
        def fileObject = FileObject.builder()
            .fileName(fileName)
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("51")
        result.violations[0].contains("50")
    }

    def "validate should handle very long filenames"() {
        given:
        def fileName = "a" * 500 + ".pdf"
        def fileObject = FileObject.builder()
            .fileName(fileName)
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
    }

    def "validate should handle filename with multiple extensions"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("archive.tar.gz")
            .contentType("application/gzip")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }
}

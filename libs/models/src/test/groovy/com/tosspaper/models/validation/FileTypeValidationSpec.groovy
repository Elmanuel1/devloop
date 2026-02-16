package com.tosspaper.models.validation

import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.properties.FileProperties
import spock.lang.Specification
import spock.lang.Subject

/**
 * Tests for FileTypeValidation.
 * Verifies content type validation against allowed types.
 */
class FileTypeValidationSpec extends Specification {

    FileProperties fileProperties

    @Subject
    FileTypeValidation validation

    def setup() {
        fileProperties = new FileProperties()
        fileProperties.allowedContentTypes = [
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
        ] as Set
        validation = new FileTypeValidation(fileProperties)
    }

    def "validate should pass for allowed content types"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .contentType(contentType)
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()

        where:
        contentType << [
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
        ]
    }

    def "validate should be case-insensitive for content types"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .contentType("APPLICATION/PDF")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should handle mixed case content types"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.png")
            .contentType("Image/PNG")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should reject disallowed content types"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.exe")
            .contentType("application/octet-stream")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("application/octet-stream")
        result.violations[0].contains("not allowed")
    }

    def "validate should reject null content type"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .contentType(null)
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("required")
    }

    def "validate should reject blank content type"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .contentType("   ")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("required")
    }

    def "validate should reject empty content type"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .contentType("")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("required")
    }

    def "validate should list allowed types in error message"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.doc")
            .contentType("application/msword")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("Allowed types:")
        result.violations[0].contains("application/pdf")
        result.violations[0].contains("image/jpeg")
    }

    def "validate should reject various disallowed types"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.${extension}")
            .contentType(contentType)
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()

        where:
        extension | contentType
        "exe"     | "application/x-msdownload"
        "zip"     | "application/zip"
        "doc"     | "application/msword"
        "xls"     | "application/vnd.ms-excel"
        "html"    | "text/html"
        "js"      | "application/javascript"
        "txt"     | "text/plain"
    }

    def "validate should respect custom allowed content types"() {
        given:
        fileProperties.allowedContentTypes = ["text/plain", "text/csv"] as Set
        validation = new FileTypeValidation(fileProperties)

        def fileObject = FileObject.builder()
            .fileName("data.csv")
            .contentType("text/csv")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should reject previously allowed types after reconfiguration"() {
        given:
        fileProperties.allowedContentTypes = ["text/plain"] as Set
        validation = new FileTypeValidation(fileProperties)

        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
    }

    def "validate should handle content types with parameters"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .contentType("application/pdf; charset=utf-8")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid() // Parameters are not stripped, exact match required
    }

    def "validate should handle content types with whitespace"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .contentType(" application/pdf ")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid() // Whitespace is not stripped
    }
}

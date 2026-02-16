package com.tosspaper.models.validation

import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.properties.FileProperties
import spock.lang.Specification
import spock.lang.Subject

/**
 * Tests for FileExtensionValidation.
 * Verifies file extension validation against allowed extensions.
 */
class FileExtensionValidationSpec extends Specification {

    FileProperties fileProperties

    @Subject
    FileExtensionValidation validation

    def setup() {
        fileProperties = new FileProperties()
        fileProperties.allowedFileExtensions = ["pdf", "jpg", "jpeg", "png", "webp"] as Set
        validation = new FileExtensionValidation(fileProperties)
    }

    def "validate should pass for allowed extensions"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("document.${extension}")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()

        where:
        extension << ["pdf", "jpg", "jpeg", "png", "webp"]
    }

    def "validate should pass for allowed extensions with uppercase"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("document.PDF")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid() // Extension is normalized to lowercase
    }

    def "validate should pass for allowed extensions with mixed case"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("document.JpEg")
            .contentType("image/jpeg")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should reject disallowed extensions"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("malware.exe")
            .contentType("application/octet-stream")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("exe")
        result.violations[0].contains("not allowed")
        result.violations[0].contains("pdf, jpg, jpeg, png, webp")
    }

    def "validate should reject files without extension"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("noextension")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("must have an extension")
    }

    def "validate should handle filenames with multiple dots"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("my.document.name.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid() // Uses last extension
    }

    def "validate should handle null filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName(null)
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid() // Delegates to FileNameValidation
    }

    def "validate should handle blank filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("   ")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid() // Delegates to FileNameValidation
    }

    def "validate should handle empty allowed extensions set"() {
        given:
        fileProperties.allowedFileExtensions = [] as Set
        validation = new FileExtensionValidation(fileProperties)

        def fileObject = FileObject.builder()
            .fileName("anything.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid() // Empty set means no restriction
    }

    def "validate should handle filename ending with dot"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("document.")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid() // Empty extension after dot
    }

    def "validate should handle various disallowed extensions"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file.${extension}")
            .contentType("application/octet-stream")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains(extension)

        where:
        extension << ["exe", "dll", "bat", "sh", "zip", "tar", "doc", "docx", "xls", "xlsx"]
    }

    def "validate should respect custom allowed extensions"() {
        given:
        fileProperties.allowedFileExtensions = ["txt", "csv", "json"] as Set
        validation = new FileExtensionValidation(fileProperties)

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

    def "validate should reject previously allowed extensions after reconfiguration"() {
        given:
        fileProperties.allowedFileExtensions = ["txt"] as Set
        validation = new FileExtensionValidation(fileProperties)

        def fileObject = FileObject.builder()
            .fileName("document.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isInvalid()
        result.violations[0].contains("pdf")
        result.violations[0].contains("txt")
    }

    def "validate should handle single-character extensions"() {
        given:
        fileProperties.allowedFileExtensions = ["c", "h"] as Set
        validation = new FileExtensionValidation(fileProperties)

        def fileObject = FileObject.builder()
            .fileName("program.c")
            .contentType("text/plain")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should handle long extensions"() {
        given:
        fileProperties.allowedFileExtensions = ["longextension"] as Set
        validation = new FileExtensionValidation(fileProperties)

        def fileObject = FileObject.builder()
            .fileName("file.longextension")
            .contentType("application/octet-stream")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should handle special characters in filename before extension"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("my-doc_v2.1[final].pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validation.validate(fileObject)

        then:
        result.isValid() // Only extension is validated
    }
}

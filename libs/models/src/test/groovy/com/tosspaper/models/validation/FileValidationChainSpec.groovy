package com.tosspaper.models.validation

import com.tosspaper.models.domain.FileObject
import spock.lang.Specification
import spock.lang.Subject

/**
 * Tests for FileValidationChain.
 * Verifies chain of responsibility pattern for file validations.
 */
class FileValidationChainSpec extends Specification {

    @Subject
    FileValidationChain validationChain

    def "validate should pass when all validations pass"() {
        given:
        def validation1 = Mock(FileValidation)
        def validation2 = Mock(FileValidation)
        def validation3 = Mock(FileValidation)
        validationChain = new FileValidationChain([validation1, validation2, validation3])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        validation1.validate(fileObject) >> ValidationResult.valid()
        validation2.validate(fileObject) >> ValidationResult.valid()
        validation3.validate(fileObject) >> ValidationResult.valid()

        when:
        def result = validationChain.validate(fileObject)

        then:
        result.isValid()
        result.violations.isEmpty()
    }

    def "validate should collect violations from all validations"() {
        given:
        def validation1 = Mock(FileValidation)
        def validation2 = Mock(FileValidation)
        def validation3 = Mock(FileValidation)
        validationChain = new FileValidationChain([validation1, validation2, validation3])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        validation1.validate(fileObject) >> ValidationResult.invalid("Error 1")
        validation2.validate(fileObject) >> ValidationResult.valid()
        validation3.validate(fileObject) >> ValidationResult.invalid("Error 3")

        when:
        def result = validationChain.validate(fileObject)

        then:
        result.isInvalid()
        result.violations.size() == 2
        result.violations.contains("Error 1")
        result.violations.contains("Error 3")
    }

    def "validate should execute all validations even if some fail"() {
        given:
        def validation1 = Mock(FileValidation)
        def validation2 = Mock(FileValidation)
        def validation3 = Mock(FileValidation)
        validationChain = new FileValidationChain([validation1, validation2, validation3])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validationChain.validate(fileObject)

        then:
        // Combine interaction verification with stub responses
        1 * validation1.validate(fileObject) >> ValidationResult.invalid("Error 1")
        1 * validation2.validate(fileObject) >> ValidationResult.invalid("Error 2")
        1 * validation3.validate(fileObject) >> ValidationResult.invalid("Error 3")
        result.violations.size() == 3
    }

    def "validate should handle empty validation list"() {
        given:
        validationChain = new FileValidationChain([])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        when:
        def result = validationChain.validate(fileObject)

        then:
        result.isValid()
    }

    def "validate should handle single validation"() {
        given:
        def validation = Mock(FileValidation)
        validationChain = new FileValidationChain([validation])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        validation.validate(fileObject) >> ValidationResult.invalid("Single error")

        when:
        def result = validationChain.validate(fileObject)

        then:
        result.isInvalid()
        result.violations == ["Single error"]
    }

    def "validate should preserve order of violations"() {
        given:
        def validation1 = Mock(FileValidation)
        def validation2 = Mock(FileValidation)
        def validation3 = Mock(FileValidation)
        validationChain = new FileValidationChain([validation1, validation2, validation3])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        validation1.validate(fileObject) >> ValidationResult.invalid("First")
        validation2.validate(fileObject) >> ValidationResult.invalid("Second")
        validation3.validate(fileObject) >> ValidationResult.invalid("Third")

        when:
        def result = validationChain.validate(fileObject)

        then:
        result.violations == ["First", "Second", "Third"]
    }

    def "validate should handle validations with multiple violations"() {
        given:
        def validation1 = Mock(FileValidation)
        def validation2 = Mock(FileValidation)
        validationChain = new FileValidationChain([validation1, validation2])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        validation1.validate(fileObject) >> ValidationResult.invalid(["Error 1A", "Error 1B"])
        validation2.validate(fileObject) >> ValidationResult.invalid(["Error 2A", "Error 2B"])

        when:
        def result = validationChain.validate(fileObject)

        then:
        result.violations.size() == 4
        result.violations.containsAll(["Error 1A", "Error 1B", "Error 2A", "Error 2B"])
    }

    def "validate should handle mix of valid and invalid results"() {
        given:
        def validation1 = Mock(FileValidation)
        def validation2 = Mock(FileValidation)
        def validation3 = Mock(FileValidation)
        def validation4 = Mock(FileValidation)
        validationChain = new FileValidationChain([validation1, validation2, validation3, validation4])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        validation1.validate(fileObject) >> ValidationResult.valid()
        validation2.validate(fileObject) >> ValidationResult.invalid("Error 2")
        validation3.validate(fileObject) >> ValidationResult.valid()
        validation4.validate(fileObject) >> ValidationResult.invalid("Error 4")

        when:
        def result = validationChain.validate(fileObject)

        then:
        result.isInvalid()
        result.violations == ["Error 2", "Error 4"]
    }

    def "validate should call validations in order"() {
        given:
        def validation1 = Mock(FileValidation)
        def validation2 = Mock(FileValidation)
        def validation3 = Mock(FileValidation)
        validationChain = new FileValidationChain([validation1, validation2, validation3])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        def callOrder = []
        validation1.validate(fileObject) >> {
            callOrder << "v1"
            ValidationResult.valid()
        }
        validation2.validate(fileObject) >> {
            callOrder << "v2"
            ValidationResult.valid()
        }
        validation3.validate(fileObject) >> {
            callOrder << "v3"
            ValidationResult.valid()
        }

        when:
        validationChain.validate(fileObject)

        then:
        callOrder == ["v1", "v2", "v3"]
    }

    def "validate should handle validation throwing exception"() {
        given:
        def validation1 = Mock(FileValidation)
        def validation2 = Mock(FileValidation)
        validationChain = new FileValidationChain([validation1, validation2])

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .build()

        validation1.validate(fileObject) >> { throw new RuntimeException("Validation error") }

        when:
        validationChain.validate(fileObject)

        then:
        thrown(RuntimeException)
    }
}

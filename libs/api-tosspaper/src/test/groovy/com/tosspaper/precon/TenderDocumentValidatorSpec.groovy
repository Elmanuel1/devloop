package com.tosspaper.precon

import com.tosspaper.common.BadRequestException
import com.tosspaper.generated.model.PresignedUrlRequest
import com.tosspaper.generated.model.TenderContentType
import spock.lang.Specification

class TenderDocumentValidatorSpec extends Specification {

    TenderFileProperties fileProperties
    TenderDocumentValidator validator

    def setup() {
        fileProperties = new TenderFileProperties()
        validator = new TenderDocumentValidator(fileProperties)
    }

    def "should accept valid PDF request"() {
        given:
            def request = new PresignedUrlRequest("doc.pdf", TenderContentType.APPLICATION_PDF, 1024L)

        when:
            validator.validate(request)

        then:
            noExceptionThrown()
    }

    def "should accept valid PNG request"() {
        given:
            def request = new PresignedUrlRequest("img.png", TenderContentType.IMAGE_PNG, 1024L)

        when:
            validator.validate(request)

        then:
            noExceptionThrown()
    }

    def "should accept valid JPEG request"() {
        given:
            def request = new PresignedUrlRequest("img.jpg", TenderContentType.IMAGE_JPEG, 1024L)

        when:
            validator.validate(request)

        then:
            noExceptionThrown()
    }

    def "should reject unsupported content type"() {
        given:
            def request = new PresignedUrlRequest()
            request.setFileName("doc.zip")
            request.setContentType(null) // unsupported
            request.setFileSize(1024L)

        when:
            validator.validate(request)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "api.validation.invalidContentType"
    }

    def "should reject file_size exceeding max"() {
        given:
            def request = new PresignedUrlRequest("doc.pdf", TenderContentType.APPLICATION_PDF, 209715201L)

        when:
            validator.validate(request)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "api.validation.fileTooLarge"
    }

    def "should reject blank file_name"() {
        given:
            def request = new PresignedUrlRequest(" ", TenderContentType.APPLICATION_PDF, 1024L)

        when:
            validator.validate(request)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "api.validation.fileNameRequired"
    }

    def "should reject double extension"() {
        given:
            def request = new PresignedUrlRequest("file.pdf.exe", TenderContentType.APPLICATION_PDF, 1024L)

        when:
            validator.validate(request)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "api.validation.invalidFileName"
    }

    def "should reject extension mismatch"() {
        given:
            def request = new PresignedUrlRequest("doc.png", TenderContentType.APPLICATION_PDF, 1024L)

        when:
            validator.validate(request)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "api.validation.fileNameMismatch"
    }

    def "should accept .jpeg extension for image/jpeg"() {
        given:
            def request = new PresignedUrlRequest("img.jpeg", TenderContentType.IMAGE_JPEG, 1024L)

        when:
            validator.validate(request)

        then:
            noExceptionThrown()
    }
}

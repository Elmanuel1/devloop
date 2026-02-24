package com.tosspaper.common.validator

import com.tosspaper.models.properties.FileProperties
import com.tosspaper.precon.generated.model.ContentType
import com.tosspaper.precon.generated.model.PresignedUrlRequest
import jakarta.validation.ConstraintValidatorContext
import spock.lang.Specification
import spock.lang.Unroll

class ValidPresignedUploadValidatorSpec extends Specification {

    FileProperties fileProperties
    ConstraintValidatorContext context
    ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder
    ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext nodeBuilder
    ValidPresignedUploadValidator validator

    def setup() {
        fileProperties = new FileProperties()
        // Default allows: application/pdf, image/jpeg, image/png, image/gif, image/webp
        // Default extensions: pdf, jpg, jpeg, png, webp

        context = Mock(ConstraintValidatorContext)
        violationBuilder = Mock(ConstraintValidatorContext.ConstraintViolationBuilder)
        nodeBuilder = Mock(ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext)

        context.buildConstraintViolationWithTemplate(_) >> violationBuilder
        violationBuilder.addPropertyNode(_) >> nodeBuilder
        nodeBuilder.addConstraintViolation() >> context

        validator = new ValidPresignedUploadValidator(fileProperties)
    }

    // ==================== null request ====================

    def "should return true when request is null"() {
        when: "validating a null request"
            def result = validator.isValid(null, context)

        then: "result is valid and context is never touched"
            result == true
            0 * context.disableDefaultConstraintViolation()
    }

    // ==================== fully valid requests ====================

    @Unroll
    def "should return true for allowed content type '#contentType' with valid extension '#fileName'"() {
        given: "a request with an allowed content type and matching valid extension"
            def request = new PresignedUrlRequest(fileName, contentType, 102400)

        when: "validating the request"
            def result = validator.isValid(request, context)

        then: "result is valid and no violations are added"
            result == true
            0 * violationBuilder.addPropertyNode(_)

        where:
        contentType              | fileName
        ContentType.APPLICATION_PDF | "contract.pdf"
        ContentType.IMAGE_JPEG   | "photo.jpeg"
        ContentType.IMAGE_JPEG   | "photo.jpg"
        ContentType.IMAGE_PNG    | "logo.PNG"       // extension check is case-insensitive
        ContentType.IMAGE_PNG    | "diagram.png"
    }

    // ==================== disallowed content type ====================

    def "should return false and add contentType violation when content type is not in allowed list"() {
        given: "a request with application/zip which is not in the allowed content types"
            def request = new PresignedUrlRequest("archive.pdf", ContentType.APPLICATION_ZIP, 65536)

        when: "validating the request"
            def result = validator.isValid(request, context)

        then: "the default constraint violation is disabled first"
            1 * context.disableDefaultConstraintViolation()

        and: "a violation message mentioning the disallowed type is built"
            1 * context.buildConstraintViolationWithTemplate({ it.contains("application/zip") && it.contains("not allowed") }) >> violationBuilder

        and: "the violation is attached to the contentType property node"
            1 * violationBuilder.addPropertyNode("contentType") >> nodeBuilder
            1 * nodeBuilder.addConstraintViolation() >> context

        and: "result is invalid"
            result == false
    }

    // ==================== null contentType skips content type check ====================

    def "should skip content type check when contentType is null"() {
        given: "a request with null content type but a valid file name"
            def request = new PresignedUrlRequest()
            request.fileName = "document.pdf"
            request.contentType = null
            request.fileSize = 1024

        when: "validating the request"
            def result = validator.isValid(request, context)

        then: "no contentType violation is added"
            0 * context.buildConstraintViolationWithTemplate({ it.contains("Content type") })

        and: "result is valid (only the extension is checked)"
            result == true
    }

    // ==================== missing extension (no dot) ====================

    def "should return false and add fileName violation when file name has no dot"() {
        given: "a request with a file name containing no extension separator"
            def request = new PresignedUrlRequest("nodotfilename", ContentType.APPLICATION_PDF, 2048)

        when: "validating the request"
            def result = validator.isValid(request, context)

        then: "the default constraint violation is disabled"
            1 * context.disableDefaultConstraintViolation()

        and: "a violation about a required valid extension is built"
            1 * context.buildConstraintViolationWithTemplate({ it.contains("valid extension") }) >> violationBuilder

        and: "the violation is attached to the fileName property node"
            1 * violationBuilder.addPropertyNode("fileName") >> nodeBuilder
            1 * nodeBuilder.addConstraintViolation() >> context

        and: "result is invalid"
            result == false
    }

    // ==================== trailing dot ====================

    def "should return false and add fileName violation when file name ends with a dot"() {
        given: "a request where the file name ends with a trailing dot"
            def request = new PresignedUrlRequest("file.", ContentType.APPLICATION_PDF, 2048)

        when: "validating the request"
            def result = validator.isValid(request, context)

        then: "the default constraint violation is disabled"
            1 * context.disableDefaultConstraintViolation()

        and: "a violation about a required valid extension is built"
            1 * context.buildConstraintViolationWithTemplate({ it.contains("valid extension") }) >> violationBuilder
            1 * violationBuilder.addPropertyNode("fileName") >> nodeBuilder
            1 * nodeBuilder.addConstraintViolation() >> context

        and: "result is invalid"
            result == false
    }

    // ==================== disallowed extension ====================

    @Unroll
    def "should return false and add fileName violation when extension '#ext' is not in the allowed list"() {
        given: "a request with a file name that has a disallowed extension"
            def request = new PresignedUrlRequest("file.${ext}", ContentType.APPLICATION_PDF, 2048)

        when: "validating the request"
            def result = validator.isValid(request, context)

        then: "the default constraint violation is disabled"
            1 * context.disableDefaultConstraintViolation()

        and: "a violation message naming the disallowed extension is built"
            1 * context.buildConstraintViolationWithTemplate({ it.contains(".${ext}") && it.contains("not allowed") }) >> violationBuilder
            1 * violationBuilder.addPropertyNode("fileName") >> nodeBuilder
            1 * nodeBuilder.addConstraintViolation() >> context

        and: "result is invalid"
            result == false

        where:
        ext << ["exe", "docx", "zip", "sh", "bat", "gif"]
    }

    // ==================== null fileName skips extension check ====================

    def "should skip extension check when fileName is null"() {
        given: "a request with a null file name but allowed content type"
            def request = new PresignedUrlRequest()
            request.fileName = null
            request.contentType = ContentType.APPLICATION_PDF
            request.fileSize = 4096

        when: "validating the request"
            def result = validator.isValid(request, context)

        then: "no fileName violation is added"
            0 * context.buildConstraintViolationWithTemplate({ it.contains("extension") })

        and: "result is valid (content type check passes)"
            result == true
    }

    // ==================== multiple violations at once ====================

    def "should return false and add both violations when content type and extension are both invalid"() {
        given: "a request with a disallowed content type and a disallowed extension"
            def request = new PresignedUrlRequest("virus.exe", ContentType.APPLICATION_ZIP, 8192)

        when: "validating the request"
            def result = validator.isValid(request, context)

        then: "the default constraint violation is disabled once"
            1 * context.disableDefaultConstraintViolation()

        and: "a contentType violation is built mentioning the disallowed MIME type"
            1 * context.buildConstraintViolationWithTemplate({ it.contains("application/zip") && it.contains("not allowed") }) >> violationBuilder
            1 * violationBuilder.addPropertyNode("contentType") >> nodeBuilder
            1 * nodeBuilder.addConstraintViolation() >> context

        and: "a fileName violation is built mentioning the disallowed extension"
            1 * context.buildConstraintViolationWithTemplate({ it.contains(".exe") && it.contains("not allowed") }) >> violationBuilder
            1 * violationBuilder.addPropertyNode("fileName") >> nodeBuilder
            1 * nodeBuilder.addConstraintViolation() >> context

        and: "result is invalid"
            result == false
    }

    // ==================== custom FileProperties ====================

    def "should respect custom allowedContentTypes from FileProperties"() {
        given: "FileProperties that only allows application/zip"
            def customProps = new FileProperties()
            customProps.allowedContentTypes = Set.of("application/zip")
            customProps.allowedFileExtensions = Set.of("zip")
            def customValidator = new ValidPresignedUploadValidator(customProps)

        and: "a request with application/zip and .zip extension"
            def request = new PresignedUrlRequest("archive.zip", ContentType.APPLICATION_ZIP, 65536)

        when: "validating the request"
            def result = customValidator.isValid(request, context)

        then: "result is valid under the custom rules"
            result == true
            0 * violationBuilder.addPropertyNode(_)
    }

    def "should respect custom allowedFileExtensions from FileProperties"() {
        given: "FileProperties that does not include 'pdf' as an allowed extension"
            def customProps = new FileProperties()
            customProps.allowedFileExtensions = Set.of("jpg", "png")
            def customValidator = new ValidPresignedUploadValidator(customProps)

        and: "a request with a .pdf extension"
            def request = new PresignedUrlRequest("report.pdf", ContentType.APPLICATION_PDF, 1024)

        when: "validating the request"
            def result = customValidator.isValid(request, context)

        then: "result is invalid because pdf is no longer allowed"
            result == false
    }

    // ==================== extension comparison is case-insensitive ====================

    @Unroll
    def "should treat extension '#fileName' as case-insensitive and return true"() {
        given: "a request with a mixed-case extension"
            def request = new PresignedUrlRequest(fileName, ContentType.APPLICATION_PDF, 2048)

        when: "validating the request"
            def result = validator.isValid(request, context)

        then: "result is valid because extension matching is case-insensitive"
            result == true

        where:
        fileName << ["document.PDF", "document.Pdf", "document.pDf"]
    }
}

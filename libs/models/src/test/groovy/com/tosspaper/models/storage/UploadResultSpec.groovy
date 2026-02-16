package com.tosspaper.models.storage

import com.tosspaper.models.validation.ValidationResult
import software.amazon.awssdk.services.s3.model.S3Exception
import spock.lang.Specification

/**
 * Tests for UploadResult.
 * Verifies upload result creation and status checking.
 */
class UploadResultSpec extends Specification {

    def "success should create successful upload result"() {
        when:
        def result = UploadResult.success("key123", "checksum", 1024L, "application/pdf")

        then:
        result.isSuccessful()
        !result.isFailed()
        result.key() == "key123"
        result.checksum() == "checksum"
        result.actualSizeBytes() == 1024L
        result.contentType() == "application/pdf"
        result.error() == null
        result.uploadSuccessful()
    }

    def "success with metadata should include metadata"() {
        given:
        def metadata = ["bucket": "test-bucket", "region": "us-east-1"]

        when:
        def result = UploadResult.success("key", "check", 100L, "image/png", metadata)

        then:
        result.isSuccessful()
        result.metadata() == metadata
    }

    def "validationFailure should create failed result with violations"() {
        given:
        def validationResult = ValidationResult.invalid("File too large", "Invalid format")

        when:
        def result = UploadResult.validationFailure(validationResult)

        then:
        result.isFailed()
        !result.isSuccessful()
        result.isValidationFailure()
        !result.isArbitraryFailure()
        result.error() != null
        result.validationResult().isInvalid()
        result.getValidationViolations().size() == 2
    }

    def "failure with throwable should create failed result"() {
        given:
        def error = new RuntimeException("Upload failed")

        when:
        def result = UploadResult.failure(error)

        then:
        result.isFailed()
        !result.isSuccessful()
        !result.isValidationFailure()
        result.isArbitraryFailure()
        result.error() == error
        result.errorMessage == "Upload failed"
    }

    def "failure with message should create failed result"() {
        when:
        def result = UploadResult.failure("Connection timeout")

        then:
        result.isFailed()
        result.error() != null
        result.errorMessage.contains("Connection timeout")
    }

    def "isValidationFailure should detect validation failures"() {
        given:
        def validationResult = ValidationResult.invalid("Error")
        def result = UploadResult.validationFailure(validationResult)

        expect:
        result.isValidationFailure()
    }

    def "isArbitraryFailure should detect non-validation failures"() {
        given:
        def result = UploadResult.failure("Network error")

        expect:
        result.isArbitraryFailure()
    }

    def "isS3Failure should detect S3 exceptions"() {
        given:
        def s3Error = S3Exception.builder().message("Access denied").build()
        def result = UploadResult.failure(s3Error)

        expect:
        result.isS3Failure()
    }

    def "isS3Failure should return false for non-S3 exceptions"() {
        given:
        def result = UploadResult.failure(new RuntimeException("Generic error"))

        expect:
        !result.isS3Failure()
    }

    def "isNetworkFailure should detect connection exceptions"() {
        given:
        def networkError = new java.net.ConnectException("Connection refused")
        def result = UploadResult.failure(networkError)

        expect:
        result.isNetworkFailure()
    }

    def "isNetworkFailure should return false for non-network exceptions"() {
        given:
        def result = UploadResult.failure(new RuntimeException("Generic error"))

        expect:
        !result.isNetworkFailure()
    }

    def "getErrorMessage should return null for successful result"() {
        given:
        def result = UploadResult.success("key", "check", 100L, "application/pdf")

        expect:
        result.errorMessage == null
    }

    def "getValidationViolations should return empty list for successful result"() {
        given:
        def result = UploadResult.success("key", "check", 100L, "application/pdf")

        expect:
        result.validationViolations.isEmpty()
    }

    def "getValidationViolations should return violations for validation failure"() {
        given:
        def validationResult = ValidationResult.invalid("Error 1", "Error 2")
        def result = UploadResult.validationFailure(validationResult)

        expect:
        result.validationViolations == ["Error 1", "Error 2"]
    }

    def "fluent accessors should work correctly"() {
        when:
        def result = UploadResult.success("test-key", "abc123", 2048L, "image/jpeg")

        then:
        result.key() == "test-key"
        result.checksum() == "abc123"
        result.actualSizeBytes() == 2048L
        result.contentType() == "image/jpeg"
        result.uploadSuccessful()
    }

    def "successful result should have valid validation result"() {
        when:
        def result = UploadResult.success("key", "check", 100L, "application/pdf")

        then:
        result.validationResult.isValid()
    }

    def "failed result should have null key and checksum"() {
        when:
        def result = UploadResult.failure("Error")

        then:
        result.key() == null
        result.checksum() == null
        result.actualSizeBytes() == 0L
        result.contentType() == null
    }

    def "validation failure should contain validation result"() {
        given:
        def validationResult = ValidationResult.invalid("Too large")

        when:
        def result = UploadResult.validationFailure(validationResult)

        then:
        result.validationResult == validationResult
    }

    def "arbitrary failure should have valid validation result"() {
        when:
        def result = UploadResult.failure("Error")

        then:
        result.validationResult.isValid()
    }

    def "isSuccessful and isFailed should be opposites"() {
        given:
        def success = UploadResult.success("key", "check", 100L, "application/pdf")
        def failure = UploadResult.failure("Error")

        expect:
        success.isSuccessful() == !success.isFailed()
        failure.isSuccessful() == !failure.isFailed()
    }

    def "metadata should be null for basic success"() {
        when:
        def result = UploadResult.success("key", "check", 100L, "application/pdf")

        then:
        result.metadata() == null
    }

    def "error should be null for successful result"() {
        when:
        def result = UploadResult.success("key", "check", 100L, "application/pdf")

        then:
        result.error() == null
        result.getError() == null
    }

    def "should handle various content types"() {
        expect:
        def result = UploadResult.success("key", "check", 100L, contentType)
        result.contentType() == contentType

        where:
        contentType << [
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "text/plain"
        ]
    }

    def "should handle various file sizes"() {
        expect:
        def result = UploadResult.success("key", "check", size, "application/pdf")
        result.actualSizeBytes() == size

        where:
        size << [0L, 1L, 1024L, 1048576L, 10485760L]
    }

    def "should handle long keys"() {
        given:
        def longKey = "a" * 500

        when:
        def result = UploadResult.success(longKey, "check", 100L, "application/pdf")

        then:
        result.key() == longKey
    }

    def "should handle long checksums"() {
        given:
        def longChecksum = "a" * 64 // SHA-256

        when:
        def result = UploadResult.success("key", longChecksum, 100L, "application/pdf")

        then:
        result.checksum() == longChecksum
    }
}

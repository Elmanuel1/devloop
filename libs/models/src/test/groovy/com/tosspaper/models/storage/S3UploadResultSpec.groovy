package com.tosspaper.models.storage

import com.tosspaper.models.validation.ValidationResult
import spock.lang.Specification

/**
 * Tests for S3UploadResult.
 * Verifies S3-specific upload result with additional metadata.
 */
class S3UploadResultSpec extends Specification {

    def "success should create successful S3 upload result"() {
        given:
        def metadata = ["upload-timestamp": "2024-01-15T10:00:00Z"]

        when:
        def result = S3UploadResult.success(
            "key123", "checksum", 1024L, "application/pdf",
            metadata, "us-east-1", "test-bucket", "etag123", "version1", "https://s3.amazonaws.com"
        )

        then:
        result.isSuccessful()
        result.key() == "key123"
        result.checksum() == "checksum"
        result.actualSizeBytes() == 1024L
        result.contentType() == "application/pdf"
        result.metadata() == metadata
        result.region() == "us-east-1"
        result.bucket() == "test-bucket"
        result.etag() == "etag123"
        result.versionId() == "version1"
        result.endpoint() == "https://s3.amazonaws.com"
    }

    def "validationFailure should create failed S3 result"() {
        given:
        def validationResult = ValidationResult.invalid("File too large")

        when:
        def result = S3UploadResult.validationFailure(validationResult)

        then:
        result.isFailed()
        result.isValidationFailure()
        result.region() == null
        result.bucket() == null
        result.etag() == null
        result.versionId() == null
    }

    def "failure with throwable should create failed S3 result"() {
        given:
        def error = new RuntimeException("S3 upload failed")

        when:
        def result = S3UploadResult.failure(error)

        then:
        result.isFailed()
        result.error() == error
        result.region() == null
        result.bucket() == null
    }

    def "failure with message should create failed S3 result"() {
        when:
        def result = S3UploadResult.failure("Connection timeout")

        then:
        result.isFailed()
        result.errorMessage.contains("Connection timeout")
    }

    def "S3UploadResult should extend UploadResult"() {
        when:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            null, "us-west-2", "bucket", "etag", "v1", "endpoint"
        )

        then:
        result instanceof UploadResult
        result instanceof S3UploadResult
    }

    def "should have S3-specific fields"() {
        when:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            null, "eu-west-1", "my-bucket", "abc123", "v2", "https://s3.eu-west-1.amazonaws.com"
        )

        then:
        result.region() == "eu-west-1"
        result.bucket() == "my-bucket"
        result.etag() == "abc123"
        result.versionId() == "v2"
        result.endpoint() == "https://s3.eu-west-1.amazonaws.com"
    }

    def "should handle null S3-specific fields"() {
        when:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            null, null, null, null, null, null
        )

        then:
        result.isSuccessful()
        result.region() == null
        result.bucket() == null
        result.etag() == null
        result.versionId() == null
        result.endpoint() == null
    }

    def "should handle various AWS regions"() {
        expect:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            null, region, "bucket", "etag", "v1", "endpoint"
        )
        result.region() == region

        where:
        region << [
            "us-east-1",
            "us-west-2",
            "eu-west-1",
            "ap-southeast-1",
            "sa-east-1"
        ]
    }

    def "should handle S3 versioning"() {
        when:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            null, "us-east-1", "bucket", "etag", versionId, "endpoint"
        )

        then:
        result.versionId() == versionId

        where:
        versionId << [
            "version-1",
            "abc123def456",
            null
        ]
    }

    def "should handle various ETags"() {
        expect:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            null, "us-east-1", "bucket", etag, "v1", "endpoint"
        )
        result.etag() == etag

        where:
        etag << [
            "abc123",
            "\"abc123\"",
            "abc123-5",
            null
        ]
    }

    def "should include upload metadata"() {
        given:
        def metadata = [
            "bucket": "test-bucket",
            "upload-timestamp": "2024-01-15T10:00:00Z",
            "uploader": "service-1"
        ]

        when:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            metadata, "us-east-1", "bucket", "etag", "v1", "endpoint"
        )

        then:
        result.metadata() == metadata
        result.metadata()["bucket"] == "test-bucket"
    }

    def "fluent accessors should work for S3 fields"() {
        when:
        def result = S3UploadResult.success(
            "test-key", "abc123", 2048L, "image/jpeg",
            null, "ap-northeast-1", "my-bucket", "etag456", "v3", "https://s3.amazonaws.com"
        )

        then:
        result.region() == "ap-northeast-1"
        result.bucket() == "my-bucket"
        result.etag() == "etag456"
        result.versionId() == "v3"
        result.endpoint() == "https://s3.amazonaws.com"
    }

    def "should inherit all UploadResult methods"() {
        given:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            null, "us-east-1", "bucket", "etag", "v1", "endpoint"
        )

        expect:
        result.isSuccessful()
        !result.isFailed()
        !result.isValidationFailure()
        !result.isArbitraryFailure()
        result.validationResult.isValid()
    }

    def "failed S3 result should have null S3 fields"() {
        when:
        def result = S3UploadResult.failure("Upload failed")

        then:
        result.region() == null
        result.bucket() == null
        result.etag() == null
        result.versionId() == null
        result.endpoint() == null
        result.key() == null
    }

    def "should handle custom endpoints"() {
        expect:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            null, "us-east-1", "bucket", "etag", "v1", endpoint
        )
        result.endpoint() == endpoint

        where:
        endpoint << [
            "https://s3.amazonaws.com",
            "https://s3.us-east-1.amazonaws.com",
            "https://custom-endpoint.com",
            "http://localhost:9000" // MinIO
        ]
    }

    def "should handle large files"() {
        when:
        def result = S3UploadResult.success(
            "large-file-key", "checksum", 10737418240L, "application/octet-stream",
            null, "us-west-2", "large-bucket", "etag-multi", "v1", "endpoint"
        )

        then:
        result.actualSizeBytes() == 10737418240L // 10 GB
    }

    def "should maintain immutability of parent class properties"() {
        given:
        def result = S3UploadResult.success(
            "key", "check", 100L, "application/pdf",
            null, "us-east-1", "bucket", "etag", "v1", "endpoint"
        )

        expect:
        result.key() == "key"
        result.checksum() == "check"
        result.actualSizeBytes() == 100L
        result.contentType() == "application/pdf"
    }
}

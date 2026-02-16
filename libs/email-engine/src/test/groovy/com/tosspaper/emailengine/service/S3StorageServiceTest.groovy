package com.tosspaper.emailengine.service

import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.properties.AwsProperties
import com.tosspaper.models.properties.FileProperties
import com.tosspaper.models.service.impl.S3StorageServiceImpl
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import spock.lang.Specification
import spock.lang.Subject

class S3StorageServiceTest extends Specification {

    S3Client s3Client = Mock()
    AwsProperties awsProperties = Mock()
    AwsProperties.Bucket bucketProperties = Mock()
    FileProperties fileProperties = Mock()

    @Subject
    S3StorageServiceImpl storageService

    def setup() {
        awsProperties.getBucket() >> bucketProperties

        bucketProperties.getName() >> "test-bucket"
        bucketProperties.getRegion() >> "us-east-1"
        bucketProperties.getEndpoint() >> null

        fileProperties.getReplacementMap() >> [
            "..": "_",
            "/": "_",
            "\\": "_",
            ":": "_",
            "*": "_",
            "?": "_",
            "\"": "_",
            "<": "_",
            ">": "_",
            "|": "_",
            " ": "_",
            "&": "_",
            "!": "_",
            "@": "_",
            "#": "_"
        ]

        storageService = new S3StorageServiceImpl(s3Client, awsProperties, fileProperties)
    }

    def "should successfully upload single file"() {
        given: "a file object to upload (checksum set by provider during ingestion)"
        def fileContent = "test file content".getBytes()
        def expectedChecksum = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        def fileObject = FileObject.builder()
            .key("test-key/test.txt")
            .fileName("test.txt")
            .contentType("text/plain")
            .content(fileContent)
            .checksum(expectedChecksum)
            .metadata(["original-filename": "test.txt"])
            .build()

        and: "S3 client returns successful response"
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> PutObjectResponse.builder().build()

        when: "uploading the file"
        def results = storageService.uploadFiles([fileObject])

        then: "should return successful result with checksum preserved"
        results.size() == 1
        def result = results[0]
        result.isSuccessful()
        result.key() == "test-key/test.txt"
        result.checksum() == expectedChecksum
        result.actualSizeBytes() == fileContent.length
        result.contentType() == "text/plain"
        result.getErrorMessage() == null
    }

    def "should handle multiple file uploads"() {
        given: "multiple file objects"
        def file1 = FileObject.builder()
            .key("test-key/file1.txt")
            .fileName("file1.txt")
            .contentType("text/plain")
            .content("content1".getBytes())
            .metadata([:])
            .build()

        def file2 = FileObject.builder()
            .key("test-key/file2.pdf")
            .fileName("file2.pdf")
            .contentType("application/pdf")
            .content("pdf content".getBytes())
            .metadata([:])
            .build()

        and: "S3 client returns successful responses"
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> PutObjectResponse.builder().build()

        when: "uploading multiple files"
        def results = storageService.uploadFiles([file1, file2])

        then: "should return results for both files"
        results.size() == 2
        results.every { it.isSuccessful() }
        results[0].key().contains("file1.txt")
        results[1].key().contains("file2.pdf")
    }

    def "should handle S3 exceptions"() {
        given: "a file object to upload"
        def fileObject = FileObject.builder()
            .key("test-key/test.txt")
            .fileName("test.txt")
            .contentType("text/plain")
            .content("content".getBytes())
            .metadata([:])
            .build()

        and: "S3 client throws exception"
        def awsErrorDetails = AwsErrorDetails.builder()
            .errorCode("AccessDenied")
            .errorMessage("Access denied to bucket")
            .build()
        def s3Exception = S3Exception.builder()
            .awsErrorDetails(awsErrorDetails)
            .message("S3 error occurred")
            .build()

        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { throw s3Exception }

        when: "uploading the file"
        def results = storageService.uploadFiles([fileObject])

        then: "should return failure result"
        results.size() == 1
        def result = results[0]
        result.isFailed()
        result.getErrorMessage() != null
        result.key() == null
    }

    def "should handle generic exceptions"() {
        given: "a file object to upload"
        def fileObject = FileObject.builder()
            .key("test-key/test.txt")
            .fileName("test.txt")
            .contentType("text/plain")
            .content("content".getBytes())
            .metadata([:])
            .build()

        and: "S3 client throws generic exception"
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> {
            throw new RuntimeException("Network error")
        }

        when: "uploading the file"
        def results = storageService.uploadFiles([fileObject])

        then: "should return failure result"
        results.size() == 1
        def result = results[0]
        result.isFailed()
        result.getErrorMessage().contains("Network error")
        result.key() == null
    }

    def "should sanitize filenames"() {
        given: "a file with problematic filename"
        def fileObject = FileObject.builder()
            .key("test-key/file with spaces & special chars!@#.txt")
            .fileName("file with spaces & special chars!@#.txt")
            .contentType("text/plain")
            .content("content".getBytes())
            .metadata([:])
            .build()

        and: "S3 client returns successful response"
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> PutObjectResponse.builder().build()

        when: "uploading the file"
        def results = storageService.uploadFiles([fileObject])

        then: "should return successful result"
        results.size() == 1
        def result = results[0]
        result.isSuccessful()
    }

    def "should preserve checksums from provider"() {
        given: "two file objects with checksums set by provider"
        def content = "identical content".getBytes()
        def sharedChecksum = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234"
        def file1 = FileObject.builder()
            .key("test-key/file1.txt")
            .fileName("file1.txt")
            .contentType("text/plain")
            .content(content)
            .checksum(sharedChecksum)
            .metadata([:])
            .build()

        def file2 = FileObject.builder()
            .key("test-key/file2.txt")
            .fileName("file2.txt")
            .contentType("text/plain")
            .content(content)
            .checksum(sharedChecksum)
            .metadata([:])
            .build()

        and: "S3 client returns successful responses"
        s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> PutObjectResponse.builder().build()

        when: "uploading both files"
        def results = storageService.uploadFiles([file1, file2])

        then: "should preserve identical checksums from input"
        results.size() == 2
        results[0].checksum() == sharedChecksum
        results[1].checksum() == sharedChecksum
        results[0].checksum() == results[1].checksum()
    }

    def "should handle empty file list"() {
        when: "uploading empty file list"
        def results = storageService.uploadFiles([])

        then: "should return empty results"
        results.isEmpty()

        and: "should not call S3 client"
        0 * s3Client.putObject(_, _)
    }
}
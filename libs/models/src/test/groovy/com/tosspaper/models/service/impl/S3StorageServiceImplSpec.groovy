package com.tosspaper.models.service.impl

import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.properties.AwsProperties
import com.tosspaper.models.properties.FileProperties
import com.tosspaper.models.storage.DownloadResult
import com.tosspaper.models.storage.S3UploadResult
import com.tosspaper.models.storage.UploadResult
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.TimeUnit

/**
 * Tests for S3StorageServiceImpl.
 * Verifies S3 upload/download operations with mocked AWS SDK.
 */
class S3StorageServiceImplSpec extends Specification {

    S3Client s3Client = Mock()
    AwsProperties awsProperties = new AwsProperties()
    FileProperties fileProperties = new FileProperties()

    @Subject
    S3StorageServiceImpl storageService

    def setup() {
        awsProperties.bucket = new AwsProperties.Bucket()
        awsProperties.bucket.name = "test-bucket"
        awsProperties.bucket.region = "us-east-1"
        awsProperties.bucket.endpoint = "https://s3.us-east-1.amazonaws.com"

        storageService = new S3StorageServiceImpl(s3Client, awsProperties, fileProperties)
        storageService.start()
    }

    def cleanup() {
        storageService.stop()
    }

    // ==================== uploadFile Tests ====================

    def "uploadFile should successfully upload file to S3"() {
        given:
        def content = "PDF content here".bytes
        def fileObject = FileObject.builder()
            .fileName("invoice.pdf")
            .key("recipient@example.com/sender@example.com/inv-123-invoice.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .checksum("abc123def456")
            .metadata(["provider-message-id": "msg-123"])
            .build()

        def putObjectResponse = PutObjectResponse.builder().build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        1 * s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { PutObjectRequest req, RequestBody body ->
            assert req.bucket() == "test-bucket"
            assert req.key() == fileObject.key
            assert req.contentType() == "application/pdf"
            assert req.contentLength() == content.length
            putObjectResponse
        }

        and: "result is successful"
        result.isSuccessful()
        result instanceof S3UploadResult

        and: "result contains correct data"
        def s3Result = result as S3UploadResult
        s3Result.key() == fileObject.key
        s3Result.checksum() == "abc123def456"
        s3Result.actualSizeBytes() == content.length
        s3Result.contentType() == "application/pdf"
        s3Result.bucket() == "test-bucket"
        s3Result.region() == "us-east-1"
    }

    def "uploadFile should return failure result on S3Exception"() {
        given:
        def content = "PDF content".bytes
        def fileObject = FileObject.builder()
            .fileName("fail.pdf")
            .key("test-key")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        def s3Exception = S3Exception.builder()
            .message("Access Denied")
            .awsErrorDetails(
                AwsErrorDetails.builder()
                    .errorCode("AccessDenied")
                    .errorMessage("Access Denied")
                    .build()
            )
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        1 * s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { throw s3Exception }

        and: "result is failure"
        result.isFailed()
        result.error() != null
        result.error().message.contains("Access Denied")
    }

    def "uploadFile should return failure result on general exception"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("error.pdf")
            .key("test-key")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        1 * s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> {
            throw new RuntimeException("Network error")
        }

        and:
        result.isFailed()
        result.error() != null
    }

    // ==================== uploadFiles Tests ====================

    def "uploadFiles should upload multiple files and return results"() {
        given:
        def file1 = FileObject.builder()
            .fileName("file1.pdf")
            .key("key1")
            .contentType("application/pdf")
            .content("content1".bytes)
            .sizeBytes(8)
            .build()

        def file2 = FileObject.builder()
            .fileName("file2.pdf")
            .key("key2")
            .contentType("application/pdf")
            .content("content2".bytes)
            .sizeBytes(8)
            .build()

        when:
        def results = storageService.uploadFiles([file1, file2])

        then:
        2 * s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> PutObjectResponse.builder().build()

        and:
        results.size() == 2
        results.every { it.isSuccessful() }
    }

    def "uploadFiles should handle partial failures"() {
        given:
        def file1 = FileObject.builder()
            .fileName("success.pdf")
            .key("key1")
            .contentType("application/pdf")
            .content("content1".bytes)
            .sizeBytes(8)
            .build()

        def file2 = FileObject.builder()
            .fileName("fail.pdf")
            .key("key2")
            .contentType("application/pdf")
            .content("content2".bytes)
            .sizeBytes(8)
            .build()

        def s3Exception = S3Exception.builder()
            .message("Upload failed")
            .awsErrorDetails(
                AwsErrorDetails.builder()
                    .errorCode("InternalError")
                    .errorMessage("Internal error")
                    .build()
            )
            .build()

        when:
        def results = storageService.uploadFiles([file1, file2])

        then:
        1 * s3Client.putObject({ it.key() == "key1" } as PutObjectRequest, _ as RequestBody) >>
            PutObjectResponse.builder().build()
        1 * s3Client.putObject({ it.key() == "key2" } as PutObjectRequest, _ as RequestBody) >>
            { throw s3Exception }

        and:
        results.size() == 2
        results.count { it.isSuccessful() } == 1
        results.count { it.isFailed() } == 1
    }

    // ==================== uploadFilesAsync Tests ====================

    def "uploadFilesAsync should upload files asynchronously and return results"() {
        given:
        def file1 = FileObject.builder()
            .fileName("async1.pdf")
            .key("key1")
            .contentType("application/pdf")
            .content("content1".bytes)
            .sizeBytes(8)
            .build()

        def file2 = FileObject.builder()
            .fileName("async2.pdf")
            .key("key2")
            .contentType("application/pdf")
            .content("content2".bytes)
            .sizeBytes(8)
            .build()

        when:
        def future = storageService.uploadFilesAsync([file1, file2])
        def results = future.get(10, TimeUnit.SECONDS)

        then:
        2 * s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> PutObjectResponse.builder().build()

        and:
        results.size() == 2
        results.every { it.isSuccessful() }
    }

    // ==================== download Tests ====================

    def "download should successfully download file from S3"() {
        given:
        def key = "recipient@example.com/sender@example.com/file.pdf"
        def content = "Downloaded PDF content".bytes

        def getObjectResponse = GetObjectResponse.builder()
            .contentType("application/pdf")
            .contentLength((long) content.length)
            .metadata(["custom-header": "value"])
            .build()

        def responseInputStream = new ResponseInputStream<>(
            getObjectResponse,
            new ByteArrayInputStream(content)
        )

        when:
        def result = storageService.download(key)

        then:
        1 * s3Client.getObject(_ as GetObjectRequest) >> { GetObjectRequest req ->
            assert req.bucket() == "test-bucket"
            assert req.key() == key
            responseInputStream
        }

        and: "result is successful"
        result.isSuccessful()
        result.key == key
        result.fileObject != null
        result.fileObject.content == content
        result.fileObject.contentType == "application/pdf"
        result.fileObject.sizeBytes == content.length
    }

    def "download should return failure on S3Exception"() {
        given:
        def key = "non-existent-key"

        def s3Exception = S3Exception.builder()
            .message("Not Found")
            .awsErrorDetails(
                AwsErrorDetails.builder()
                    .errorCode("NoSuchKey")
                    .errorMessage("The specified key does not exist.")
                    .build()
            )
            .build()

        when:
        def result = storageService.download(key)

        then:
        1 * s3Client.getObject(_ as GetObjectRequest) >> { throw s3Exception }

        and:
        result.isFailed()
        result.key == key
        result.error != null
    }

    def "download should return failure on general exception"() {
        given:
        def key = "error-key"

        when:
        def result = storageService.download(key)

        then:
        1 * s3Client.getObject(_ as GetObjectRequest) >> {
            throw new RuntimeException("Connection timeout")
        }

        and:
        result.isFailed()
    }

    // ==================== Lifecycle Tests ====================

    def "should implement SmartLifecycle correctly"() {
        expect:
        storageService.isRunning()

        when:
        storageService.stop()

        then:
        !storageService.isRunning()

        when:
        storageService.start()

        then:
        storageService.isRunning()
    }

    // ==================== File Sanitization Tests ====================

    def "uploadFile should sanitize filename with special characters"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("file:with*special?chars.pdf")
            .key("test/path/file:with*special?chars.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        1 * s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { PutObjectRequest req, RequestBody body ->
            // FileNameSanitizer only sanitizes the fileName, not the key
            // The key is passed through unchanged
            assert req.key() == "test/path/file:with*special?chars.pdf"
            PutObjectResponse.builder().build()
        }

        and:
        result.isSuccessful()
    }

    // ==================== Metadata Tests ====================

    def "uploadFile should include file metadata in S3 request"() {
        given:
        def content = "content".bytes
        def metadata = [
            "provider-message-id": "msg-abc123",
            "from-address": "sender@example.com",
            "to-address": "recipient@example.com"
        ]
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .key("test-key")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .metadata(metadata)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        1 * s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> { PutObjectRequest req, RequestBody body ->
            assert req.metadata() == metadata
            PutObjectResponse.builder().build()
        }

        and:
        result.isSuccessful()
    }

    def "upload result should include S3-specific metadata"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .key("test-key")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        1 * s3Client.putObject(_ as PutObjectRequest, _ as RequestBody) >> PutObjectResponse.builder().build()

        and:
        result instanceof S3UploadResult
        def s3Result = result as S3UploadResult
        s3Result.metadata()["bucket"] == "test-bucket"
        s3Result.metadata()["upload-timestamp"] != null
    }

    // ==================== Empty File Tests ====================

    def "uploadFiles should handle empty list"() {
        when:
        def results = storageService.uploadFiles([])

        then:
        0 * s3Client._
        results.isEmpty()
    }

    def "uploadFilesAsync should handle empty list"() {
        when:
        def future = storageService.uploadFilesAsync([])
        def results = future.get(5, TimeUnit.SECONDS)

        then:
        0 * s3Client._
        results.isEmpty()
    }
}

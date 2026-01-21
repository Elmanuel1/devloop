package com.tosspaper.file

import com.tosspaper.file.exception.FileDeleteException
import com.tosspaper.file.exception.FileUploadException
import com.tosspaper.generated.model.CreatePresignedUrlRequest
import com.tosspaper.generated.model.DeletePresignedUrlRequest
import com.tosspaper.models.properties.AwsProperties
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.DeleteObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedDeleteObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import spock.lang.Specification
import spock.lang.Subject

import java.time.Instant
import java.time.ZoneOffset

class FilesServiceImplSpec extends Specification {

    AwsProperties awsS3Properties
    AwsProperties.Bucket bucket
    S3Presigner s3Presigner

    @Subject
    FilesServiceImpl filesService

    def setup() {
        awsS3Properties = Mock(AwsProperties)
        bucket = Mock(AwsProperties.Bucket)
        s3Presigner = Mock(S3Presigner)
        filesService = new FilesServiceImpl(awsS3Properties, s3Presigner)

        awsS3Properties.getBucket() >> bucket
        bucket.getName() >> "test-bucket"
    }

    def "should create presigned upload URL successfully"() {
        given: "a valid upload request"
        def companyId = 1L
        def userId = "test-user@example.com"
        def request = new CreatePresignedUrlRequest()
        request.setKey("company-logos/logo.png")
        request.setSize(1024L)
        request.setContentType(CreatePresignedUrlRequest.ContentTypeEnum.IMAGE_PNG)

        and: "S3 presigner returns a valid response"
        def mockUrl = new URL("https://test-bucket.s3.amazonaws.com/presigned-url")
        def mockExpiration = Instant.now().plusSeconds(300)
        def presignedRequest = Mock(PresignedPutObjectRequest) {
            url() >> mockUrl
            expiration() >> mockExpiration
        }

        when: "creating presigned upload URL"
        def result = filesService.createPresignedUploadUrl(companyId, userId, request)

        then: "returns valid presigned URL"
        result.url == mockUrl.toString()
        result.expiration == mockExpiration.atOffset(ZoneOffset.UTC)

        and: "S3 presigner was called"
        1 * s3Presigner.presignPutObject(_ as PutObjectPresignRequest) >> presignedRequest
    }

    def "should create presigned delete URL successfully"() {
        given: "a valid delete request"
        def companyId = 1L
        def userId = "test-user@example.com"
        def request = new DeletePresignedUrlRequest()
        request.setKey("company-logos/logo.png")

        and: "S3 presigner returns a valid response"
        def mockUrl = new URL("https://test-bucket.s3.amazonaws.com/presigned-delete-url")
        def mockExpiration = Instant.now().plusSeconds(300)
        def presignedRequest = Mock(PresignedDeleteObjectRequest) {
            url() >> mockUrl
            expiration() >> mockExpiration
        }

        when: "creating presigned delete URL"
        def result = filesService.createPresignedDeleteUrl(companyId, userId, request)

        then: "returns valid presigned URL"
        result.url == mockUrl.toString()
        result.expiration == mockExpiration.atOffset(ZoneOffset.UTC)

        and: "S3 presigner was called"
        1 * s3Presigner.presignDeleteObject(_ as DeleteObjectPresignRequest) >> presignedRequest
    }

    def "should throw exception for zero file size"() {
        given: "a request with zero file size"
        def request = new CreatePresignedUrlRequest()
        request.setKey("files/logo.png")
        request.setSize(0L)
        request.setContentType(CreatePresignedUrlRequest.ContentTypeEnum.IMAGE_PNG)

        when: "creating presigned upload URL"
        filesService.createPresignedUploadUrl(1L, "user@example.com", request)

        then: "throws FileUploadException"
        def exception = thrown(FileUploadException)
        exception.message == "File size must be greater than 0"
    }

    def "should throw exception for negative file size"() {
        given: "a request with negative file size"
        def request = new CreatePresignedUrlRequest()
        request.setKey("files/logo.png")
        request.setSize(-1L)
        request.setContentType(CreatePresignedUrlRequest.ContentTypeEnum.IMAGE_PNG)

        when: "creating presigned upload URL"
        filesService.createPresignedUploadUrl(1L, "user@example.com", request)

        then: "throws FileUploadException"
        def exception = thrown(FileUploadException)
        exception.message == "File size must be greater than 0"
    }

    def "should throw exception for file size exceeding 3MB"() {
        given: "a request with file size over 3MB"
        def request = new CreatePresignedUrlRequest()
        request.setKey("files/logo.png")
        request.setSize((3 * 1024 * 1024L) + 1)
        request.setContentType(CreatePresignedUrlRequest.ContentTypeEnum.IMAGE_PNG)

        when: "creating presigned upload URL"
        filesService.createPresignedUploadUrl(1L, "user@example.com", request)

        then: "throws FileUploadException"
        def exception = thrown(FileUploadException)
        exception.message == "Please upload a file less than 3 MB"
    }

    def "should accept valid file sizes"() {
        given: "a request with valid file size"
        def request = new CreatePresignedUrlRequest()
        request.setKey("files/logo.png")
        request.setSize(fileSize)
        request.setContentType(CreatePresignedUrlRequest.ContentTypeEnum.IMAGE_PNG)

        and: "S3 presigner returns a valid response"
        def mockUrl = new URL("https://test-bucket.s3.amazonaws.com/presigned-url")
        def mockExpiration = Instant.now().plusSeconds(300)
        def presignedRequest = Mock(PresignedPutObjectRequest) {
            url() >> mockUrl
            expiration() >> mockExpiration
        }

        when: "creating presigned upload URL"
        def result = filesService.createPresignedUploadUrl(1L, "user@example.com", request)

        then: "succeeds without validation error"
        result.url == mockUrl.toString()
        1 * s3Presigner.presignPutObject(_ as PutObjectPresignRequest) >> presignedRequest

        where:
        fileSize << [
            1L,                        // 1 byte
            1024L,                     // 1KB
            1024 * 1024L,              // 1MB
            2 * 1024 * 1024L,          // 2MB
            3 * 1024 * 1024L           // 3MB (exactly at limit)
        ]
    }

    def "should handle S3 presigner exceptions for delete"() {
        given: "a valid delete request"
        def request = new DeletePresignedUrlRequest()
        request.setKey("company-logos/logo.png")

        and: "S3 presigner throws exception"
        s3Presigner.presignDeleteObject(_ as DeleteObjectPresignRequest) >> { throw new RuntimeException("S3 error") }

        when: "creating presigned delete URL"
        filesService.createPresignedDeleteUrl(1L, "user@example.com", request)

        then: "throws FileDeleteException"
        def exception = thrown(FileDeleteException)
        exception.message == "Failed to generate presigned delete URL"
        exception.cause.message == "S3 error"
    }

    def "should throw exception for null delete key"() {
        given: "a delete request with null key"
        def request = new DeletePresignedUrlRequest()
        request.setKey(null)

        when: "creating presigned delete URL"
        filesService.createPresignedDeleteUrl(1L, "user@example.com", request)

        then: "throws FileDeleteException"
        def exception = thrown(FileDeleteException)
        exception.message == "Failed to generate presigned delete URL"
    }

    def "should throw exception for empty delete key"() {
        given: "a delete request with empty key"
        def request = new DeletePresignedUrlRequest()
        request.setKey("")

        when: "creating presigned delete URL"
        filesService.createPresignedDeleteUrl(1L, "user@example.com", request)

        then: "throws FileDeleteException"
        def exception = thrown(FileDeleteException)
        exception.message == "Failed to generate presigned delete URL"
    }

    def "should throw exception for key ending with slash"() {
        given: "a delete request with key ending in slash"
        def request = new DeletePresignedUrlRequest()
        request.setKey("files/")

        when: "creating presigned delete URL"
        filesService.createPresignedDeleteUrl(1L, "user@example.com", request)

        then: "throws FileDeleteException"
        def exception = thrown(FileDeleteException)
        exception.message == "Failed to generate presigned delete URL"
    }
}

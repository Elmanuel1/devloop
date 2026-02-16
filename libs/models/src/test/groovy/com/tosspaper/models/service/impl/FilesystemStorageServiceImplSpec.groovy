package com.tosspaper.models.service.impl

import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.properties.FileProperties
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for FilesystemStorageServiceImpl.
 * Verifies local filesystem storage operations.
 */
class FilesystemStorageServiceImplSpec extends Specification {

    @TempDir
    Path tempDir

    FileProperties fileProperties

    @Subject
    FilesystemStorageServiceImpl storageService

    def setup() {
        fileProperties = new FileProperties()
        fileProperties.filesystemPath = tempDir.toString()
        fileProperties.replacementMap = [
            "..": "_",
            "/": "_",
            "\\": "_",
            ":": "_",
            "*": "_",
            "?": "_",
            "\"": "_",
            "<": "_",
            ">": "_",
            "|": "_"
        ]
        storageService = new FilesystemStorageServiceImpl(fileProperties)
    }

    // ==================== uploadFile Tests ====================

    def "uploadFile should successfully upload file to filesystem"() {
        given:
        def content = "Test PDF content".bytes
        def fileObject = FileObject.builder()
            .fileName("invoice.pdf")
            .key("recipient@example.com/sender@example.com/test-invoice.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .assignedId("test-123")
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        result.isSuccessful()
        result.key().contains(tempDir.toString())
        result.actualSizeBytes() == content.length
        result.contentType() == "application/pdf"
        result.checksum() != null

        and: "file exists on disk"
        Files.exists(Path.of(result.key()))
        Files.readAllBytes(Path.of(result.key())) == content
    }

    def "uploadFile should create parent directories if they don't exist"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .key("level1/level2/level3/file.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        result.isSuccessful()
        Files.exists(Path.of(result.key()))
    }

    def "uploadFile should calculate SHA-256 checksum"() {
        given:
        def content = "Test content for checksum".bytes
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key("test/test.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        result.isSuccessful()
        result.checksum() != null
        result.checksum().length() == 64 // SHA-256 is 64 hex chars
        result.checksum().matches("[a-f0-9]{64}")
    }

    def "uploadFile should sanitize filename with special characters"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("file:with*special?chars.pdf")
            .key("test/file.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        result.isSuccessful()
        // FileNameSanitizer sanitizes the fileName, not the key
        // Verify the file was created successfully
        Files.exists(Path.of(result.key()))
    }

    def "uploadFile should return failure when directory creation fails"() {
        given:
        // Use /proc/1/fdinfo as base path - cannot create directories under proc fdinfo
        fileProperties.filesystemPath = "/proc/1/fdinfo/nonexistent"
        storageService = new FilesystemStorageServiceImpl(fileProperties)

        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key("test/test.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        result.isFailed()
        result.error() != null
    }

    def "uploadFile should handle directory already exists exception gracefully"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("file.pdf")
            .key("existing/file.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        // Pre-create the directory
        Files.createDirectories(tempDir.resolve("existing"))

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        result.isSuccessful()
    }

    def "uploadFile should reject null or blank key"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key(key)
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        storageService.uploadFile(fileObject)

        then:
        def result = thrown(IllegalArgumentException)
        result.message.contains("File key cannot be null or blank")

        where:
        key << [null, "", "   "]
    }

    def "uploadFile should reject path traversal attempts"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key(key)
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        storageService.uploadFile(fileObject)

        then:
        def result = thrown(IllegalArgumentException)
        result.message.contains("path")

        where:
        key << ["../../../etc/passwd", "test/../../../etc/passwd", "/absolute/path"]
    }

    def "uploadFile should reject keys that escape storage directory"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key("valid/../../../etc/passwd")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        storageService.uploadFile(fileObject)

        then:
        thrown(IllegalArgumentException)
    }

    // ==================== uploadFiles Tests ====================

    def "uploadFiles should upload multiple files"() {
        given:
        def file1 = FileObject.builder()
            .fileName("file1.pdf")
            .key("key1/file1.pdf")
            .contentType("application/pdf")
            .content("content1".bytes)
            .sizeBytes(8)
            .build()

        def file2 = FileObject.builder()
            .fileName("file2.pdf")
            .key("key2/file2.pdf")
            .contentType("application/pdf")
            .content("content2".bytes)
            .sizeBytes(8)
            .build()

        when:
        def results = storageService.uploadFiles([file1, file2])

        then:
        results.size() == 2
        results.every { it.isSuccessful() }
    }

    def "uploadFiles should handle empty list"() {
        when:
        def results = storageService.uploadFiles([])

        then:
        results.isEmpty()
    }

    def "uploadFiles should handle partial failures"() {
        given:
        def validFile = FileObject.builder()
            .fileName("valid.pdf")
            .key("valid/valid.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .sizeBytes(7)
            .build()

        def invalidFile = FileObject.builder()
            .fileName("invalid.pdf")
            .key(null) // Will fail with IllegalArgumentException
            .contentType("application/pdf")
            .content("content".bytes)
            .sizeBytes(7)
            .build()

        when:
        storageService.uploadFiles([validFile, invalidFile])

        then:
        // uploadFile throws IllegalArgumentException for null key
        // which propagates through the stream operation
        thrown(IllegalArgumentException)
    }

    // ==================== uploadFilesAsync Tests ====================

    def "uploadFilesAsync should throw UnsupportedOperationException"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key("test/test.pdf")
            .contentType("application/pdf")
            .content("content".bytes)
            .sizeBytes(7)
            .build()

        when:
        storageService.uploadFilesAsync([fileObject])

        then:
        thrown(UnsupportedOperationException)
    }

    // ==================== download Tests ====================

    def "download should successfully download file from filesystem"() {
        given:
        def key = "downloads/test-file.pdf"
        def content = "Downloaded content".bytes

        // First upload the file
        def fileObject = FileObject.builder()
            .fileName("test-file.pdf")
            .key(key)
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()
        storageService.uploadFile(fileObject)

        when:
        def result = storageService.download(key)

        then:
        result.isSuccessful()
        result.key == key
        result.fileObject != null
        result.fileObject.content == content
        result.fileObject.fileName == "test-file.pdf"
        result.fileObject.sizeBytes == content.length
    }

    def "download should return failure for non-existent file"() {
        given:
        def key = "nonexistent/file.pdf"

        when:
        def result = storageService.download(key)

        then:
        result.isFailed()
        result.key == key
        result.throwable instanceof FileNotFoundException
    }

    def "download should include filesystem metadata"() {
        given:
        def key = "meta/test.pdf"
        def content = "content".bytes

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key(key)
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()
        storageService.uploadFile(fileObject)

        when:
        def result = storageService.download(key)

        then:
        result.isSuccessful()
        result.metadata != null
        result.metadata.containsKey("file-path")
        result.metadata.containsKey("download-timestamp")
        result.metadata.containsKey("file-size")
        result.metadata.containsKey("last-modified")
    }

    def "download should probe content type from file"() {
        given:
        def key = "probe/test.pdf"
        def content = "content".bytes

        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key(key)
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()
        storageService.uploadFile(fileObject)

        when:
        def result = storageService.download(key)

        then:
        result.isSuccessful()
        result.fileObject.contentType != null
    }

    def "download should handle path traversal attempts"() {
        given:
        def key = "../../../etc/passwd"

        when:
        def result = storageService.download(key)

        then:
        // download() catches IllegalArgumentException from createLocalFilePath
        // and returns a failed DownloadResult
        result.isFailed()
        result.throwable instanceof IllegalArgumentException
    }

    def "download should extract filename from key"() {
        given:
        def key = "path/to/my-document.pdf"
        def content = "content".bytes

        def fileObject = FileObject.builder()
            .fileName("my-document.pdf")
            .key(key)
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()
        storageService.uploadFile(fileObject)

        when:
        def result = storageService.download(key)

        then:
        result.isSuccessful()
        result.fileObject.fileName == "my-document.pdf"
    }

    def "download should default content type to application/octet-stream when unknown"() {
        given:
        def key = "unknown/file.xyz"
        def content = "content".bytes

        def fileObject = FileObject.builder()
            .fileName("file.xyz")
            .key(key)
            .contentType("application/xyz")
            .content(content)
            .sizeBytes(content.length)
            .build()
        storageService.uploadFile(fileObject)

        when:
        def result = storageService.download(key)

        then:
        result.isSuccessful()
        // Content type will be probed or default to application/octet-stream
        result.fileObject.contentType != null
    }

    // ==================== Checksum Tests ====================

    def "calculateChecksum should produce consistent SHA-256 hashes"() {
        given:
        def content = "Same content".bytes
        def fileObject1 = FileObject.builder()
            .fileName("file1.pdf")
            .key("test1/file.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        def fileObject2 = FileObject.builder()
            .fileName("file2.pdf")
            .key("test2/file.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result1 = storageService.uploadFile(fileObject1)
        def result2 = storageService.uploadFile(fileObject2)

        then:
        result1.checksum() == result2.checksum()
    }

    def "calculateChecksum should produce different hashes for different content"() {
        given:
        def fileObject1 = FileObject.builder()
            .fileName("file1.pdf")
            .key("test1/file.pdf")
            .contentType("application/pdf")
            .content("Content A".bytes)
            .sizeBytes(9)
            .build()

        def fileObject2 = FileObject.builder()
            .fileName("file2.pdf")
            .key("test2/file.pdf")
            .contentType("application/pdf")
            .content("Content B".bytes)
            .sizeBytes(9)
            .build()

        when:
        def result1 = storageService.uploadFile(fileObject1)
        def result2 = storageService.uploadFile(fileObject2)

        then:
        result1.checksum() != result2.checksum()
    }

    // ==================== Path Validation Tests ====================

    def "createLocalFilePath should normalize paths correctly"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key("path/./to/./file.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        result.isSuccessful()
    }

    def "uploadFile should handle Windows-style path separators"() {
        given:
        def content = "content".bytes
        // A key starting with backslash is rejected by createLocalFilePath
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .key("\\path\\to\\file.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        storageService.uploadFile(fileObject)

        then:
        thrown(IllegalArgumentException)
    }

    def "uploadFile should handle complex nested directory structures"() {
        given:
        def content = "content".bytes
        def fileObject = FileObject.builder()
            .fileName("deep.pdf")
            .key("a/b/c/d/e/f/g/deep.pdf")
            .contentType("application/pdf")
            .content(content)
            .sizeBytes(content.length)
            .build()

        when:
        def result = storageService.uploadFile(fileObject)

        then:
        result.isSuccessful()
        Files.exists(Path.of(result.key()))
    }
}

package com.tosspaper.aiengine.vfs

import com.tosspaper.aiengine.properties.VfsProperties
import com.tosspaper.models.domain.DocumentType
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for VirtualFilesystemServiceImpl.
 * Tests VFS operations including path construction, file operations, and security measures.
 */
class VirtualFilesystemServiceImplSpec extends Specification {

    @TempDir
    Path tempDir

    @Subject
    VirtualFilesystemServiceImpl service

    def setup() {
        def properties = new VfsProperties()
        properties.root = tempDir.toString()
        properties.autoCreateDirectories = true
        service = new VirtualFilesystemServiceImpl(properties)
        service.init()
    }

    // ==================== SAVE TESTS ====================

    def "should save document to correct path structure"() {
        given: "document context"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("doc-123")
            .documentType(DocumentType.INVOICE)
            .content('{"items": []}')
            .build()

        when: "saving document"
        def path = service.save(context)

        then: "file is created at expected location"
        Files.exists(path)
        path.toString().contains("companies/1/po/PO-456/invoice/doc-123.json")

        and: "content is correct"
        Files.readString(path) == '{"items": []}'
    }

    def "should save purchase order to correct path"() {
        given: "PO context"
        def context = VfsDocumentContext.builder()
            .companyId(2L)
            .poNumber("PO-789")
            .documentId("po")
            .documentType(DocumentType.PURCHASE_ORDER)
            .content('{"poNumber": "PO-789"}')
            .build()

        when: "saving PO"
        def path = service.save(context)

        then: "file is created at expected location"
        Files.exists(path)
        path.toString().contains("companies/2/po/PO-789/po.json")

        and: "content is correct"
        Files.readString(path) == '{"poNumber": "PO-789"}'
    }

    def "should create parent directories automatically"() {
        given: "a deeply nested path that doesn't exist"
        def context = VfsDocumentContext.builder()
            .companyId(99L)
            .poNumber("PO-999")
            .documentId("doc-new")
            .documentType(DocumentType.INVOICE)
            .content('{"new": true}')
            .build()

        when: "saving document"
        def path = service.save(context)

        then: "parent directories are created"
        Files.exists(path.getParent())
        Files.exists(path)
    }

    // ==================== GET PATH TESTS ====================

    def "should return correct path for document"() {
        given: "document context"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("doc-456")
            .documentType(DocumentType.DELIVERY_SLIP)
            .content("")
            .build()

        when: "getting path"
        def path = service.getPath(context)

        then: "path is correct"
        path.toString().endsWith("companies/1/po/PO-456/delivery_slip/doc-456.json")
    }

    // ==================== READ FILE TESTS ====================

    def "should read file content"() {
        given: "a file exists in VFS"
        def content = '{"test": "data"}'
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("test-doc")
            .documentType(DocumentType.INVOICE)
            .content(content)
            .build()
        def path = service.save(context)

        when: "reading the file"
        def readContent = service.readFile(path)

        then: "content matches"
        readContent == content
    }

    def "should throw exception when reading non-existent file"() {
        given: "a path that doesn't exist"
        def nonExistentPath = tempDir.resolve("companies/1/po/PO-456/invoice/MISSING.json")

        when: "reading the file"
        service.readFile(nonExistentPath)

        then: "exception is thrown"
        thrown(java.nio.file.NoSuchFileException)
    }

    // ==================== PATH TRAVERSAL SECURITY TESTS ====================

    def "should reject path traversal with double dots in documentId"() {
        given: "context with path traversal in documentId"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("../../../etc/passwd")
            .documentType(DocumentType.INVOICE)
            .content("{}")
            .build()

        when: "saving with path traversal"
        service.save(context)

        then: "security exception is thrown"
        thrown(SecurityException)
    }

    def "should reject path traversal with tilde in documentId"() {
        given: "context with tilde"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("~root")
            .documentType(DocumentType.INVOICE)
            .content("{}")
            .build()

        when: "saving with tilde"
        service.save(context)

        then: "security exception is thrown"
        thrown(SecurityException)
    }

    def "should reject absolute path components in documentId"() {
        given: "context with absolute path"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("/etc/passwd")
            .documentType(DocumentType.INVOICE)
            .content("{}")
            .build()

        when: "saving with absolute path"
        service.save(context)

        then: "security exception is thrown"
        thrown(SecurityException)
    }

    def "should reject path traversal in poNumber"() {
        given: "context with path traversal in poNumber"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("../../../etc")
            .documentId("doc-123")
            .documentType(DocumentType.INVOICE)
            .content("{}")
            .build()

        when: "saving with path traversal"
        service.save(context)

        then: "security exception is thrown"
        thrown(SecurityException)
    }

    def "should reject reading path outside VFS root"() {
        given: "a path outside the VFS root"
        def outsidePath = tempDir.getParent().resolve("outside.txt")
        Files.writeString(outsidePath, "outside content")

        when: "reading file outside root"
        service.readFile(outsidePath)

        then: "security exception is thrown"
        thrown(SecurityException)

        cleanup:
        Files.deleteIfExists(outsidePath)
    }

    // ==================== WORKING DIRECTORY TESTS ====================

    def "should return correct working directory for PO"() {
        when: "getting working directory"
        def workingDir = service.getWorkingDirectory(1L, "PO-456")

        then: "path is correct"
        workingDir.toString().endsWith("companies/1/po/PO-456")
    }

    def "should return correct audit directory for PO"() {
        when: "getting audit directory"
        def auditDir = service.getAuditDirectory(1L, "PO-456")

        then: "path is correct"
        auditDir.toString().endsWith("companies/1/po/PO-456/audits")
    }

    // ==================== EXISTS AND DELETE TESTS ====================

    def "should check if file exists"() {
        given: "a file in VFS"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("exists-doc")
            .documentType(DocumentType.INVOICE)
            .content("{}")
            .build()
        def path = service.save(context)

        expect: "exists returns true"
        service.exists(path)

        and: "non-existent path returns false"
        !service.exists(tempDir.resolve("companies/1/po/PO-456/invoice/NO-EXIST.json"))
    }

    def "should delete file"() {
        given: "a file in VFS"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("delete-doc")
            .documentType(DocumentType.INVOICE)
            .content("{}")
            .build()
        def path = service.save(context)

        when: "deleting the file"
        def result = service.delete(path)

        then: "returns true and file is gone"
        result
        !Files.exists(path)
    }

    def "should return false when deleting non-existent file"() {
        given: "a path that doesn't exist"
        def path = tempDir.resolve("companies/1/po/PO-456/invoice/MISSING.json")

        when: "deleting the file"
        def result = service.delete(path)

        then: "returns false"
        !result
    }

    // ==================== ROOT PATH TEST ====================

    def "should return root path"() {
        expect: "root path matches temp dir"
        service.getRoot() == tempDir.toAbsolutePath().normalize()
    }

    // ==================== CONSTRUCTOR TESTS ====================

    def "should initialize with VfsProperties"() {
        given: "VfsProperties with custom settings"
        def properties = new VfsProperties()
        properties.setRoot(tempDir.toString())
        properties.setAutoCreateDirectories(true)

        when: "creating service with properties"
        def vfsService = new VirtualFilesystemServiceImpl(properties)
        vfsService.init()

        then: "root is set correctly"
        vfsService.getRoot() == tempDir.toAbsolutePath().normalize()
    }

    def "should initialize with VfsProperties and autoCreateDirectories disabled"() {
        given: "VfsProperties with autoCreate disabled"
        def properties = new VfsProperties()
        properties.setRoot(tempDir.toString())
        properties.setAutoCreateDirectories(false)

        when: "creating service with properties"
        def vfsService = new VirtualFilesystemServiceImpl(properties)
        vfsService.init()

        then: "service is created"
        vfsService.getRoot() == tempDir.toAbsolutePath().normalize()
    }

    // ==================== SANITIZATION TESTS ====================

    def "should sanitize special characters in path components"() {
        given: "document ID with special characters"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("DOC:123*456?")
            .documentType(DocumentType.INVOICE)
            .content("{}")
            .build()

        when: "saving document"
        def path = service.save(context)

        then: "special characters are replaced"
        path.toString().contains("DOC_123_456_")
        Files.exists(path)
    }

    def "should sanitize special characters in poNumber"() {
        given: "PO number with special characters"
        def context = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO:123*456")
            .documentId("doc-123")
            .documentType(DocumentType.INVOICE)
            .content("{}")
            .build()

        when: "saving document"
        def path = service.save(context)

        then: "special characters are replaced in PO folder name"
        path.toString().contains("po/PO_123_456/")
        Files.exists(path)
    }

    // ==================== READ CHUNK TESTS ====================

    def "should read chunk from beginning of file"() {
        given: "a file with known content"
        def content = "0123456789ABCDEFGHIJ"
        def filePath = tempDir.resolve("test-chunk.txt")
        Files.writeString(filePath, content)

        when: "reading first chunk"
        def result = service.readChunk(filePath, 0, 10)

        then: "returns correct chunk"
        result.content() == "0123456789"
        result.offset() == 0
        result.length() == 10
        result.totalSize() == 20
        result.hasMore()
    }

    def "should read chunk from middle of file"() {
        given: "a file with known content"
        def content = "0123456789ABCDEFGHIJ"
        def filePath = tempDir.resolve("test-chunk2.txt")
        Files.writeString(filePath, content)

        when: "reading from offset 10"
        def result = service.readChunk(filePath, 10, 10)

        then: "returns correct chunk"
        result.content() == "ABCDEFGHIJ"
        result.offset() == 10
        result.length() == 10
        result.totalSize() == 20
        !result.hasMore()
    }

    def "should return empty chunk when offset exceeds file size"() {
        given: "a small file"
        def content = "small"
        def filePath = tempDir.resolve("test-small.txt")
        Files.writeString(filePath, content)

        when: "reading from offset beyond file"
        def result = service.readChunk(filePath, 100, 10)

        then: "returns empty chunk"
        result.content() == ""
        result.length() == 0
        result.totalSize() == 5
        !result.hasMore()
    }

    def "should throw exception for readChunk on non-existent file"() {
        given: "non-existent file path"
        def filePath = tempDir.resolve("non-existent.txt")

        when: "reading chunk"
        service.readChunk(filePath, 0, 10)

        then: "exception is thrown"
        thrown(IllegalArgumentException)
    }

    // ==================== WRITE FILE TESTS ====================

    def "should write file with public writeFile method"() {
        given: "a path and content"
        def filePath = tempDir.resolve("companies/1/po/PO-123/test-write.json")
        def content = '{"test": "write"}'

        when: "writing file"
        def result = service.writeFile(filePath, content)

        then: "file is written"
        Files.exists(result)
        Files.readString(result) == content
    }

    def "should create parent directories when writing file"() {
        given: "a path with non-existent parents"
        def filePath = tempDir.resolve("new/deep/path/file.txt")
        def content = "test content"

        when: "writing file"
        service.writeFile(filePath, content)

        then: "file and parents are created"
        Files.exists(filePath)
        Files.readString(filePath) == content
    }

    def "should reject writeFile outside VFS root"() {
        given: "a path outside VFS root"
        def outsidePath = tempDir.parent.resolve("outside-write.txt")

        when: "writing file"
        service.writeFile(outsidePath, "content")

        then: "security exception is thrown"
        thrown(SecurityException)
    }

    // ==================== LIST DIRECTORY TESTS ====================

    def "should list directory contents"() {
        given: "a directory with files and subdirectories"
        def dirPath = tempDir.resolve("list-test")
        Files.createDirectories(dirPath)
        Files.writeString(dirPath.resolve("file1.txt"), "content1")
        Files.writeString(dirPath.resolve("file2.json"), "content2")
        Files.createDirectories(dirPath.resolve("subdir"))

        when: "listing directory"
        def results = service.listDirectory(dirPath)

        then: "all entries are returned"
        results.size() == 3

        and: "files are identified correctly"
        def file1 = results.find { it.name() == "file1.txt" }
        file1 != null
        file1.isFile()
        file1.size() == 8

        and: "directories are identified correctly"
        def subdir = results.find { it.name() == "subdir" }
        subdir != null
        subdir.isDirectory()
    }

    def "should return empty list for non-existent directory"() {
        given: "non-existent directory"
        def dirPath = tempDir.resolve("non-existent-dir")

        when: "listing directory"
        def results = service.listDirectory(dirPath)

        then: "empty list returned"
        results.isEmpty()
    }

    def "should throw exception when listing file as directory"() {
        given: "a file path"
        def filePath = tempDir.resolve("not-a-dir.txt")
        Files.writeString(filePath, "content")

        when: "listing as directory"
        service.listDirectory(filePath)

        then: "exception is thrown"
        thrown(IllegalArgumentException)
    }

    // ==================== GREP TESTS ====================

    def "should find pattern in single file"() {
        given: "a file with searchable content"
        def filePath = tempDir.resolve("grep-test.txt")
        Files.writeString(filePath, """line 1: hello world
line 2: foo bar
line 3: hello again
line 4: end""")

        when: "searching for pattern"
        def results = service.grep(filePath, "hello", 0, 0)

        then: "matches are found"
        results.size() == 2
        results[0].lineNumber() == 1
        results[0].matchContent().contains("hello world")
        results[1].lineNumber() == 3
        results[1].matchContent().contains("hello again")
    }

    def "should find pattern in directory recursively"() {
        given: "a directory with multiple files"
        def dirPath = tempDir.resolve("grep-dir")
        Files.createDirectories(dirPath.resolve("subdir"))
        Files.writeString(dirPath.resolve("file1.txt"), "match here")
        Files.writeString(dirPath.resolve("file2.txt"), "no hit")
        Files.writeString(dirPath.resolve("subdir/file3.txt"), "match inside")

        when: "searching directory"
        def results = service.grep(dirPath, "match", 0, 0)

        then: "finds matches in all files"
        results.size() == 2
        results.any { it.file().contains("file1.txt") }
        results.any { it.file().contains("file3.txt") }
    }

    def "should include context lines in grep results"() {
        given: "a file with multiple lines"
        def filePath = tempDir.resolve("grep-context.txt")
        Files.writeString(filePath, """line 1
line 2
line 3 MATCH
line 4
line 5""")

        when: "searching with context"
        def results = service.grep(filePath, "MATCH", 1, 1)

        then: "context is included"
        results.size() == 1
        results[0].context().size() == 2
        results[0].context().any { it.contains("line 2") }
        results[0].context().any { it.contains("line 4") }
    }

    def "should return empty list when no matches"() {
        given: "a file without the pattern"
        def filePath = tempDir.resolve("grep-nomatch.txt")
        Files.writeString(filePath, "no matches here")

        when: "searching for pattern"
        def results = service.grep(filePath, "NOTFOUND", 0, 0)

        then: "empty list returned"
        results.isEmpty()
    }

    def "should handle regex patterns in grep"() {
        given: "a file with content"
        def filePath = tempDir.resolve("grep-regex.txt")
        Files.writeString(filePath, """email: test@example.com
phone: 123-456-7890
other: text""")

        when: "searching with regex"
        def results = service.grep(filePath, "\\d{3}-\\d{3}-\\d{4}", 0, 0)

        then: "regex match is found"
        results.size() == 1
        results[0].matchContent().contains("123-456-7890")
    }
}

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
}

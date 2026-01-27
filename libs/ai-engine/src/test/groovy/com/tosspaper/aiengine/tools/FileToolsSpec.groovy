package com.tosspaper.aiengine.tools

import com.tosspaper.aiengine.vfs.FileInfo
import com.tosspaper.aiengine.vfs.GrepResult
import com.tosspaper.aiengine.vfs.ReadChunkResult
import com.tosspaper.aiengine.vfs.VirtualFilesystemService
import spock.lang.Specification
import spock.lang.Subject

import java.nio.file.Path

/**
 * Unit tests for FileTools.
 * Tests the embedded file tools used by the AI agent for document comparison.
 */
class FileToolsSpec extends Specification {

    VirtualFilesystemService vfs = Mock()

    @Subject
    FileTools fileTools

    Path workingDir = Path.of("/app/files/companies/1/po/PO-123")

    def setup() {
        fileTools = new FileTools(vfs)
        fileTools.setWorkingDirectory(workingDir)
    }

    // ==================== READ FILE TESTS ====================

    def "should read entire file"() {
        given: "VFS returns file content"
        def content = '{"test": "data"}'
        vfs.readFile(workingDir.resolve("po.json")) >> content

        when: "reading file"
        def result = fileTools.readFile("po.json")

        then: "content is returned"
        result == content
    }

    def "should resolve relative paths correctly"() {
        given: "VFS returns content"
        vfs.readFile(_) >> "content"

        when: "reading nested path"
        fileTools.readFile("invoice/doc-123.json")

        then: "path is resolved correctly"
        1 * vfs.readFile(workingDir.resolve("invoice/doc-123.json"))
    }

    // ==================== READ FILE CHUNK TESTS ====================

    def "should read file chunk with default limit"() {
        given: "VFS returns chunk"
        def chunkResult = new ReadChunkResult("chunk content", 0, 13, 100, true)
        vfs.readChunk(_, 0, 10000) >> chunkResult

        when: "reading chunk with zero limit"
        def result = fileTools.readFileChunk("large-file.json", 0, 0)

        then: "default limit is used"
        result == chunkResult
    }

    def "should read file chunk with specified offset and limit"() {
        given: "VFS returns chunk"
        def chunkResult = new ReadChunkResult("next chunk", 5000, 5000, 15000, true)
        vfs.readChunk(workingDir.resolve("data.json"), 5000, 5000) >> chunkResult

        when: "reading chunk"
        def result = fileTools.readFileChunk("data.json", 5000, 5000)

        then: "chunk is returned"
        result.offset() == 5000
        result.hasMore()
    }

    // ==================== WRITE FILE TESTS ====================

    def "should write file and return confirmation"() {
        given: "VFS accepts write"
        def path = workingDir.resolve("_results.json")
        vfs.writeFile(path, _) >> path

        when: "writing file"
        def result = fileTools.writeFile("_results.json", '{"results": []}')

        then: "confirmation message is returned"
        result.contains("_results.json")
        result.contains("characters")
    }

    def "should pass content to VFS correctly"() {
        given: "content to write"
        def content = '{"key": "value"}'

        when: "writing file"
        fileTools.writeFile("output.json", content)

        then: "VFS receives correct content"
        1 * vfs.writeFile(workingDir.resolve("output.json"), content)
    }

    // ==================== LIST DIRECTORY TESTS ====================

    def "should list current directory with empty path"() {
        given: "VFS returns directory listing"
        def listing = [FileInfo.file("po.json", 1024), FileInfo.directory("invoice")]
        vfs.listDirectory(workingDir) >> listing

        when: "listing with empty path"
        def result = fileTools.listDirectory("")

        then: "current directory is listed"
        result.size() == 2
    }

    def "should list current directory with dot path"() {
        given: "VFS returns directory listing"
        def listing = [FileInfo.file("po.json", 1024)]
        vfs.listDirectory(workingDir) >> listing

        when: "listing with dot"
        def result = fileTools.listDirectory(".")

        then: "current directory is listed"
        result.size() == 1
    }

    def "should list subdirectory"() {
        given: "VFS returns subdirectory listing"
        def listing = [FileInfo.file("doc-1.json", 512), FileInfo.file("doc-2.json", 768)]
        vfs.listDirectory(workingDir.resolve("invoice")) >> listing

        when: "listing subdirectory"
        def result = fileTools.listDirectory("invoice")

        then: "subdirectory contents returned"
        result.size() == 2
    }

    // ==================== GREP TESTS ====================

    def "should search for pattern in file"() {
        given: "VFS returns grep results"
        def grepResults = [GrepResult.of("po.json", 5, "vendor: Acme Corp")]
        vfs.grep(workingDir.resolve("po.json"), "Acme", 0, 0) >> grepResults

        when: "grepping"
        def result = fileTools.grep("Acme", "po.json", 0, 0)

        then: "results are returned"
        result.size() == 1
        result[0].lineNumber() == 5
    }

    def "should limit context lines to 5"() {
        when: "grepping with large context"
        fileTools.grep("pattern", "file.txt", 10, 10)

        then: "context is limited to 5"
        1 * vfs.grep(_, _, 5, 5) >> []
    }

    def "should handle negative context values"() {
        when: "grepping with negative context"
        fileTools.grep("pattern", "file.txt", -5, -3)

        then: "context is set to 0"
        1 * vfs.grep(_, _, 0, 0) >> []
    }

    // ==================== WORKING DIRECTORY TESTS ====================

    def "should throw exception when working directory not set"() {
        given: "FileTools without working directory"
        def tools = new FileTools(vfs)

        when: "reading file"
        tools.readFile("test.txt")

        then: "exception is thrown"
        thrown(IllegalStateException)
    }

    def "should return working directory"() {
        expect: "working directory is set"
        fileTools.getWorkingDirectory() == workingDir
    }

    def "should allow changing working directory"() {
        given: "new working directory"
        def newDir = Path.of("/app/files/companies/2/po/PO-456")

        when: "setting new directory"
        fileTools.setWorkingDirectory(newDir)

        then: "directory is updated"
        fileTools.getWorkingDirectory() == newDir
    }

    // ==================== PATH RESOLUTION TESTS ====================

    def "should normalize paths"() {
        given: "VFS accepts any path"
        vfs.readFile(_) >> "content"

        when: "reading with relative components"
        fileTools.readFile("subdir/../po.json")

        then: "path is normalized"
        1 * vfs.readFile({ Path p -> p.toString().endsWith("po.json") && !p.toString().contains("..") })
    }
}

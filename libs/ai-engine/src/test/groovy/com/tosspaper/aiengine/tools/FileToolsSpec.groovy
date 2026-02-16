package com.tosspaper.aiengine.tools

import com.tosspaper.aiengine.vfs.FileInfo
import com.tosspaper.aiengine.vfs.GrepResult
import com.tosspaper.aiengine.vfs.ReadChunkResult
import com.tosspaper.aiengine.vfs.VirtualFilesystemService
import org.springframework.ai.chat.client.ChatClient
import spock.lang.Specification
import spock.lang.Subject

import java.nio.file.Path

/**
 * Unit tests for FileTools.
 * Tests the embedded file tools used by the AI agent for document comparison.
 */
class FileToolsSpec extends Specification {

    VirtualFilesystemService vfs = Mock()
    ChatClient validationChatClient = Mock()

    @Subject
    FileTools fileTools

    Path workingDir = Path.of("/app/files/companies/1/po/PO-123")

    def setup() {
        fileTools = new FileTools(vfs, validationChatClient)
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
        def tools = new FileTools(vfs, validationChatClient)

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

    // ==================== CLEAR THREAD LOCAL TESTS ====================

    def "clearThreadLocalState should clear working directory"() {
        when: "clearing state"
        fileTools.clearThreadLocalState()

        then: "working directory is null"
        fileTools.getWorkingDirectory() == null
    }

    // ==================== PO ITEM COUNT TESTS ====================

    def "setPoItemCount should set count"() {
        when: "setting PO item count"
        fileTools.setPoItemCount(5)

        then: "no exception"
        noExceptionThrown()
    }

    // ==================== LIST DIRECTORY NULL PATH ====================

    def "should list current directory with null path"() {
        given: "VFS returns directory listing"
        def listing = [FileInfo.file("po.json", 1024)]
        vfs.listDirectory(workingDir) >> listing

        when: "listing with null"
        def result = fileTools.listDirectory(null)

        then: "current directory is listed"
        result.size() == 1
    }

    // ==================== CHECK PO INDEX AVAILABLE TESTS ====================

    def "checkPoIndexAvailable should return available when tracker is empty"() {
        given: "no tracker file exists"
        vfs.exists(_) >> false

        when:
        def result = fileTools.checkPoIndexAvailable(0)

        then:
        result.contains('"available": true')
        result.contains('"poIndex": 0')
    }

    def "checkPoIndexAvailable should return unavailable when index is used"() {
        given: "tracker has used index"
        vfs.exists(_) >> true
        vfs.readFile(_) >> '{"usedPoIndices": [0, 1], "matches": [{"docIndex": 0, "poIndex": 0, "matchedBy": "validated"}]}'

        when:
        def result = fileTools.checkPoIndexAvailable(0)

        then:
        result.contains('"available": false')
    }

    // ==================== LIST PO MATCHES TESTS ====================

    def "listPoMatches should return empty tracker when no file"() {
        given:
        vfs.exists(_) >> false

        when:
        def result = fileTools.listPoMatches()

        then:
        result.contains("usedPoIndices")
        result.contains("matches")
    }

    def "listPoMatches should return existing tracker data"() {
        given:
        vfs.exists(_) >> true
        vfs.readFile(_) >> '{"usedPoIndices": [0, 2], "matches": [{"docIndex": 0, "poIndex": 0, "matchedBy": "validated"}, {"docIndex": 1, "poIndex": 2, "matchedBy": "validated"}]}'

        when:
        def result = fileTools.listPoMatches()

        then:
        result.contains("0")
        result.contains("2")
    }

    // ==================== VALIDATE LINE ITEM MATCH TESTS ====================

    def "validateLineItemMatch should return invalid for negative poIndex"() {
        when:
        def result = fileTools.validateLineItemMatch(-1, "CODE", "Desc")

        then:
        !result.valid()
        result.actualItemCode() == "INVALID_INDEX"
    }

    def "validateLineItemMatch should return invalid when po.json has no items"() {
        given:
        vfs.readFile(_) >> '{"displayId": "PO-123"}'

        when:
        def result = fileTools.validateLineItemMatch(0, "CODE", "Desc")

        then:
        !result.valid()
        result.actualItemCode() == "ERROR"
    }

    def "validateLineItemMatch should return invalid when poIndex out of bounds"() {
        given:
        vfs.readFile(_) >> '{"items": [{"unitCode": "A", "name": "Widget"}]}'

        when:
        def result = fileTools.validateLineItemMatch(5, "CODE", "Desc")

        then:
        !result.valid()
        result.actualItemCode() == "OUT_OF_BOUNDS"
    }

    def "validateLineItemMatch should return valid for exact match"() {
        given:
        vfs.readFile(_) >> '{"items": [{"unitCode": "ITEM-001", "name": "Widget A"}]}'

        when:
        def result = fileTools.validateLineItemMatch(0, "ITEM-001", "Widget A")

        then:
        result.valid()
        result.actualItemCode() == "ITEM-001"
        result.actualDescription() == "Widget A"
    }

    def "validateLineItemMatch should return valid for case-insensitive match"() {
        given:
        vfs.readFile(_) >> '{"items": [{"unitCode": "item-001", "name": "widget a"}]}'

        when:
        def result = fileTools.validateLineItemMatch(0, "ITEM-001", "WIDGET A")

        then:
        result.valid()
    }

    def "validateLineItemMatch should return invalid when item codes differ"() {
        given:
        vfs.readFile(_) >> '{"items": [{"unitCode": "ITEM-002", "name": "Widget B"}]}'

        when:
        def result = fileTools.validateLineItemMatch(0, "ITEM-001", "Widget A")

        then:
        !result.valid()
    }

    def "validateLineItemMatch should handle exception gracefully"() {
        given:
        vfs.readFile(_) >> { throw new RuntimeException("File not found") }

        when:
        def result = fileTools.validateLineItemMatch(0, "CODE", "Desc")

        then:
        !result.valid()
        result.actualItemCode() == "ERROR"
    }

    // ==================== GET PO ITEMS LIST TESTS ====================

    def "getPoItemsList should return items from po.json"() {
        given:
        vfs.readFile(_) >> '{"items": [{"unitCode": "A1", "name": "Item One"}, {"unitCode": "B2", "name": "Item Two"}]}'

        when:
        def result = fileTools.getPoItemsList()

        then:
        result.size() == 2
        result[0].index() == 0
        result[0].itemCode() == "A1"
        result[0].description() == "Item One"
        result[1].index() == 1
    }

    def "getPoItemsList should return empty when no items"() {
        given:
        vfs.readFile(_) >> '{"displayId": "PO-123"}'

        when:
        def result = fileTools.getPoItemsList()

        then:
        result.isEmpty()
    }

    def "getPoItemsList should return empty on exception"() {
        given:
        vfs.readFile(_) >> { throw new RuntimeException("File not found") }

        when:
        def result = fileTools.getPoItemsList()

        then:
        result.isEmpty()
    }

    // ==================== GET USED PO INDICES TESTS ====================

    def "getUsedPoIndices should return empty set when no tracker"() {
        given:
        vfs.exists(_) >> false

        when:
        def result = fileTools.getUsedPoIndices()

        then:
        result.isEmpty()
    }

    def "getUsedPoIndices should return set from tracker"() {
        given:
        vfs.exists(_) >> true
        vfs.readFile(_) >> '{"usedPoIndices": [0, 3, 5], "matches": []}'

        when:
        def result = fileTools.getUsedPoIndices()

        then:
        result.size() == 3
        result.contains(0)
        result.contains(3)
        result.contains(5)
    }

    // ==================== CLEAR PO INDICES FROM TRACKER TESTS ====================

    def "clearPoIndicesFromTracker should remove specified indices"() {
        given:
        vfs.exists(_) >> true
        vfs.readFile(_) >> '{"usedPoIndices": [0, 1, 2], "matches": [{"docIndex": 0, "poIndex": 0, "matchedBy": "validated"}, {"docIndex": 1, "poIndex": 1, "matchedBy": "validated"}, {"docIndex": 2, "poIndex": 2, "matchedBy": "validated"}]}'

        when:
        fileTools.clearPoIndicesFromTracker([0, 2] as Set)

        then:
        1 * vfs.writeFile(_, { String json ->
            def parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map)
            parsed.usedPoIndices == [1] &&
            parsed.matches.size() == 1
        })
    }

    // ==================== WRITE VALIDATED MATCH TESTS ====================

    def "writeValidatedMatch should write when index available"() {
        given:
        vfs.exists(_) >> false

        when:
        def result = fileTools.writeValidatedMatch(0, 3)

        then:
        result == true
        1 * vfs.writeFile(_, { String json ->
            json.contains('"poIndex" : 3')
        })
    }

    def "writeValidatedMatch should return false when index already used"() {
        given:
        vfs.exists(_) >> true
        vfs.readFile(_) >> '{"usedPoIndices": [3], "matches": [{"docIndex": 0, "poIndex": 3, "matchedBy": "validated"}]}'

        when:
        def result = fileTools.writeValidatedMatch(1, 3)

        then:
        result == false
        0 * vfs.writeFile(_, _)
    }

    // ==================== READ FILE CHUNK NEGATIVE LIMIT ====================

    def "should read file chunk with negative limit using default"() {
        given:
        def chunkResult = new ReadChunkResult("data", 0, 100, 200, true)
        vfs.readChunk(_, 0, 10000) >> chunkResult

        when:
        def result = fileTools.readFileChunk("file.json", 0, -5)

        then:
        result == chunkResult
    }

    // ==================== PO MATCH TRACKER / PO MATCH DATA CLASSES ====================

    def "PoMatchTracker should have default empty lists"() {
        when:
        def tracker = new FileTools.PoMatchTracker()

        then:
        tracker.getUsedPoIndices().isEmpty()
        tracker.getMatches().isEmpty()
    }

    def "PoMatch should be constructable"() {
        when:
        def match = new FileTools.PoMatch(0, 3, "validated")

        then:
        match.getDocIndex() == 0
        match.getPoIndex() == 3
        match.getMatchedBy() == "validated"
    }

    def "PoMatch default constructor should work"() {
        when:
        def match = new FileTools.PoMatch()

        then:
        match.getDocIndex() == 0
        match.getPoIndex() == 0
        match.getMatchedBy() == null
    }

    // ==================== VALIDATE AND WRITE LINE ITEM (DEPRECATED) TESTS ====================

    def "validateAndWriteLineItem should reject negative poIndex"() {
        when:
        def result = fileTools.validateAndWriteLineItem(0, -1, "CODE", "Desc")

        then:
        result.contains('"written": false')
        result.contains("invalid")
    }

    def "validateAndWriteLineItem should reject when po items is null"() {
        given:
        vfs.readFile(_) >> '{"displayId": "PO-123"}'

        when:
        def result = fileTools.validateAndWriteLineItem(0, 0, "CODE", "Desc")

        then:
        result.contains('"written": false')
        result.contains("Cannot read PO items")
    }

    def "validateAndWriteLineItem should reject out of bounds index"() {
        given:
        vfs.readFile(_) >> '{"items": [{"unitCode": "A", "name": "Widget"}]}'
        vfs.exists(_) >> false

        when:
        def result = fileTools.validateAndWriteLineItem(0, 5, "CODE", "Desc")

        then:
        result.contains('"written": false')
        result.contains("out of bounds")
    }

    def "validateAndWriteLineItem should reject when index already matched"() {
        given:
        def trackerJson = '{"usedPoIndices": [0], "matches": [{"docIndex": 0, "poIndex": 0, "matchedBy": "validated"}]}'
        vfs.readFile(workingDir.resolve("po.json")) >> '{"items": [{"unitCode": "A", "name": "Widget"}]}'
        vfs.exists(workingDir.resolve("_po_matches.json")) >> true
        vfs.readFile(workingDir.resolve("_po_matches.json")) >> trackerJson

        when:
        def result = fileTools.validateAndWriteLineItem(1, 0, "A", "Widget")

        then:
        result.contains('"written": false')
        result.contains("already matched")
    }

    def "validateAndWriteLineItem should write when exact match"() {
        given:
        vfs.exists(_) >> false
        vfs.readFile(workingDir.resolve("po.json")) >> '{"items": [{"unitCode": "ITEM-001", "name": "Widget A"}]}'

        when:
        def result = fileTools.validateAndWriteLineItem(0, 0, "ITEM-001", "Widget A")

        then:
        result.contains('"written": true')
        result.contains('"docIndex": 0')
        result.contains('"poIndex": 0')
    }

    def "validateAndWriteLineItem should reject when item codes differ"() {
        given:
        vfs.exists(_) >> false
        vfs.readFile(workingDir.resolve("po.json")) >> '{"items": [{"unitCode": "ITEM-002", "name": "Widget B"}]}'

        when:
        def result = fileTools.validateAndWriteLineItem(0, 0, "ITEM-001", "Widget A")

        then:
        result.contains('"written": false')
        result.contains("ITEM-002")
    }

    def "validateAndWriteLineItem should handle exception"() {
        given:
        vfs.readFile(_) >> { throw new RuntimeException("IO error") }

        when:
        def result = fileTools.validateAndWriteLineItem(0, 0, "CODE", "Desc")

        then:
        result.contains('"written": false')
        result.contains("error")
    }

    // ==================== ESCAPE JSON TESTS (via validateAndWriteLineItem) ====================

    def "validateAndWriteLineItem should handle items without unitCode"() {
        given:
        vfs.exists(_) >> false
        vfs.readFile(workingDir.resolve("po.json")) >> '{"items": [{"name": "Widget A"}]}'

        when:
        def result = fileTools.validateAndWriteLineItem(0, 0, "", "Widget A")

        then:
        result.contains('"written": true')
    }

    // ==================== VALIDATE ITEM WITH SAME CODE DIFFERENT DESC ====================

    def "validateLineItemMatch should use validation model for same code different desc"() {
        given:
        vfs.readFile(_) >> '{"items": [{"unitCode": "ITEM-001", "name": "Widget Alpha"}]}'

        and: "validation model call stub"
        def promptSpec = Mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec)
        def callSpec = Mock(org.springframework.ai.chat.client.ChatClient.CallResponseSpec)
        validationChatClient.prompt() >> promptSpec
        promptSpec.user(_) >> promptSpec
        promptSpec.call() >> callSpec
        callSpec.content() >> "true"

        when:
        def result = fileTools.validateLineItemMatch(0, "ITEM-001", "Widget A")

        then:
        result.valid()
    }

    def "validateLineItemMatch should return invalid when validation model returns false"() {
        given:
        vfs.readFile(_) >> '{"items": [{"unitCode": "ITEM-001", "name": "Totally Different Product"}]}'

        and: "validation model says not a match"
        def promptSpec = Mock(org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec)
        def callSpec = Mock(org.springframework.ai.chat.client.ChatClient.CallResponseSpec)
        validationChatClient.prompt() >> promptSpec
        promptSpec.user(_) >> promptSpec
        promptSpec.call() >> callSpec
        callSpec.content() >> "false"

        when:
        def result = fileTools.validateLineItemMatch(0, "ITEM-001", "Widget A")

        then:
        !result.valid()
    }

    def "validateLineItemMatch should fallback to exact match when validation model fails"() {
        given:
        vfs.readFile(_) >> '{"items": [{"unitCode": "ITEM-001", "name": "Widget A"}]}'

        and: "validation model throws"
        validationChatClient.prompt() >> { throw new RuntimeException("Model unavailable") }

        when:
        def result = fileTools.validateLineItemMatch(0, "ITEM-001", "Widget A")

        then: "fallback to exact match succeeds"
        result.valid()
    }
}

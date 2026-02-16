package com.tosspaper.aiengine.agent

import com.tosspaper.models.domain.ComparisonContext
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.PurchaseOrder
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

/**
 * Unit tests for ActivityMapper.
 * Tests mapping of tool calls to user-friendly activity messages.
 */
class ActivityMapperSpec extends Specification {

    @Subject
    ActivityMapper mapper = new ActivityMapper()

    ComparisonContext context

    def setup() {
        def task = ExtractionTask.builder()
            .assignedId("doc-123")
            .companyId(1L)
            .poNumber("PO-456")
            .build()
        def po = PurchaseOrder.builder()
            .id("po-id")
            .displayId("PO-456")
            .build()
        context = new ComparisonContext(po, task)
    }

    // ==================== READ FILE MAPPING TESTS ====================

    def "should map po.json read to reviewing purchase order"() {
        given: "readFile arguments for PO"
        def args = [path: "po.json"]

        when: "mapping"
        def activity = mapper.map("readFile", args, context)

        then: "shows reviewing PO"
        activity.icon() == "📋"
        activity.message().contains("Reviewing")
        activity.message().contains("purchase order")
        activity.message().contains("PO-456")
    }

    def "should map invoice read to analyzing invoice"() {
        given: "readFile arguments for invoice"
        def args = [path: "invoice/inv-123.json"]

        when: "mapping"
        def activity = mapper.map("readFile", args, context)

        then: "shows analyzing invoice"
        activity.icon() == "📄"
        activity.message().contains("Analyzing")
        activity.message().contains("invoice")
    }

    def "should map delivery_slip read to analyzing delivery slip"() {
        given: "readFile arguments for delivery slip"
        def args = [path: "delivery_slip/ds-123.json"]

        when: "mapping"
        def activity = mapper.map("readFile", args, context)

        then: "shows analyzing delivery slip"
        activity.message().contains("delivery slip")
    }

    def "should map delivery_note read to analyzing delivery note"() {
        given: "readFile arguments for delivery note"
        def args = [path: "delivery_note/dn-123.json"]

        when: "mapping"
        def activity = mapper.map("readFile", args, context)

        then: "shows analyzing delivery note"
        activity.message().contains("delivery note")
    }

    def "should map _results.json read to reading results"() {
        given: "readFile arguments for results"
        def args = [path: "_results.json"]

        when: "mapping"
        def activity = mapper.map("readFile", args, context)

        then: "shows reading results"
        activity.icon() == "📊"
        activity.message().contains("results")
    }

    def "should map schema read to loading schema"() {
        given: "readFile arguments for schema"
        def args = [path: "comparison.schema.json"]

        when: "mapping"
        def activity = mapper.map("readFile", args, context)

        then: "shows loading schema"
        activity.icon() == "📋"
        activity.message().contains("schema")
    }

    def "should handle null context gracefully"() {
        given: "readFile arguments for PO with null context"
        def args = [path: "po.json"]

        when: "mapping without context"
        def activity = mapper.map("readFile", args, null)

        then: "shows placeholder"
        activity.message().contains("...")
    }

    // ==================== READ FILE CHUNK MAPPING TESTS ====================

    def "should map first chunk read same as full read"() {
        given: "readFileChunk arguments at offset 0"
        def args = [path: "po.json", offset: 0]

        when: "mapping"
        def activity = mapper.map("readFileChunk", args, context)

        then: "shows same as readFile"
        activity.message().contains("purchase order")
    }

    def "should map subsequent chunk reads to reading more"() {
        given: "readFileChunk arguments at offset > 0"
        def args = [path: "large-file.json", offset: 5000]

        when: "mapping"
        def activity = mapper.map("readFileChunk", args, context)

        then: "shows reading more"
        activity.icon() == "📄"
        activity.message().contains("Reading more")
    }

    // ==================== WRITE FILE MAPPING TESTS ====================

    def "should map _results.json write to saving results"() {
        given: "writeFile arguments for results"
        def args = [path: "_results.json"]

        when: "mapping"
        def activity = mapper.map("writeFile", args, context)

        then: "shows saving results"
        activity.icon() == "💾"
        activity.message().contains("Saving")
        activity.message().contains("comparison results")
    }

    def "should map _vendor_analysis write to saving vendor analysis"() {
        given: "writeFile arguments for vendor analysis"
        def args = [path: "_vendor_analysis.json"]

        when: "mapping"
        def activity = mapper.map("writeFile", args, context)

        then: "shows saving vendor analysis"
        activity.message().contains("vendor analysis")
    }

    def "should map _line_items write to saving line items"() {
        given: "writeFile arguments for line items"
        def args = [path: "_line_items.json"]

        when: "mapping"
        def activity = mapper.map("writeFile", args, context)

        then: "shows saving line items"
        activity.message().contains("line item")
    }

    def "should map generic write to saving file"() {
        given: "writeFile arguments for generic file"
        def args = [path: "custom-output.json"]

        when: "mapping"
        def activity = mapper.map("writeFile", args, context)

        then: "shows writing filename"
        activity.icon() == "💾"
        activity.message().contains("Writing")
        activity.message().contains("custom-output.json")
    }

    // ==================== LIST DIRECTORY MAPPING TESTS ====================

    def "should map empty path list to listing working directory"() {
        given: "listDirectory with empty path"
        def args = [path: ""]

        when: "mapping"
        def activity = mapper.map("listDirectory", args, context)

        then: "shows listing working directory"
        activity.icon() == "📁"
        activity.message().contains("working directory")
    }

    def "should map dot path list to listing working directory"() {
        given: "listDirectory with dot path"
        def args = [path: "."]

        when: "mapping"
        def activity = mapper.map("listDirectory", args, context)

        then: "shows listing working directory"
        activity.message().contains("working directory")
    }

    def "should map subdirectory list to listing path"() {
        given: "listDirectory with path"
        def args = [path: "invoice"]

        when: "mapping"
        def activity = mapper.map("listDirectory", args, context)

        then: "shows listing path"
        activity.message().contains("invoice")
    }

    // ==================== GREP MAPPING TESTS ====================

    def "should map grep to searching"() {
        given: "grep arguments"
        def args = [pattern: "Acme Corp", path: "po.json"]

        when: "mapping"
        def activity = mapper.map("grep", args, context)

        then: "shows searching"
        activity.icon() == "🔍"
        activity.message().contains("Searching")
        activity.message().contains("Acme Corp")
    }

    def "should truncate long grep patterns"() {
        given: "grep with long pattern"
        def longPattern = "a" * 50
        def args = [pattern: longPattern, path: "file.txt"]

        when: "mapping"
        def activity = mapper.map("grep", args, context)

        then: "pattern is truncated"
        activity.message().contains("...")
        activity.message().length() < 100
    }

    def "should handle empty grep pattern"() {
        given: "grep with empty pattern"
        def args = [pattern: "", path: "file.txt"]

        when: "mapping"
        def activity = mapper.map("grep", args, context)

        then: "shows generic search"
        activity.message().contains("documents")
    }

    // ==================== UNKNOWN TOOL MAPPING TESTS ====================

    def "should map unknown tool to processing"() {
        given: "unknown tool"
        def args = [:]

        when: "mapping"
        def activity = mapper.map("unknownTool", args, context)

        then: "shows processing"
        activity.icon() == "⚙️"
        activity.message().contains("Processing")
    }

    // ==================== SNAKE_CASE TOOL NAME TESTS ====================

    @Unroll
    def "should handle both camelCase and snake_case tool names: #toolName"() {
        given: "arguments"
        def args = [path: "po.json"]

        when: "mapping"
        def activity = mapper.map(toolName, args, context)

        then: "maps correctly"
        activity.message().contains("purchase order")

        where:
        toolName << ["readFile", "read_file"]
    }

    // ==================== NULL HANDLING TESTS ====================

    def "should handle null arguments"() {
        when: "mapping with null args"
        def activity = mapper.map("readFile", null, context)

        then: "returns generic file read"
        activity.message().contains("file")
    }

    def "should handle missing path argument"() {
        given: "args without path"
        def args = [other: "value"]

        when: "mapping"
        def activity = mapper.map("readFile", args, context)

        then: "returns generic file read"
        activity.message().contains("file")
    }

    // ==================== ADDITIONAL COVERAGE TESTS ====================

    def "should map analysis write to saving analysis"() {
        given: "writeFile arguments for generic analysis"
        def args = [path: "_analysis_output.json"]

        when: "mapping"
        def activity = mapper.map("writeFile", args, context)

        then: "shows saving analysis"
        activity.message().contains("Saving")
        activity.message().contains("analysis")
    }

    def "should extract filename from path with forward slash"() {
        given: "readFile arguments with forward slash path"
        def args = [path: "/some/nested/data.json"]

        when: "mapping"
        def activity = mapper.map("readFile", args, context)

        then: "shows just the filename"
        activity.message().contains("data.json")
    }

    def "should handle readFileChunk with string offset"() {
        given: "readFileChunk arguments with string offset"
        def args = [path: "file.txt", offset: "1000"]

        when: "mapping"
        def activity = mapper.map("readFileChunk", args, context)

        then: "shows reading more content"
        activity.message().contains("Reading more")
    }

    def "should handle readFileChunk with non-number offset"() {
        given: "readFileChunk arguments with non-number offset"
        def args = [path: "file.txt", offset: "not_a_number"]

        when: "mapping"
        def activity = mapper.map("readFileChunk", args, context)

        then: "falls back to default offset (0) and acts like first chunk"
        activity.message().contains("Reading")
    }

    def "should handle readFileChunk with unsupported offset type"() {
        given: "readFileChunk arguments with boolean offset"
        def args = [path: "file.txt", offset: true]

        when: "mapping"
        def activity = mapper.map("readFileChunk", args, context)

        then: "falls back to default offset (0)"
        activity.message().contains("Reading")
    }
}

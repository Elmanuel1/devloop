package com.tosspaper.aiengine.judge

import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.agents.judge.context.JudgmentContext
import org.springaicommunity.agents.judge.result.JudgmentStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class JsonObjectJudgeSpec extends Specification {

    @TempDir
    Path tempDir

    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    JsonObjectJudge judge

    ComparisonResultsReader reader

    def setup() {
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new JsonObjectJudge(reader)
    }

    def "should pass when file contains valid wrapper object"() {
        given: "a file with valid wrapper object"
        Files.writeString(tempDir.resolve("results.json"), '''
            {
                "documentId": "doc-123",
                "poId": "PO-456",
                "results": [{"type": "line_item"}, {"type": "vendor"}]
            }
        ''')

        and: "judgment context with workspace"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.pass()
        judgment.status() == JudgmentStatus.PASS
        judgment.reasoning().contains("Valid wrapper object with 2 results")
    }

    def "should pass for wrapper object with empty results array"() {
        given: "a file with wrapper object containing empty results"
        Files.writeString(tempDir.resolve("results.json"), '''
            {
                "documentId": "doc-123",
                "poId": "PO-456",
                "results": []
            }
        ''')

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.pass()
        judgment.reasoning().contains("0 results")
    }

    def "should fail when wrapper object missing documentId"() {
        given: "a file with wrapper object missing documentId"
        Files.writeString(tempDir.resolve("results.json"), '''
            {
                "poId": "PO-456",
                "results": []
            }
        ''')

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.status() == JudgmentStatus.FAIL
        judgment.reasoning().contains("documentId")
    }

    def "should fail when wrapper object missing poId"() {
        given: "a file with wrapper object missing poId"
        Files.writeString(tempDir.resolve("results.json"), '''
            {
                "documentId": "doc-123",
                "results": []
            }
        ''')

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.status() == JudgmentStatus.FAIL
        judgment.reasoning().contains("poId")
    }

    def "should fail when wrapper object missing results array"() {
        given: "a file with wrapper object missing results"
        Files.writeString(tempDir.resolve("results.json"), '''
            {
                "documentId": "doc-123",
                "poId": "PO-456"
            }
        ''')

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.status() == JudgmentStatus.FAIL
        judgment.reasoning().contains("results")
    }

    def "should fail when results is not an array"() {
        given: "a file with results as object instead of array"
        Files.writeString(tempDir.resolve("results.json"), '''
            {
                "documentId": "doc-123",
                "poId": "PO-456",
                "results": {"key": "value"}
            }
        ''')

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.status() == JudgmentStatus.FAIL
        judgment.reasoning().contains("Missing or invalid 'results' array")
    }

    def "should fail when file contains JSON array instead of object"() {
        given: "a file with JSON array"
        Files.writeString(tempDir.resolve("results.json"), '[{"type": "line_item"}]')

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.status() == JudgmentStatus.FAIL
        judgment.reasoning().contains("Expected JSON object")
    }

    def "should fail when file contains invalid JSON"() {
        given: "a file with invalid JSON"
        Files.writeString(tempDir.resolve("results.json"), '{broken json')

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.status() == JudgmentStatus.FAIL
        judgment.reasoning().contains("Invalid JSON")
    }

    def "should fail when file does not exist"() {
        given: "no file created"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.status() == JudgmentStatus.FAIL
        judgment.reasoning().contains("File not found")
    }

    def "should fail when file is empty"() {
        given: "an empty file"
        Files.writeString(tempDir.resolve("results.json"), '')

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.reasoning().contains("empty")
    }
}

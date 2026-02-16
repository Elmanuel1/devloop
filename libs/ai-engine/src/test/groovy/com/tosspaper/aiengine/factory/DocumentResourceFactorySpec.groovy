package com.tosspaper.aiengine.factory

import com.tosspaper.models.domain.DocumentType
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class DocumentResourceFactorySpec extends Specification {

    @TempDir
    Path tempDir

    @Subject
    DocumentResourceFactory factory = new DocumentResourceFactory()

    def setup() {
        // Set schemasBasePath via reflection
        def field = DocumentResourceFactory.getDeclaredField("schemasBasePath")
        field.setAccessible(true)
        field.set(factory, tempDir.toString())
    }

    def "loadSchema should load schema file for document type"() {
        given: "schema file exists"
            def schemasDir = tempDir.resolve("schemas")
            Files.createDirectories(schemasDir)
            Files.writeString(schemasDir.resolve("invoice.schema.json"), '{"type": "object"}')

        when:
            def result = factory.loadSchema(DocumentType.INVOICE)

        then:
            result == '{"type": "object"}'
    }

    def "loadSchema should cache schema on second call"() {
        given: "schema file exists"
            def schemasDir = tempDir.resolve("schemas")
            Files.createDirectories(schemasDir)
            Files.writeString(schemasDir.resolve("invoice.schema.json"), '{"type": "object"}')

        when: "loading schema twice"
            def result1 = factory.loadSchema(DocumentType.INVOICE)
            // Delete the file to verify cache is used
            Files.delete(schemasDir.resolve("invoice.schema.json"))
            def result2 = factory.loadSchema(DocumentType.INVOICE)

        then:
            result1 == '{"type": "object"}'
            result2 == '{"type": "object"}'
    }

    def "loadSchema should throw when file not found"() {
        when:
            factory.loadSchema(DocumentType.INVOICE)

        then:
            thrown(RuntimeException)
    }

    def "loadGenerationPrompt should load prompt file"() {
        given: "prompt file exists"
            def promptsDir = tempDir.resolve("prompts")
            Files.createDirectories(promptsDir)
            Files.writeString(promptsDir.resolve("invoice.prompt"), "Generate invoice data from {rawData}")

        when:
            def result = factory.loadGenerationPrompt(DocumentType.INVOICE)

        then:
            result == "Generate invoice data from {rawData}"
    }

    def "loadGenerationPrompt should throw when file not found"() {
        when:
            factory.loadGenerationPrompt(DocumentType.INVOICE)

        then:
            thrown(RuntimeException)
    }

    def "loadEvaluationPrompt should load evaluation prompt file"() {
        given: "evaluation prompt file exists"
            def promptsDir = tempDir.resolve("prompts")
            Files.createDirectories(promptsDir)
            Files.writeString(promptsDir.resolve("invoice.evaluation.prompt"), "Evaluate {schema}")

        when:
            def result = factory.loadEvaluationPrompt(DocumentType.INVOICE)

        then:
            result == "Evaluate {schema}"
    }

    def "loadEvaluationPrompt should throw when file not found"() {
        when:
            factory.loadEvaluationPrompt(DocumentType.INVOICE)

        then:
            thrown(RuntimeException)
    }

    def "loadPoMatchEvaluationPrompt should load PO match prompt"() {
        given: "PO match prompt file exists"
            def promptsDir = tempDir.resolve("prompts")
            Files.createDirectories(promptsDir)
            Files.writeString(promptsDir.resolve("po-match.evaluation.prompt"), "Evaluate PO match")

        when:
            def result = factory.loadPoMatchEvaluationPrompt()

        then:
            result == "Evaluate PO match"
    }

    def "loadPoMatchEvaluationPrompt should throw when file not found"() {
        when:
            factory.loadPoMatchEvaluationPrompt()

        then:
            thrown(RuntimeException)
    }

    def "loadSchema should load delivery_slip schema"() {
        given:
            def schemasDir = tempDir.resolve("schemas")
            Files.createDirectories(schemasDir)
            Files.writeString(schemasDir.resolve("delivery_slip.schema.json"), '{"type": "delivery"}')

        when:
            def result = factory.loadSchema(DocumentType.DELIVERY_SLIP)

        then:
            result == '{"type": "delivery"}'
    }

    def "loadGenerationPrompt should load delivery_slip prompt"() {
        given:
            def promptsDir = tempDir.resolve("prompts")
            Files.createDirectories(promptsDir)
            Files.writeString(promptsDir.resolve("delivery_slip.prompt"), "Generate delivery slip")

        when:
            def result = factory.loadGenerationPrompt(DocumentType.DELIVERY_SLIP)

        then:
            result == "Generate delivery slip"
    }
}

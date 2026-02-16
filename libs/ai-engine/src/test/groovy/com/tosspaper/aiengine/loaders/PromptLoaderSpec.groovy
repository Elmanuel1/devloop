package com.tosspaper.aiengine.loaders

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class PromptLoaderSpec extends Specification {

    @TempDir
    Path tempDir

    @Subject
    PromptLoader promptLoader

    def setup() {
        promptLoader = new PromptLoader()
        // Set the private field via reflection
        def field = PromptLoader.getDeclaredField("schemasBasePath")
        field.setAccessible(true)
        field.set(promptLoader, tempDir.toString())
    }

    def "loadPrompt should read default prompt file"() {
        given: "a prompt file exists"
            def promptDir = tempDir.resolve("prompts")
            Files.createDirectories(promptDir)
            Files.writeString(promptDir.resolve("extraction.prompt"), "Extract all fields from document")

        when: "loading the prompt"
            def result = promptLoader.loadPrompt()

        then: "prompt content is returned"
            result == "Extract all fields from document"
    }

    def "loadPrompt should cache result on second call"() {
        given: "a prompt file exists"
            def promptDir = tempDir.resolve("prompts")
            Files.createDirectories(promptDir)
            Files.writeString(promptDir.resolve("extraction.prompt"), "Extract all fields")

        when: "loading the prompt twice"
            def first = promptLoader.loadPrompt()
            def second = promptLoader.loadPrompt()

        then: "same cached value returned"
            first == second
            first == "Extract all fields"
    }

    def "loadPrompt should throw RuntimeException when file not found"() {
        when: "loading a nonexistent prompt"
            promptLoader.loadPrompt()

        then: "exception is thrown"
            def ex = thrown(RuntimeException)
            ex.message.contains("Failed to load prompt")
    }

    def "loadPrompt with name should read named prompt file"() {
        given: "a named prompt file exists"
            def promptDir = tempDir.resolve("prompts")
            Files.createDirectories(promptDir)
            Files.writeString(promptDir.resolve("invoice.prompt"), "Extract invoice fields")

        when: "loading the named prompt"
            def result = promptLoader.loadPrompt("invoice")

        then: "named prompt content is returned"
            result == "Extract invoice fields"
    }

    def "loadPrompt with name should cache result"() {
        given: "a named prompt file exists"
            def promptDir = tempDir.resolve("prompts")
            Files.createDirectories(promptDir)
            Files.writeString(promptDir.resolve("po.prompt"), "Extract PO fields")

        when: "loading the named prompt twice"
            def first = promptLoader.loadPrompt("po")
            def second = promptLoader.loadPrompt("po")

        then: "cached value returned"
            first == second
            first == "Extract PO fields"
    }

    def "loadPrompt with name should throw RuntimeException when file not found"() {
        when: "loading a nonexistent named prompt"
            promptLoader.loadPrompt("nonexistent")

        then: "exception is thrown"
            def ex = thrown(RuntimeException)
            ex.message.contains("Failed to load prompt 'nonexistent'")
    }
}

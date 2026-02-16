package com.tosspaper.aiengine.loaders

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class JsonSchemaLoaderSpec extends Specification {

    @TempDir
    Path tempDir

    @Subject
    JsonSchemaLoader schemaLoader

    def setup() {
        schemaLoader = new JsonSchemaLoader()
        def field = JsonSchemaLoader.getDeclaredField("schemasBasePath")
        field.setAccessible(true)
        field.set(schemaLoader, tempDir.toString())
    }

    def "loadSchema should read default schema file"() {
        given: "a schema file exists"
            def schemaDir = tempDir.resolve("schemas")
            Files.createDirectories(schemaDir)
            Files.writeString(schemaDir.resolve("extraction.json"), '{"type": "object"}')

        when: "loading the schema"
            def result = schemaLoader.loadSchema()

        then: "schema content is returned"
            result == '{"type": "object"}'
    }

    def "loadSchema should cache result on second call"() {
        given: "a schema file exists"
            def schemaDir = tempDir.resolve("schemas")
            Files.createDirectories(schemaDir)
            Files.writeString(schemaDir.resolve("extraction.json"), '{"type": "object"}')

        when: "loading the schema twice"
            def first = schemaLoader.loadSchema()
            def second = schemaLoader.loadSchema()

        then: "same cached value returned"
            first == second
            first == '{"type": "object"}'
    }

    def "loadSchema should throw RuntimeException when file not found"() {
        when: "loading a nonexistent schema"
            schemaLoader.loadSchema()

        then: "exception is thrown"
            def ex = thrown(RuntimeException)
            ex.message.contains("Failed to load schema")
    }

    def "loadSchema with name should read named schema file"() {
        given: "a named schema file exists"
            def schemaDir = tempDir.resolve("schemas")
            Files.createDirectories(schemaDir)
            Files.writeString(schemaDir.resolve("invoice.json"), '{"type": "object", "title": "Invoice"}')

        when: "loading the named schema"
            def result = schemaLoader.loadSchema("invoice")

        then: "named schema content is returned"
            result == '{"type": "object", "title": "Invoice"}'
    }

    def "loadSchema with name should cache result"() {
        given: "a named schema file exists"
            def schemaDir = tempDir.resolve("schemas")
            Files.createDirectories(schemaDir)
            Files.writeString(schemaDir.resolve("po.json"), '{"title": "PO"}')

        when: "loading the named schema twice"
            def first = schemaLoader.loadSchema("po")
            def second = schemaLoader.loadSchema("po")

        then: "cached value returned"
            first == second
            first == '{"title": "PO"}'
    }

    def "loadSchema with name should throw RuntimeException when file not found"() {
        when: "loading a nonexistent named schema"
            schemaLoader.loadSchema("nonexistent")

        then: "exception is thrown"
            def ex = thrown(RuntimeException)
            ex.message.contains("Failed to load schema 'nonexistent'")
    }

    def "getSchemaPath should return correct path"() {
        when: "getting schema path"
            def path = schemaLoader.getSchemaPath("invoice")

        then: "correct path is returned"
            path.toString().endsWith("schemas/invoice.json")
    }
}

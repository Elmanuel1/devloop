package com.tosspaper.aiengine.judge

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests that validate judges work correctly with real agent output.
 * Uses actual results file from production to ensure schema compliance.
 */
class ComparisonJuryIntegrationSpec extends Specification {

    @TempDir
    Path tempDir

    ObjectMapper objectMapper = new ObjectMapper()

    def "should pass all judges with real agent output"() {
        given: "real results file from production"
        def resultsContent = getClass().getResourceAsStream('/comparison-results-sample.json').text
        def resultsPath = tempDir.resolve("results.json")
        Files.writeString(resultsPath, resultsContent)

        and: "schema path - navigate to project root from working directory"
        def projectRoot = Path.of(System.getProperty("user.dir")).parent.parent
        def schemaPath = projectRoot.resolve("schema-prompts/schemas/comparison.json")

        and: "jury factory"
        def juryFactory = new ComparisonJuryFactory(objectMapper)

        when: "running verification with 1 expected line item"
        def verdict = juryFactory.runVerification(resultsPath, schemaPath, 1, tempDir)

        then: "aggregated verdict passes"
        verdict.aggregated().pass()

        and: "each individual judge passes"
        verdict.individualByName().each { name, judgment ->
            assert judgment.pass() : "Judge '$name' failed: ${judgment.reasoning()}"
        }
    }

    def "should have all 6 judges in the jury"() {
        given: "jury factory"
        def juryFactory = new ComparisonJuryFactory(objectMapper)

        and: "real results file"
        def resultsContent = getClass().getResourceAsStream('/comparison-results-sample.json').text
        def resultsPath = tempDir.resolve("results.json")
        Files.writeString(resultsPath, resultsContent)

        and: "schema path - navigate to project root from working directory"
        def projectRoot = Path.of(System.getProperty("user.dir")).parent.parent
        def schemaPath = projectRoot.resolve("schema-prompts/schemas/comparison.json")

        when: "running verification"
        def verdict = juryFactory.runVerification(resultsPath, schemaPath, 1, tempDir)

        then: "all 6 judges are present"
        verdict.individualByName().keySet().containsAll([
            "json-object",
            "json-schema",
            "required-fields",
            "index-validation",
            "po-index-uniqueness",
            "contact-coverage"
        ])
    }
}

package com.tosspaper.aiengine.repository

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.jooq.tables.records.DocumentPartComparisonsRecord
import org.jooq.*
import spock.lang.Specification
import spock.lang.Subject

import static com.tosspaper.models.jooq.Tables.DOCUMENT_PART_COMPARISONS
import static com.tosspaper.models.jooq.Tables.EXTRACTION_TASK

class DocumentPartComparisonRepositoryImplSpec extends Specification {

    DSLContext dsl = Mock()
    ObjectMapper objectMapper = Mock()

    @Subject
    DocumentPartComparisonRepositoryImpl repository = new DocumentPartComparisonRepositoryImpl(dsl, objectMapper)

    // ==================== UPSERT TESTS ====================
    // NOTE: The upsert() method uses PostgreSQL's ON CONFLICT ... DO UPDATE pattern.
    // Similar to JooqExtractionTaskRepositoryImpl.save(), the jOOQ upsert chain is
    // extremely complex to mock properly. The upsert logic is thoroughly tested via
    // integration tests. Unit tests focus on error handling and simpler query methods.

    def "upsert should throw RuntimeException when serialization fails"() {
        given:
        def ctx = Mock(DSLContext)
        def comparison = new Comparison()

        and:
        objectMapper.writeValueAsString(comparison) >> { throw new JsonProcessingException("Serialization error") {} }

        when:
        repository.upsert(ctx, "extract-123", comparison)

        then:
        def ex = thrown(RuntimeException)
        ex.message == "Failed to serialize comparison result"
    }

    // ==================== DELETE TESTS ====================

    def "deleteByExtractionId should return deleted count"() {
        given:
        def ctx = Mock(DSLContext)
        ctx.deleteFrom(_) >> Mock(DeleteUsingStep) {
            where(_ as Condition) >> Mock(DeleteConditionStep) {
                execute() >> 2
            }
        }

        when:
        def result = repository.deleteByExtractionId(ctx, "extract-123")

        then:
        result == 2
    }

    def "deleteByExtractionId should return zero when nothing deleted"() {
        given:
        def ctx = Mock(DSLContext)
        ctx.deleteFrom(_) >> Mock(DeleteUsingStep) {
            where(_ as Condition) >> Mock(DeleteConditionStep) {
                execute() >> 0
            }
        }

        when:
        def result = repository.deleteByExtractionId(ctx, "nonexistent")

        then:
        result == 0
    }

    // ==================== FIND BY EXTRACTION ID TESTS ====================

    def "findByExtractionId should return comparison when found"() {
        given:
        def record = Mock(DocumentPartComparisonsRecord)
        record.getResultData() >> org.jooq.JSONB.jsonb('{"documentId":"doc-1"}')
        record.getExtractionId() >> "extract-123"

        def selectStep = Mock(SelectWhereStep)
        def conditionStep = Mock(SelectConditionStep)

        dsl.selectFrom(DOCUMENT_PART_COMPARISONS) >> selectStep
        selectStep.where(_ as Condition) >> conditionStep
        conditionStep.fetchOptional() >> Optional.of(record)

        and:
        def comparison = new Comparison()
        comparison.setDocumentId("doc-1")
        objectMapper.readValue('{"documentId":"doc-1"}', Comparison.class) >> comparison

        when:
        def result = repository.findByExtractionId("extract-123")

        then:
        result.isPresent()
        result.get().documentId == "doc-1"
    }

    def "findByExtractionId should return empty when not found"() {
        given:
        def selectStep = Mock(SelectWhereStep)
        def conditionStep = Mock(SelectConditionStep)

        dsl.selectFrom(DOCUMENT_PART_COMPARISONS) >> selectStep
        selectStep.where(_ as Condition) >> conditionStep
        conditionStep.fetchOptional() >> Optional.empty()

        when:
        def result = repository.findByExtractionId("nonexistent")

        then:
        result.isEmpty()
    }

    def "findByExtractionId should return empty when result_data is null"() {
        given: "record has null result_data"
        def record = Mock(DocumentPartComparisonsRecord)
        record.getResultData() >> null
        record.getExtractionId() >> "extract-123"

        def selectStep = Mock(SelectWhereStep)
        def conditionStep = Mock(SelectConditionStep)

        dsl.selectFrom(DOCUMENT_PART_COMPARISONS) >> selectStep
        selectStep.where(_ as Condition) >> conditionStep
        conditionStep.fetchOptional() >> Optional.of(record)

        when:
        def result = repository.findByExtractionId("extract-123")

        then: "Optional.map returns empty when toComparison returns null"
        result.isEmpty()
    }

    def "findByExtractionId should return empty when result_data.data() is null"() {
        given: "record has JSONB with null data"
        def record = Mock(DocumentPartComparisonsRecord)
        // JSONB.jsonb(null) creates a JSONB instance with null data
        record.getResultData() >> org.jooq.JSONB.jsonb(null)
        record.getExtractionId() >> "extract-123"

        def selectStep = Mock(SelectWhereStep)
        def conditionStep = Mock(SelectConditionStep)

        dsl.selectFrom(DOCUMENT_PART_COMPARISONS) >> selectStep
        selectStep.where(_ as Condition) >> conditionStep
        conditionStep.fetchOptional() >> Optional.of(record)

        when:
        def result = repository.findByExtractionId("extract-123")

        then: "Optional.map returns empty when toComparison returns null"
        result.isEmpty()
    }

    def "findByExtractionId should throw when deserialization fails"() {
        given:
        def record = Mock(DocumentPartComparisonsRecord)
        record.getResultData() >> org.jooq.JSONB.jsonb('{"bad":"json"}')
        record.getExtractionId() >> "extract-123"

        def selectStep = Mock(SelectWhereStep)
        def conditionStep = Mock(SelectConditionStep)

        dsl.selectFrom(DOCUMENT_PART_COMPARISONS) >> selectStep
        selectStep.where(_ as Condition) >> conditionStep
        conditionStep.fetchOptional() >> Optional.of(record)

        and:
        objectMapper.readValue('{"bad":"json"}', Comparison.class) >> { throw new JsonProcessingException("Parse error") {} }

        when:
        repository.findByExtractionId("extract-123")

        then:
        def ex = thrown(RuntimeException)
        ex.message == "Failed to deserialize comparison result"
    }

    // ==================== FIND BY ASSIGNED ID TESTS ====================

    def "findByAssignedId should return comparison when found"() {
        given: "an assigned ID and company ID"
            def assignedId = "attach-123"
            def companyId = 1L

        and: "select chain with join"
            def selectStep = Mock(SelectSelectStep)
            def fromStep = Mock(SelectJoinStep)
            def joinStep = Mock(SelectOnStep)
            def onStep = Mock(SelectOnConditionStep)
            def conditionStep = Mock(SelectConditionStep)
            def record = Mock(Record)
            def comparisonRecord = Mock(DocumentPartComparisonsRecord)

        and: "mock jOOQ select chain with join"
            dsl.select(_) >> selectStep
            selectStep.from(DOCUMENT_PART_COMPARISONS) >> fromStep
            fromStep.join(EXTRACTION_TASK) >> joinStep
            joinStep.on(_) >> onStep
            onStep.where(_) >> conditionStep
            conditionStep.and(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)
            record.into(DOCUMENT_PART_COMPARISONS) >> comparisonRecord

        and: "mock deserialization"
            comparisonRecord.getResultData() >> org.jooq.JSONB.jsonb('{"documentId":"doc-1"}')
            comparisonRecord.getExtractionId() >> "extract-123"
            def comparison = new Comparison()
            comparison.setDocumentId("doc-1")
            objectMapper.readValue('{"documentId":"doc-1"}', Comparison.class) >> comparison

        when: "finding by assigned ID"
            def result = repository.findByAssignedId(assignedId, companyId)

        then: "comparison is returned"
            result.isPresent()
            result.get().documentId == "doc-1"
    }

    def "findByAssignedId should return empty when not found"() {
        given: "a non-existent assigned ID and company ID"
            def assignedId = "nonexistent"
            def companyId = 1L

        and: "select chain with join"
            def selectStep = Mock(SelectSelectStep)
            def fromStep = Mock(SelectJoinStep)
            def joinStep = Mock(SelectOnStep)
            def onStep = Mock(SelectOnConditionStep)
            def conditionStep = Mock(SelectConditionStep)

        and: "mock jOOQ select chain with join"
            dsl.select(_) >> selectStep
            selectStep.from(DOCUMENT_PART_COMPARISONS) >> fromStep
            fromStep.join(EXTRACTION_TASK) >> joinStep
            joinStep.on(_) >> onStep
            onStep.where(_) >> conditionStep
            conditionStep.and(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.empty()

        when: "finding by assigned ID"
            def result = repository.findByAssignedId(assignedId, companyId)

        then: "empty is returned"
            result.isEmpty()
    }

    def "findByAssignedId should return empty when result_data is null"() {
        given: "an assigned ID and company ID"
            def assignedId = "attach-123"
            def companyId = 1L

        and: "select chain with join"
            def selectStep = Mock(SelectSelectStep)
            def fromStep = Mock(SelectJoinStep)
            def joinStep = Mock(SelectOnStep)
            def onStep = Mock(SelectOnConditionStep)
            def conditionStep = Mock(SelectConditionStep)
            def record = Mock(Record)
            def comparisonRecord = Mock(DocumentPartComparisonsRecord)

        and: "mock jOOQ select chain with join"
            dsl.select(_) >> selectStep
            selectStep.from(DOCUMENT_PART_COMPARISONS) >> fromStep
            fromStep.join(EXTRACTION_TASK) >> joinStep
            joinStep.on(_) >> onStep
            onStep.where(_) >> conditionStep
            conditionStep.and(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)
            record.into(DOCUMENT_PART_COMPARISONS) >> comparisonRecord

        and: "mock null result data"
            comparisonRecord.getResultData() >> null
            comparisonRecord.getExtractionId() >> "extract-123"

        when: "finding by assigned ID"
            def result = repository.findByAssignedId(assignedId, companyId)

        then: "empty is returned when toComparison returns null"
            result.isEmpty()
    }

    def "findByAssignedId should throw when deserialization fails"() {
        given: "an assigned ID and company ID"
            def assignedId = "attach-123"
            def companyId = 1L

        and: "select chain with join"
            def selectStep = Mock(SelectSelectStep)
            def fromStep = Mock(SelectJoinStep)
            def joinStep = Mock(SelectOnStep)
            def onStep = Mock(SelectOnConditionStep)
            def conditionStep = Mock(SelectConditionStep)
            def record = Mock(Record)
            def comparisonRecord = Mock(DocumentPartComparisonsRecord)

        and: "mock jOOQ select chain with join"
            dsl.select(_) >> selectStep
            selectStep.from(DOCUMENT_PART_COMPARISONS) >> fromStep
            fromStep.join(EXTRACTION_TASK) >> joinStep
            joinStep.on(_) >> onStep
            onStep.where(_) >> conditionStep
            conditionStep.and(_) >> conditionStep
            conditionStep.fetchOptional() >> Optional.of(record)
            record.into(DOCUMENT_PART_COMPARISONS) >> comparisonRecord

        and: "mock failed deserialization"
            comparisonRecord.getResultData() >> org.jooq.JSONB.jsonb('{"bad":"json"}')
            comparisonRecord.getExtractionId() >> "extract-123"
            objectMapper.readValue('{"bad":"json"}', Comparison.class) >> { throw new JsonProcessingException("Parse error") {} }

        when: "finding by assigned ID"
            repository.findByAssignedId(assignedId, companyId)

        then: "exception is thrown"
            def ex = thrown(RuntimeException)
            ex.message == "Failed to deserialize comparison result"
    }

}

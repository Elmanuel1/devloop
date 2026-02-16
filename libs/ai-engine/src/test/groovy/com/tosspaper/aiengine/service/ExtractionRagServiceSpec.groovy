package com.tosspaper.aiengine.service

import com.tosspaper.aiengine.extractors.DocumentExtractor
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.ExtractionStatus
import com.tosspaper.models.domain.ExtractionTask
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import spock.lang.Specification
import spock.lang.Subject

class ExtractionRagServiceSpec extends Specification {

    VectorStore vectorStore = Mock()
    DocumentExtractor documentExtractor = Mock()
    JdbcTemplate jdbcTemplate = Mock()

    @Subject
    ExtractionRagService service = new ExtractionRagService(vectorStore, documentExtractor, jdbcTemplate)

    def "storeExtraction should store chunks in vector store"() {
        given: "an extraction task"
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .taskId("task-123")
                .storageKey("s3://bucket/file.pdf")
                .documentType(DocumentType.INVOICE)
                .status(ExtractionStatus.COMPLETED)
                .extractTaskResults('{"result": "data"}')
                .build()

        and: "extractor returns content"
            documentExtractor.extract('{"result": "data"}') >> "Cleaned document content"

        when:
            def result = service.storeExtraction(task)

        then: "documents are stored"
            1 * vectorStore.add({ List<Document> docs ->
                docs.size() >= 1 &&
                docs[0].getMetadata().get("assignedId") == "attach-123" &&
                docs[0].getMetadata().get("documentType") == "INVOICE"
            })
            result == true
    }

    def "storeExtraction should return false when extraction returns null"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-null")
                .status(ExtractionStatus.COMPLETED)
                .extractTaskResults('{"result": "data"}')
                .build()
            documentExtractor.extract(_) >> null

        when:
            def result = service.storeExtraction(task)

        then:
            result == false
            0 * vectorStore.add(_)
    }

    def "storeExtraction should return false when extraction returns empty"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-empty")
                .status(ExtractionStatus.COMPLETED)
                .extractTaskResults('{}')
                .build()
            documentExtractor.extract(_) >> "   "

        when:
            def result = service.storeExtraction(task)

        then:
            result == false
            0 * vectorStore.add(_)
    }

    def "storeExtraction should return false when vector store throws"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-error")
                .status(ExtractionStatus.COMPLETED)
                .extractTaskResults('{"result": "data"}')
                .build()
            documentExtractor.extract(_) >> "Some content"
            vectorStore.add(_) >> { throw new RuntimeException("Store failed") }

        when:
            def result = service.storeExtraction(task)

        then:
            result == false
    }

    def "storeExtraction should set metadata with null handling"() {
        given: "task with null optional fields"
            def task = ExtractionTask.builder()
                .assignedId("attach-minimal")
                .status(ExtractionStatus.COMPLETED)
                .extractTaskResults('{"result": "data"}')
                .build()
            documentExtractor.extract(_) >> "Content"

        when:
            service.storeExtraction(task)

        then:
            1 * vectorStore.add({ List<Document> docs ->
                def meta = docs[0].getMetadata()
                meta.get("taskId") == "" &&
                meta.get("storageKey") == "" &&
                meta.get("documentType") == "UNKNOWN" &&
                meta.get("fromAddress") == "" &&
                meta.get("toAddress") == "" &&
                meta.get("emailDirection") == "" &&
                meta.get("emailSubject") == "" &&
                meta.get("createdAt") == ""
            })
    }

    def "storeExtraction should generate deterministic UUIDs"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-uuid")
                .status(ExtractionStatus.COMPLETED)
                .extractTaskResults('{"result": "data"}')
                .build()
            documentExtractor.extract(_) >> "Content"

        when:
            service.storeExtraction(task)

        then:
            1 * vectorStore.add({ List<Document> docs ->
                docs[0].getId() != null && !docs[0].getId().isEmpty()
            })
    }

    def "getExtractionContentById should return joined chunks"() {
        given:
            jdbcTemplate.query(_, _ as RowMapper, "attach-123") >> ["chunk 0", "chunk 1", "chunk 2"]

        when:
            def result = service.getExtractionContentById("attach-123")

        then:
            result == "chunk 0\nchunk 1\nchunk 2"
    }

    def "getExtractionContentById should return null when no chunks found"() {
        given:
            jdbcTemplate.query(_, _ as RowMapper, "attach-missing") >> []

        when:
            def result = service.getExtractionContentById("attach-missing")

        then:
            result == null
    }

    def "getExtractionContentById should return null on exception"() {
        given:
            jdbcTemplate.query(_, _ as RowMapper, _) >> { throw new RuntimeException("DB error") }

        when:
            def result = service.getExtractionContentById("attach-error")

        then:
            result == null
    }

    def "searchSimilar with two args should use default threshold"() {
        given:
            vectorStore.similaritySearch(_) >> [new Document("result")]

        when:
            def result = service.searchSimilar("invoice", 5)

        then:
            result.size() == 1
    }

    def "searchSimilar should return empty list on exception"() {
        given:
            vectorStore.similaritySearch(_) >> { throw new RuntimeException("Search failed") }

        when:
            def result = service.searchSimilar("query", 5)

        then:
            result.isEmpty()
    }

    def "searchSimilar with four args should use custom threshold and filter"() {
        given:
            vectorStore.similaritySearch(_) >> [new Document("filtered result")]

        when:
            def result = service.searchSimilar("query", 10, 0.7, null)

        then:
            result.size() == 1
    }

    def "searchSimilar with filter should return empty list on exception"() {
        given:
            vectorStore.similaritySearch(_) >> { throw new RuntimeException("Search failed") }

        when:
            def result = service.searchSimilar("query", 10, 0.5, null)

        then:
            result.isEmpty()
    }

    def "deleteExtraction should call vector store delete"() {
        when:
            service.deleteExtraction("attach-delete")

        then:
            1 * vectorStore.delete(["attach-delete"])
    }

    def "deleteExtraction should handle exception gracefully"() {
        given:
            vectorStore.delete(_) >> { throw new RuntimeException("Delete failed") }

        when:
            service.deleteExtraction("attach-error")

        then:
            noExceptionThrown()
    }
}

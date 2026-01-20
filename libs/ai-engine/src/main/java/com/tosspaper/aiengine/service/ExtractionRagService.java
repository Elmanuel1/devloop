package com.tosspaper.aiengine.service;

import com.tosspaper.aiengine.extractors.DocumentExtractor;
import com.tosspaper.models.domain.ExtractionTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for Retrieval Augmented Generation (RAG) using pgvector.
 * Stores extraction results as embeddings for semantic search.
 * Uses TokenTextSplitter to chunk large documents.
 */
@Slf4j
@Service
public class ExtractionRagService {

    private final VectorStore vectorStore;
    private final DocumentExtractor documentExtractor;
    private final JdbcTemplate jdbcTemplate;
    private final TokenTextSplitter textSplitter;
    
    public ExtractionRagService(VectorStore vectorStore, DocumentExtractor documentExtractor, 
                               JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.documentExtractor = documentExtractor;
        this.jdbcTemplate = jdbcTemplate;
        // Configure splitter: 7000 tokens per chunk, NO overlap (chunks are joined for retrieval)
        this.textSplitter = new TokenTextSplitter(7000, 0, 5, 10000, true);
    }

    /**
     * Store extraction results in the vector database for RAG.
     * Cleans and chunks content using TokenTextSplitter.
     * Uses extractTaskResults from the task.
     * 
     * @param task the extraction task with extractTaskResults populated
     * @return true if storage succeeded, false otherwise
     */
    public boolean storeExtraction(ExtractionTask task) {

        try {
            // 1. Clean the raw content first using DocumentExtractor
            String cleanedContent = documentExtractor.extract(task.getExtractTaskResults());
            
            if (cleanedContent == null || cleanedContent.trim().isEmpty()) {
                log.warn("No cleaned content after extraction for task: {}", task.getAssignedId());
                return false;
            }
            
            // 2. Create a document and split using TokenTextSplitter
            Document baseDoc = new Document(cleanedContent);
            List<Document> chunks = textSplitter.split(baseDoc);
            
            // 3. Update each chunk with proper UUID and metadata
            List<Document> documentsToStore = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
                metadata.put("assignedId", task.getAssignedId());
                metadata.put("chunkIndex", i);
                metadata.put("totalChunks", chunks.size());
                metadata.put("taskId", task.getTaskId() != null ? task.getTaskId() : "");
                metadata.put("storageKey", task.getStorageKey() != null ? task.getStorageKey() : "");
                metadata.put("documentType", task.getDocumentType() != null ? task.getDocumentType().name() : "UNKNOWN");
                metadata.put("status", task.getStatus().name());
                metadata.put("fromAddress", task.getFromAddress() != null ? task.getFromAddress() : "");
                metadata.put("toAddress", task.getToAddress() != null ? task.getToAddress() : "");
                metadata.put("emailDirection", task.getEmailDirection() != null ? task.getEmailDirection() : "");
                metadata.put("emailSubject", task.getEmailSubject() != null ? task.getEmailSubject() : "");
                metadata.put("createdAt", task.getCreatedAt() != null ? task.getCreatedAt().toString() : "");
                
                // Generate a deterministic UUID from assignedId + chunkIndex (UUID v5)
                // This ensures the same assignedId + chunk always produces the same UUID
                String chunkKey = task.getAssignedId() + "_chunk_" + i;
                UUID chunkUuid = UUID.nameUUIDFromBytes(chunkKey.getBytes());
                
                Document docWithId = new Document(
                    chunkUuid.toString(),
                    chunk.getText(),
                    metadata
                );
                documentsToStore.add(docWithId);
            }
            
            // 4. Store all chunks in vector database
            vectorStore.add(documentsToStore);
            
            log.info("Stored extraction in vector database: {} (type: {}, chunks: {})",
                task.getAssignedId(),
                task.getDocumentType(),
                chunks.size());
            
            return true;

        } catch (Exception e) {
            log.error("Failed to store extraction in vector database: {}", task.getAssignedId(), e);
            return false;
        }
    }

    /**
     * Get extraction content by assigned ID using direct JDBC lookup.
     * Fetches all chunks by assignedId metadata and joins them back together.
     * 
     * @param assignedId the assigned ID of the extraction task
     * @return joined extraction content from all chunks, or null if not found
     */
    public String getExtractionContentById(String assignedId) {
        try {
            // Direct JDBC query to fetch all chunks by assignedId in metadata
            String sql = "SELECT content, metadata->>'chunkIndex' as chunk_index " +
                         "FROM extraction_embeddings " +
                         "WHERE metadata->>'assignedId' = ? " +
                         "ORDER BY (metadata->>'chunkIndex')::int";
            
            List<String> chunks = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getString("content"),
                assignedId
            );
            
            if (chunks.isEmpty()) {
                log.warn("No chunks found for assignedId: {}", assignedId);
                return null;
            }
            
            // Join all chunks (no overlap, so simple join)
            String joinedContent = String.join("\n", chunks);
            
            log.debug("Retrieved {} chunks for assignedId: {}, total size: {} chars", 
                chunks.size(), assignedId, joinedContent.length());
            
            return joinedContent;
            
        } catch (Exception e) {
            log.error("Failed to retrieve chunks for: {}", assignedId, e);
            return null;
        }
    }

    /**
     * Search for similar extractions using semantic similarity.
     *
     * @param query the search query
     * @param topK number of results to return
     * @return list of similar documents
     */
    public List<Document> searchSimilar(String query, int topK) {
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .similarityThreshold(0.85)
                    .topK(topK)
                    .build();
            return vectorStore.similaritySearch(searchRequest);
        } catch (Exception e) {
            log.error("Failed to search vector database with query: {}", query, e);
            return List.of();
        }
    }

    /**
     * Search for similar extractions with similarity threshold.
     *
     * @param query the search query
     * @param topK number of results to return
     * @param similarityThreshold minimum similarity score (0.0 to 1.0)
     * @return list of similar documents
     */
    public List<Document> searchSimilar(String query, int topK, double similarityThreshold, Filter.Expression filter) {
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(filter)
                    .topK(topK)
                    .build();

            return vectorStore.similaritySearch(searchRequest);

        } catch (Exception e) {
            log.error("Failed to search vector database with query: {}", query, e);
            return List.of();
        }
    }

    /**
     * Delete extraction from vector database.
     *
     * @param assignedId the assigned ID of the extraction to delete
     */
    public void deleteExtraction(String assignedId) {
        try {
            vectorStore.delete(List.of(assignedId));
            log.info("Deleted extraction from vector database: {}", assignedId);
        } catch (Exception e) {
            log.error("Failed to delete extraction from vector database: {}", assignedId, e);
        }
    }
}

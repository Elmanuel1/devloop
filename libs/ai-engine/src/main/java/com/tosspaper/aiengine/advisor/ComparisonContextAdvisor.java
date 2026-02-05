package com.tosspaper.aiengine.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.loaders.JsonSchemaLoader;
import com.tosspaper.aiengine.vfs.VFSContextMapper;
import com.tosspaper.aiengine.vfs.VfsDocumentContext;
import com.tosspaper.aiengine.vfs.VirtualFilesystemService;
import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.domain.ExtractionTask;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agents.client.AgentClientRequest;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisor;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain;
import org.springframework.core.Ordered;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Advisor that prepares the filesystem context for document comparison.
 * Saves PO and document data to VFS before agent execution.
 *
 * <p>This advisor implements AgentCallAdvisor to intercept agent calls
 * and prepare the working directory with necessary files.
 *
 * <p>Create a new instance for each comparison with the context bound at construction.
 */
@Slf4j
public class ComparisonContextAdvisor implements AgentCallAdvisor {

    public static final String PREPARED_CONTEXT_KEY = "preparedContext";

    private final ComparisonContext comparisonContext;
    private final VirtualFilesystemService vfsService;
    private final VFSContextMapper contextMapper;
    private final ObjectMapper objectMapper;
    private final JsonSchemaLoader schemaLoader;

    /**
     * Creates a new advisor with the comparison context bound.
     *
     * @param comparisonContext the comparison context containing PO and extraction task
     * @param vfsService the VFS service for file operations
     * @param contextMapper the mapper for creating VFS document contexts
     * @param objectMapper the JSON object mapper for parsing document content
     * @param schemaLoader the schema loader for getting schema paths
     */
    public ComparisonContextAdvisor(ComparisonContext comparisonContext,
                                    VirtualFilesystemService vfsService,
                                    VFSContextMapper contextMapper,
                                    ObjectMapper objectMapper,
                                    JsonSchemaLoader schemaLoader) {
        this.comparisonContext = comparisonContext;
        this.vfsService = vfsService;
        this.contextMapper = contextMapper;
        this.objectMapper = objectMapper;
        this.schemaLoader = schemaLoader;
    }

    @Override
    public String getName() {
        return "ComparisonContextAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public AgentClientResponse adviseCall(AgentClientRequest request, AgentCallAdvisorChain chain) {
        ExtractionTask task = comparisonContext.extractionTask();
        log.debug("Preparing comparison context: company={}, document={}, po={}",
                task.getCompanyId(), task.getAssignedId(), task.getPoNumber());

        // Save PO to VFS (always overwrite to ensure fresh data)
        VfsDocumentContext poContext = contextMapper.from(comparisonContext.purchaseOrder());
        Path poPath = vfsService.save(poContext);
        log.debug("Saved PO to VFS: {}", poPath);

        // Save document extraction to VFS
        VfsDocumentContext docContext = VFSContextMapper.from(task);
        Path documentPath = vfsService.save(docContext);
        log.debug("Saved document to VFS: {}", documentPath);

        // Get working directory for this PO and results path
        Path workingDir = vfsService.getWorkingDirectory(task.getCompanyId(), task.getPoNumber());
        Path resultsPath = workingDir.resolve("_results.json");

        // Count line items in the document for validation
        int lineItemCount = countLineItems(task.getConformedJson());

        // Get schema path for agent to reference
        Path schemaPath = schemaLoader.getSchemaPath("comparison");

        log.info("Context prepared: workingDir={}, poPath={}, docPath={}, resultsPath={}, schemaPath={}, lineItems={}",
                workingDir, poPath, documentPath, resultsPath, schemaPath, lineItemCount);

        // Create prepared context
        PreparedContext prepared = new PreparedContext(
                workingDir, poPath, documentPath, resultsPath, schemaPath, lineItemCount,
                task.getCompanyId(), task.getPoNumber());

        // Add prepared context to request context so downstream advisors (like JudgeAdvisor) can access it
        Map<String, Object> enrichedRequestContext = new HashMap<>(request.context());
        enrichedRequestContext.put(PREPARED_CONTEXT_KEY, prepared);
        AgentClientRequest enrichedRequest = new AgentClientRequest(
                request.goal(),
                request.workingDirectory(),
                request.options(),
                enrichedRequestContext
        );

        // Continue chain with enriched request
        AgentClientResponse response = chain.nextCall(enrichedRequest);

        // Merge prepared context into response context for callers
        Map<String, Object> mergedContext = new HashMap<>(response.context());
        mergedContext.put(PREPARED_CONTEXT_KEY, prepared);

        return new AgentClientResponse(response.agentResponse(), mergedContext);
    }

    /**
     * Counts line items in the conformed JSON document.
     * Looks for a "lineItems" array in the document structure.
     */
    private int countLineItems(String conformedJson) {
        if (conformedJson == null || conformedJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(conformedJson);
            JsonNode lineItems = root.path("lineItems");
            if (lineItems.isArray()) {
                return lineItems.size();
            }
            // Try alternative field names
            lineItems = root.path("line_items");
            if (lineItems.isArray()) {
                return lineItems.size();
            }
            lineItems = root.path("items");
            if (lineItems.isArray()) {
                return lineItems.size();
            }
            // Count charges across all deliveryTransactions
            JsonNode transactions = root.path("deliveryTransactions");
            if (transactions.isArray()) {
                int total = 0;
                for (JsonNode txn : transactions) {
                    JsonNode charges = txn.path("charges");
                    if (charges.isArray()) {
                        total += charges.size();
                    }
                }
                if (total > 0) return total;
            }
            return 0;
        } catch (Exception e) {
            log.warn("Failed to count line items: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Prepared context with all file paths for agent execution.
     */
    public record PreparedContext(
            Path workingDirectory,
            Path poPath,
            Path documentPath,
            Path resultsPath,
            Path schemaPath,
            int documentLineItemCount,
            Long companyId,
            String poNumber
    ) {}
}

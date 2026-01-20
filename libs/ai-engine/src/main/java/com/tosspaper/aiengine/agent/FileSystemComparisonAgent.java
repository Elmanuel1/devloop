package com.tosspaper.aiengine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.advisor.AgentAuditAdvisor;
import com.tosspaper.aiengine.advisor.ComparisonContextAdvisor;
import com.tosspaper.aiengine.loaders.JsonSchemaLoader;
import com.tosspaper.aiengine.advisor.ComparisonContextAdvisor.PreparedContext;
import com.tosspaper.aiengine.advisor.ComparisonJudgeAdvisor;
import com.tosspaper.aiengine.judge.ComparisonJuryFactory;
import com.tosspaper.aiengine.judge.ComparisonReportBuilder;
import com.tosspaper.aiengine.judge.ComparisonVerificationException;
import com.tosspaper.aiengine.judge.ComparisonVerificationReport;
import org.springaicommunity.agents.judge.jury.Verdict;
import com.tosspaper.aiengine.vfs.VFSContextMapper;
import com.tosspaper.aiengine.vfs.VirtualFilesystemService;
import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.extraction.dto.Comparison;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agents.client.AgentClient;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.claude.ClaudeAgentModel;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.claude.sdk.ClaudeAgentClient;
import org.springaicommunity.agents.model.sandbox.LocalSandbox;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Agent that orchestrates filesystem-based document comparison using Claude Code SDK.
 *
 * <p>Workflow:
 * <ol>
 *   <li>ComparisonContextAdvisor prepares files in VFS</li>
 *   <li>Run Claude agent with comparison goal</li>
 *   <li>Read results from VFS</li>
 *   <li>Parse and return comparison results</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileSystemComparisonAgent {

    private static final String COMPARISON_GOAL_TEMPLATE = """
            Compare the extracted document against the purchase order and identify matches and discrepancies.

            Read the PO and document files from the working directory and compare:
            1. Vendor contact - compare names, addresses, phone, email
            2. Ship-to contact - compare names, addresses, phone, email
            3. Line items - match by item code or description, compare quantities and prices

            For each part, determine:
            - Whether it matches a PO part (match score 0.0 to 1.0)
            - Any discrepancies between extracted and PO values
            - Match reasoning with an array of reasons

            IMPORTANT: Read the JSON schema at %s and follow it exactly for your output format.

            Write your results to a file named _results.json as a wrapper object containing:
            - documentId: the document identifier
            - poId: the purchase order identifier
            - results: array of comparison results following the schema

            The results array must include:
            - One entry with type "vendor" for the vendor contact
            - One entry with type "ship_to" for the ship-to contact
            - One entry with type "line_item" for EACH line item (extractedIndex 0 to N-1, no gaps)

            For line items with no PO match, set poIndex to null. Each poIndex can only be used once (1:1 matching).
            """;

    private final ClaudeAgentClient claudeClient;
    private final ClaudeAgentOptions agentOptions;
    private final VirtualFilesystemService vfsService;
    private final VFSContextMapper contextMapper;
    private final ObjectMapper objectMapper;
    private final ComparisonJuryFactory juryFactory;
    private final ComparisonReportBuilder reportBuilder;
    private final JsonSchemaLoader schemaLoader;

    /**
     * Execute document comparison using Claude agent.
     *
     * @param context Comparison context containing PurchaseOrder and ExtractionTask
     * @return Comparison result with all document parts
     */
    public Comparison executeComparison(ComparisonContext context) {
        ExtractionTask task = context.extractionTask();

        log.info("Starting filesystem comparison: company={}, document={}, po={}",
                task.getCompanyId(), task.getAssignedId(), task.getPoNumber());

        try {
            // Create agent client with working directory from VFS (PO-specific)
            Path workingDir = vfsService.getWorkingDirectory(task.getCompanyId(), task.getPoNumber());

            // Create sandbox restricted to working directory (auto-closed)
            try (LocalSandbox sandbox = new LocalSandbox(workingDir)) {
                // Create Claude agent model with sandbox
                ClaudeAgentModel agentModel = new ClaudeAgentModel(claudeClient, agentOptions, sandbox);

                // Create advisors - context setup, judge verification, and audit logging
                ComparisonContextAdvisor contextAdvisor = new ComparisonContextAdvisor(
                        context, vfsService, contextMapper, objectMapper, schemaLoader);
                ComparisonJudgeAdvisor judgeAdvisor = new ComparisonJudgeAdvisor(juryFactory);
                AgentAuditAdvisor auditAdvisor = new AgentAuditAdvisor(vfsService, objectMapper);

                // Build AgentClient with advisors (context first, judge middle, audit last)
                AgentClient agentClient = AgentClient.builder(agentModel)
                        .defaultAdvisors(List.of(contextAdvisor, judgeAdvisor, auditAdvisor))
                        .defaultWorkingDirectory(workingDir)
                        .build();

                // Format goal with schema path
                Path schemaPath = schemaLoader.getSchemaPath("comparison");
                String comparisonGoal = String.format(COMPARISON_GOAL_TEMPLATE, schemaPath.toAbsolutePath());

                // Execute agent
                log.info("Executing Claude agent for document comparison");
                AgentClientResponse response = agentClient
                        .goal(comparisonGoal)
                        .workingDirectory(workingDir)
                        .run();

                log.debug("Agent response: {}", response.getResult());

                // Get prepared context from response (added by advisor)
                PreparedContext prepared = (PreparedContext) response.context()
                        .get(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY);

                if (prepared == null) {
                    log.error("PreparedContext not found in response - advisor may not have run");
                    throw new ComparisonAgentException("PreparedContext not found - advisor may not have run", null);
                }

                // Get verdict from response (added by judge advisor)
                Verdict verdict = (Verdict) response.context()
                        .get(ComparisonJudgeAdvisor.JUDGE_VERDICT_KEY);

                if (verdict != null && !verdict.aggregated().pass()) {
                    // Build verification report for debugging
                    ComparisonVerificationReport report = reportBuilder.buildReport(
                            prepared.resultsPath(), verdict);
                    log.warn("Verification failed: structureValid={}, status={}",
                            report.isStructureValid(), report.getStatus());
                    throw new ComparisonVerificationException(verdict);
                }

                // Read results from VFS
                return readResults(prepared.resultsPath());
            }

        } catch (ComparisonVerificationException e) {
            // Re-throw verification exceptions as-is
            throw e;
        } catch (Exception e) {
            log.error("Filesystem comparison failed: company={}, document={}",
                    task.getCompanyId(), task.getAssignedId(), e);
            throw new ComparisonAgentException("Comparison failed: " + e.getMessage(), e);
        }
    }

    private Comparison readResults(Path resultsPath) {
        try {
            if (!vfsService.exists(resultsPath)) {
                log.warn("Results file not found: {}", resultsPath);
                throw new ComparisonAgentException("Results file not found: " + resultsPath, null);
            }

            String json = vfsService.readFile(resultsPath);
            Comparison comparison = objectMapper.readValue(json, Comparison.class);

            log.info("Read comparison results: documentId={}, poId={}, resultCount={}",
                    comparison.getDocumentId(), comparison.getPoId(),
                    comparison.getResults() != null ? comparison.getResults().size() : 0);

            return comparison;

        } catch (ComparisonAgentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read comparison results: {}", resultsPath, e);
            throw new ComparisonAgentException("Failed to read results: " + e.getMessage(), e);
        }
    }

    /**
     * Exception thrown when comparison agent execution fails.
     */
    public static class ComparisonAgentException extends RuntimeException {
        public ComparisonAgentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

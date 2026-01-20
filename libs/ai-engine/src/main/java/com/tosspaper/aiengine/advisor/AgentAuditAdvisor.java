package com.tosspaper.aiengine.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tosspaper.aiengine.vfs.VirtualFilesystemService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springaicommunity.agents.client.AgentClientRequest;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisor;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain;
import org.springaicommunity.agents.model.AgentResponse;
import org.springaicommunity.agents.model.AgentResponseMetadata;
import org.springframework.core.Ordered;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Advisor that logs agent interactions to audit files in VFS.
 * Captures all steps, thinking, tool use, and results for debugging and compliance.
 *
 * <p>Audit files are saved to: {vfs-root}/companies/{companyId}/po/{poNumber}/audits/{timestamp}.json
 *
 * <p>This advisor logs:
 * <ul>
 *   <li>Goal and working directory</li>
 *   <li>All agent turns and messages</li>
 *   <li>Tool use blocks with inputs</li>
 *   <li>Duration, cost, and token usage</li>
 *   <li>Full result text (no truncation)</li>
 * </ul>
 */
@Slf4j
public class AgentAuditAdvisor implements AgentCallAdvisor {

    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;
    private static final String QUERY_RESULT_KEY = "queryResult";

    private final VirtualFilesystemService vfsService;
    private final ObjectMapper objectMapper;

    public AgentAuditAdvisor(VirtualFilesystemService vfsService, ObjectMapper objectMapper) {
        this.vfsService = vfsService;
        // Copy to enable pretty printing without affecting the shared ObjectMapper
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public @NotNull String getName() {
        return "AgentAuditAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public @NotNull AgentClientResponse adviseCall(@NotNull AgentClientRequest request,
                                                    @NotNull AgentCallAdvisorChain chain) {
        Instant startTime = Instant.now();
        Exception caughtException = null;
        AgentClientResponse response = null;

        log.info("=== Agent Execution Started ===");
        log.debug("Goal: {}", request.goal() != null ? request.goal().getContent() : "N/A");
        log.info("Working Directory: {}", request.workingDirectory());

        try {
            response = chain.nextCall(request);
            logAgentResponse(response);
            return response;
        } catch (Exception e) {
            caughtException = e;
            log.error("Agent execution failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            Duration duration = Duration.between(startTime, Instant.now());
            log.info("=== Agent Execution Completed in {}ms ===", duration.toMillis());

            try {
                writeAuditLog(request, response, startTime, caughtException);
            } catch (Exception e) {
                log.warn("Failed to write audit log: {}", e.getMessage());
            }
        }
    }

    private void logAgentResponse(AgentClientResponse response) {
        if (response == null) {
            log.warn("Response is null");
            return;
        }

        AgentResponse agentResponse = response.agentResponse();
        if (agentResponse == null) {
            log.warn("AgentResponse is null");
            return;
        }

        // Log metadata
        AgentResponseMetadata metadata = agentResponse.getMetadata();
        if (metadata != null) {
            log.info("--- Agent Metadata ---");
            log.info("Model: {}", metadata.getModel());
            log.info("Session ID: {}", metadata.getSessionId());
            log.info("Duration: {}", metadata.getDuration());

            // Log provider-specific fields (contains Claude SDK details)
            Map<String, Object> providerFields = metadata.getProviderFields();
            if (providerFields != null && !providerFields.isEmpty()) {
                logProviderFields(providerFields);
            }
        }

        // Log full result
        String result = response.getResult();
        if (result != null) {
            log.info("--- Agent Result ---");
            log.info("{}", result);
        }

        log.info("Success: {}", response.isSuccessful());
    }

    private void logProviderFields(Map<String, Object> providerFields) {
        // Log token usage
        if (providerFields.containsKey("inputTokens")) {
            log.info("Input Tokens: {}", providerFields.get("inputTokens"));
        }
        if (providerFields.containsKey("outputTokens")) {
            log.info("Output Tokens: {}", providerFields.get("outputTokens"));
        }
        if (providerFields.containsKey("thinkingTokens")) {
            log.info("Thinking Tokens: {}", providerFields.get("thinkingTokens"));
        }

        // Log cost
        if (providerFields.containsKey("totalCostUsd")) {
            log.info("Total Cost: ${}", providerFields.get("totalCostUsd"));
        }

        // Log number of turns
        if (providerFields.containsKey("numTurns")) {
            log.info("Number of Turns: {}", providerFields.get("numTurns"));
        }

        // Log tool uses
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolUses = (List<Map<String, Object>>) providerFields.get("toolUses");
        if (toolUses != null && !toolUses.isEmpty()) {
            log.info("--- Tool Uses ({}) ---", toolUses.size());
            for (int i = 0; i < toolUses.size(); i++) {
                Map<String, Object> toolUse = toolUses.get(i);
                log.info("Tool {}: {} (id: {})", i + 1, toolUse.get("name"), toolUse.get("id"));
                Object input = toolUse.get("input");
                if (input != null) {
                    log.info("  Input: {}", input);
                }
            }
        }

        // Log messages if available
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) providerFields.get("messages");
        if (messages != null && !messages.isEmpty()) {
            log.info("--- Messages ({}) ---", messages.size());
            for (int i = 0; i < messages.size(); i++) {
                Map<String, Object> message = messages.get(i);
                String type = (String) message.get("type");
                log.info("Message {}: [{}]", i + 1, type);
                Object content = message.get("content");
                if (content != null) {
                    log.info("  Content: {}", content);
                }
            }
        }
    }

    private void writeAuditLog(AgentClientRequest request, AgentClientResponse response,
                               Instant startTime, Exception exception) {
        try {
            // Get audit directory from PreparedContext if available
            Path auditDir;
            if (response != null && response.context() != null) {
                ComparisonContextAdvisor.PreparedContext prepared =
                        (ComparisonContextAdvisor.PreparedContext) response.context()
                                .get(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY);
                if (prepared != null && prepared.companyId() != null && prepared.poNumber() != null) {
                    auditDir = vfsService.getAuditDirectory(prepared.companyId(), prepared.poNumber());
                } else {
                    auditDir = vfsService.getRoot().resolve("audit");
                }
            } else {
                auditDir = vfsService.getRoot().resolve("audit");
            }
            Files.createDirectories(auditDir);

            String timestamp = startTime.toString().replace(":", "-");
            Path auditFile = auditDir.resolve("comparison_" + timestamp + ".json");

            Map<String, Object> auditEntry = new LinkedHashMap<>();
            auditEntry.put("timestamp", startTime.toString());
            auditEntry.put("durationMs", Instant.now().toEpochMilli() - startTime.toEpochMilli());

            // Request details
            Map<String, Object> requestData = new LinkedHashMap<>();
            if (request.goal() != null) {
                requestData.put("goal", request.goal().getContent());
            }
            if (request.workingDirectory() != null) {
                requestData.put("workingDirectory", request.workingDirectory().toString());
            }
            auditEntry.put("request", requestData);

            // Response details
            if (response != null) {
                Map<String, Object> responseData = new LinkedHashMap<>();
                responseData.put("success", response.isSuccessful());
                responseData.put("result", response.getResult());

                AgentResponse agentResponse = response.agentResponse();
                if (agentResponse != null) {
                    AgentResponseMetadata metadata = agentResponse.getMetadata();
                    if (metadata != null) {
                        Map<String, Object> metadataMap = new LinkedHashMap<>();
                        metadataMap.put("model", metadata.getModel());
                        metadataMap.put("sessionId", metadata.getSessionId());
                        if (metadata.getDuration() != null) {
                            metadataMap.put("durationMs", metadata.getDuration().toMillis());
                        }

                        // Include all provider fields (token usage, cost, turns, etc.)
                        Map<String, Object> providerFields = metadata.getProviderFields();
                        if (providerFields != null) {
                            metadataMap.put("providerFields", providerFields);
                        }

                        responseData.put("metadata", metadataMap);
                    }

                    // Include all texts from the response
                    List<String> texts = agentResponse.getTexts();
                    if (texts != null && !texts.isEmpty()) {
                        responseData.put("texts", texts);
                    }
                }

                auditEntry.put("response", responseData);
            }

            // Error details
            if (exception != null) {
                Map<String, Object> errorData = new LinkedHashMap<>();
                errorData.put("type", exception.getClass().getSimpleName());
                errorData.put("message", exception.getMessage());
                auditEntry.put("error", errorData);
            }

            auditEntry.put("success", exception == null && (response == null || response.isSuccessful()));

            String json = objectMapper.writeValueAsString(auditEntry);
            Files.writeString(auditFile, json, StandardCharsets.UTF_8);

            log.debug("Wrote audit log: {}", auditFile);

        } catch (Exception e) {
            log.warn("Failed to write audit log: {}", e.getMessage());
        }
    }
}

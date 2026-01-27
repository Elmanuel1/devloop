package com.tosspaper.aiengine.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tosspaper.aiengine.vfs.VirtualFilesystemService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Advisor that logs ChatClient interactions to audit files in VFS.
 * Captures prompts, tool calls, responses, and token usage for debugging and compliance.
 *
 * <p>Audit files are saved to: {vfs-root}/companies/{companyId}/po/{poNumber}/audits/{timestamp}.json
 *
 * <p>This advisor logs:
 * <ul>
 *   <li>User prompt and system messages</li>
 *   <li>All tool calls and results</li>
 *   <li>AI response content</li>
 *   <li>Duration and token usage</li>
 * </ul>
 */
@Slf4j
@Component
public class ComparisonAuditAdvisor implements CallAdvisor {

    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    /**
     * Context key for working directory. Set by StreamingComparisonAgent before calling ChatClient.
     */
    public static final String WORKING_DIR_KEY = "workingDirectory";

    private final VirtualFilesystemService vfsService;
    private final ObjectMapper objectMapper;

    public ComparisonAuditAdvisor(VirtualFilesystemService vfsService, ObjectMapper objectMapper) {
        this.vfsService = vfsService;
        // Copy to enable pretty printing without affecting the shared ObjectMapper
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public String getName() {
        return "ComparisonAuditAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Instant startTime = Instant.now();
        Exception caughtException = null;
        ChatClientResponse response = null;

        log.info("=== ChatClient Comparison Started ===");
        logRequest(request);

        try {
            response = chain.nextCall(request);
            logResponse(response);
            return response;
        } catch (Exception e) {
            caughtException = e;
            log.error("ChatClient comparison failed: {}", e.getMessage(), e);
            throw e;
        } finally {
            Duration duration = Duration.between(startTime, Instant.now());
            log.info("=== ChatClient Comparison Completed in {}ms ===", duration.toMillis());

            try {
                writeAuditLog(request, response, startTime, caughtException);
            } catch (Exception e) {
                log.warn("Failed to write audit log: {}", e.getMessage());
            }
        }
    }

    private void logRequest(ChatClientRequest request) {
        if (request.prompt() == null) {
            return;
        }

        log.debug("Prompt messages: {}", request.prompt().getInstructions().size());
        request.prompt().getInstructions().forEach(message -> {
            log.debug("  [{}]: {}", message.getMessageType(),
                    truncate(message.getText(), 200));
        });
    }

    private void logResponse(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            log.warn("Response is null");
            return;
        }

        ChatResponse chatResponse = response.chatResponse();
        log.info("--- ChatClient Response ---");

        // Log generations
        if (chatResponse.getResults() != null) {
            for (Generation gen : chatResponse.getResults()) {
                if (gen.getOutput() != null) {
                    log.info("Response content length: {} chars",
                            gen.getOutput().getText() != null ? gen.getOutput().getText().length() : 0);
                }
            }
        }

        // Log metadata
        if (chatResponse.getMetadata() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            if (usage != null) {
                log.info("Tokens - Input: {}, Output: {}, Total: {}",
                        usage.getPromptTokens(),
                        usage.getCompletionTokens(),
                        usage.getTotalTokens());
            }
        }
    }

    private void writeAuditLog(ChatClientRequest request, ChatClientResponse response,
                               Instant startTime, Exception exception) {
        try {
            // Get audit directory from context
            Path auditDir = determineAuditDirectory(request);
            Files.createDirectories(auditDir);

            String timestamp = startTime.toString().replace(":", "-");
            Path auditFile = auditDir.resolve("chatclient_" + timestamp + ".json");

            Map<String, Object> auditEntry = new LinkedHashMap<>();
            auditEntry.put("timestamp", startTime.toString());
            auditEntry.put("durationMs", Instant.now().toEpochMilli() - startTime.toEpochMilli());

            // Request details
            Map<String, Object> requestData = buildRequestData(request);
            auditEntry.put("request", requestData);

            // Response details
            if (response != null && response.chatResponse() != null) {
                Map<String, Object> responseData = buildResponseData(response.chatResponse());
                auditEntry.put("response", responseData);
            }

            // Error details
            if (exception != null) {
                Map<String, Object> errorData = new LinkedHashMap<>();
                errorData.put("type", exception.getClass().getSimpleName());
                errorData.put("message", exception.getMessage());
                auditEntry.put("error", errorData);
            }

            auditEntry.put("success", exception == null);

            String json = objectMapper.writeValueAsString(auditEntry);
            Files.writeString(auditFile, json, StandardCharsets.UTF_8);

            log.debug("Wrote audit log: {}", auditFile);

        } catch (Exception e) {
            log.warn("Failed to write audit log: {}", e.getMessage());
        }
    }

    private Path determineAuditDirectory(ChatClientRequest request) {
        // Try to get working directory from context
        Object workingDirObj = request.context().get(WORKING_DIR_KEY);
        if (workingDirObj instanceof Path workingDir) {
            // audits directory is sibling to working directory
            return workingDir.resolve("audits");
        }

        // Fallback to root audit directory
        return vfsService.getRoot().resolve("audits");
    }

    private Map<String, Object> buildRequestData(ChatClientRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();

        if (request.prompt() != null) {
            List<Map<String, String>> messages = request.prompt().getInstructions().stream()
                    .map(msg -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("type", msg.getMessageType().toString());
                        m.put("content", msg.getText());
                        return m;
                    })
                    .toList();
            data.put("messages", messages);

            // Include tool definitions if present
            if (request.prompt().getOptions() != null) {
                data.put("options", request.prompt().getOptions().toString());
            }
        }

        return data;
    }

    private Map<String, Object> buildResponseData(ChatResponse chatResponse) {
        Map<String, Object> data = new LinkedHashMap<>();

        // Content from generations
        if (chatResponse.getResults() != null && !chatResponse.getResults().isEmpty()) {
            List<String> contents = chatResponse.getResults().stream()
                    .filter(gen -> gen.getOutput() != null && gen.getOutput().getText() != null)
                    .map(gen -> gen.getOutput().getText())
                    .toList();
            data.put("contents", contents);

            // Include tool calls if any
            List<Map<String, Object>> toolCalls = chatResponse.getResults().stream()
                    .filter(gen -> gen.getOutput() != null && gen.getOutput().getToolCalls() != null)
                    .flatMap(gen -> gen.getOutput().getToolCalls().stream())
                    .map(tc -> {
                        Map<String, Object> tcMap = new LinkedHashMap<>();
                        tcMap.put("id", tc.id());
                        tcMap.put("name", tc.name());
                        tcMap.put("type", tc.type());
                        tcMap.put("arguments", tc.arguments());
                        return tcMap;
                    })
                    .toList();
            if (!toolCalls.isEmpty()) {
                data.put("toolCalls", toolCalls);
            }
        }

        // Token usage
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            Map<String, Object> usageMap = new LinkedHashMap<>();
            usageMap.put("promptTokens", usage.getPromptTokens());
            usageMap.put("completionTokens", usage.getCompletionTokens());
            usageMap.put("totalTokens", usage.getTotalTokens());
            data.put("usage", usageMap);
        }

        // Model info
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getModel() != null) {
            data.put("model", chatResponse.getMetadata().getModel());
        }

        return data;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}

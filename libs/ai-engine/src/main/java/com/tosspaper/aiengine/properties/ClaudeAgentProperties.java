package com.tosspaper.aiengine.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;

/**
 * Configuration properties for the Claude AI agent used in document comparison.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.ai.agents.claude")
public class ClaudeAgentProperties {

    /**
     * Claude model to use for comparison agent.
     * Recommended: claude-sonnet-4-20250514 for balance of speed and accuracy.
     */
    @NotBlank(message = "Claude model must be specified")
    private String model = "claude-sonnet-4-20250514";

    /**
     * Maximum time allowed for agent execution.
     * Complex comparisons with many line items may take several minutes.
     */
    private Duration timeout = Duration.ofMinutes(10);

    /**
     * Maximum number of tokens for agent output.
     */
    private int maxTokens = 8192;

    /**
     * Whether to enable YOLO mode (skip permission prompts for autonomous execution).
     * WARNING: Only enable in trusted, sandboxed environments.
     */
    private boolean dangerouslySkipPermissions = false;

    /**
     * Anthropic API key for Claude access.
     */
    private String apiKey;

    /**
     * Path to the Claude CLI binary.
     * Defaults to "claude" which uses PATH lookup.
     */
    private String binaryPath = "claude";
}

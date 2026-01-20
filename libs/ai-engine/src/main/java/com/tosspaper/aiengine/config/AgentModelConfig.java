package com.tosspaper.aiengine.config;

import com.tosspaper.aiengine.properties.ClaudeAgentProperties;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.claude.sdk.ClaudeAgentClient;
import org.springaicommunity.agents.claude.sdk.transport.CLIOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration for Claude agent components used in filesystem-based document comparison.
 * Configures ClaudeAgentClient and ClaudeAgentOptions beans for the FileSystemComparisonAgent.
 */
@Configuration
@EnableConfigurationProperties(ClaudeAgentProperties.class)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ai.agents.claude", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AgentModelConfig {

    private final ClaudeAgentProperties properties;

    /**
     * Creates the Claude SDK client bean.
     * This client is reused across all agent executions.
     *
     * @return configured ClaudeAgentClient
     */
    @Bean
    public ClaudeAgentClient claudeAgentClient() {
        String binaryPath = properties.getBinaryPath();
        Path workingDir = Paths.get(System.getProperty("user.dir"));
        return ClaudeAgentClient.create(CLIOptions.defaultOptions(), workingDir, binaryPath);
    }

    /**
     * Creates the Claude agent options bean.
     * Configures model and permission settings from properties.
     *
     * @return configured ClaudeAgentOptions
     */
    @Bean
    public ClaudeAgentOptions claudeAgentOptions() {
        return ClaudeAgentOptions.builder()
                .model(properties.getModel())
                .yolo(properties.isDangerouslySkipPermissions())
                .build();
    }
}

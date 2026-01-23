package com.tosspaper.aiengine.config;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agents.claude.ClaudeAgentModel;
import org.springaicommunity.agents.claude.ClaudeAgentOptions;
import org.springaicommunity.agents.claude.autoconfigure.ClaudeAgentProperties;
import org.springaicommunity.agents.model.AgentModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Custom agent model configuration with explicit timeout on model builder.
 */
@Slf4j
@Configuration
public class AgentModelConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "spring.ai.agents.claude-code", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AgentModel claudeAgentModeld(ClaudeAgentProperties properties) {
        log.info("Configuring Claude agent model: model={}, timeout={}, yolo={}",
                properties.getModel(), properties.getTimeout(), properties.isYolo());

        ClaudeAgentOptions options = ClaudeAgentOptions.builder()
                .model(properties.getModel())
                .yolo(properties.isYolo())
                .build();

        options.setTimeout(properties.getTimeout());
        if (properties.getExecutablePath() != null) {
            options.setExecutablePath(properties.getExecutablePath());
        }

        return ClaudeAgentModel.builder()
                .defaultOptions(options)
                .timeout(properties.getTimeout())
                .build();
    }
}

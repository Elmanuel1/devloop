package com.tosspaper.aiengine.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AI-powered document comparison.
 *
 * <p>Supports multiple providers (OpenAI, Anthropic) with streaming SSE output
 * and extended thinking capabilities.
 *
 * <p>Configuration prefix: {@code ai.comparison}
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ai.comparison")
public class ComparisonProperties {

    /**
     * AI provider to use for comparisons.
     * Supported values: "openai", "anthropic"
     * Default: "openai" (lower cost)
     */
    private String provider = "openai";

    /**
     * Enable extended thinking (Claude only).
     * When enabled, Claude will show its reasoning process.
     */
    private boolean extendedThinking = true;

    /**
     * Stream thinking tokens to the client.
     * When true, thinking tokens are sent via SSE in real-time.
     */
    private boolean streamThinking = true;

    /**
     * Thinking budget for extended thinking (max tokens for thinking).
     * Only used when extendedThinking is true.
     */
    private int thinkingBudget = 10000;

    /**
     * Enable fallback to Claude if OpenAI fails.
     * Only applicable when provider is "openai".
     */
    private boolean fallbackEnabled = true;

    /**
     * Streaming configuration for SSE output.
     */
    private Streaming streaming = new Streaming();

    /**
     * Context management settings for token efficiency.
     */
    private Context context = new Context();

    /**
     * Streaming-specific configuration.
     */
    @Getter
    @Setter
    public static class Streaming {

        /**
         * Enable streaming implementation.
         * When false, uses existing CLI-based implementation.
         * When true, uses new ChatClient-based streaming implementation.
         */
        private boolean enabled = false;

        /**
         * Timeout for streaming connections in seconds.
         */
        private int timeoutSeconds = 300;

        /**
         * Maximum concurrent streaming connections.
         */
        private int maxConcurrent = 100;
    }

    /**
     * Context management settings for token efficiency.
     */
    @Getter
    @Setter
    public static class Context {

        /**
         * Default chunk size for reading large files.
         */
        private int defaultChunkSize = 10000;

        /**
         * Enable observation masking to reduce token usage.
         * Previous tool outputs are summarized instead of kept in full.
         */
        private boolean observationMasking = true;

        /**
         * Enable file-based results to keep large outputs out of context.
         */
        private boolean fileBasedResults = true;
    }
}

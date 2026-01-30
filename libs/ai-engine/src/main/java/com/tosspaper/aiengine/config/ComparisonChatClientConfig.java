package com.tosspaper.aiengine.config;

import com.tosspaper.aiengine.advisor.ComparisonAuditAdvisor;
import com.tosspaper.aiengine.properties.ComparisonProperties;
import com.tosspaper.aiengine.tools.FileTools;
import com.tosspaper.aiengine.vfs.VirtualFilesystemService;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.retry.support.RetryTemplate;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.lang.Nullable;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Configuration for ChatClient used in streaming document comparison.
 *
 * <p>Configures multi-provider support (OpenAI and Anthropic) with embedded file tools.
 * Provider selection is controlled by {@code ai.comparison.provider} property.
 *
 * <p>Configuration options:
 * <ul>
 *   <li>openai - Uses OpenAI GPT-4o or GPT-4o-mini (lower cost)</li>
 *   <li>anthropic - Uses Claude with extended thinking (better analysis)</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(ComparisonProperties.class)
public class ComparisonChatClientConfig {

    private final ComparisonProperties properties;

    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;

    @Value("${spring.ai.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${ai.comparison.openai.model:gpt-5.1}")
    private String openAiModel;

    @Value("${ai.comparison.anthropic.model:claude-sonnet-4-20250514}")
    private String anthropicModel;

    /**
     * Create FileTools bean for AI to read/write files.
     */
    @Bean
    public FileTools comparisonFileTools(VirtualFilesystemService vfs) {
        log.info("Creating FileTools for document comparison");
        return new FileTools(vfs);
    }

    /**
     * Retry template for AI API calls with exponential backoff.
     * Handles rate limit errors (429) by waiting and retrying.
     */
    @Bean
    @Qualifier("comparisonRetryTemplate")
    public RetryTemplate comparisonRetryTemplate() {
        // Exponential backoff: 30s, 60s, 120s (for rate limits, need longer waits)
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(30_000);  // 30 seconds initial wait
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(120_000);     // Max 2 minutes between retries

        // Retry on rate limit and transient errors
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                4,  // Max 4 attempts (initial + 3 retries)
                Map.of(
                        org.springframework.ai.retry.NonTransientAiException.class, true,
                        org.springframework.web.client.ResourceAccessException.class, true
                ),
                true  // Traverse cause chain
        );

        return RetryTemplate.builder()
                .customPolicy(retryPolicy)
                .customBackoff(backOffPolicy)
                .build();
    }

    /**
     * Dedicated OpenAI ChatModel for document comparison.
     * Separate from other ChatModels to allow different configuration.
     */
    @Bean
    @Qualifier("comparisonOpenAiChatModel")
    @ConditionalOnProperty(name = "ai.comparison.provider", havingValue = "openai", matchIfMissing = true)
    public OpenAiChatModel comparisonOpenAiChatModel(
            ObservationRegistry observationRegistry,
            @Qualifier("comparisonRetryTemplate") RetryTemplate retryTemplate) {
        log.info("Creating OpenAI ChatModel for comparison: model={} (with retry)", openAiModel);

        // Configure HTTP client with longer timeouts for AI completions (5 min for large requests)
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(5));

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("https://api.openai.com")
                .apiKey(new SimpleApiKey(openAiApiKey))
                .restClientBuilder(RestClient.builder()
                        .requestFactory(requestFactory)
                        .observationRegistry(observationRegistry))
                .webClientBuilder(WebClient.builder()
                        .observationRegistry(observationRegistry))
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(openAiModel)
                        .temperature(0.1)  // Low temperature for consistent comparisons
                        .maxCompletionTokens(100000)
                        .build())
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * ChatClient for document comparison using OpenAI.
     * Created when ai.comparison.provider=openai.
     * Includes MCP tools (Tavily web search) when available.
     */
    @Bean
    @Qualifier("comparisonChatClient")
    @ConditionalOnProperty(name = "ai.comparison.provider", havingValue = "openai", matchIfMissing = true)
    public ChatClient openAiComparisonChatClient(
            @Qualifier("comparisonOpenAiChatModel") OpenAiChatModel chatModel,
            FileTools fileTools,
            ComparisonAuditAdvisor auditAdvisor,
            @Nullable ToolCallbackProvider mcpToolCallbackProvider) {

        var builder = ChatClient.builder(chatModel)
                .defaultTools(fileTools)  // @Tool annotated methods
                .defaultAdvisors(auditAdvisor)
                .defaultSystem(buildSystemPrompt());

        // Add MCP tools (Tavily) using toolCallbacks if available
        if (mcpToolCallbackProvider != null) {
            var mcpTools = mcpToolCallbackProvider.getToolCallbacks();
            if (mcpTools.length > 0) {
                var toolNames = java.util.Arrays.stream(mcpTools)
                        .map(tool -> tool.getToolDefinition().name())
                        .toList();
                log.info("Creating OpenAI ChatClient with MCP tools: {}", toolNames);
                builder.defaultToolCallbacks(mcpTools);
            }
        } else {
            log.info("Creating OpenAI ChatClient for document comparison (no MCP tools)");
        }

        return builder.build();
    }

    /**
     * Dedicated Anthropic ChatModel for document comparison.
     * Includes retry template and prompt caching for efficiency.
     *
     * <p>Prompt caching caches system prompt + tools (90% cost reduction on cache hits).
     * Cache TTL is 5 minutes by default.
     */
    @Bean
    @Qualifier("comparisonAnthropicChatModel")
    @ConditionalOnProperty(name = "ai.comparison.provider", havingValue = "anthropic")
    public AnthropicChatModel comparisonAnthropicChatModel(
            ObservationRegistry observationRegistry,
            @Qualifier("comparisonRetryTemplate") RetryTemplate retryTemplate) {
        log.info("Creating Anthropic ChatModel for comparison: model={} (with retry + prompt caching)", anthropicModel);

        // Configure HTTP client with longer timeouts for AI completions (5 min for large requests)
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(5));

        AnthropicApi api = AnthropicApi.builder()
                .baseUrl("https://api.anthropic.com")
                .apiKey(anthropicApiKey)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(requestFactory)
                        .observationRegistry(observationRegistry))
                .webClientBuilder(WebClient.builder()
                        .observationRegistry(observationRegistry))
                .build();

        // Enable prompt caching for system prompt and tools
        // This caches the large system prompt (~4K tokens) and tool definitions
        // Cache hits cost 90% less and help with rate limits
        AnthropicCacheOptions cacheOptions = AnthropicCacheOptions.builder()
                .strategy(AnthropicCacheStrategy.SYSTEM_AND_TOOLS)
                .build();

        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model(anthropicModel)
                        .temperature(0.0)
                        .maxTokens(4096)
                        .cacheOptions(cacheOptions)
                        .build())
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * ChatClient for document comparison using Anthropic Claude.
     * Created when ai.comparison.provider=anthropic.
     * Includes MCP tools (Tavily web search) when available.
     */
    @Bean
    @Qualifier("comparisonChatClient")
    @ConditionalOnProperty(name = "ai.comparison.provider", havingValue = "anthropic")
    public ChatClient anthropicComparisonChatClient(
            @Qualifier("comparisonAnthropicChatModel") AnthropicChatModel chatModel,
            FileTools fileTools,
            ComparisonAuditAdvisor auditAdvisor,
            @Nullable ToolCallbackProvider mcpToolCallbackProvider) {

        var builder = ChatClient.builder(chatModel)
                .defaultTools(fileTools)  // @Tool annotated methods
                .defaultAdvisors(auditAdvisor)
                .defaultSystem(buildSystemPrompt());

        // Add MCP tools (Tavily) using toolCallbacks if available
        if (mcpToolCallbackProvider != null) {
            var mcpTools = mcpToolCallbackProvider.getToolCallbacks();
            if (mcpTools != null && mcpTools.length > 0) {
                log.info("Creating Anthropic ChatClient with {} MCP tools (Tavily web search enabled), extended thinking: {}",
                        mcpTools.length, properties.isExtendedThinking());
                builder.defaultToolCallbacks(mcpTools);  // ToolCallback objects
            }
        } else {
            log.info("Creating Anthropic ChatClient for document comparison (no MCP tools), extended thinking: {}",
                    properties.isExtendedThinking());
        }

        return builder.build();
    }

    /**
     * Build the system prompt for document comparison.
     * Compressed version (~1,500 tokens vs ~4,000 tokens) with exactFields optimization.
     */
    private String buildSystemPrompt() {
        return """
            You are a document comparison agent. Use tools to read files, compare field-by-field, output JSON.

            ## TOOLS
            - readFile(path): Read file contents
            - listDirectory(path): List directory files
            - tavily_search(query): Web search to verify vendor relationships

            ## WORKFLOW
            1. readFile("po.json") - read purchase order
            2. Check existing docs: listDirectory("invoice"), listDirectory("delivery_slip"), listDirectory("delivery_note")
            3. Read current document (ID in user prompt)
            4. Compare document vs PO, match line items by description/quantity
            5. Output JSON (ONLY - no explanations, no markdown)

            ## OUTPUT FORMAT
            {
              "documentId": "<doc ID>",
              "poId": "<PO display ID>",
              "overallStatus": "matched|partial|unmatched",
              "confidence": <0.0-1.0>,
              "blockingIssues": <count of blocking results>,
              "results": [
                {
                  "type": "vendor|ship_to|line_item",
                  "status": "matched|partial|unmatched",
                  "matchScore": <0.0-1.0>,
                  "severity": "info|warning|blocking",
                  "extractedIndex": <number, line_item only>,
                  "poIndex": <number|null, line_item only>,
                  "exactFields": ["Description", "Quantity"],
                  "comparisons": [
                    { "field": "Unit Price", "poValue": "USD $28.00", "documentValue": "CAD $28.00", "match": "mismatch", "isBlocking": true, "explanation": "Currency differs" }
                  ]
                }
              ]
            }

            ## OUTPUT OPTIMIZATION
            - Keep ALL results with extractedIndex/poIndex for line item mapping
            - For each result, list exact-match field names in `exactFields` array (names only)
            - Only include full comparison objects in `comparisons` for close/mismatch fields
            - This reduces output size while preserving all needed information

            ## BLOCKING RULES

            LINE_ITEM blocking issues (isBlocking=true, severity="blocking"):
            - Price differs at all (even $0.01)
            - Currency mismatch (USD vs CAD)
            - Quantity mismatch
            - Item not in PO (unordered item)
            - Oversupply (total supplied > PO qty)

            VENDOR blocking issue:
            - Wrong company (truly different legal entities) - USE WEB SEARCH TO VERIFY

            ## VENDOR NAME MATCHING - MUST USE WEB SEARCH

            When vendor names differ, ALWAYS use tavily_search to verify:
            - Search: "[name1] [name2] same company subsidiary product"

            **NOT blocking** (same entity, different name format):
            - "Anysphere" vs "Anysphere, Inc." - just suffix difference
            - "Cursor" vs "Anysphere" - Cursor is Anysphere's product
            - Inc./LLC/Corp suffixes added or omitted

            **BLOCKING** (different legal entities after web search confirms):
            - GitHub vs Microsoft - separate billing entities
            - AWS vs Amazon.com - separate billing entities
            - Unrelated companies with no relationship found

            ## NOT BLOCKING (info only)
            - Email: If domain matches and is corporate, it's fine (hi@cursor.com vs hIOi@cursor.com = OK)
            - Phone: Minor digit differences are just data entry issues
            - Address formatting (CA ↔ California)
            - PO item missing from doc (may come in future delivery)
            - Abbreviations (LLC ↔ L.L.C., Inc. ↔ Incorporated)

            ## MATCH CLASSIFICATION
            - "exact": Identical or semantically equivalent
            - "close": Minor differences (formatting, typos, abbreviations)
            - "mismatch": Significant difference requiring attention

            ## STATUS/SEVERITY RULES
            - matched: matchScore > 0.9, partial: 0.5-0.9, unmatched: < 0.5
            - info: Cosmetic only, warning: Notable but OK, blocking: Must block approval
            - blockingIssues = count of RESULTS (not fields) with severity=blocking

            ## FIELDS TO COMPARE
            - VENDOR/SHIP_TO: Name, Address, City, State, Country, Postal, Email, Phone
            - LINE_ITEM: Description, Quantity, Unit Price, Currency, Item Code (if present)

            ## EXPLANATION STYLE - WRITE LIKE A HUMAN REVIEWER

            Write explanations as a helpful colleague would, not a robot. Be conversational and clear.

            BAD (robotic):
            - "Legal suffix 'Inc.' added; same entity"
            - "Different local parts; PO email appears to contain typos"

            GOOD (human):
            - "Same company - the invoice just includes 'Inc.' in the legal name."
            - "Looks like there's a typo in the PO email (hIOi vs hi). The invoice has the correct address."
            - "The invoice shows a $20.35 credit, but the PO expected a $9.00 charge. This needs review before approval."
            - "Phone numbers are slightly different - the PO has an extra digit at the end. Probably a data entry error."

            Be specific, explain the impact, and guide the reviewer on what matters.
            """;
    }
}

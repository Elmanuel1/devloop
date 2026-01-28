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

    @Value("${ai.comparison.openai.model:gpt-4o}")
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
                        .maxCompletionTokens(4096)
                        .build())
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * ChatClient for document comparison using OpenAI.
     * Created when ai.comparison.provider=openai.
     */
    @Bean
    @Qualifier("comparisonChatClient")
    @ConditionalOnProperty(name = "ai.comparison.provider", havingValue = "openai", matchIfMissing = true)
    public ChatClient openAiComparisonChatClient(
            @Qualifier("comparisonOpenAiChatModel") OpenAiChatModel chatModel,
            FileTools fileTools,
            ComparisonAuditAdvisor auditAdvisor) {
        log.info("Creating OpenAI ChatClient for document comparison with file tools and audit");

        return ChatClient.builder(chatModel)
                .defaultTools(fileTools)
                .defaultAdvisors(auditAdvisor)
                .defaultSystem(buildSystemPrompt())
                .build();
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
     */
    @Bean
    @Qualifier("comparisonChatClient")
    @ConditionalOnProperty(name = "ai.comparison.provider", havingValue = "anthropic")
    public ChatClient anthropicComparisonChatClient(
            @Qualifier("comparisonAnthropicChatModel") AnthropicChatModel chatModel,
            FileTools fileTools,
            ComparisonAuditAdvisor auditAdvisor) {
        log.info("Creating Anthropic ChatClient for document comparison with file tools and audit (extended thinking: {})",
                properties.isExtendedThinking());

        return ChatClient.builder(chatModel)
                .defaultTools(fileTools)
                .defaultAdvisors(auditAdvisor)
                .defaultSystem(buildSystemPrompt())
                .build();
    }

    /**
     * Build the system prompt for document comparison.
     * Instructs the AI to return JSON directly with detailed field-by-field comparisons.
     */
    private String buildSystemPrompt() {
        return """
            You are a document comparison agent with file tools. Use tools to read files and perform THOROUGH field-by-field comparison.

            ## AVAILABLE TOOLS

            - readFile(path): Read a file's contents
            - listDirectory(path): List files in a directory
            - grep(pattern, path): Search for text in files

            ## WORKFLOW

            1. Use readFile("_schema/comparison.json") to understand the output format
            2. Use readFile("po.json") to read the purchase order
            3. Check ALL document types to understand what's already been received:
               - listDirectory("invoice") - read all invoice JSONs
               - listDirectory("delivery_slip") - read all delivery slip JSONs
               - listDirectory("delivery_note") - read all delivery note JSONs
            4. For each PO line item, calculate: totalSupplied = sum of quantities from all documents
            5. Read the CURRENT document being compared (identified by Document ID in prompt)
            6. Compare the current document against PO field-by-field
            7. Return your comparison result as JSON matching the schema

            ## OUTPUT RULES

            After reading and comparing, respond with ONLY valid JSON - no explanations, no markdown.
            Start with { and end with }

            ## JSON SCHEMA

            {
              "documentId": "<string - the document's assigned ID>",
              "poId": "<string - the PO display ID>",
              "overallStatus": "matched|partial|unmatched",
              "confidence": <0.0-1.0>,
              "blockingIssues": <count of results with severity=blocking>,
              "results": [
                {
                  "type": "vendor|ship_to|line_item",
                  "status": "matched|partial|unmatched",
                  "matchScore": <0.0-1.0>,
                  "severity": "info|warning|blocking",
                  "extractedIndex": <number, only for line_item>,
                  "poIndex": <number or null, only for line_item>,
                  "comparisons": [
                    {
                      "field": "<Human-readable field name>",
                      "poValue": "<Value from PO, human-readable format>",
                      "documentValue": "<Value from document, human-readable format>",
                      "match": "exact|close|mismatch",
                      "isBlocking": <true if this field causes blocking>,
                      "explanation": "<Human-readable explanation of comparison>"
                    }
                  ]
                }
              ]
            }

            ## COMPARISONS ARRAY - THE SINGLE SOURCE OF TRUTH

            Each result MUST have a "comparisons" array with field-by-field breakdown.
            This is the ONLY place to report differences - no separate reasons/signals/discrepancies.

            For VENDOR type, include comparisons for:
            - Name, Address, City, State/Province, Country, Postal Code, Email, Phone

            For SHIP_TO type, include comparisons for:
            - Name, Address, City, State/Province, Country, Postal Code, Email, Phone

            For LINE_ITEM type, include comparisons for:
            - Description, Quantity, Unit Price, Currency, Item Code (if present)

            ## FIELD VALUE FORMATTING

            Format values for human readability:
            - Prices: Include currency symbol and code (e.g., "USD $28.00", "CAD $28.00")
            - Quantities: Plain numbers (e.g., "1", "10")
            - Empty fields: Use "Not specified"
            - Addresses: Full formatted address

            ## MATCH CLASSIFICATION

            - "exact": Values are identical or semantically equivalent
            - "close": Minor differences (formatting, typos, abbreviations, brand vs legal name)
            - "mismatch": Significant difference requiring attention

            ## STATUS RULES (based on matchScore)

            - "matched": matchScore > 0.9 (almost everything matches)
            - "partial": matchScore 0.5-0.9 (some differences)
            - "unmatched": matchScore < 0.5 (significant mismatches)

            ## SEVERITY RULES

            - "info": Cosmetic differences only (formatting, abbreviations)
            - "warning": Notable differences that don't block approval (typos, name variations)
            - "blocking": Critical mismatches - approval MUST be blocked

            ## AUTOMATIC BLOCKING ISSUES (isBlocking=true, severity="blocking")

            These are ALWAYS blocking - no exceptions. Set isBlocking=true for the specific field:

            1. ANY price discrepancy: if unitPrice differs AT ALL (20.00 vs 20.35, 20 vs -20)
               → Field: "Unit Price", isBlocking: true
               → Explanation: "The unit price on the invoice is $X.XX different from the purchase order amount."

            2. Currency mismatch: if currencies differ (USD vs CAD)
               → Field: "Currency", isBlocking: true
               → Explanation: "The purchase order specifies USD currency but the invoice is billed in CAD. This requires review as currency conversion may affect the final amount."

            3. Quantity mismatch: document qty differs from PO qty
               → Field: "Quantity", isBlocking: true
               → Explanation: "The invoice quantity of X units does not match the purchase order quantity of Y units."

            4. Wrong company: vendor name completely different (not brand vs legal name)
               → Field: "Name", isBlocking: true
               → Explanation: "The vendor name on the invoice does not match the vendor on the purchase order. These appear to be different companies."

            5. Item NOT in PO: document has item that doesn't exist in PO
               → Field: "Description", isBlocking: true
               → Explanation: "This line item appears on the invoice but was not included in the original purchase order."

            6. OVERSUPPLY: (previously supplied + this document) > PO quantity
               → Field: "Quantity", isBlocking: true
               → Explanation: "The invoice quantity of X units, combined with Y units already supplied, exceeds the purchase order quantity of Z units by N units."

            ## NOT BLOCKING (severity = "info")

            PO item missing from document is NOT blocking:
            - If already supplied: explanation = "Already fulfilled by previous documents"
            - If not yet supplied: explanation = "May appear in future documents"
            Do NOT create blocking issues for PO items missing from the current document.

            ## CALCULATING matchScore

            matchScore = (fields with match=exact or close) / (total fields compared)
            If ANY field has isBlocking=true, matchScore should be < 0.5

            ## blockingIssues COUNT

            blockingIssues = count of results where severity = "blocking"
            NOT the count of fields, but the count of RESULTS (vendor/ship_to/line_item) that have blocking severity.

            ## EXPLANATION GUIDELINES

            For EXACT matches (match="exact"):
            - Use empty string "" or brief "Exact match"
            - No detailed explanation needed

            For CLOSE or MISMATCH (match="close" or match="mismatch"):
            - Provide a complete, detailed human-readable sentence
            - Easy to read and understand by non-technical users
            - Specific about what differs and why it matters
            - Do NOT include "BLOCKING:" prefix or match scores

            Good examples for close/mismatch:
            - "The address uses a slightly different format but refers to the same location."
            - "The purchase order specifies USD currency but the invoice is billed in CAD. This requires review as currency conversion may affect the final amount."
            - "The unit price on the invoice is $5.00 higher than what was agreed on the purchase order."

            Bad examples (avoid these):
            - "BLOCKING: Currency mismatch" (don't use BLOCKING prefix)
            - "score: 0.8" (don't include scores)

            ## EXAMPLE OUTPUT

            {
              "documentId": "mg-1769309498742-ceee7536",
              "poId": "2505007",
              "overallStatus": "partial",
              "confidence": 0.85,
              "blockingIssues": 1,
              "results": [
                {
                  "type": "vendor",
                  "status": "matched",
                  "matchScore": 0.95,
                  "severity": "info",
                  "comparisons": [
                    {
                      "field": "Name",
                      "poValue": "Anthropic, PBC",
                      "documentValue": "Anthropic, PBC",
                      "match": "exact",
                      "isBlocking": false,
                      "explanation": ""
                    },
                    {
                      "field": "Address",
                      "poValue": "548 Market Street, PMB 90375, San Francisco, CA 94104",
                      "documentValue": "548 Market Street, PMB 90375, San Francisco, California 94104",
                      "match": "close",
                      "isBlocking": false,
                      "explanation": "The address uses 'California' instead of 'CA' but refers to the same location."
                    }
                  ]
                },
                {
                  "type": "line_item",
                  "status": "unmatched",
                  "matchScore": 0.4,
                  "severity": "blocking",
                  "extractedIndex": 0,
                  "poIndex": 0,
                  "comparisons": [
                    {
                      "field": "Description",
                      "poValue": "Claude Pro (Dec 5, 2025 – Jan 5, 2026)",
                      "documentValue": "Claude Pro (Dec 5, 2025 – Jan 5, 2026)",
                      "match": "exact",
                      "isBlocking": false,
                      "explanation": ""
                    },
                    {
                      "field": "Quantity",
                      "poValue": "1",
                      "documentValue": "1",
                      "match": "exact",
                      "isBlocking": false,
                      "explanation": ""
                    },
                    {
                      "field": "Unit Price",
                      "poValue": "USD $28.00",
                      "documentValue": "CAD $28.00",
                      "match": "mismatch",
                      "isBlocking": true,
                      "explanation": "The purchase order specifies USD $28.00 but the invoice is billed in CAD $28.00. While the numeric amount is the same, the currencies differ which may affect the final payment amount."
                    }
                  ]
                }
              ]
            }
            """;
    }
}

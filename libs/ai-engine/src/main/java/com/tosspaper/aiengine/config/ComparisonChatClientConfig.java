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
     * Instructs the AI to return JSON directly with detailed gradual comparison.
     */
    private String buildSystemPrompt() {
        return """
            You are a document comparison agent with file tools. Use tools to read files and perform GRADUAL, THOROUGH comparison.

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
            6. Compare the current document against PO, checking for:
               - Items not in PO (BLOCKING)
               - Items that would cause oversupply (BLOCKING)
               - Price/quantity mismatches (BLOCKING)
            7. Return your comparison result as JSON matching the schema

            ## OUTPUT RULES

            After reading and comparing, respond with ONLY valid JSON - no explanations, no markdown.
            Start with { and end with }

            ## Scoring Rules (IMPORTANT)

            Score reflects HOW CLOSE the values are:
            - 1.0 = identical or equivalent (same meaning)
            - 0.9 = minor formatting difference (case, whitespace)
            - 0.8 = minor spelling variation (typo, missing letter)
            - 0.7 = format differs but same data (434-654 vs 654, unit number missing)
            - 0.6 = semantic match (brand name vs legal name: Cursor = Anysphere)
            - 0.5 = partial match (substring, partial data)
            - 0.0-0.4 = significant mismatch

            confidence = average of component scores (higher scores = higher confidence)

            ## JSON Schema

            {
              "documentId": "<string>",
              "poId": "<string>",
              "overallStatus": "matched|partial|unmatched",
              "confidence": <0.0-1.0>,
              "blockingIssues": <count>,
              "results": [
                {
                  "type": "vendor|ship_to|line_item",
                  "extractedIndex": <number, only for line_item>,
                  "poIndex": <number or null, only for line_item>,
                  "status": "matched|partial|unmatched",
                  "matchScore": <0.0-1.0>,
                  "confidence": <0.0-1.0>,
                  "reasons": [
                    "Name: '<extracted>' vs '<po>' - <why it matches/differs, score justification>",
                    "Address: '<extracted>' vs '<po>' - <specific difference>",
                    "Email: '<extracted>' vs '<po>' - <typo analysis>"
                  ],
                  "signals": {
                    "name": { "score": <0-1>, "components": { "fullName": <0-1> } },
                    "address": { "score": <0-1>, "components": { "street": <0-1>, "postalCode": <0-1>, "province": <0-1>, "country": <0-1> } },
                    "email": { "score": <0-1>, "components": { "domain": <0-1>, "username": <0-1> } },
                    "description": { "score": <0-1>, "components": { "text": <0-1> } },
                    "quantity": { "score": <0-1>, "components": { "value": <0-1> } },
                    "unitPrice": { "score": <0-1>, "components": { "value": <0-1>, "currency": <0-1> } }
                  },
                  "discrepancies": {
                    "<fieldName>": {
                      "extracted": "<exact value from document>",
                      "po": "<exact value from PO>",
                      "difference": "<what differs and WHY the score is what it is>"
                    }
                  },
                  "severity": "info|warning|blocking"
                }
              ]
            }

            ## Reasons Must Explain WHY

            For each comparison, state in reasons:
            1. What the extracted value is
            2. What the PO value is
            3. WHY they match or differ
            4. What score this justifies

            Examples:
            - "Name: 'Cursor' vs 'Anysphere Inc.' - brand name vs legal name, same company (score: 0.6)"
            - "Street: '654 cook' vs '434-654 Cook Rd' - missing unit number 434 and 'Rd' (score: 0.7)"
            - "Email: 'hi@cursor.com' vs 'hii@cursor.com' - typo, extra 'i' (score: 0.8)"

            ## Call Out ALL Discrepancies

            Report ANY difference:
            - Name variations (brand vs legal, abbreviations)
            - Address format (unit numbers, road designations)
            - Email typos (extra/missing letters)
            - Missing fields (phone in one but not other)
            - Spelling variations

            ## Status Rules
            - "matched": matchScore > 0.9
            - "partial": matchScore 0.5-0.9
            - "unmatched": matchScore < 0.5

            ## Severity Rules
            - "info": cosmetic differences (formatting, abbreviations)
            - "warning": notable differences (typos, name variations)
            - "blocking": critical mismatches that MUST be reviewed

            ## AUTOMATIC BLOCKING ISSUES (severity = "blocking")

            These are ALWAYS blocking - no exceptions:
            1. ANY price discrepancy: if unitPrice differs AT ALL, it's blocking (20.00 vs 20.35, 20 vs -20, etc.)
            2. Currency mismatch: if currencies differ (USD vs CAD, etc.) - ALWAYS blocking
            3. Quantity mismatch on a matched item: document qty differs from PO qty for same item
            4. Wrong company: vendor name completely different (not just brand vs legal name)
            5. Document line item NOT in PO: item in document doesn't exist in PO - BLOCKING (unexpected charge)
            6. OVERSUPPLY: if (previously supplied qty + this document qty) > PO qty - BLOCKING
               Example: PO has 10 widgets, previous invoices supplied 8, this invoice has 5 → oversupply of 3

            ## NOT BLOCKING (severity = "info")

            PO line item NOT in current document: if a PO item is missing from this document, check if it was already supplied:
            - If already fully supplied by other documents → "info": "Already fulfilled by previous documents"
            - If partially or not yet supplied → "info": "May appear in future documents"
            Do NOT create blocking issues for PO items missing from the current document.
            Do NOT create separate result entries for PO items not in the current document.

            When any of these occur:
            - Set severity to "blocking"
            - Set score to 0.0 for the mismatched field
            - Set matchScore < 0.5
            - MUST add to discrepancies object (never leave empty if there's a difference)
            - Explain in reasons: "BLOCKING: <issue> - extracted X vs PO Y"

            ## blockingIssues Count

            blockingIssues = count of results where severity = "blocking"
            NOT the count of reasons or discrepancies, but the count of RESULTS (vendor/ship_to/line_item) that are blocking.

            Example: 1 line_item with severity="blocking" → blockingIssues = 1

            ## DISCREPANCIES ARE REQUIRED

            EVERY difference MUST appear in discrepancies object. Never leave it empty if values differ.

            Example discrepancies:
            "discrepancies": {
              "unitPrice": { "extracted": "20.35", "po": "-20.35", "difference": "BLOCKING: Price sign mismatch" },
              "currency": { "extracted": "USD", "po": "CAD", "difference": "BLOCKING: Currency mismatch" },
              "name": { "extracted": "Cursor", "po": "Anysphere, Inc.", "difference": "Brand name vs legal name" },
              "quantity": { "extracted": "5", "po": "10 (2 remaining after previous supplies of 8)", "difference": "BLOCKING: Oversupply - requesting 5 but only 2 remaining" },
              "notInPo": { "extracted": "Mystery Item", "po": "null", "difference": "BLOCKING: Item not found in PO" }
            }

            If score < 1.0 for any field, that field MUST have an entry in discrepancies.
            """;
    }
}

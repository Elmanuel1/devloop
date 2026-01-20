package com.tosspaper.aiengine.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring AI configuration with separate ChatModel beans for different purposes.
 * Each model is optimized for its specific task (classification, generation, evaluation).
 * RestClient and WebClient are configured with ObservationRegistry for automatic tracing.
 */
@Configuration
public class ChatModelConfig {
    
    @Value("${spring.ai.openai.api-key}")
    private String apiKey;
    
        @Value("${spring.ai.openai.classifier.model:o4-mini}")
        private String classifierModel;
        
        @Value("${spring.ai.openai.generator.model:o4-mini}")
        private String generatorModel;
        
        @Value("${spring.ai.openai.evaluator.model:o4-mini}")
        private String evaluatorModel;
    
    @Value("${spring.ai.openai.classifier.temperature:1.0}")
    private double classifierTemperature;
    
    @Value("${spring.ai.openai.generator.temperature:1.0}")
    private double generatorTemperature;
    
    @Value("${spring.ai.openai.evaluator.temperature:1.0}")
    private double evaluatorTemperature;
    
    @Value("${spring.ai.openai.classifier.max-tokens:50}")
    private int classifierMaxTokens;
    
    @Value("${spring.ai.openai.generator.max-tokens:2000}")
    private int generatorMaxTokens;
    
    @Value("${spring.ai.openai.evaluator.max-tokens:3000}")
    private int evaluatorMaxTokens;
    
    /**
     * Configure RestClient.Builder with ObservationRegistry for automatic HTTP instrumentation.
     * This ensures OpenAI API calls (embeddings, completions) are traced and join parent spans.
     */
    private RestClient.Builder createRestClientBuilder(ObservationRegistry observationRegistry) {
        return RestClient.builder()
                .observationRegistry(observationRegistry);
    }
    
    /**
     * Configure WebClient.Builder with ObservationRegistry for automatic HTTP instrumentation.
     * This ensures streaming OpenAI API calls are traced and join parent spans.
     */
    private WebClient.Builder createWebClientBuilder(ObservationRegistry observationRegistry) {
        return WebClient.builder()
                .observationRegistry(observationRegistry);
    }

    /**
     * Create OpenAiApi instance using the builder pattern.
     */
    private OpenAiApi createOpenAiApi(ObservationRegistry observationRegistry) {
        return OpenAiApi.builder()
                .baseUrl("https://api.openai.com")
                .apiKey(new SimpleApiKey(apiKey))
                .restClientBuilder(createRestClientBuilder(observationRegistry))
                .webClientBuilder(createWebClientBuilder(observationRegistry))
                .build();
    }

    @Bean
    @Qualifier("classifierClient")
    public OpenAiChatModel classifierChatModel(ObservationRegistry observationRegistry) {
        return OpenAiChatModel.builder()
                .openAiApi(createOpenAiApi(observationRegistry))
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(classifierModel)
                        .temperature(classifierTemperature)
                        .maxCompletionTokens(classifierMaxTokens)
                        .build())
                .observationRegistry(observationRegistry)
                .build();
    }

    @Bean
    @Qualifier("generatorClient")
    public OpenAiChatModel generatorChatModel(ObservationRegistry observationRegistry) {
        return OpenAiChatModel.builder()
                .openAiApi(createOpenAiApi(observationRegistry))
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(generatorModel)
                        .temperature(generatorTemperature)
                        .maxCompletionTokens(generatorMaxTokens)
                        .build())
                .observationRegistry(observationRegistry)
                .build();
    }

    @Bean
    @Qualifier("evaluatorClient")
    public OpenAiChatModel evaluatorChatModel(ObservationRegistry observationRegistry) {
        return OpenAiChatModel.builder()
                .openAiApi(createOpenAiApi(observationRegistry))
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(evaluatorModel)
                        .temperature(evaluatorTemperature)
                        .maxCompletionTokens(evaluatorMaxTokens)
                        .build())
                .observationRegistry(observationRegistry)
                .build();
    }
}

package com.tosspaper.aiengine.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.dto.conformance.EvaluationResponse;
import com.tosspaper.aiengine.dto.conformance.ValidationIssue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple evaluator-optimizer workflow for document conformance using Spring AI.
 * Uses dual LLM approach: generator creates JSON, evaluator provides feedback for refinement.
 */
@Slf4j
@Component
public class ConformanceEvaluatorOptimizer {
    
    private final ChatModel generatorModel;
    private final ChatModel evaluatorModel;
    private final ObjectMapper objectMapper;
    
    @Value("${conformance.success-threshold:0.95}")
    private double successThreshold;
    
    @Value("${conformance.max-iterations:5}")
    private int maxIterations;
    
    public ConformanceEvaluatorOptimizer(
            @Qualifier("generatorClient") ChatModel generatorModel,
            @Qualifier("evaluatorClient") ChatModel evaluatorModel,
            ObjectMapper objectMapper) {
        this.generatorModel = generatorModel;
        this.evaluatorModel = evaluatorModel;
        this.objectMapper = objectMapper;
    }
    
    private static final Pattern JSON_PATTERN = Pattern.compile("```json\\s*(.*?)\\s*```", Pattern.DOTALL);
    
    /**
     * Run the evaluator-optimizer loop to conform document to schema.
     * Uses a simple iterative approach with dual LLMs.
     */
    public RefinedConformanceResult conform(
            String rawExtraction,
            String jsonSchema,
            String generationPrompt,
            String evaluationPrompt) throws JsonProcessingException {
        
        // Track iteration history
        List<IterationAttempt> history = new ArrayList<>();
        String currentPrompt = buildInitialPrompt(rawExtraction, jsonSchema, generationPrompt);
        String bestJson = null;
        double bestScore = 0.0;
        EvaluationResponse bestEvaluation = null;
        
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            log.info("Conformance iteration {}/{}", iteration, maxIterations);
            
            // GENERATOR: Generate/refine JSON
            String generatedContent = generateWithAI(currentPrompt);
            String generatedJson = extractJson(generatedContent);
            
            // EVALUATOR: Evaluate quality and provide feedback
            EvaluationResponse evaluation = evaluateWithAI(
                generatedJson, jsonSchema, rawExtraction, evaluationPrompt);
            
            // Track iteration
            IterationAttempt attempt = new IterationAttempt(
                iteration,
                evaluation.getScore(),
                extractIssueSummary(evaluation.getIssues())
            );
            history.add(attempt);
            
            // Track best attempt
            if (evaluation.getScore() > bestScore) {
                bestScore = evaluation.getScore();
                bestJson = generatedJson;
                bestEvaluation = evaluation;
            }
            
            // SUCCESS: Schema valid and score meets threshold
            if (evaluation.isSchemaValid() && evaluation.getScore() >= successThreshold) {
                log.info("Conformance succeeded on iteration {} with score {}", 
                    iteration, evaluation.getScore());
                
                return RefinedConformanceResult.success(
                    bestJson,
                    evaluation.getScore(),
                    iteration,
                    history,
                    evaluation
                );
            }
            
            // Build refined prompt with feedback for next iteration
            currentPrompt = buildRefinedPrompt(
                generationPrompt,
                jsonSchema,
                rawExtraction,
                generatedJson,
                evaluation
            );
        }
        
        // Max iterations reached - return best attempt with NEEDS_REVIEW status
        log.warn("Conformance did not meet threshold after {} iterations. Best score: {}", 
            maxIterations, bestScore);
        
        return RefinedConformanceResult.needsReview(
            bestJson,
            bestScore,
            maxIterations,
            history,
            bestEvaluation
        );
    }
    
    private String buildInitialPrompt(String rawData, String schema, String template) {
        return template
            .replace("{schema}", schema)
            .replace("{rawData}", rawData);
    }
    
    private String generateWithAI(String prompt) {
        // For generation, the prompt already contains both instructions and data
        Prompt promptObj = new Prompt(List.of(new UserMessage(prompt)));
        return generatorModel.call(promptObj).getResult().getOutput().getText();
    }
    
    private EvaluationResponse evaluateWithAI(
            String generatedJson,
            String jsonSchema,
            String rawData,
            String evaluationPromptTemplate) throws JsonProcessingException {
        
        String systemPrompt = evaluationPromptTemplate
            .replace("{schema}", jsonSchema);
        
        String userPrompt = String.format("""
            Generated JSON to evaluate:
            ```json
            %s
            ```
            
            Original raw data:
            ```json
            %s
            ```
            """, generatedJson, rawData);
        
        Prompt prompt = new Prompt(List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(userPrompt)
        ));
        String evaluationResult = evaluatorModel.call(prompt).getResult().getOutput().getText();
        
        return objectMapper.readValue(evaluationResult, EvaluationResponse.class);
    }
    
    private String buildRefinedPrompt(
            String originalTemplate,
            String jsonSchema,
            String rawData,
            String previousJson,
            EvaluationResponse evaluation) {
        
        String basePrompt = buildInitialPrompt(rawData, jsonSchema, originalTemplate);
        
        return String.format("""
            %s
            
            PREVIOUS ATTEMPT (Score: %.2f):
            ```json
            %s
            ```
            
            ISSUES FOUND:
            %s
            
            SUGGESTIONS FOR IMPROVEMENT:
            %s
            
            Please generate an IMPROVED version that addresses all the issues above.
            Return ONLY valid JSON matching the schema.
            """,
            basePrompt,
            evaluation.getScore(),
            previousJson,
            formatIssues(evaluation.getIssues()),
            evaluation.getSuggestions()
        );
    }
    
    private String extractJson(String content) {
        // Try to extract JSON from code blocks first
        Matcher matcher = JSON_PATTERN.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        // If no code blocks, try to find JSON object boundaries
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return content.substring(start, end + 1);
        }
        
        // Fallback to original content
        return content.trim();
    }
    
    private String formatIssues(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "No issues found";
        }
        
        StringBuilder sb = new StringBuilder();
        for (ValidationIssue issue : issues) {
            sb.append(String.format("- %s: %s (%s)\n", 
                issue.getField(), issue.getProblem(), issue.getSeverity()));
        }
        return sb.toString();
    }
    
    private List<String> extractIssueSummary(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        
        return issues.stream()
            .map(issue -> String.format("%s: %s", issue.getField(), issue.getProblem()))
            .toList();
    }
    
    /**
     * Result of conformance processing with iteration history.
     */
    public record RefinedConformanceResult(
        String conformedJson,
        double qualityScore,
        int attemptCount,
        List<IterationAttempt> history,
        EvaluationResponse finalEvaluation,
        boolean needsReview
    ) {
        public static RefinedConformanceResult success(String conformedJson, double qualityScore, 
                                                      int attemptCount, List<IterationAttempt> history,
                                                      EvaluationResponse finalEvaluation) {
            return new RefinedConformanceResult(conformedJson, qualityScore, attemptCount, 
                history, finalEvaluation, false);
        }
        
        public static RefinedConformanceResult needsReview(String conformedJson, double qualityScore, 
                                                          int attemptCount, List<IterationAttempt> history,
                                                          EvaluationResponse finalEvaluation) {
            return new RefinedConformanceResult(conformedJson, qualityScore, attemptCount, 
                history, finalEvaluation, true);
        }
    }
    
    /**
     * Summary of a single iteration attempt.
     */
    public record IterationAttempt(
        int iteration,
        double score,
        List<String> issues
    ) {}
}

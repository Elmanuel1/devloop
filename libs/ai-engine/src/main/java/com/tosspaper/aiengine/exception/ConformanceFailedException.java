package com.tosspaper.aiengine.exception;

import lombok.Getter;

import java.util.List;

/**
 * Exception thrown when document conformance fails after all retry attempts.
 */
@Getter
public class ConformanceFailedException extends RuntimeException {
    
    private final String bestAttemptJson;
    private final double bestScore;
    private final List<String> allIssues;
    private final int attemptCount;
    
    public ConformanceFailedException(String message, String bestAttemptJson, double bestScore, 
                                     List<String> allIssues, int attemptCount) {
        super(message);
        this.bestAttemptJson = bestAttemptJson;
        this.bestScore = bestScore;
        this.allIssues = allIssues;
        this.attemptCount = attemptCount;
    }
    
    public ConformanceFailedException(String message, Throwable cause, String bestAttemptJson, 
                                     double bestScore, List<String> allIssues, int attemptCount) {
        super(message, cause);
        this.bestAttemptJson = bestAttemptJson;
        this.bestScore = bestScore;
        this.allIssues = allIssues;
        this.attemptCount = attemptCount;
    }
}

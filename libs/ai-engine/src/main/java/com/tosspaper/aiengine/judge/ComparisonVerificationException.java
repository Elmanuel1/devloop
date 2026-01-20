package com.tosspaper.aiengine.judge;

import org.springaicommunity.agents.judge.jury.Verdict;
import org.springaicommunity.agents.judge.result.Judgment;

import java.util.Map;

/**
 * Exception thrown when comparison verification fails.
 *
 * <p>Contains the complete Verdict from the jury, including:
 * <ul>
 *   <li>Aggregated judgment (overall pass/fail)</li>
 *   <li>Individual judgments from each judge</li>
 * </ul>
 */
public class ComparisonVerificationException extends RuntimeException {

    private final Verdict verdict;

    public ComparisonVerificationException(Verdict verdict) {
        super("Comparison verification failed: " + verdict.aggregated().reasoning());
        this.verdict = verdict;
    }

    public ComparisonVerificationException(String message, Verdict verdict) {
        super(message);
        this.verdict = verdict;
    }

    /**
     * Returns the complete verdict from the jury.
     */
    public Verdict getVerdict() {
        return verdict;
    }

    /**
     * Returns the aggregated judgment from the jury.
     */
    public Judgment getAggregatedJudgment() {
        return verdict.aggregated();
    }

    /**
     * Returns individual judgments from each judge (by name).
     */
    public Map<String, Judgment> getIndividualJudgments() {
        return verdict.individualByName();
    }

    /**
     * Returns the reasoning for the failure.
     */
    public String getFailureReason() {
        return verdict.aggregated().reasoning();
    }
}

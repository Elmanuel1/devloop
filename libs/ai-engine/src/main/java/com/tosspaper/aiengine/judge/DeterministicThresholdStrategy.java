package com.tosspaper.aiengine.judge;

import org.springaicommunity.judge.jury.VotingStrategy;
import org.springaicommunity.judge.result.Judgment;
import org.springaicommunity.judge.result.JudgmentStatus;
import org.springaicommunity.judge.score.NumericalScore;

import java.util.List;
import java.util.Map;

/**
 * Voting strategy that requires deterministic judges to pass.
 *
 * <p>Uses weighted average scoring but sets threshold to the total weight
 * of deterministic judges, ensuring ALL deterministic judges must pass
 * for the verdict to pass. Non-deterministic (LLM) judges are informational
 * only - the user can review their results but they don't affect the verdict.
 *
 * <p>Example with 4 deterministic judges (weight 10 each = 40 total):
 * <ul>
 *   <li>All 4 pass: score = 1.0, threshold = 1.0 → PASS</li>
 *   <li>3 pass: score = 0.75, threshold = 1.0 → FAIL</li>
 *   <li>With future non-deterministic (weight 2.5 each = 10 total for 4):
 *       All deterministic pass + all non-deterministic fail = 40/50 = 0.8,
 *       threshold = 0.8 → PASS</li>
 * </ul>
 */
public class DeterministicThresholdStrategy implements VotingStrategy {

    private final double threshold;

    /**
     * Creates strategy with threshold equal to 1.0 (all must pass).
     * Use this when all judges are deterministic.
     */
    public DeterministicThresholdStrategy() {
        this(1.0);
    }

    /**
     * Creates strategy with custom threshold.
     *
     * @param threshold score threshold for passing (0.0 to 1.0)
     */
    public DeterministicThresholdStrategy(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be between 0.0 and 1.0");
        }
        this.threshold = threshold;
    }

    /**
     * Creates strategy where deterministic judges must all pass.
     *
     * @param deterministicWeight total weight of deterministic judges
     * @param totalWeight         total weight of all judges
     * @return strategy with threshold = deterministicWeight/totalWeight
     */
    public static DeterministicThresholdStrategy forDeterministicWeight(
            double deterministicWeight, double totalWeight) {
        return new DeterministicThresholdStrategy(deterministicWeight / totalWeight);
    }

    @Override
    public Judgment aggregate(List<Judgment> judgments, Map<String, Double> weights) {
        if (judgments.isEmpty()) {
            return Judgment.fail("No judgments to aggregate");
        }

        double totalWeight = 0.0;
        double weightedScore = 0.0;

        for (int i = 0; i < judgments.size(); i++) {
            Judgment judgment = judgments.get(i);
            double weight = weights.getOrDefault(String.valueOf(i), 1.0);
            totalWeight += weight;

            if (judgment.pass()) {
                weightedScore += weight;
            }
        }

        double score = totalWeight > 0 ? weightedScore / totalWeight : 0.0;
        boolean pass = score >= threshold;

        String reasoning = String.format(
                "Weighted average: %.2f (threshold: %.2f, result: %s)",
                score, threshold, pass ? "pass" : "fail");

        return Judgment.builder()
                .score(NumericalScore.normalized(score))
                .status(pass ? JudgmentStatus.PASS : JudgmentStatus.FAIL)
                .reasoning(reasoning)
                .build();
    }

    @Override
    public String getName() {
        return "deterministic-threshold";
    }
}

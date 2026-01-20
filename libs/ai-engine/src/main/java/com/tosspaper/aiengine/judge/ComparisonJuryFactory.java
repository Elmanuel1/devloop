package com.tosspaper.aiengine.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.agents.judge.Judges;
import org.springaicommunity.agents.judge.context.JudgmentContext;
import org.springaicommunity.agents.judge.jury.Jury;
import org.springaicommunity.agents.judge.jury.SimpleJury;
import org.springaicommunity.agents.judge.jury.Verdict;
import org.springaicommunity.agents.judge.jury.WeightedAverageStrategy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Factory for creating and running the comparison verification jury.
 *
 * <p>Creates a jury of deterministic judges that validate the comparison results:
 * <ol>
 *   <li>JsonObjectJudge - File exists and is valid wrapper object</li>
 *   <li>JsonSchemaValidationJudge - Results conform to JSON schema</li>
 *   <li>RequiredFieldsJudge - Each entry has required fields</li>
 *   <li>IndexValidationJudge - Line item extractedIndex values are valid</li>
 *   <li>PoIndexUniquenessJudge - No duplicate poIndex values (1:1 matching)</li>
 *   <li>ContactCoverageJudge - Both contacts are present</li>
 * </ol>
 *
 * <p>Uses default voting strategy (all judges must pass).
 * <p>All judges share a {@link ComparisonResultsReader} to avoid multiple file reads.
 */
@Component
@RequiredArgsConstructor
public class ComparisonJuryFactory {

    private final ObjectMapper objectMapper;

    /**
     * Creates a jury with all comparison verification judges.
     *
     * @param resultsPath             Path to the results JSON file
     * @param schemaPath              Path to the JSON schema file
     * @param expectedLineItemCount   Expected number of line items in the document
     * @return Jury configured with all judges
     */
    public Jury createJury(Path resultsPath, Path schemaPath, int expectedLineItemCount) {
        ComparisonResultsReader reader = new ComparisonResultsReader(resultsPath, objectMapper);

        // All deterministic judges get equal weight (10)
        return SimpleJury.builder()
                .judge(Judges.named(new JsonObjectJudge(reader), "json-object"), 10.0)
                .judge(Judges.named(new JsonSchemaValidationJudge(reader, schemaPath), "json-schema"), 10.0)
                .judge(Judges.named(new RequiredFieldsJudge(reader), "required-fields"), 10.0)
                .judge(Judges.named(new IndexValidationJudge(reader, expectedLineItemCount), "index-validation"), 10.0)
                .judge(Judges.named(new PoIndexUniquenessJudge(reader), "po-index-uniqueness"), 10.0)
                .judge(Judges.named(new ContactCoverageJudge(reader), "contact-coverage"), 10.0)
                .votingStrategy(new WeightedAverageStrategy())
                .build();
    }

    /**
     * Runs all judges and returns a verdict using weighted average strategy.
     *
     * @param resultsPath             Path to the results JSON file
     * @param schemaPath              Path to the JSON schema file
     * @param expectedLineItemCount   Expected number of line items
     * @param workspace               Working directory for judgment context
     * @return Verdict with aggregated and individual judgments
     */
    public Verdict runVerification(Path resultsPath, Path schemaPath, int expectedLineItemCount, Path workspace) {
        Jury jury = createJury(resultsPath, schemaPath, expectedLineItemCount);
        JudgmentContext context = JudgmentContext.builder()
                .workspace(workspace)
                .build();

        return jury.vote(context);
    }

    /**
     * Runs verification and throws exception if failed.
     *
     * @param resultsPath             Path to the results JSON file
     * @param schemaPath              Path to the JSON schema file
     * @param expectedLineItemCount   Expected number of line items
     * @param workspace               Working directory for judgment context
     * @throws ComparisonVerificationException if verification fails
     */
    public void verifyOrThrow(Path resultsPath, Path schemaPath, int expectedLineItemCount, Path workspace) {
        Verdict verdict = runVerification(resultsPath, schemaPath, expectedLineItemCount, workspace);
        if (!verdict.aggregated().pass()) {
            throw new ComparisonVerificationException(verdict);
        }
    }
}

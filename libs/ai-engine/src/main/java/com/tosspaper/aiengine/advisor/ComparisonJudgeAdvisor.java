package com.tosspaper.aiengine.advisor;

import com.tosspaper.aiengine.judge.ComparisonJuryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springaicommunity.agents.advisors.judge.JudgeAdvisor;
import org.springaicommunity.agents.client.AgentClientRequest;
import org.springaicommunity.agents.client.AgentClientResponse;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisor;
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain;
import org.springaicommunity.judge.jury.Verdict;
import org.springframework.core.Ordered;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Advisor that runs deterministic judges after agent execution to verify results.
 *
 * <p>This advisor:
 * <ol>
 *   <li>Lets the agent complete its execution</li>
 *   <li>Gets PreparedContext from response (added by ComparisonContextAdvisor)</li>
 *   <li>Runs the judge chain to verify the results file</li>
 *   <li>Adds the verdict to the response context</li>
 * </ol>
 *
 * <p>Order: Runs after ComparisonContextAdvisor, before AgentAuditAdvisor
 */
@Slf4j
@RequiredArgsConstructor
public class ComparisonJudgeAdvisor implements AgentCallAdvisor {

    public static final String JUDGE_VERDICT_KEY = "judgeVerdict";

    private static final int ORDER = Ordered.LOWEST_PRECEDENCE - 50;

    private final ComparisonJuryFactory juryFactory;

    @Override
    public @NotNull String getName() {
        return "ComparisonJudgeAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public @NotNull AgentClientResponse adviseCall(@NotNull AgentClientRequest request,
                                                    @NotNull AgentCallAdvisorChain chain) {
        // Get PreparedContext from request (added by ComparisonContextAdvisor upstream)
        ComparisonContextAdvisor.PreparedContext preparedContext =
                (ComparisonContextAdvisor.PreparedContext) request.context()
                        .get(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY);
        if (preparedContext == null) {
            log.warn("PreparedContext not found in request - skipping judge verification");
            return chain.nextCall(request);
        }

        // Execute the agent
        AgentClientResponse response = chain.nextCall(request);

        // After agent completes, run judges
        Path resultsPath = preparedContext.resultsPath();
        Path schemaPath = preparedContext.schemaPath();
        int lineItemCount = preparedContext.documentLineItemCount();
        Path workspace = preparedContext.workingDirectory();

        log.debug("Running judge verification: resultsPath={}, schemaPath={}, expectedLineItems={}",
                resultsPath, schemaPath, lineItemCount);

        Verdict verdict = juryFactory.runVerification(resultsPath, schemaPath, lineItemCount, workspace);

        log.info("Judge verification {}: {}",
                verdict.aggregated().pass() ? "PASSED" : "FAILED",
                verdict.aggregated().reasoning());

        // Log individual judge results
        verdict.individualByName().forEach((name, judgment) ->
                log.debug("  Judge [{}]: {} - {}", name, judgment.status(), judgment.reasoning()));

        // Merge verdict into response context
        Map<String, Object> enrichedContext = new HashMap<>(response.context());
        enrichedContext.put(JUDGE_VERDICT_KEY, verdict);

        return new AgentClientResponse(response.agentResponse(), enrichedContext);
    }
}

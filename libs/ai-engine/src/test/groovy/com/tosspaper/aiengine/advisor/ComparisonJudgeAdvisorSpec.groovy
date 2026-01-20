package com.tosspaper.aiengine.advisor

import com.tosspaper.aiengine.judge.ComparisonJuryFactory
import org.springaicommunity.agents.client.AgentClientRequest
import org.springaicommunity.agents.client.AgentClientResponse
import org.springaicommunity.agents.client.Goal
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain
import org.springaicommunity.agents.judge.jury.Verdict
import org.springaicommunity.agents.judge.result.Judgment
import org.springaicommunity.agents.model.AgentResponse
import org.springframework.core.Ordered
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Unit tests for ComparisonJudgeAdvisor.
 */
class ComparisonJudgeAdvisorSpec extends Specification {

    @TempDir
    Path tempDir

    ComparisonJuryFactory juryFactory

    @Subject
    ComparisonJudgeAdvisor advisor

    def setup() {
        juryFactory = Mock()
        advisor = new ComparisonJudgeAdvisor(juryFactory)
    }

    def "should return correct advisor name"() {
        expect:
        advisor.getName() == "ComparisonJudgeAdvisor"
    }

    def "should have correct ordering - after context advisor, before audit"() {
        expect:
        advisor.getOrder() == Ordered.LOWEST_PRECEDENCE - 50
    }

    def "should skip verification when PreparedContext is not found in response"() {
        given: "a response without PreparedContext"
        def workingDir = tempDir.resolve("companies/1/po/PO-456")
        def request = new AgentClientRequest(
            new Goal("test goal"),
            workingDir,
            null,
            [:]
        )
        def chainResponse = new AgentClientResponse(Mock(AgentResponse), [:])
        def chain = Mock(AgentCallAdvisorChain)

        when: "adviseCall is invoked"
        def response = advisor.adviseCall(request, chain)

        then: "chain is called"
        1 * chain.nextCall(request) >> chainResponse

        and: "no jury verification is run"
        0 * juryFactory._

        and: "response has no verdict"
        response.context().get(ComparisonJudgeAdvisor.JUDGE_VERDICT_KEY) == null
    }

    def "should run jury verification when PreparedContext is present"() {
        given: "a response with PreparedContext"
        def workingDir = tempDir.resolve("companies/1/po/PO-456")
        def poPath = tempDir.resolve("companies/1/po/PO-456/po.json")
        def docPath = tempDir.resolve("companies/1/po/PO-456/invoice/doc-123.json")
        def resultsPath = tempDir.resolve("companies/1/po/PO-456/_results.json")
        def schemaPath = tempDir.resolve("schema.json")

        def prepared = new ComparisonContextAdvisor.PreparedContext(
            workingDir, poPath, docPath, resultsPath, schemaPath, 3, 1L, "PO-456"
        )

        def request = new AgentClientRequest(
            new Goal("test goal"),
            workingDir,
            null,
            [(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY): prepared]
        )
        def chainResponse = new AgentClientResponse(
            Mock(AgentResponse),
            [(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY): prepared]
        )
        def chain = Mock(AgentCallAdvisorChain)

        def verdict = Verdict.builder()
            .aggregated(Judgment.pass("All checks passed"))
            .individualByName([:])
            .build()

        when: "adviseCall is invoked"
        def response = advisor.adviseCall(request, chain)

        then: "chain is called first"
        1 * chain.nextCall(request) >> chainResponse

        and: "jury verification is run with correct parameters"
        1 * juryFactory.runVerification(resultsPath, schemaPath, 3, workingDir) >> verdict

        and: "verdict is added to response context"
        response.context().get(ComparisonJudgeAdvisor.JUDGE_VERDICT_KEY) == verdict
    }

    def "should preserve PreparedContext in response when adding verdict"() {
        given: "a response with PreparedContext"
        def workingDir = tempDir.resolve("companies/1/po/PO-456")
        def poPath = tempDir.resolve("companies/1/po/PO-456/po.json")
        def docPath = tempDir.resolve("companies/1/po/PO-456/invoice/doc-123.json")
        def resultsPath = tempDir.resolve("companies/1/po/PO-456/_results.json")
        def schemaPath = tempDir.resolve("schema.json")

        def prepared = new ComparisonContextAdvisor.PreparedContext(
            workingDir, poPath, docPath, resultsPath, schemaPath, 5, 1L, "PO-456"
        )

        def request = new AgentClientRequest(
            new Goal("test goal"),
            workingDir,
            null,
            [(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY): prepared]
        )
        def chainResponse = new AgentClientResponse(
            Mock(AgentResponse),
            [(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY): prepared]
        )
        def chain = Mock(AgentCallAdvisorChain)

        def verdict = Verdict.builder()
            .aggregated(Judgment.fail("Some checks failed"))
            .individualByName([:])
            .build()

        when: "adviseCall is invoked"
        def response = advisor.adviseCall(request, chain)

        then: "chain is called"
        1 * chain.nextCall(request) >> chainResponse

        and: "jury verification is run"
        1 * juryFactory.runVerification(resultsPath, schemaPath, 5, workingDir) >> verdict

        and: "both PreparedContext and verdict are in response"
        response.context().get(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY) == prepared
        response.context().get(ComparisonJudgeAdvisor.JUDGE_VERDICT_KEY) == verdict
    }

    def "should include individual judgments in verdict"() {
        given: "a response with PreparedContext"
        def workingDir = tempDir.resolve("companies/1/po/PO-456")
        def resultsPath = tempDir.resolve("companies/1/po/PO-456/_results.json")
        def schemaPath = tempDir.resolve("schema.json")

        def prepared = new ComparisonContextAdvisor.PreparedContext(
            workingDir,
            tempDir.resolve("companies/1/po/PO-456/po.json"),
            tempDir.resolve("companies/1/po/PO-456/invoice/doc.json"),
            resultsPath,
            schemaPath,
            2,
            1L,
            "PO-456"
        )

        def request = new AgentClientRequest(
            new Goal("test goal"),
            workingDir,
            null,
            [(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY): prepared]
        )
        def chainResponse = new AgentClientResponse(
            Mock(AgentResponse),
            [(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY): prepared]
        )
        def chain = Mock(AgentCallAdvisorChain)

        def individualJudgments = [
            "json-object": Judgment.pass("Valid JSON object"),
            "contacts": Judgment.pass("Both contacts present")
        ]
        def verdict = Verdict.builder()
            .aggregated(Judgment.pass("All passed"))
            .individualByName(individualJudgments)
            .build()

        when: "adviseCall is invoked"
        def response = advisor.adviseCall(request, chain)

        then: "chain is called"
        1 * chain.nextCall(request) >> chainResponse

        and: "jury verification is run"
        1 * juryFactory.runVerification(resultsPath, schemaPath, 2, workingDir) >> verdict

        and: "verdict with individual judgments is in response"
        def returnedVerdict = response.context().get(ComparisonJudgeAdvisor.JUDGE_VERDICT_KEY) as Verdict
        returnedVerdict.aggregated().pass()
        returnedVerdict.individualByName().size() == 2
        returnedVerdict.individualByName().get("json-object").pass()
        returnedVerdict.individualByName().get("contacts").pass()
    }

    def "should handle failed verdict"() {
        given: "a response with PreparedContext"
        def workingDir = tempDir.resolve("companies/1/po/PO-456")
        def resultsPath = tempDir.resolve("companies/1/po/PO-456/_results.json")
        def schemaPath = tempDir.resolve("schema.json")

        def prepared = new ComparisonContextAdvisor.PreparedContext(
            workingDir,
            tempDir.resolve("companies/1/po/PO-456/po.json"),
            tempDir.resolve("companies/1/po/PO-456/invoice/doc.json"),
            resultsPath,
            schemaPath,
            2,
            1L,
            "PO-456"
        )

        def request = new AgentClientRequest(
            new Goal("test goal"),
            workingDir,
            null,
            [(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY): prepared]
        )
        def chainResponse = new AgentClientResponse(
            Mock(AgentResponse),
            [(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY): prepared]
        )
        def chain = Mock(AgentCallAdvisorChain)

        def individualJudgments = [
            "json-object": Judgment.fail("File not found or invalid JSON"),
            "contacts": Judgment.abstain("Cannot check contacts without valid JSON")
        ]
        def verdict = Verdict.builder()
            .aggregated(Judgment.fail("JSON file is not valid"))
            .individualByName(individualJudgments)
            .build()

        when: "adviseCall is invoked"
        def response = advisor.adviseCall(request, chain)

        then: "chain is called"
        1 * chain.nextCall(request) >> chainResponse

        and: "jury verification is run"
        1 * juryFactory.runVerification(resultsPath, schemaPath, 2, workingDir) >> verdict

        and: "failed verdict is in response"
        def returnedVerdict = response.context().get(ComparisonJudgeAdvisor.JUDGE_VERDICT_KEY) as Verdict
        !returnedVerdict.aggregated().pass()
        !returnedVerdict.individualByName().get("json-object").pass()
    }
}

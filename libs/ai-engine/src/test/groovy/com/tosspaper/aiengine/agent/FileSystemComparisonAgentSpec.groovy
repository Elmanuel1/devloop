package com.tosspaper.aiengine.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.judge.ComparisonJuryFactory
import com.tosspaper.aiengine.judge.ComparisonReportBuilder
import com.tosspaper.aiengine.judge.ComparisonVerificationException
import com.tosspaper.aiengine.loaders.JsonSchemaLoader
import com.tosspaper.aiengine.vfs.VFSContextMapper
import com.tosspaper.aiengine.vfs.VirtualFilesystemService
import com.tosspaper.models.domain.ComparisonContext
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.PurchaseOrder
import org.springaicommunity.agents.claude.ClaudeAgentOptions
import org.springaicommunity.agents.claude.sdk.ClaudeAgentClient
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Unit tests for FileSystemComparisonAgent.
 * Note: Full integration tests require Claude CLI to be available.
 * Only early-failure paths and configuration are tested here.
 */
class FileSystemComparisonAgentSpec extends Specification {

    @TempDir
    Path tempDir

    ClaudeAgentClient claudeClient
    ClaudeAgentOptions agentOptions
    VirtualFilesystemService vfsService
    VFSContextMapper contextMapper
    ObjectMapper objectMapper
    ComparisonJuryFactory juryFactory
    ComparisonReportBuilder reportBuilder
    JsonSchemaLoader schemaLoader

    @Subject
    FileSystemComparisonAgent agent

    def setup() {
        claudeClient = Mock()
        agentOptions = ClaudeAgentOptions.builder()
            .model("claude-sonnet-4-20250514")
            .yolo(false)
            .build()
        vfsService = Mock()
        contextMapper = Mock()
        objectMapper = new ObjectMapper()
        juryFactory = new ComparisonJuryFactory(objectMapper)
        reportBuilder = new ComparisonReportBuilder(objectMapper)
        schemaLoader = Mock()
        schemaLoader.getSchemaPath("comparison") >> tempDir.resolve("schema.json")
        agent = new FileSystemComparisonAgent(
            claudeClient,
            agentOptions,
            vfsService,
            contextMapper,
            objectMapper,
            juryFactory,
            reportBuilder,
            schemaLoader
        )
    }

    def "should throw ComparisonVerificationException when verification fails"() {
        given: "an extraction task and PO"
        def task = ExtractionTask.builder()
            .companyId(1L)
            .assignedId("doc-123")
            .documentType(DocumentType.INVOICE)
            .poNumber("PO-456")
            .conformedJson('{"items": []}')
            .build()

        def po = PurchaseOrder.builder()
            .id("po-789")
            .companyId(1L)
            .displayId("PO-456")
            .build()

        def context = new ComparisonContext(po, task)

        and: "VFS returns working directory"
        vfsService.getWorkingDirectory(1L, "PO-456") >> tempDir.resolve("companies/1/po/PO-456")

        when: "executing comparison (will fail because Claude CLI is not available in test)"
        agent.executeComparison(context)

        then: "ComparisonVerificationException is thrown"
        thrown(ComparisonVerificationException)
    }
}

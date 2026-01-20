package com.tosspaper.aiengine.advisor

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.loaders.JsonSchemaLoader
import com.tosspaper.aiengine.vfs.VFSContextMapper
import com.tosspaper.aiengine.vfs.VfsDocumentContext
import com.tosspaper.aiengine.vfs.VirtualFilesystemService
import com.tosspaper.models.domain.ComparisonContext
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.PurchaseOrder
import org.springaicommunity.agents.client.AgentClientRequest
import org.springaicommunity.agents.client.AgentClientResponse
import org.springaicommunity.agents.client.Goal
import org.springaicommunity.agents.client.advisor.api.AgentCallAdvisorChain
import org.springaicommunity.agents.model.AgentResponse
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Unit tests for ComparisonContextAdvisor.
 */
class ComparisonContextAdvisorSpec extends Specification {

    @TempDir
    Path tempDir

    VirtualFilesystemService vfsService
    VFSContextMapper contextMapper
    JsonSchemaLoader schemaLoader
    ObjectMapper objectMapper = new ObjectMapper()

    def "should prepare context and save files to VFS"() {
        given: "comparison context"
        def task = ExtractionTask.builder()
            .companyId(1L)
            .assignedId("doc-123")
            .documentType(DocumentType.INVOICE)
            .poNumber("PO-456")
            .conformedJson('{"items": []}')
            .build()

        def po = PurchaseOrder.builder()
            .id("po-id")
            .companyId(1L)
            .displayId("PO-456")
            .build()

        def context = new ComparisonContext(po, task)

        def poContext = VfsDocumentContext.builder()
            .companyId(1L)
            .poNumber("PO-456")
            .documentId("po")
            .documentType(DocumentType.PURCHASE_ORDER)
            .content('{"po": "data"}')
            .build()

        def poPath = tempDir.resolve("companies/1/po/PO-456/po.json")
        def docPath = tempDir.resolve("companies/1/po/PO-456/invoice/doc-123.json")
        def workingDir = tempDir.resolve("companies/1/po/PO-456")

        and: "mock services"
        vfsService = Mock()
        contextMapper = Mock()
        schemaLoader = Mock()
        def schemaPath = tempDir.resolve("schema.json")

        and: "create advisor with context"
        @Subject
        def advisor = new ComparisonContextAdvisor(context, vfsService, contextMapper, objectMapper, schemaLoader)

        and: "mock request and chain"
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

        then: "PO is saved to VFS"
        1 * contextMapper.from(po) >> poContext
        1 * vfsService.save(poContext) >> poPath

        and: "document is saved to VFS"
        1 * vfsService.save(_ as VfsDocumentContext) >> docPath

        and: "working directory is retrieved"
        1 * vfsService.getWorkingDirectory(1L, "PO-456") >> workingDir

        and: "schema path is retrieved"
        1 * schemaLoader.getSchemaPath("comparison") >> schemaPath

        and: "chain is called"
        1 * chain.nextCall(_ as AgentClientRequest) >> chainResponse

        and: "response contains prepared context"
        def prepared = response.context().get(ComparisonContextAdvisor.PREPARED_CONTEXT_KEY)
        prepared != null
        prepared.workingDirectory() == workingDir
        prepared.poPath() == poPath
        prepared.documentPath() == docPath
        prepared.resultsPath().toString().endsWith("_results.json")
        prepared.schemaPath() == schemaPath
        prepared.documentLineItemCount() == 0  // conformedJson has {"items": []}
        prepared.companyId() == 1L
        prepared.poNumber() == "PO-456"
    }

    def "should return correct advisor name"() {
        given: "mock context and services"
        def task = ExtractionTask.builder()
            .companyId(1L)
            .assignedId("doc-123")
            .documentType(DocumentType.INVOICE)
            .poNumber("PO-456")
            .build()
        def po = PurchaseOrder.builder().id("po-id").companyId(1L).displayId("PO-456").build()
        def context = new ComparisonContext(po, task)
        vfsService = Mock()
        contextMapper = Mock()
        schemaLoader = Mock()

        @Subject
        def advisor = new ComparisonContextAdvisor(context, vfsService, contextMapper, objectMapper, schemaLoader)

        expect:
        advisor.getName() == "ComparisonContextAdvisor"
    }

    def "should have highest precedence order"() {
        given: "mock context and services"
        def task = ExtractionTask.builder()
            .companyId(1L)
            .assignedId("doc-123")
            .documentType(DocumentType.INVOICE)
            .poNumber("PO-456")
            .build()
        def po = PurchaseOrder.builder().id("po-id").companyId(1L).displayId("PO-456").build()
        def context = new ComparisonContext(po, task)
        vfsService = Mock()
        contextMapper = Mock()
        schemaLoader = Mock()

        @Subject
        def advisor = new ComparisonContextAdvisor(context, vfsService, contextMapper, objectMapper, schemaLoader)

        expect:
        advisor.getOrder() == org.springframework.core.Ordered.HIGHEST_PRECEDENCE
    }
}

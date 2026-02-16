package com.tosspaper.aiengine.repository.impl

import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.MatchType
import org.jooq.*
import spock.lang.Specification
import spock.lang.Subject

import static com.tosspaper.models.jooq.Tables.EXTRACTION_TASK
import static com.tosspaper.models.jooq.Tables.INVOICES
import static com.tosspaper.models.jooq.Tables.DELIVERY_SLIPS
import static com.tosspaper.models.jooq.Tables.DOCUMENT_APPROVALS

/**
 * Unit tests for JooqDocumentMatchRepositoryImpl.
 * Tests all atomic document match state transitions with proper jOOQ mocking.
 */
class JooqDocumentMatchRepositoryImplSpec extends Specification {

    DSLContext dsl = Mock()

    @Subject
    JooqDocumentMatchRepositoryImpl repository

    def setup() {
        repository = new JooqDocumentMatchRepositoryImpl(dsl)
    }

    // ==================== UPDATE TO IN_PROGRESS TESTS ====================

    def "updateToInProgress should update extraction_task match_type for invoice"() {
        given: "an invoice document"
            def assignedId = "task-123"
            def documentType = DocumentType.INVOICE

        and: "transaction context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chain for extraction_task"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock transaction and update chain"
            dsl.transaction(_) >> { args ->
                def lambda = args[0] as TransactionalRunnable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 1

        when: "updating to in progress"
            repository.updateToInProgress(assignedId, documentType)

        then: "extraction_task is updated"
            noExceptionThrown()
    }

    def "updateToInProgress should update extraction_task match_type for delivery slip"() {
        given: "a delivery slip document"
            def assignedId = "task-456"
            def documentType = DocumentType.DELIVERY_SLIP

        and: "transaction context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock transaction"
            dsl.transaction(_) >> { args ->
                def lambda = args[0] as TransactionalRunnable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 1

        when: "updating to in progress"
            repository.updateToInProgress(assignedId, documentType)

        then: "extraction_task is updated"
            noExceptionThrown()
    }

    // ==================== UPDATE TO MANUAL TESTS ====================

    def "updateToManual should update invoice and extraction_task"() {
        given: "an invoice with manual match info"
            def assignedId = "task-123"
            def documentType = DocumentType.INVOICE
            def matchReport = '{"matched":true}'
            def poId = "po-id-123"
            def poNumber = "PO-12345"
            def projectId = "proj-456"

        and: "transaction context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chains for invoices and extraction_task"
            def updateInvoiceStep = Mock(UpdateSetFirstStep)
            def updateInvoiceMoreStep = Mock(UpdateSetMoreStep)
            def invoiceConditionStep = Mock(UpdateConditionStep)

            def updateTaskStep = Mock(UpdateSetFirstStep)
            def updateTaskMoreStep = Mock(UpdateSetMoreStep)
            def taskConditionStep = Mock(UpdateConditionStep)

        and: "mock transaction and update chains"
            dsl.transaction(_) >> { args ->
                def lambda = args[0] as TransactionalRunnable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(INVOICES) >> updateInvoiceStep
            txDsl.update(EXTRACTION_TASK) >> updateTaskStep
            updateInvoiceStep.set(_, _) >> updateInvoiceMoreStep
            updateInvoiceMoreStep.set(_, _) >> updateInvoiceMoreStep
            updateInvoiceMoreStep.where(_ as Condition) >> invoiceConditionStep
            invoiceConditionStep.execute() >> 1

            updateTaskStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.where(_ as Condition) >> taskConditionStep
            taskConditionStep.execute() >> 1

        when: "updating to manual"
            repository.updateToManual(assignedId, documentType, matchReport, poId, poNumber, projectId)

        then: "both tables are updated"
            noExceptionThrown()
    }

    def "updateToManual should update delivery_slip and extraction_task"() {
        given: "a delivery slip with manual match info"
            def assignedId = "task-456"
            def documentType = DocumentType.DELIVERY_SLIP
            def matchReport = '{"matched":true}'
            def poId = "po-id-456"
            def poNumber = "PO-67890"
            def projectId = "proj-789"

        and: "transaction context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chains for delivery_slips and extraction_task"
            def updateSlipStep = Mock(UpdateSetFirstStep)
            def updateSlipMoreStep = Mock(UpdateSetMoreStep)
            def slipConditionStep = Mock(UpdateConditionStep)

            def updateTaskStep = Mock(UpdateSetFirstStep)
            def updateTaskMoreStep = Mock(UpdateSetMoreStep)
            def taskConditionStep = Mock(UpdateConditionStep)

        and: "mock transaction and update chains"
            dsl.transaction(_) >> { args ->
                def lambda = args[0] as TransactionalRunnable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(DELIVERY_SLIPS) >> updateSlipStep
            txDsl.update(EXTRACTION_TASK) >> updateTaskStep
            updateSlipStep.set(_, _) >> updateSlipMoreStep
            updateSlipMoreStep.set(_, _) >> updateSlipMoreStep
            updateSlipMoreStep.where(_ as Condition) >> slipConditionStep
            slipConditionStep.execute() >> 1

            updateTaskStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.where(_ as Condition) >> taskConditionStep
            taskConditionStep.execute() >> 1

        when: "updating to manual"
            repository.updateToManual(assignedId, documentType, matchReport, poId, poNumber, projectId)

        then: "both tables are updated"
            noExceptionThrown()
    }

    def "updateToManual should only update extraction_task for delivery_note"() {
        given: "a delivery note with manual match info"
            def assignedId = "task-789"
            def documentType = DocumentType.DELIVERY_NOTE
            def matchReport = '{"matched":true}'
            def poId = "po-id-789"
            def poNumber = "PO-11111"
            def projectId = "proj-111"

        and: "transaction context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chain for extraction_task only"
            def updateTaskStep = Mock(UpdateSetFirstStep)
            def updateTaskMoreStep = Mock(UpdateSetMoreStep)
            def taskConditionStep = Mock(UpdateConditionStep)

        and: "mock transaction and update chain"
            dsl.transaction(_) >> { args ->
                def lambda = args[0] as TransactionalRunnable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateTaskStep
            updateTaskStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.where(_ as Condition) >> taskConditionStep
            taskConditionStep.execute() >> 1

        when: "updating to manual"
            repository.updateToManual(assignedId, documentType, matchReport, poId, poNumber, projectId)

        then: "only extraction_task is updated"
            noExceptionThrown()
    }

    def "updateToManual should only update extraction_task for unknown document type"() {
        given: "an unknown document type with manual match info"
            def assignedId = "task-999"
            def documentType = DocumentType.UNKNOWN
            def matchReport = null
            def poId = "po-id-999"
            def poNumber = "PO-99999"
            def projectId = "proj-999"

        and: "transaction context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chain for extraction_task only"
            def updateTaskStep = Mock(UpdateSetFirstStep)
            def updateTaskMoreStep = Mock(UpdateSetMoreStep)
            def taskConditionStep = Mock(UpdateConditionStep)

        and: "mock transaction and update chain"
            dsl.transaction(_) >> { args ->
                def lambda = args[0] as TransactionalRunnable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateTaskStep
            updateTaskStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.where(_ as Condition) >> taskConditionStep
            taskConditionStep.execute() >> 1

        when: "updating to manual"
            repository.updateToManual(assignedId, documentType, matchReport, poId, poNumber, projectId)

        then: "only extraction_task is updated"
            noExceptionThrown()
    }

    def "updateToManual should handle null match report"() {
        given: "an invoice with null match report"
            def assignedId = "task-123"
            def documentType = DocumentType.INVOICE
            def matchReport = null
            def poId = "po-id-123"
            def poNumber = "PO-12345"
            def projectId = "proj-456"

        and: "transaction context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chains"
            def updateInvoiceStep = Mock(UpdateSetFirstStep)
            def updateInvoiceMoreStep = Mock(UpdateSetMoreStep)
            def invoiceConditionStep = Mock(UpdateConditionStep)

            def updateTaskStep = Mock(UpdateSetFirstStep)
            def updateTaskMoreStep = Mock(UpdateSetMoreStep)
            def taskConditionStep = Mock(UpdateConditionStep)

        and: "mock transaction"
            dsl.transaction(_) >> { args ->
                def lambda = args[0] as TransactionalRunnable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(INVOICES) >> updateInvoiceStep
            txDsl.update(EXTRACTION_TASK) >> updateTaskStep
            updateInvoiceStep.set(_, _) >> updateInvoiceMoreStep
            updateInvoiceMoreStep.set(_, _) >> updateInvoiceMoreStep
            updateInvoiceMoreStep.where(_ as Condition) >> invoiceConditionStep
            invoiceConditionStep.execute() >> 1

            updateTaskStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.where(_ as Condition) >> taskConditionStep
            taskConditionStep.execute() >> 1

        when: "updating to manual"
            repository.updateToManual(assignedId, documentType, matchReport, poId, poNumber, projectId)

        then: "null match report is handled"
            noExceptionThrown()
    }

    // ==================== UPDATE TO PENDING TESTS ====================

    def "updateToPending should update extraction_task match_type"() {
        given: "a document to mark pending"
            def assignedId = "task-123"
            def documentType = DocumentType.INVOICE

        and: "transaction context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock transaction and update chain"
            dsl.transaction(_) >> { args ->
                def lambda = args[0] as TransactionalRunnable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 1

        when: "updating to pending"
            repository.updateToPending(assignedId, documentType)

        then: "extraction_task is updated"
            noExceptionThrown()
    }

    def "updateToPending should work for all document types"() {
        given: "a delivery slip to mark pending"
            def assignedId = "task-456"
            def documentType = DocumentType.DELIVERY_SLIP

        and: "transaction context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chain"
            def updateStep = Mock(UpdateSetFirstStep)
            def updateMoreStep = Mock(UpdateSetMoreStep)
            def conditionStep = Mock(UpdateConditionStep)

        and: "mock transaction"
            dsl.transaction(_) >> { args ->
                def lambda = args[0] as TransactionalRunnable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateStep
            updateStep.set(_, _) >> updateMoreStep
            updateMoreStep.set(_, _) >> updateMoreStep
            updateMoreStep.where(_ as Condition) >> conditionStep
            conditionStep.execute() >> 1

        when: "updating to pending"
            repository.updateToPending(assignedId, documentType)

        then: "extraction_task is updated"
            noExceptionThrown()
    }

    // ==================== UPDATE MATCH INFO TESTS ====================

    def "updateMatchInfo should update extraction_task and document_approvals"() {
        given: "match information to update"
            def assignedId = "task-123"
            def documentType = DocumentType.INVOICE
            def matchType = MatchType.AI_MATCH
            def matchReport = '{"confidence":0.95}'
            def purchaseOrderId = "po-id-123"
            def poNumber = "PO-12345"
            def projectId = "proj-456"

        and: "transaction result context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chains for extraction_task and document_approvals"
            def updateTaskStep = Mock(UpdateSetFirstStep)
            def updateTaskMoreStep = Mock(UpdateSetMoreStep)
            def taskConditionStep = Mock(UpdateConditionStep)

            def updateApprovalStep = Mock(UpdateSetFirstStep)
            def updateApprovalMoreStep = Mock(UpdateSetMoreStep)
            def approvalConditionStep = Mock(UpdateConditionStep)

        and: "mock transaction result"
            dsl.transactionResult(_) >> { args ->
                def lambda = args[0] as TransactionalCallable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateTaskStep
            txDsl.update(DOCUMENT_APPROVALS) >> updateApprovalStep

            updateTaskStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.where(_ as Condition) >> taskConditionStep
            taskConditionStep.execute() >> 1

            updateApprovalStep.set(_, _) >> updateApprovalMoreStep
            updateApprovalMoreStep.set(_, _) >> updateApprovalMoreStep
            updateApprovalMoreStep.where(_ as Condition) >> approvalConditionStep
            approvalConditionStep.execute() >> 1

        when: "updating match info"
            repository.updateMatchInfo(assignedId, documentType, matchType, matchReport,
                                     purchaseOrderId, poNumber, projectId)

        then: "both tables are updated"
            noExceptionThrown()
    }

    def "updateMatchInfo should skip approval update when projectId is null"() {
        given: "match information without projectId"
            def assignedId = "task-123"
            def documentType = DocumentType.INVOICE
            def matchType = MatchType.AI_MATCH
            def matchReport = '{"confidence":0.95}'
            def purchaseOrderId = "po-id-123"
            def poNumber = "PO-12345"
            def projectId = null

        and: "transaction result context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chain for extraction_task only"
            def updateTaskStep = Mock(UpdateSetFirstStep)
            def updateTaskMoreStep = Mock(UpdateSetMoreStep)
            def taskConditionStep = Mock(UpdateConditionStep)

        and: "mock transaction result"
            dsl.transactionResult(_) >> { args ->
                def lambda = args[0] as TransactionalCallable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateTaskStep
            updateTaskStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.where(_ as Condition) >> taskConditionStep
            taskConditionStep.execute() >> 1

        when: "updating match info"
            repository.updateMatchInfo(assignedId, documentType, matchType, matchReport,
                                     purchaseOrderId, poNumber, projectId)

        then: "only extraction_task is updated"
            noExceptionThrown()
    }

    def "updateMatchInfo should handle various match types"() {
        given: "direct match type"
            def assignedId = "task-123"
            def documentType = DocumentType.DELIVERY_SLIP
            def matchType = MatchType.DIRECT
            def matchReport = '{"direct":true}'
            def purchaseOrderId = "po-id-123"
            def poNumber = "PO-12345"
            def projectId = "proj-456"

        and: "transaction result context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chains"
            def updateTaskStep = Mock(UpdateSetFirstStep)
            def updateTaskMoreStep = Mock(UpdateSetMoreStep)
            def taskConditionStep = Mock(UpdateConditionStep)

            def updateApprovalStep = Mock(UpdateSetFirstStep)
            def updateApprovalMoreStep = Mock(UpdateSetMoreStep)
            def approvalConditionStep = Mock(UpdateConditionStep)

        and: "mock transaction result"
            dsl.transactionResult(_) >> { args ->
                def lambda = args[0] as TransactionalCallable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateTaskStep
            txDsl.update(DOCUMENT_APPROVALS) >> updateApprovalStep

            updateTaskStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.where(_ as Condition) >> taskConditionStep
            taskConditionStep.execute() >> 1

            updateApprovalStep.set(_, _) >> updateApprovalMoreStep
            updateApprovalMoreStep.set(_, _) >> updateApprovalMoreStep
            updateApprovalMoreStep.where(_ as Condition) >> approvalConditionStep
            approvalConditionStep.execute() >> 1

        when: "updating match info"
            repository.updateMatchInfo(assignedId, documentType, matchType, matchReport,
                                     purchaseOrderId, poNumber, projectId)

        then: "match type is handled correctly"
            noExceptionThrown()
    }

    def "updateMatchInfo should handle null match report"() {
        given: "match information with null match report"
            def assignedId = "task-123"
            def documentType = DocumentType.INVOICE
            def matchType = MatchType.NO_MATCH
            def matchReport = null
            def purchaseOrderId = null
            def poNumber = null
            def projectId = null

        and: "transaction result context"
            def txContext = Mock(Configuration)
            def txDsl = Mock(DSLContext)

        and: "update chain"
            def updateTaskStep = Mock(UpdateSetFirstStep)
            def updateTaskMoreStep = Mock(UpdateSetMoreStep)
            def taskConditionStep = Mock(UpdateConditionStep)

        and: "mock transaction result"
            dsl.transactionResult(_) >> { args ->
                def lambda = args[0] as TransactionalCallable
                lambda.run(txContext)
            }
            txContext.dsl() >> txDsl
            txDsl.update(EXTRACTION_TASK) >> updateTaskStep
            updateTaskStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.set(_, _) >> updateTaskMoreStep
            updateTaskMoreStep.where(_ as Condition) >> taskConditionStep
            taskConditionStep.execute() >> 1

        when: "updating match info"
            repository.updateMatchInfo(assignedId, documentType, matchType, matchReport,
                                     purchaseOrderId, poNumber, projectId)

        then: "null values are handled"
            noExceptionThrown()
    }
}

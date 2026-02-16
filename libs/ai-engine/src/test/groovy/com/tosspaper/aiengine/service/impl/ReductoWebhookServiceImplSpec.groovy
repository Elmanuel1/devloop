package com.tosspaper.aiengine.service.impl

import com.tosspaper.aiengine.api.dto.ReductoWebhookPayload
import com.tosspaper.aiengine.service.ExtractionService
import com.tosspaper.models.domain.ExtractionStatus
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.messaging.MessagePublisher
import spock.lang.Specification
import spock.lang.Subject

class ReductoWebhookServiceImplSpec extends Specification {

    ExtractionService extractionService = Mock()
    MessagePublisher streamPublisher = Mock()

    @Subject
    ReductoWebhookServiceImpl webhookService = new ReductoWebhookServiceImpl(extractionService, streamPublisher)

    def "processWebhook should publish ai-process message on completed status"() {
        given: "a completed webhook payload"
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-123")
            payload.setStatus("completed")

        and: "extraction task exists with non-final status"
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .storageKey("s3://bucket/file.pdf")
                .status(ExtractionStatus.STARTED)
                .build()
            extractionService.findByTaskId("job-123") >> Optional.of(task)

        when: "processing webhook"
            webhookService.processWebhook(payload)

        then: "ai-process message is published"
            1 * streamPublisher.publish("ai-process", { Map msg ->
                msg["assignedId"] == "attach-123" &&
                msg["storageUrl"] == "s3://bucket/file.pdf"
            })
    }

    def "processWebhook should publish ai-process message on succeeded status"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-456")
            payload.setStatus("succeeded")

        and:
            def task = ExtractionTask.builder()
                .assignedId("attach-456")
                .storageKey("s3://bucket/file2.pdf")
                .status(ExtractionStatus.STARTED)
                .build()
            extractionService.findByTaskId("job-456") >> Optional.of(task)

        when:
            webhookService.processWebhook(payload)

        then:
            1 * streamPublisher.publish("ai-process", _)
    }

    def "processWebhook should publish ai-process message on failed status"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-789")
            payload.setStatus("failed")

        and:
            def task = ExtractionTask.builder()
                .assignedId("attach-789")
                .storageKey("s3://bucket/file3.pdf")
                .status(ExtractionStatus.STARTED)
                .build()
            extractionService.findByTaskId("job-789") >> Optional.of(task)

        when:
            webhookService.processWebhook(payload)

        then:
            1 * streamPublisher.publish("ai-process", _)
    }

    def "processWebhook should skip non-final status"() {
        given: "a pending webhook payload"
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-pending")
            payload.setStatus("processing")

        when: "processing webhook"
            webhookService.processWebhook(payload)

        then: "no message published and no extraction lookup"
            0 * streamPublisher.publish(_, _)
            0 * extractionService.findByTaskId(_)
    }

    def "processWebhook should skip when no extraction record found"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-missing")
            payload.setStatus("completed")

        and:
            extractionService.findByTaskId("job-missing") >> Optional.empty()

        when:
            webhookService.processWebhook(payload)

        then:
            0 * streamPublisher.publish(_, _)
    }

    def "processWebhook should skip when task already has final status COMPLETED"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-done")
            payload.setStatus("completed")

        and: "task already completed"
            def task = ExtractionTask.builder()
                .assignedId("attach-done")
                .storageKey("s3://bucket/done.pdf")
                .status(ExtractionStatus.COMPLETED)
                .build()
            extractionService.findByTaskId("job-done") >> Optional.of(task)

        when:
            webhookService.processWebhook(payload)

        then:
            0 * streamPublisher.publish(_, _)
    }

    def "processWebhook should skip when task already has final status FAILED"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-already-failed")
            payload.setStatus("completed")

        and:
            def task = ExtractionTask.builder()
                .assignedId("attach-fail")
                .storageKey("s3://bucket/fail.pdf")
                .status(ExtractionStatus.FAILED)
                .build()
            extractionService.findByTaskId("job-already-failed") >> Optional.of(task)

        when:
            webhookService.processWebhook(payload)

        then:
            0 * streamPublisher.publish(_, _)
    }

    def "processWebhook should skip when task already has final status CANCELLED"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-cancelled")
            payload.setStatus("completed")

        and:
            def task = ExtractionTask.builder()
                .assignedId("attach-cancel")
                .storageKey("s3://bucket/cancel.pdf")
                .status(ExtractionStatus.CANCELLED)
                .build()
            extractionService.findByTaskId("job-cancelled") >> Optional.of(task)

        when:
            webhookService.processWebhook(payload)

        then:
            0 * streamPublisher.publish(_, _)
    }

    def "processWebhook should handle error status"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-error")
            payload.setStatus("error")

        and:
            def task = ExtractionTask.builder()
                .assignedId("attach-err")
                .storageKey("s3://bucket/err.pdf")
                .status(ExtractionStatus.STARTED)
                .build()
            extractionService.findByTaskId("job-error") >> Optional.of(task)

        when:
            webhookService.processWebhook(payload)

        then:
            1 * streamPublisher.publish("ai-process", _)
    }

    def "processWebhook should handle cancelled status from Reducto"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-cancel-from-reducto")
            payload.setStatus("cancelled")

        and:
            def task = ExtractionTask.builder()
                .assignedId("attach-cancel-2")
                .storageKey("s3://bucket/cancel2.pdf")
                .status(ExtractionStatus.STARTED)
                .build()
            extractionService.findByTaskId("job-cancel-from-reducto") >> Optional.of(task)

        when:
            webhookService.processWebhook(payload)

        then:
            1 * streamPublisher.publish("ai-process", _)
    }
}

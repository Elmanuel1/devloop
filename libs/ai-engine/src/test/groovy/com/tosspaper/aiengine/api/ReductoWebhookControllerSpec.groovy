package com.tosspaper.aiengine.api

import com.tosspaper.aiengine.api.dto.ReductoWebhookPayload
import com.tosspaper.aiengine.service.ReductoWebhookService
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Subject

class ReductoWebhookControllerSpec extends Specification {

    ReductoWebhookService webhookService = Mock()

    @Subject
    ReductoWebhookController controller = new ReductoWebhookController(webhookService)

    def "handleWebhook should process payload and return accepted"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-123")
            payload.setStatus("completed")

        when:
            def response = controller.handleWebhook(payload)

        then:
            1 * webhookService.processWebhook(payload)
            response.statusCode == HttpStatus.ACCEPTED
    }

    def "handleWebhook should rethrow exception from service"() {
        given:
            def payload = new ReductoWebhookPayload()
            payload.setJobId("job-error")
            payload.setStatus("failed")
            webhookService.processWebhook(payload) >> { throw new RuntimeException("Processing failed") }

        when:
            controller.handleWebhook(payload)

        then:
            thrown(RuntimeException)
    }
}

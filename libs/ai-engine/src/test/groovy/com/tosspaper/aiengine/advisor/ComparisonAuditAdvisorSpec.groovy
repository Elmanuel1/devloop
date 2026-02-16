package com.tosspaper.aiengine.advisor

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.vfs.VirtualFilesystemService
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.Usage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Constructor

/**
 * Comprehensive unit tests for ComparisonAuditAdvisor.
 * Tests all methods including adviseCall flow with real Spring AI objects.
 * Uses reflection to create ChatClientResponse instances since it's a Java record.
 */
class ComparisonAuditAdvisorSpec extends Specification {

    @TempDir
    Path tempDir

    VirtualFilesystemService vfsService = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    ComparisonAuditAdvisor advisor

    def setup() {
        advisor = new ComparisonAuditAdvisor(vfsService, objectMapper)
        vfsService.getRoot() >> tempDir
    }

    def "getName should return ComparisonAuditAdvisor"() {
        expect:
            advisor.getName() == "ComparisonAuditAdvisor"
    }

    def "getOrder should return LOWEST_PRECEDENCE minus 100"() {
        expect:
            advisor.getOrder() == Integer.MAX_VALUE - 100
    }

    def "WORKING_DIR_KEY should be 'workingDirectory'"() {
        expect:
            ComparisonAuditAdvisor.WORKING_DIR_KEY == "workingDirectory"
    }

    def "constructor should create copy of ObjectMapper with indent output"() {
        when: "creating advisor"
            def advisor2 = new ComparisonAuditAdvisor(vfsService, objectMapper)

        then: "no exception"
            advisor2 != null
            advisor2.getName() == "ComparisonAuditAdvisor"
    }

    def "adviseCall should write audit file with error details on exception"() {
        given: "a request with prompt"
            def userMsg = new UserMessage("Compare documents")
            def messages = [userMsg] as List<Message>
            def prompt = new Prompt(messages)
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): tempDir.resolve("session")]
            def request = new ChatClientRequest(prompt, context)

        and: "advisor chain that throws exception"
            def chain = Mock(CallAdvisorChain)
            def expectedException = new RuntimeException("AI model error")
            chain.nextCall(request) >> { throw expectedException }

        when: "advising call"
            advisor.adviseCall(request, chain)

        then: "exception is propagated"
            def ex = thrown(RuntimeException)
            ex.message == "AI model error"

        and: "audit file is written with error"
            def auditDir = tempDir.resolve("session").resolve("audits")
            Files.exists(auditDir)
            def auditFiles = Files.list(auditDir).toList()
            auditFiles.size() == 1

        and: "audit file contains error details"
            def auditContent = Files.readString(auditFiles[0])
            auditContent.contains('"success" : false')
            auditContent.contains("error")
            auditContent.contains("RuntimeException")
            auditContent.contains("AI model error")
    }

    def "adviseCall should handle null response gracefully"() {
        given: "a request"
            def userMsg = new UserMessage("Test")
            def messages = [userMsg] as List<Message>
            def prompt = new Prompt(messages)
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): tempDir.resolve("session")]
            def request = new ChatClientRequest(prompt, context)

        and: "advisor chain that returns null"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> null

        when: "advising call"
            def result = advisor.adviseCall(request, chain)

        then: "null is returned"
            result == null

        and: "audit file is written"
            def auditDir = tempDir.resolve("session").resolve("audits")
            Files.exists(auditDir)
    }

    // Helper method to create ChatClientResponse using reflection since it's a Java record
    private ChatClientResponse createResponse(ChatResponse chatResponse) {
        try {
            Constructor constructor = ChatClientResponse.class.getDeclaredConstructors()[0]
            constructor.setAccessible(true)
            // ChatClientResponse record has: ChatResponse chatResponse, Map<String, Object> additionalData
            return constructor.newInstance(chatResponse, [:])
        } catch (Exception e) {
            throw new RuntimeException("Failed to create ChatClientResponse", e)
        }
    }

    def "adviseCall should log request and response and write audit file on success"() {
        given: "a request with prompt"
            def userMsg = new UserMessage("Compare documents")
            def messages = [userMsg] as List<Message>
            def prompt = new Prompt(messages)
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): tempDir.resolve("session")]
            def request = new ChatClientRequest(prompt, context)

        and: "a successful response"
            def assistantMsg = new AssistantMessage("Comparison complete")
            def generation = new Generation(assistantMsg)
            def chatResponse = new ChatResponse([generation])
            def response = createResponse(chatResponse)

        and: "advisor chain that returns response"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> response

        when: "advising call"
            def result = advisor.adviseCall(request, chain)

        then: "result is returned"
            result == response

        and: "audit file is written"
            def auditDir = tempDir.resolve("session").resolve("audits")
            Files.exists(auditDir)
            def auditFiles = Files.list(auditDir).toList()
            auditFiles.size() == 1
            auditFiles[0].fileName.toString().startsWith("chatclient_")

        and: "audit file contains request and response data"
            def auditContent = Files.readString(auditFiles[0])
            auditContent.contains("timestamp")
            auditContent.contains("durationMs")
            auditContent.contains("request")
            auditContent.contains("response")
            auditContent.contains("success")
            auditContent.contains('"success" : true')
    }

    def "adviseCall should use fallback audit directory when working directory not in context"() {
        given: "a request without working directory in context"
            def userMsg = new UserMessage("Test prompt")
            def messages = [userMsg] as List<Message>
            def prompt = new Prompt(messages)
            def context = [:] // empty context
            def request = new ChatClientRequest(prompt, context)

        and: "a successful response"
            def assistantMsg = new AssistantMessage("Response")
            def generation = new Generation(assistantMsg)
            def chatResponse = new ChatResponse([generation])
            def response = createResponse(chatResponse)

        and: "advisor chain that returns response"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> response

        when: "advising call"
            advisor.adviseCall(request, chain)

        then: "audit file is written to fallback directory"
            def auditDir = tempDir.resolve("audits")
            Files.exists(auditDir)
            def auditFiles = Files.list(auditDir).toList()
            auditFiles.size() == 1
    }

    def "adviseCall should include options in audit when present"() {
        given: "a request with options"
            def userMsg = new UserMessage("Test")
            def messages = [userMsg] as List<Message>
            def options = ChatOptions.builder().model("gpt-4").temperature(0.7).build()
            def prompt = new Prompt(messages, options)
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): tempDir.resolve("session")]
            def request = new ChatClientRequest(prompt, context)

        and: "a response"
            def assistantMsg = new AssistantMessage("Response")
            def generation = new Generation(assistantMsg)
            def chatResponse = new ChatResponse([generation])
            def response = createResponse(chatResponse)

        and: "advisor chain"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> response

        when: "advising call"
            advisor.adviseCall(request, chain)

        then: "audit file contains options"
            def auditDir = tempDir.resolve("session").resolve("audits")
            def auditFiles = Files.list(auditDir).toList()
            def auditContent = Files.readString(auditFiles[0])
            auditContent.contains("options")
    }

    def "adviseCall should include token usage in audit when present"() {
        given: "a request"
            def userMsg = new UserMessage("Test")
            def messages = [userMsg] as List<Message>
            def prompt = new Prompt(messages)
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): tempDir.resolve("session")]
            def request = new ChatClientRequest(prompt, context)

        and: "a response with metadata and usage"
            def assistantMsg = new AssistantMessage("Response")
            def generation = new Generation(assistantMsg)
            def usage = Mock(Usage)
            usage.getPromptTokens() >> 100L
            usage.getCompletionTokens() >> 50L
            usage.getTotalTokens() >> 150L
            def metadata = Mock(ChatResponseMetadata)
            metadata.getUsage() >> usage
            metadata.getModel() >> null
            def chatResponse = new ChatResponse([generation], metadata)
            def response = createResponse(chatResponse)

        and: "advisor chain"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> response

        when: "advising call"
            advisor.adviseCall(request, chain)

        then: "audit file contains usage data"
            def auditDir = tempDir.resolve("session").resolve("audits")
            def auditFiles = Files.list(auditDir).toList()
            def auditContent = Files.readString(auditFiles[0])
            auditContent.contains("usage")
            auditContent.contains("promptTokens")
            auditContent.contains("completionTokens")
            auditContent.contains("totalTokens")
    }

    def "adviseCall should include model info in audit when present"() {
        given: "a request"
            def userMsg = new UserMessage("Test")
            def messages = [userMsg] as List<Message>
            def prompt = new Prompt(messages)
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): tempDir.resolve("session")]
            def request = new ChatClientRequest(prompt, context)

        and: "a response with model metadata"
            def assistantMsg = new AssistantMessage("Response")
            def generation = new Generation(assistantMsg)
            def metadata = Mock(ChatResponseMetadata)
            metadata.getModel() >> "gpt-4-turbo"
            metadata.getUsage() >> null
            def chatResponse = new ChatResponse([generation], metadata)
            def response = createResponse(chatResponse)

        and: "advisor chain"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> response

        when: "advising call"
            advisor.adviseCall(request, chain)

        then: "audit file contains model info"
            def auditDir = tempDir.resolve("session").resolve("audits")
            def auditFiles = Files.list(auditDir).toList()
            def auditContent = Files.readString(auditFiles[0])
            auditContent.contains("model")
            auditContent.contains("gpt-4-turbo")
    }

    def "adviseCall should log even when audit write fails"() {
        given: "a request with invalid working directory"
            def userMsg = new UserMessage("Test")
            def messages = [userMsg] as List<Message>
            def prompt = new Prompt(messages)
            // Use a file as working directory to cause write failure
            def invalidDir = tempDir.resolve("invalid-file")
            Files.writeString(invalidDir, "not a directory")
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): invalidDir]
            def request = new ChatClientRequest(prompt, context)

        and: "a response"
            def assistantMsg = new AssistantMessage("Response")
            def generation = new Generation(assistantMsg)
            def chatResponse = new ChatResponse([generation])
            def response = createResponse(chatResponse)

        and: "advisor chain"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> response

        when: "advising call"
            def result = advisor.adviseCall(request, chain)

        then: "no exception is thrown despite audit write failure"
            result == response
    }

    def "adviseCall should handle empty prompt messages"() {
        given: "a request with empty messages list"
            def messages = [] as List<Message>
            def prompt = new Prompt(messages)
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): tempDir.resolve("session")]
            def request = new ChatClientRequest(prompt, context)

        and: "a response"
            def assistantMsg = new AssistantMessage("Response")
            def generation = new Generation(assistantMsg)
            def chatResponse = new ChatResponse([generation])
            def response = createResponse(chatResponse)

        and: "advisor chain"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> response

        when: "advising call"
            def result = advisor.adviseCall(request, chain)

        then: "no exception thrown"
            result == response

        and: "audit file is still written"
            def auditDir = tempDir.resolve("session").resolve("audits")
            Files.exists(auditDir)
    }

    def "adviseCall should handle multiple messages in prompt"() {
        given: "a request with multiple messages"
            def systemMsg = new org.springframework.ai.chat.messages.SystemMessage("You are a helpful assistant")
            def userMsg = new UserMessage("Compare these documents")
            def messages = [systemMsg, userMsg] as List<Message>
            def prompt = new Prompt(messages)
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): tempDir.resolve("session")]
            def request = new ChatClientRequest(prompt, context)

        and: "a response"
            def assistantMsg = new AssistantMessage("Analysis complete")
            def generation = new Generation(assistantMsg)
            def chatResponse = new ChatResponse([generation])
            def response = createResponse(chatResponse)

        and: "advisor chain"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> response

        when: "advising call"
            advisor.adviseCall(request, chain)

        then: "audit file contains all messages"
            def auditDir = tempDir.resolve("session").resolve("audits")
            def auditFiles = Files.list(auditDir).toList()
            def auditContent = Files.readString(auditFiles[0])
            auditContent.contains("messages")
            auditContent.contains("SYSTEM")
            auditContent.contains("USER")
    }

    def "adviseCall should handle multiple generations in response"() {
        given: "a request"
            def userMsg = new UserMessage("Test")
            def messages = [userMsg] as List<Message>
            def prompt = new Prompt(messages)
            def context = [(ComparisonAuditAdvisor.WORKING_DIR_KEY): tempDir.resolve("session")]
            def request = new ChatClientRequest(prompt, context)

        and: "a response with multiple generations"
            def msg1 = new AssistantMessage("First response")
            def msg2 = new AssistantMessage("Second response")
            def gen1 = new Generation(msg1)
            def gen2 = new Generation(msg2)
            def chatResponse = new ChatResponse([gen1, gen2])
            def response = createResponse(chatResponse)

        and: "advisor chain"
            def chain = Mock(CallAdvisorChain)
            chain.nextCall(request) >> response

        when: "advising call"
            advisor.adviseCall(request, chain)

        then: "audit file contains all generations"
            def auditDir = tempDir.resolve("session").resolve("audits")
            def auditFiles = Files.list(auditDir).toList()
            def auditContent = Files.readString(auditFiles[0])
            auditContent.contains("contents")
            auditContent.contains("First response")
            auditContent.contains("Second response")
    }
}

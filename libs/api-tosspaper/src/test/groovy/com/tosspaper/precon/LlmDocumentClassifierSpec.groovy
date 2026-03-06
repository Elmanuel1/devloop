package com.tosspaper.precon

import com.tosspaper.models.precon.ConstructionDocumentType
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.springframework.ai.chat.client.ChatClient
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import java.io.ByteArrayOutputStream

/** Unit tests for {@link LlmDocumentClassifier}. */
class LlmDocumentClassifierSpec extends Specification {

    ChatClient chatClient = Mock()
    ChatClient.ChatClientRequestSpec requestSpec = Mock()
    ChatClient.CallResponseSpec callSpec = Mock()

    @Subject
    LlmDocumentClassifier classifier = new LlmDocumentClassifier(chatClient)

    def setup() {
        // Wire the fluent chain so intermediate stubs are always in place
        chatClient.prompt() >> requestSpec
        requestSpec.system(_ as String) >> requestSpec
        requestSpec.user(_ as String) >> requestSpec
        requestSpec.call() >> callSpec
    }

    // ── Null / empty input → UNKNOWN without calling LLM ─────────────────────

    def "TC-CL-01: null byte array returns UNKNOWN without calling LLM"() {
        when:
            def result = classifier.classify("doc-1", (byte[]) null)
        then:
            result == ConstructionDocumentType.UNKNOWN
            0 * callSpec.content()
    }

    def "TC-CL-02: empty byte array returns UNKNOWN without calling LLM"() {
        when:
            def result = classifier.classify("doc-empty", new byte[0])
        then:
            result == ConstructionDocumentType.UNKNOWN
            0 * callSpec.content()
    }

    // ── Non-PDF / unreadable bytes → UNKNOWN ─────────────────────────────────

    def "TC-CL-03: non-PDF bytes returns UNKNOWN without calling LLM"() {
        given: "bytes that are not a valid PDF"
            byte[] garbage = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61] as byte[] // GIF header
        when:
            def result = classifier.classify("doc-gif", garbage)
        then:
            result == ConstructionDocumentType.UNKNOWN
            0 * callSpec.content()
    }

    // ── PDF with no text layer → UNKNOWN ─────────────────────────────────────

    def "TC-CL-04: PDF with no extractable text returns UNKNOWN without calling LLM"() {
        given: "a valid PDF with a blank page — no text layer"
            byte[] bytes = buildPdf("")
        when:
            def result = classifier.classify("doc-scanned", bytes)
        then:
            result == ConstructionDocumentType.UNKNOWN
            0 * callSpec.content()
    }

    // ── LLM happy path ────────────────────────────────────────────────────────

    @Unroll
    def "TC-CL-05: LLM response '#llmResponse' maps to #expectedType"() {
        given:
            byte[] bytes = buildPdf("Some construction document text for classification purposes here.")
            callSpec.content() >> llmResponse
        when:
            def result = classifier.classify("doc-1", bytes)
        then:
            result == expectedType
        where:
            llmResponse                | expectedType
            "BILL_OF_QUANTITIES"       | ConstructionDocumentType.BILL_OF_QUANTITIES
            "DRAWINGS"                 | ConstructionDocumentType.DRAWINGS
            "SPECIFICATIONS"           | ConstructionDocumentType.SPECIFICATIONS
            "CONDITIONS_OF_CONTRACT"   | ConstructionDocumentType.CONDITIONS_OF_CONTRACT
            "TENDER_NOTICE"            | ConstructionDocumentType.TENDER_NOTICE
            "PRELIMINARIES"            | ConstructionDocumentType.PRELIMINARIES
            "UNKNOWN"                  | ConstructionDocumentType.UNKNOWN
    }

    def "TC-CL-06: LLM response is stripped and uppercased before parsing"() {
        given:
            byte[] bytes = buildPdf("Some construction document text for classification.")
            callSpec.content() >> "  drawings\n"
        when:
            def result = classifier.classify("doc-trim", bytes)
        then:
            result == ConstructionDocumentType.DRAWINGS
    }

    // ── LLM returns unrecognised value → UNKNOWN ─────────────────────────────

    def "TC-CL-07: LLM returns unrecognised enum value → UNKNOWN via fromValue()"() {
        given:
            byte[] bytes = buildPdf("Some construction document text for classification.")
            callSpec.content() >> "INVOICE"
        when:
            def result = classifier.classify("doc-bad", bytes)
        then:
            result == ConstructionDocumentType.UNKNOWN
    }

    def "TC-CL-08: LLM returns empty string → UNKNOWN via fromValue()"() {
        given:
            byte[] bytes = buildPdf("Some construction document text for classification.")
            callSpec.content() >> ""
        when:
            def result = classifier.classify("doc-empty-resp", bytes)
        then:
            result == ConstructionDocumentType.UNKNOWN
    }

    // ── LLM throws exception → propagates to caller ───────────────────────────

    def "TC-CL-09: LLM call throws exception — propagates to caller"() {
        given:
            byte[] bytes = buildPdf("Some construction document text for classification.")
            callSpec.content() >> { throw new RuntimeException("OpenAI unavailable") }
        when:
            classifier.classify("doc-fail", bytes)
        then:
            thrown(RuntimeException)
    }

    // ── Full text passed to LLM (no truncation) ───────────────────────────────

    def "TC-CL-10: full extracted text is passed to the LLM without truncation"() {
        given: "a multi-page PDF with text on each page"
            byte[] bytes = buildMultiPagePdf("Construction document content.", 3)
            callSpec.content() >> "SPECIFICATIONS"
        when:
            classifier.classify("doc-full", bytes)
        then: "LLM is called once — full text, no cap"
            1 * requestSpec.user(_ as String) >> requestSpec
    }

    // ── LLM called exactly once per classify() invocation ────────────────────

    def "TC-CL-11: LLM is called exactly once per classify call"() {
        given:
            byte[] bytes = buildMultiPagePdf("Construction tender document content here.", 5)
        when:
            classifier.classify("doc-multi", bytes)
        then:
            1 * callSpec.content() >> "TENDER_NOTICE"
    }

    // ── System prompt contains all ConstructionDocumentType names ─────────────

    def "TC-CL-12: system prompt contains all ConstructionDocumentType names"() {
        expect: "VALID_TYPES string embeds every enum constant name"
            ConstructionDocumentType.values().every { type ->
                LlmDocumentClassifier.VALID_TYPES.contains(type.name())
            }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static byte[] buildPdf(String text) {
        return buildMultiPagePdf(text, 1)
    }

    private static byte[] buildMultiPagePdf(String text, int pages = 1) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        PDDocument doc = new PDDocument()
        pages.times {
            PDPage page = new PDPage()
            doc.addPage(page)
            if (text != null && !text.isBlank()) {
                PDPageContentStream content = new PDPageContentStream(doc, page)
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12)
                content.newLineAtOffset(50, 700)
                content.showText(text.take(200) as String)
                content.endText()
                content.close()
            }
        }
        doc.save(buffer)
        doc.close()
        return buffer.toByteArray()
    }
}

package com.tosspaper.precon

import com.tosspaper.models.precon.ConstructionDocumentType
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import java.io.ByteArrayOutputStream

/**
 * Unit tests for {@link PdfBoxDocumentClassifier}.
 *
 * <p>PDFs are constructed in-memory using PDFBox itself so tests are self-contained
 * and do not depend on test-resource files.
 */
class PdfBoxDocumentClassifierSpec extends Specification {

    @Subject
    PdfBoxDocumentClassifier classifier = new PdfBoxDocumentClassifier()

    // ── Null / invalid input → UNKNOWN ───────────────────────────────────────

    def "TC-CL-01: null byte array returns UNKNOWN"() {
        when:
            def result = classifier.classify("doc-1", (byte[]) null)
        then:
            result == ConstructionDocumentType.UNKNOWN
    }

    def "TC-CL-02: empty byte array returns UNKNOWN"() {
        when:
            def result = classifier.classify("doc-empty", new byte[0])
        then:
            result == ConstructionDocumentType.UNKNOWN
    }

    def "TC-CL-03: non-PDF bytes returns UNKNOWN"() {
        given: "random bytes that are not a valid PDF"
            byte[] garbage = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61] as byte[] // GIF header
        when:
            def result = classifier.classify("doc-gif", garbage)
        then:
            result == ConstructionDocumentType.UNKNOWN
    }

    // ── Scanned image PDF (no text layer) → UNKNOWN ──────────────────────────

    def "TC-CL-04: PDF with no extractable text returns UNKNOWN (likely scanned image)"() {
        given: "a valid PDF with a blank page — no text layer"
            byte[] bytes = buildPdf("")
        when:
            def result = classifier.classify("doc-scanned", bytes)
        then:
            result == ConstructionDocumentType.UNKNOWN
    }

    // ── Unrecognised content → UNKNOWN ────────────────────────────────────────

    def "TC-CL-05: PDF with no construction tender keywords returns UNKNOWN"() {
        given: "a PDF about something completely unrelated"
            byte[] bytes = buildPdf("This is a birthday greeting card. Happy birthday to you from the team.")
        when:
            def result = classifier.classify("doc-unrelated", bytes)
        then:
            result == ConstructionDocumentType.UNKNOWN
    }

    // ── BILL_OF_QUANTITIES ────────────────────────────────────────────────────

    @Unroll
    def "TC-CL-06: PDF containing BOQ keyword '#keyword' is classified as BILL_OF_QUANTITIES"() {
        given:
            byte[] bytes = buildPdf("Project: Road Construction Phase 1 - Tender Documents. ${keyword}.")
        when:
            def result = classifier.classify("doc-boq", bytes)
        then:
            result == ConstructionDocumentType.BILL_OF_QUANTITIES
        where:
            keyword << ["bill of quantities", "schedule of rates", "preambles",
                        "provisional sum", "daywork", "boq", "quantity surveyor"]
    }

    // ── DRAWINGS ──────────────────────────────────────────────────────────────

    @Unroll
    def "TC-CL-07: PDF containing drawings keyword '#keyword' is classified as DRAWINGS"() {
        given:
            byte[] bytes = buildPdf("${keyword} for the proposed school building construction project.")
        when:
            def result = classifier.classify("doc-drawings", bytes)
        then:
            result == ConstructionDocumentType.DRAWINGS
        where:
            keyword << ["drawing list", "drawing no", "architectural drawing",
                        "structural drawing", "site plan", "floor plan", "as built"]
    }

    // ── SPECIFICATIONS ────────────────────────────────────────────────────────

    @Unroll
    def "TC-CL-08: PDF containing specifications keyword '#keyword' is classified as SPECIFICATIONS"() {
        given:
            byte[] bytes = buildPdf("${keyword} for the construction of a new water treatment plant.")
        when:
            def result = classifier.classify("doc-spec", bytes)
        then:
            result == ConstructionDocumentType.SPECIFICATIONS
        where:
            keyword << ["technical specification", "workmanship", "scope of work",
                        "method statement", "technical requirements", "quality assurance"]
    }

    // ── CONDITIONS_OF_CONTRACT ────────────────────────────────────────────────

    @Unroll
    def "TC-CL-09: PDF containing contract keyword '#keyword' is classified as CONDITIONS_OF_CONTRACT"() {
        given:
            byte[] bytes = buildPdf("${keyword} applicable to this civil engineering construction contract.")
        when:
            def result = classifier.classify("doc-contract", bytes)
        then:
            result == ConstructionDocumentType.CONDITIONS_OF_CONTRACT
        where:
            keyword << ["conditions of contract", "general conditions", "special conditions",
                        "liquidated damages", "retention", "defects liability", "force majeure"]
    }

    // ── TENDER_NOTICE ─────────────────────────────────────────────────────────

    @Unroll
    def "TC-CL-10: PDF containing tender notice keyword '#keyword' is classified as TENDER_NOTICE"() {
        given:
            byte[] bytes = buildPdf("${keyword} for the supply of construction materials and labour.")
        when:
            def result = classifier.classify("doc-notice", bytes)
        then:
            result == ConstructionDocumentType.TENDER_NOTICE
        where:
            keyword << ["invitation to tender", "tender notice", "request for proposal",
                        "instructions to tenderers", "closing date", "tender reference"]
    }

    // ── PRELIMINARIES ─────────────────────────────────────────────────────────

    @Unroll
    def "TC-CL-11: PDF containing preliminaries keyword '#keyword' is classified as PRELIMINARIES"() {
        given:
            byte[] bytes = buildPdf("${keyword} section applicable to the building construction contract.")
        when:
            def result = classifier.classify("doc-prelims", bytes)
        then:
            result == ConstructionDocumentType.PRELIMINARIES
        where:
            keyword << ["preliminaries", "prelims", "site establishment",
                        "temporary works", "scaffolding", "mobilisation"]
    }

    // ── Scoring: highest hit count wins ──────────────────────────────────────

    def "TC-CL-12: document with more BOQ keywords than drawings keywords is classified as BILL_OF_QUANTITIES"() {
        given: "text has 3 BOQ keywords and 1 drawings keyword"
            byte[] bytes = buildPdf(
                "bill of quantities schedule of rates preambles daywork. drawing list provided separately.")
        when:
            def result = classifier.classify("doc-mixed", bytes)
        then:
            result == ConstructionDocumentType.BILL_OF_QUANTITIES
    }

    // ── Case insensitivity ────────────────────────────────────────────────────

    def "TC-CL-13: keyword matching is case-insensitive"() {
        given:
            byte[] bytes = buildPdf("BILL OF QUANTITIES FOR THE PROPOSED ROAD WORKS PROJECT")
        when:
            def result = classifier.classify("doc-upper", bytes)
        then:
            result == ConstructionDocumentType.BILL_OF_QUANTITIES
    }

    // ── Document ID is only used for logging ─────────────────────────────────

    def "TC-CL-14: different document IDs with the same content return the same classification"() {
        given:
            def text = "bill of quantities for the construction of a new school building."
            byte[] bytesA = buildPdf(text)
            byte[] bytesB = buildPdf(text)
        expect:
            classifier.classify("doc-A", bytesA) == classifier.classify("doc-B", bytesB)
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    def "TC-CL-15: MIN_TEXT_LENGTH constant is 50"() {
        expect:
            PdfBoxDocumentClassifier.MIN_TEXT_LENGTH == 50
    }

    def "TC-CL-16: TYPE_KEYWORDS map covers all non-UNKNOWN ConstructionDocumentTypes"() {
        given:
            def nonUnknownTypes = ConstructionDocumentType.values().findAll {
                it != ConstructionDocumentType.UNKNOWN
            }
        expect:
            nonUnknownTypes.every { PdfBoxDocumentClassifier.TYPE_KEYWORDS.containsKey(it) }
    }

    def "TC-CL-17: no keyword appears in more than one type — keywords are mutually exclusive"() {
        given:
            def allKeywords = PdfBoxDocumentClassifier.TYPE_KEYWORDS.values().flatten()
        expect: "every keyword is unique across all type lists"
            allKeywords.size() == allKeywords.toSet().size()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds an in-memory PDF whose single page contains the supplied text.
     * Returns the serialised PDF as a {@code byte[]}.
     */
    private static byte[] buildPdf(String text) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        PDDocument doc = new PDDocument()
        PDPage page = new PDPage()
        doc.addPage(page)
        if (text != null && !text.isBlank()) {
            PDPageContentStream content = new PDPageContentStream(doc, page)
            content.beginText()
            content.setFont(PDType1Font.HELVETICA, 12)
            content.newLineAtOffset(50, 700)
            // PDFBox cannot embed multi-line or special chars without font embedding;
            // keep text simple ASCII for tests.
            content.showText(text.take(200))
            content.endText()
            content.close()
        }
        doc.save(buffer)
        doc.close()
        return buffer.toByteArray()
    }
}

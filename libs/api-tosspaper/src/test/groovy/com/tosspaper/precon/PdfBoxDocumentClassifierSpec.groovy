package com.tosspaper.precon

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Unit tests for {@link PdfBoxDocumentClassifier}.
 *
 * <p>PDFs are constructed in-memory using PDFBox itself so tests are self-contained
 * and do not depend on test-resource files.
 */
class PdfBoxDocumentClassifierSpec extends Specification {

    @Subject
    PdfBoxDocumentClassifier classifier = new PdfBoxDocumentClassifier()

    // ── Null / invalid input ─────────────────────────────────────────────────

    def "TC-CL-01: null stream is rejected"() {
        when:
            def result = classifier.isSupported("doc-1", null)
        then:
            !result
    }

    def "TC-CL-02: empty stream (non-PDF bytes) is rejected"() {
        when:
            def result = classifier.isSupported("doc-empty", new ByteArrayInputStream(new byte[0]))
        then:
            !result
    }

    def "TC-CL-03: stream containing non-PDF bytes is rejected"() {
        given: "random bytes that are not a valid PDF"
            byte[] garbage = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61] as byte[] // GIF header
        when:
            def result = classifier.isSupported("doc-gif", new ByteArrayInputStream(garbage))
        then:
            !result
    }

    // ── Scanned image PDF (no text layer) ────────────────────────────────────

    def "TC-CL-04: PDF with no extractable text is rejected (likely scanned image)"() {
        given: "a valid PDF with a blank page — no text layer"
            InputStream stream = buildPdf("")
        when:
            def result = classifier.isSupported("doc-scanned", stream)
        then:
            !result
    }

    // ── Procurement keyword matching ─────────────────────────────────────────

    @Unroll
    def "TC-CL-05: PDF containing procurement keyword '#keyword' is accepted"() {
        given: "a PDF whose text contains the keyword"
            InputStream stream = buildPdf("This document relates to a ${keyword} for infrastructure works. Bill of quantities attached.")
        when:
            def result = classifier.isSupported("doc-procurement", stream)
        then:
            result
        where:
            keyword << ["tender", "invitation to bid", "request for proposal", "rfp", "procurement", "bid document"]
    }

    @Unroll
    def "TC-CL-06: PDF containing document-type keyword '#keyword' is accepted"() {
        given: "a PDF whose text contains the document type keyword"
            InputStream stream = buildPdf("Project: Road Construction. ${keyword} for Phase 1.")
        when:
            def result = classifier.isSupported("doc-type", stream)
        then:
            result
        where:
            keyword << ["bill of quantities", "specifications", "scope of work", "terms of reference", "contract", "drawings"]
    }

    def "TC-CL-07: PDF with procurement keyword but no document-type keyword is still accepted"() {
        given: "only a procurement keyword — the 'either' rule means one group is sufficient"
            InputStream stream = buildPdf("This is a tender notice published by the Ministry of Works.")
        when:
            def result = classifier.isSupported("doc-tender-only", stream)
        then:
            result
    }

    def "TC-CL-08: PDF with document-type keyword but no procurement keyword is still accepted"() {
        given: "only a document-type keyword"
            InputStream stream = buildPdf("Scope of work for the installation of water mains at Site A.")
        when:
            def result = classifier.isSupported("doc-scope-only", stream)
        then:
            result
    }

    def "TC-CL-09: PDF with neither procurement nor document-type keywords is rejected"() {
        given: "a PDF about something completely unrelated"
            InputStream stream = buildPdf("This is a birthday greeting card. Happy birthday to you!")
        when:
            def result = classifier.isSupported("doc-unrelated", stream)
        then:
            !result
    }

    // ── Case insensitivity ────────────────────────────────────────────────────

    def "TC-CL-10: keyword matching is case-insensitive (upper-case TENDER)"() {
        given:
            InputStream stream = buildPdf("TENDER FOR SUPPLY OF OFFICE EQUIPMENT — SPECIFICATIONS ENCLOSED")
        when:
            def result = classifier.isSupported("doc-upper", stream)
        then:
            result
    }

    // ── Document ID is only used for logging ─────────────────────────────────

    def "TC-CL-11: different document IDs with the same content return the same classification"() {
        given:
            def text = "Tender for the construction of a new school building. Bill of quantities attached."
            InputStream streamA = buildPdf(text)
            InputStream streamB = buildPdf(text)
        expect:
            classifier.isSupported("doc-A", streamA) == classifier.isSupported("doc-B", streamB)
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    def "TC-CL-12: MIN_TEXT_LENGTH constant is 50"() {
        expect:
            PdfBoxDocumentClassifier.MIN_TEXT_LENGTH == 50
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds an in-memory PDF whose single page contains the supplied text.
     * Returns an {@link InputStream} over the serialised PDF bytes.
     */
    private static InputStream buildPdf(String text) {
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
        return new ByteArrayInputStream(buffer.toByteArray())
    }
}

package com.tosspaper.precon

import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class MagicByteDocumentClassifierSpec extends Specification {

    @Subject
    MagicByteDocumentClassifier classifier = new MagicByteDocumentClassifier()

    // ── null / short ──────────────────────────────────────────────────────────

    def "TC-CL-01: null header bytes are rejected"() {
        when:
            def result = classifier.isSupported("doc-1", null)
        then:
            !result
    }

    def "TC-CL-02: header shorter than 4 bytes is rejected"() {
        when:
            def result = classifier.isSupported("doc-short", [0x25, 0x50] as byte[])
        then:
            !result
    }

    def "TC-CL-03: empty byte array is rejected"() {
        when:
            def result = classifier.isSupported("doc-empty", [] as byte[])
        then:
            !result
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    def "TC-CL-04: PDF magic bytes (%PDF) are accepted"() {
        given: "PDF header: 25 50 44 46"
            byte[] pdfHeader = [0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34] as byte[]
        when:
            def result = classifier.isSupported("doc-pdf", pdfHeader)
        then:
            result
    }

    def "TC-CL-05: exactly 4 PDF magic bytes with trailing zeros are accepted"() {
        given: "exactly the PDF signature followed by zeros"
            byte[] pdfHeader = [0x25, 0x50, 0x44, 0x46, 0x00, 0x00] as byte[]
        when:
            def result = classifier.isSupported("doc-pdf-min", pdfHeader)
        then:
            result
    }

    // ── ZIP / DOCX ────────────────────────────────────────────────────────────

    def "TC-CL-06: ZIP (DOCX/XLSX/PPTX) magic bytes (PK\\x03\\x04) are accepted"() {
        given: "ZIP/Office Open XML header: 50 4B 03 04"
            byte[] zipHeader = [0x50, 0x4B, 0x03, 0x04, 0x14, 0x00] as byte[]
        when:
            def result = classifier.isSupported("doc-docx", zipHeader)
        then:
            result
    }

    // ── OLE2 / DOC ────────────────────────────────────────────────────────────

    def "TC-CL-07: OLE2 (DOC/XLS/PPT) magic bytes (D0 CF 11 E0) are accepted"() {
        given: "OLE2 compound document header"
            byte[] ole2Header = [(byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0, 0x00] as byte[]
        when:
            def result = classifier.isSupported("doc-doc", ole2Header)
        then:
            result
    }

    // ── Unsupported types ─────────────────────────────────────────────────────

    @Unroll
    def "TC-CL-08: unsupported magic bytes are rejected (case: #description)"() {
        when:
            def result = classifier.isSupported("doc-bad", header as byte[])
        then:
            !result
        where:
            description | header
            "PNG"       | [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A]
            "JPEG"      | [0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10]
            "EXE"       | [0x4D, 0x5A, 0x00, 0x00, 0x00, 0x00]
            "zero bytes"| [0x00, 0x00, 0x00, 0x00, 0x00]
    }

    // ── Document ID is only used for logging ─────────────────────────────────

    def "TC-CL-09: different document IDs with the same header return the same result"() {
        given: "PDF header bytes"
            byte[] pdfHeader = [0x25, 0x50, 0x44, 0x46, 0x2D] as byte[]
        expect:
            classifier.isSupported("doc-A", pdfHeader) == classifier.isSupported("doc-B", pdfHeader)
    }
}

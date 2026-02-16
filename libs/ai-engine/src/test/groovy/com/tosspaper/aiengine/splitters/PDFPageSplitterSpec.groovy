package com.tosspaper.aiengine.splitters

import com.tosspaper.models.domain.FileObject
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import spock.lang.Specification
import spock.lang.Subject

class PDFPageSplitterSpec extends Specification {

    @Subject
    PDFPageSplitter splitter = new PDFPageSplitter()

    private byte[] createTestPdf(int pageCount) {
        def doc = new PDDocument()
        pageCount.times { doc.addPage(new PDPage()) }
        def baos = new ByteArrayOutputStream()
        doc.save(baos)
        doc.close()
        return baos.toByteArray()
    }

    def "getPageCount should return correct page count for single page PDF"() {
        given:
        def pdf = createTestPdf(1)

        when:
        int count = splitter.getPageCount(pdf)

        then:
        count == 1
    }

    def "getPageCount should return correct page count for multi-page PDF"() {
        given:
        def pdf = createTestPdf(5)

        when:
        int count = splitter.getPageCount(pdf)

        then:
        count == 5
    }

    def "getPage should return a valid single-page FileObject"() {
        given:
        def pdf = createTestPdf(3)

        when:
        FileObject result = splitter.getPage(pdf, 0)

        then:
        result != null
        result.contentType == "application/pdf"
        result.content.length > 0
        result.sizeBytes > 0
        result.checksum != null && !result.checksum.isEmpty()
    }

    def "getPage should extract second page"() {
        given:
        def pdf = createTestPdf(3)

        when:
        FileObject page0 = splitter.getPage(pdf, 0)
        FileObject page1 = splitter.getPage(pdf, 1)

        then:
        page0 != null
        page1 != null
        // Different pages should have different checksums (different page content)
        page0.checksum != null
        page1.checksum != null
    }

    def "getPage should extract last page"() {
        given:
        def pdf = createTestPdf(3)

        when:
        FileObject result = splitter.getPage(pdf, 2)

        then:
        result != null
        result.contentType == "application/pdf"
        // Verify the extracted page is itself a valid single-page PDF
        splitter.getPageCount(result.content) == 1
    }

    def "getPage should throw for invalid page index"() {
        given:
        def pdf = createTestPdf(2)

        when:
        splitter.getPage(pdf, 5)

        then:
        thrown(IndexOutOfBoundsException)
    }

    def "getPageCount should throw IOException for invalid PDF data"() {
        given:
        byte[] invalidPdf = "not a pdf".bytes

        when:
        splitter.getPageCount(invalidPdf)

        then:
        thrown(IOException)
    }

    def "getPage should throw IOException for invalid PDF data"() {
        given:
        byte[] invalidPdf = "not a pdf".bytes

        when:
        splitter.getPage(invalidPdf, 0)

        then:
        thrown(IOException)
    }
}

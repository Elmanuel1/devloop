package com.tosspaper.precon;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * Default {@link DocumentClassifier} that uses Apache PDFBox to extract text from
 * a PDF document and then applies keyword matching to decide whether the document
 * is a procurement artefact that Reducto should process.
 *
 * <h3>Why PDFBox and not magic bytes?</h3>
 * <p>Magic-byte sniffing only identifies the file container format (PDF vs DOCX vs PNG).
 * It cannot distinguish a procurement document from an irrelevant PDF like an invoice
 * or a photo scanned as PDF. PDFBox lets us read the actual textual content and apply
 * domain-specific rules, making classification far more accurate.
 *
 * <h3>Keyword strategy</h3>
 * <p>The classifier looks for at least one keyword from each of two groups:
 * <ol>
 *   <li><b>Procurement indicators</b> — terms that confirm this is a procurement document
 *       (tender, invitation to bid, request for proposal, …)</li>
 *   <li><b>Document-type indicators</b> — terms that confirm this is a structured
 *       procurement document with extractable fields (bill of quantities,
 *       specifications, drawings, contract, scope of work, …)</li>
 * </ol>
 * <p>A document must match at least one keyword from <em>either</em> group to be
 * considered supported. Both lists together form a broad net that catches most
 * tender documents while rejecting generic PDFs.
 *
 * <h3>Non-PDF documents</h3>
 * <p>If PDFBox cannot load the stream (e.g. it is a DOCX or an image), the classifier
 * returns {@code false} and logs a warning. A separate pre-filter or a more capable
 * classifier can be substituted via the {@link DocumentClassifier} interface.
 */
@Slf4j
@Component
public class PdfBoxDocumentClassifier implements DocumentClassifier {

    /**
     * Minimum number of extracted characters before keyword matching is attempted.
     * Documents with fewer characters are likely scanned images with no OCR layer
     * and are rejected.
     */
    static final int MIN_TEXT_LENGTH = 50;

    /**
     * Procurement indicator keywords — at least one must appear for a document to
     * be classified as a supported procurement artefact.
     */
    static final List<String> PROCUREMENT_KEYWORDS = List.of(
            "tender",
            "invitation to bid",
            "request for proposal",
            "request for quotation",
            "rfp",
            "rfq",
            "itb",
            "procurement",
            "bid document",
            "bidding document"
    );

    /**
     * Document-type indicator keywords — at least one must appear in addition to a
     * procurement keyword for a document to be classified as extractable.
     */
    static final List<String> DOCUMENT_TYPE_KEYWORDS = List.of(
            "bill of quantities",
            "boq",
            "specifications",
            "specification",
            "drawings",
            "contract",
            "scope of work",
            "terms of reference",
            "tor",
            "conditions of contract",
            "instructions to bidders",
            "evaluation criteria",
            "technical requirements"
    );

    @Override
    public boolean isSupported(String documentId, InputStream contentStream) {
        if (contentStream == null) {
            log.warn("[DocumentClassifier] Document '{}' — content stream is null, skipping", documentId);
            return false;
        }

        String text = extractText(documentId, contentStream);
        if (text == null) {
            return false;
        }

        if (text.length() < MIN_TEXT_LENGTH) {
            log.info("[DocumentClassifier] Document '{}' — extracted text too short ({} chars), "
                    + "likely a scanned image with no text layer, skipping", documentId, text.length());
            return false;
        }

        String lowerText = text.toLowerCase(Locale.ROOT);
        boolean hasProcurementKeyword = PROCUREMENT_KEYWORDS.stream().anyMatch(lowerText::contains);
        boolean hasDocumentTypeKeyword = DOCUMENT_TYPE_KEYWORDS.stream().anyMatch(lowerText::contains);

        boolean supported = hasProcurementKeyword || hasDocumentTypeKeyword;

        if (supported) {
            log.debug("[DocumentClassifier] Document '{}' — classified as supported procurement document "
                    + "(procurementMatch={}, docTypeMatch={})", documentId, hasProcurementKeyword, hasDocumentTypeKeyword);
        } else {
            log.info("[DocumentClassifier] Document '{}' — no procurement or document-type keywords found, skipping",
                    documentId);
        }

        return supported;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Loads the stream with PDFBox and strips all text.
     *
     * @return extracted text, or {@code null} if PDFBox could not load the stream
     */
    private String extractText(String documentId, InputStream contentStream) {
        try (PDDocument document = PDDocument.load(contentStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.debug("[DocumentClassifier] Document '{}' — extracted {} characters via PDFBox",
                    documentId, text.length());
            return text;
        } catch (IOException e) {
            log.warn("[DocumentClassifier] Document '{}' — PDFBox could not extract text (not a valid PDF or encrypted): {}",
                    documentId, e.getMessage());
            return null;
        }
    }
}

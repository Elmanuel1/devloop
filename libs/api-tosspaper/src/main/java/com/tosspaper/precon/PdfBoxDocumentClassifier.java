package com.tosspaper.precon;

import com.tosspaper.models.precon.TenderDocumentType;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Default {@link DocumentClassifier} that uses Apache PDFBox to extract text from a
 * PDF document and then applies per-type exclusive keyword matching to classify it as
 * a specific {@link TenderDocumentType}.
 *
 * <h3>Classification strategy</h3>
 * <p>Each {@link TenderDocumentType} owns a distinct, non-overlapping keyword set.
 * For each type, the classifier counts how many of that type's keywords appear in the
 * extracted text. The type with the highest hit count wins. Ties are broken by the
 * natural declaration order of {@link TenderDocumentType}. If no type scores at least
 * one hit, {@link TenderDocumentType#UNKNOWN} is returned.
 *
 * <h3>Non-PDF documents</h3>
 * <p>If PDFBox cannot load the stream (e.g. the document is a DOCX, a scanned image
 * with no text layer, or an encrypted PDF), {@link TenderDocumentType#UNKNOWN} is
 * returned and a warning is logged.
 */
@Slf4j
@Component
public class PdfBoxDocumentClassifier implements DocumentClassifier {

    /**
     * Minimum number of extracted characters before keyword matching is attempted.
     * Documents below this threshold are likely scanned images with no OCR layer.
     */
    static final int MIN_TEXT_LENGTH = 50;

    /**
     * Exclusive keyword sets keyed by {@link TenderDocumentType}.
     * No keyword appears in more than one type's list — they are mutually exclusive.
     */
    static final Map<TenderDocumentType, List<String>> TYPE_KEYWORDS;

    static {
        Map<TenderDocumentType, List<String>> map = new EnumMap<>(TenderDocumentType.class);

        // BILL_OF_QUANTITIES — pricing and measurement documents
        map.put(TenderDocumentType.BILL_OF_QUANTITIES, List.of(
                "bill of quantities",
                "schedule of rates",
                "preambles",
                "measured work",
                "provisional sum",
                "prime cost",
                "pc sum",
                "daywork",
                "boq",
                "rate per unit",
                "quantity surveyor"
        ));

        // DRAWINGS — graphical / plan documents
        map.put(TenderDocumentType.DRAWINGS, List.of(
                "drawing list",
                "drawing no",
                "drawing number",
                "revision",
                "architectural drawing",
                "structural drawing",
                "engineering drawing",
                "site plan",
                "floor plan",
                "elevation",
                "section detail",
                "isometric",
                "as built"
        ));

        // SPECIFICATIONS — technical description of works
        map.put(TenderDocumentType.SPECIFICATIONS, List.of(
                "technical specification",
                "workmanship",
                "materials and workmanship",
                "scope of work",
                "method statement",
                "technical requirements",
                "product specification",
                "performance specification",
                "compliance standard",
                "test method",
                "quality assurance"
        ));

        // CONDITIONS_OF_CONTRACT — legal / contractual framework
        map.put(TenderDocumentType.CONDITIONS_OF_CONTRACT, List.of(
                "conditions of contract",
                "general conditions",
                "special conditions",
                "contract data",
                "employer's requirements",
                "form of contract",
                "agreement",
                "indemnity",
                "liquidated damages",
                "retention",
                "defects liability",
                "force majeure"
        ));

        // TENDER_NOTICE — cover / invitation documents
        map.put(TenderDocumentType.TENDER_NOTICE, List.of(
                "invitation to tender",
                "tender notice",
                "request for proposal",
                "request for quotation",
                "instructions to tenderers",
                "instructions to bidders",
                "tender submission",
                "closing date",
                "tender reference",
                "evaluation criteria",
                "tender validity"
        ));

        // PRELIMINARIES — general site establishment items
        map.put(TenderDocumentType.PRELIMINARIES, List.of(
                "preliminaries",
                "prelims",
                "site establishment",
                "contractor's general obligations",
                "temporary works",
                "scaffolding",
                "site security",
                "health and safety plan",
                "environmental management",
                "site clearance",
                "mobilisation"
        ));

        TYPE_KEYWORDS = Map.copyOf(map);
    }

    @Override
    public TenderDocumentType classify(String documentId, InputStream contentStream) {
        if (contentStream == null) {
            log.warn("[DocumentClassifier] Document '{}' — content stream is null, returning UNKNOWN",
                    documentId);
            return TenderDocumentType.UNKNOWN;
        }

        String text = extractText(documentId, contentStream);
        if (text == null) {
            return TenderDocumentType.UNKNOWN;
        }

        if (text.length() < MIN_TEXT_LENGTH) {
            log.info("[DocumentClassifier] Document '{}' — extracted text too short ({} chars), "
                    + "likely a scanned image with no text layer, returning UNKNOWN",
                    documentId, text.length());
            return TenderDocumentType.UNKNOWN;
        }

        String lowerText = text.toLowerCase(Locale.ROOT);
        TenderDocumentType best = TenderDocumentType.UNKNOWN;
        int bestScore = 0;

        for (TenderDocumentType type : TenderDocumentType.values()) {
            if (type == TenderDocumentType.UNKNOWN) {
                continue;
            }
            List<String> keywords = TYPE_KEYWORDS.getOrDefault(type, List.of());
            int score = (int) keywords.stream().filter(lowerText::contains).count();
            if (score > bestScore) {
                bestScore = score;
                best = type;
            }
        }

        if (best == TenderDocumentType.UNKNOWN) {
            log.info("[DocumentClassifier] Document '{}' — no type keywords matched, returning UNKNOWN",
                    documentId);
        } else {
            log.debug("[DocumentClassifier] Document '{}' — classified as {} (score={})",
                    documentId, best, bestScore);
        }

        return best;
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
            log.warn("[DocumentClassifier] Document '{}' — PDFBox could not extract text "
                    + "(not a valid PDF or encrypted): {}", documentId, e.getMessage());
            return null;
        }
    }
}

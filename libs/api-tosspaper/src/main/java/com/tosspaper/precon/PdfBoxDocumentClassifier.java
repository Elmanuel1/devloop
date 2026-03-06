package com.tosspaper.precon;

import com.tosspaper.models.precon.ConstructionDocumentType;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** PDFBox-based document classifier using keyword matching per construction document type. */
@Slf4j
@Component
public class PdfBoxDocumentClassifier implements DocumentClassifier {

    /** Minimum extracted character count to attempt classification. */
    static final int MIN_TEXT_LENGTH = 50;

    /** Exclusive keyword sets per document type. */
    static final Map<ConstructionDocumentType, List<String>> TYPE_KEYWORDS;

    static {
        Map<ConstructionDocumentType, List<String>> map = new EnumMap<>(ConstructionDocumentType.class);

        // BILL_OF_QUANTITIES — pricing and measurement documents
        map.put(ConstructionDocumentType.BILL_OF_QUANTITIES, List.of(
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
        map.put(ConstructionDocumentType.DRAWINGS, List.of(
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
        map.put(ConstructionDocumentType.SPECIFICATIONS, List.of(
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
        map.put(ConstructionDocumentType.CONDITIONS_OF_CONTRACT, List.of(
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
        map.put(ConstructionDocumentType.TENDER_NOTICE, List.of(
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
        map.put(ConstructionDocumentType.PRELIMINARIES, List.of(
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
    public ConstructionDocumentType classify(String documentId, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            log.warn("[DocumentClassifier] Document '{}' — content bytes are null or empty, returning UNKNOWN",
                    documentId);
            return ConstructionDocumentType.UNKNOWN;
        }

        String text = extractText(documentId, new ByteArrayInputStream(contentBytes));
        if (text == null) {
            return ConstructionDocumentType.UNKNOWN;
        }

        if (text.length() < MIN_TEXT_LENGTH) {
            log.info("[DocumentClassifier] Document '{}' — extracted text too short ({} chars), "
                    + "likely a scanned image with no text layer, returning UNKNOWN",
                    documentId, text.length());
            return ConstructionDocumentType.UNKNOWN;
        }

        String lowerText = text.toLowerCase(Locale.ROOT);
        ConstructionDocumentType best = ConstructionDocumentType.UNKNOWN;
        int bestScore = 0;

        for (ConstructionDocumentType type : ConstructionDocumentType.values()) {
            if (type == ConstructionDocumentType.UNKNOWN) {
                continue;
            }
            List<String> keywords = TYPE_KEYWORDS.getOrDefault(type, List.of());
            int score = (int) keywords.stream().filter(lowerText::contains).count();
            if (score > bestScore) {
                bestScore = score;
                best = type;
            }
        }

        if (best == ConstructionDocumentType.UNKNOWN) {
            log.info("[DocumentClassifier] Document '{}' — no type keywords matched, returning UNKNOWN",
                    documentId);
        } else {
            log.debug("[DocumentClassifier] Document '{}' — classified as {} (score={})",
                    documentId, best, bestScore);
        }

        return best;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns extracted text, or {@code null} if PDFBox cannot load the stream. */
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

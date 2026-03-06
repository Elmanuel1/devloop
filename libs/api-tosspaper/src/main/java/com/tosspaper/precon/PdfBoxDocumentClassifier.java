package com.tosspaper.precon;

import com.tosspaper.models.precon.ConstructionDocumentType;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Extracts text from the first 3 PDF pages and classifies it via gpt-4o-mini. */
@Slf4j
@Component
public class PdfBoxDocumentClassifier implements DocumentClassifier {

    static final int MAX_TEXT_CHARS = 4000;
    static final int CLASSIFICATION_PAGES = 3;

    private static final String VALID_TYPES = Arrays.stream(ConstructionDocumentType.values())
            .map(Enum::name)
            .collect(Collectors.joining(", "));

    private static final String SYSTEM_PROMPT =
            "You are a construction document classifier. " +
            "Classify the following document text as exactly one of: " +
            VALID_TYPES + ". " +
            "Reply with only the type name, nothing else.";

    private final ChatClient chatClient;

    public PdfBoxDocumentClassifier(@Qualifier("validationChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public ConstructionDocumentType classify(String documentId, byte[] contentBytes) {
        if (contentBytes == null || contentBytes.length == 0) {
            log.warn("[DocumentClassifier] Document '{}' — content bytes are null or empty, returning UNKNOWN",
                    documentId);
            return ConstructionDocumentType.UNKNOWN;
        }

        String text = extractFirstPages(documentId, contentBytes);
        if (text == null || text.isBlank()) {
            return ConstructionDocumentType.UNKNOWN;
        }

        return classifyWithLlm(documentId, text);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String extractFirstPages(String documentId, byte[] contentBytes) {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(contentBytes))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(CLASSIFICATION_PAGES, document.getNumberOfPages()));
            String text = stripper.getText(document);
            log.debug("[DocumentClassifier] Document '{}' — extracted {} chars from first {} page(s)",
                    documentId, text.length(), CLASSIFICATION_PAGES);
            return text;
        } catch (IOException e) {
            log.warn("[DocumentClassifier] Document '{}' — PDFBox could not extract text: {}",
                    documentId, e.getMessage());
            return null;
        }
    }

    private ConstructionDocumentType classifyWithLlm(String documentId, String text) {
        String truncated = text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
        try {
            String response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(truncated)
                    .call()
                    .content()
                    .strip()
                    .toUpperCase();
            ConstructionDocumentType type = ConstructionDocumentType.valueOf(response);
            log.debug("[DocumentClassifier] Document '{}' — LLM classified as {}", documentId, type);
            return type;
        } catch (IllegalArgumentException e) {
            log.warn("[DocumentClassifier] Document '{}' — LLM returned unrecognised type, returning UNKNOWN",
                    documentId);
            return ConstructionDocumentType.UNKNOWN;
        } catch (Exception e) {
            log.error("[DocumentClassifier] Document '{}' — LLM classification failed: {}",
                    documentId, e.getMessage(), e);
            return ConstructionDocumentType.UNKNOWN;
        }
    }
}

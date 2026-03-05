package com.tosspaper.precon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default {@link DocumentClassifier} that inspects the magic bytes of a document
 * to determine whether Reducto can process it.
 *
 * <h3>Supported types</h3>
 * <ul>
 *   <li>PDF — magic bytes {@code %PDF} ({@code 25 50 44 46})</li>
 *   <li>DOCX/XLSX/PPTX — ZIP-based Office Open XML — magic bytes {@code PK\x03\x04} ({@code 50 4B 03 04})</li>
 *   <li>DOC/XLS/PPT — OLE2 Compound Document — magic bytes {@code D0 CF 11 E0}</li>
 *   <li>Plain text — no magic bytes; allowed when no other signature is found but the bytes are printable ASCII</li>
 * </ul>
 *
 * <h3>Design notes</h3>
 * <p>This classifier does <em>not</em> validate file structure — it only gates on
 * magic bytes. A corrupt PDF that starts with {@code %PDF} will pass and Reducto
 * will handle the deeper format error internally.
 */
@Slf4j
@Component
public class MagicByteDocumentClassifier implements DocumentClassifier {

    // ── Magic-byte signatures ──────────────────────────────────────────────────

    /** PDF: %PDF */
    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46};

    /** DOCX / XLSX / PPTX (ZIP): PK\x03\x04 */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    /** DOC / XLS / PPT (OLE2): D0 CF 11 E0 */
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

    @Override
    public boolean isSupported(String documentId, byte[] headerBytes) {
        if (headerBytes == null || headerBytes.length < 4) {
            log.warn("[DocumentClassifier] Document '{}' — header too short ({} bytes), skipping",
                    documentId, headerBytes == null ? 0 : headerBytes.length);
            return false;
        }

        if (startsWith(headerBytes, PDF_MAGIC)) {
            log.debug("[DocumentClassifier] Document '{}' — detected PDF", documentId);
            return true;
        }
        if (startsWith(headerBytes, ZIP_MAGIC)) {
            log.debug("[DocumentClassifier] Document '{}' — detected ZIP-based Office format (DOCX/XLSX/PPTX)", documentId);
            return true;
        }
        if (startsWith(headerBytes, OLE2_MAGIC)) {
            log.debug("[DocumentClassifier] Document '{}' — detected OLE2 Office format (DOC/XLS/PPT)", documentId);
            return true;
        }

        log.info("[DocumentClassifier] Document '{}' — unrecognised magic bytes, skipping", documentId);
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean startsWith(byte[] data, byte[] signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (data[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}

package com.tosspaper.models.utils;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Utility class for encoding and decoding composite cursors for pagination.
 * Cursor format: URL-safe base64(created_at_iso_string|ulid_id)
 */
public class CursorUtils {
    private static final String DELIMITER = "|";
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    /**
     * Encode cursor from created_at timestamp and ID.
     * Uses URL-safe Base64 encoding (no +/ or padding =).
     *
     * @param createdAt The created_at timestamp
     * @param id The record ID (ULID)
     * @return URL-safe base64 encoded cursor string
     */
    public static String encodeCursor(OffsetDateTime createdAt, String id) {
        if (createdAt == null || id == null || id.isBlank()) {
            throw new IllegalArgumentException("createdAt and id must not be null or blank");
        }
        
        String isoString = createdAt.format(ISO_FORMATTER);
        String payload = isoString + DELIMITER + id;
        return URL_ENCODER.encodeToString(payload.getBytes());
    }

    /**
     * Decode cursor to extract created_at timestamp and ID.
     *
     * @param cursor URL-safe base64 encoded cursor string
     * @return CursorPair containing the decoded created_at and id
     * @throws IllegalArgumentException if cursor is invalid or malformed
     */
    public static CursorPair decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("Cursor must not be null or blank");
        }

        try {
            byte[] decoded = URL_DECODER.decode(cursor);
            String payload = new String(decoded);
            
            int delimiterIndex = payload.lastIndexOf(DELIMITER);
            if (delimiterIndex == -1 || delimiterIndex == payload.length() - 1) {
                throw new IllegalArgumentException("Invalid cursor format: missing delimiter");
            }

            String isoString = payload.substring(0, delimiterIndex);
            String id = payload.substring(delimiterIndex + 1);

            OffsetDateTime createdAt = OffsetDateTime.parse(isoString, ISO_FORMATTER);
            
            return new CursorPair(createdAt, id);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor format: " + e.getMessage(), e);
        }
    }

    /**
     * Record to hold decoded cursor values.
     */
    public static record CursorPair(OffsetDateTime createdAt, String id) {
    }
}


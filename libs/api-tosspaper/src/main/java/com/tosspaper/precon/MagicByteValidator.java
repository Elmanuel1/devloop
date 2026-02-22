package com.tosspaper.precon;

/**
 * Utility class for validating file magic bytes against declared content types.
 * Magic bytes are the first few bytes of a file that identify the file format.
 */
public final class MagicByteValidator {

    // PDF: %PDF (0x25504446)
    private static final byte[] PDF_MAGIC = new byte[]{0x25, 0x50, 0x44, 0x46};

    // PNG: \x89PNG\r\n\x1a\n (0x89504E470D0A1A0A)
    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    // JPEG: \xFF\xD8\xFF
    private static final byte[] JPEG_MAGIC = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private MagicByteValidator() {
        // Utility class
    }

    /**
     * Validates that the file header bytes match the declared content type.
     *
     * @param header              the first bytes of the file
     * @param declaredContentType the MIME type declared for the file
     * @return true if the magic bytes match the declared content type
     */
    public static boolean validate(byte[] header, String declaredContentType) {
        if (header == null || header.length == 0 || declaredContentType == null) {
            return false;
        }

        return switch (declaredContentType) {
            case "application/pdf" -> startsWith(header, PDF_MAGIC);
            case "image/png" -> startsWith(header, PNG_MAGIC);
            case "image/jpeg" -> startsWith(header, JPEG_MAGIC);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}

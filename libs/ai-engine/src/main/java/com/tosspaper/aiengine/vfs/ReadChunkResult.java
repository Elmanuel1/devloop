package com.tosspaper.aiengine.vfs;

/**
 * Result of reading a chunk from a file.
 * Used for efficient reading of large files in pieces.
 *
 * @param content   The chunk content as a string
 * @param offset    Byte offset where this chunk starts
 * @param length    Number of bytes/characters returned
 * @param totalSize Total size of the file in bytes
 * @param hasMore   True if there is more content after this chunk
 */
public record ReadChunkResult(
    String content,
    long offset,
    int length,
    long totalSize,
    boolean hasMore
) {
    /**
     * Create a result for an empty file.
     */
    public static ReadChunkResult empty() {
        return new ReadChunkResult("", 0, 0, 0, false);
    }
}

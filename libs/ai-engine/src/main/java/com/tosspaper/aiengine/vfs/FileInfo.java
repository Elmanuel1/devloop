package com.tosspaper.aiengine.vfs;

/**
 * Information about a file or directory in the virtual filesystem.
 *
 * @param name Name of the file or directory
 * @param type "file" or "directory"
 * @param size Size in bytes (0 for directories)
 */
public record FileInfo(
    String name,
    String type,
    long size
) {
    /**
     * Check if this is a directory.
     */
    public boolean isDirectory() {
        return "directory".equals(type);
    }

    /**
     * Check if this is a file.
     */
    public boolean isFile() {
        return "file".equals(type);
    }

    /**
     * Create a FileInfo for a directory.
     */
    public static FileInfo directory(String name) {
        return new FileInfo(name, "directory", 0);
    }

    /**
     * Create a FileInfo for a file.
     */
    public static FileInfo file(String name, long size) {
        return new FileInfo(name, "file", size);
    }
}

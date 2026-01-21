package com.tosspaper.emailengine.utils;

/**
 * Utility class for file extension operations.
 */
public class FileExtensionUtils {

    private FileExtensionUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Extract file extension from filename.
     * 
     * @param fileName the filename
     * @return the file extension (without dot) or empty string if none
     */
    public static String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }

    /**
     * Check if a file has an extension.
     * 
     * @param fileName the filename
     * @return true if the file has an extension, false otherwise
     */
    public static boolean hasExtension(String fileName) {
        return !getFileExtension(fileName).isEmpty();
    }

    /**
     * Get filename without extension.
     * 
     * @param fileName the filename
     * @return the filename without extension
     */
    public static String getFileNameWithoutExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return fileName;
        }
        return fileName.substring(0, fileName.lastIndexOf("."));
    }
}

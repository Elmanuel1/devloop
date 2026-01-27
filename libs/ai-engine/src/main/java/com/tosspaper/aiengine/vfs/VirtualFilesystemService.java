package com.tosspaper.aiengine.vfs;

import java.nio.file.Path;
import java.util.List;

/**
 * Service for managing virtual filesystem operations.
 * Provides isolated storage per company for document comparison data.
 *
 * <p>Directory structure: {root}/companies/{companyId}/po/{poNumber}/{documentType}/{documentId}.json
 */
public interface VirtualFilesystemService {

    /**
     * Save document to VFS using context (includes content).
     *
     * @param context Document context containing IDs, type, and content
     * @return Path where the file was saved
     */
    Path save(VfsDocumentContext context);

    /**
     * Get path for a document based on context.
     *
     * @param context Document context
     * @return Path where the document would be stored
     */
    Path getPath(VfsDocumentContext context);

    /**
     * Get the working directory for a PO under a company.
     *
     * @param companyId Company identifier
     * @param poNumber  Purchase order number
     * @return Path to the PO working directory
     */
    Path getWorkingDirectory(long companyId, String poNumber);

    /**
     * Get the audit directory for a PO under a company.
     *
     * @param companyId Company identifier
     * @param poNumber  Purchase order number
     * @return Path to the PO audit directory
     */
    Path getAuditDirectory(long companyId, String poNumber);

    /**
     * Read file content from VFS.
     *
     * @param path Path to the file
     * @return File content as string
     */
    String readFile(Path path);

    /**
     * Read a chunk of a file from VFS.
     * Useful for efficiently reading large files in pieces.
     *
     * @param path   Path to the file
     * @param offset Byte offset to start reading from
     * @param limit  Maximum number of bytes to read
     * @return Chunk result with content and metadata
     */
    ReadChunkResult readChunk(Path path, long offset, int limit);

    /**
     * Write content to a file in VFS.
     * Creates parent directories if they don't exist.
     *
     * @param path    Path to the file
     * @param content Content to write
     * @return Path where the file was written
     */
    Path writeFile(Path path, String content);

    /**
     * List files and directories in a directory.
     *
     * @param path Path to the directory
     * @return List of file and directory info
     */
    List<FileInfo> listDirectory(Path path);

    /**
     * Search for a pattern in files.
     *
     * @param path          Path to file or directory to search
     * @param pattern       Regular expression pattern to search for
     * @param beforeContext Number of lines before match to include
     * @param afterContext  Number of lines after match to include
     * @return List of grep results
     */
    List<GrepResult> grep(Path path, String pattern, int beforeContext, int afterContext);

    /**
     * Check if a file exists in VFS.
     *
     * @param path Path to check
     * @return true if file exists
     */
    boolean exists(Path path);

    /**
     * Delete a file from VFS.
     *
     * @param path Path to delete
     * @return true if file was deleted
     */
    boolean delete(Path path);

    /**
     * Get the VFS root directory.
     *
     * @return Root path
     */
    Path getRoot();
}

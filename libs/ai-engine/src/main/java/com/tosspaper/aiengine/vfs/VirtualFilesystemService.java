package com.tosspaper.aiengine.vfs;

import java.nio.file.Path;

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

package com.tosspaper.aiengine.tools;

import com.tosspaper.aiengine.vfs.FileInfo;
import com.tosspaper.aiengine.vfs.GrepResult;
import com.tosspaper.aiengine.vfs.ReadChunkResult;
import com.tosspaper.aiengine.vfs.VirtualFilesystemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.util.List;

/**
 * Embedded file tools for AI document comparison.
 * Wraps VirtualFilesystemService with Spring AI @Tool annotations.
 *
 * <p>These tools are injected into the ChatClient and allow the AI to:
 * <ul>
 *   <li>Read files (whole or in chunks for large files)</li>
 *   <li>Write analysis results to files</li>
 *   <li>List directory contents</li>
 *   <li>Search for patterns in files</li>
 * </ul>
 *
 * <p>All paths are relative to the working directory set per comparison request.
 * Security is enforced by VirtualFilesystemService (path traversal prevention).
 */
@Slf4j
@RequiredArgsConstructor
public class FileTools {

    private final VirtualFilesystemService vfs;

    /**
     * The working directory for the current comparison.
     * Must be set before tools are used.
     */
    private Path workingDirectory;

    /**
     * Set the working directory for file operations.
     * Called before each comparison to scope file access.
     *
     * @param workingDirectory Absolute path to the working directory
     */
    public void setWorkingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
        log.debug("FileTools working directory set to: {}", workingDirectory);
    }

    /**
     * Get the current working directory.
     */
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    @Tool(description = """
        Read the entire contents of a file. Use this for small files.
        For large files (>10KB), use read_file_chunk instead to avoid context bloat.
        Returns the file content as a string.
        """)
    public String readFile(
            @ToolParam(description = "Relative path to the file from the working directory") String path) {
        Path resolved = resolve(path);
        log.debug("Reading file: {}", resolved);
        return vfs.readFile(resolved);
    }

    @Tool(description = """
        Read a chunk of a file. Use this for large files to avoid loading too much into context.
        Returns the chunk content plus metadata (offset, length, totalSize, hasMore).
        Start with offset=0, then use the returned offset+length for the next chunk if hasMore=true.
        """)
    public ReadChunkResult readFileChunk(
            @ToolParam(description = "Relative path to the file from the working directory") String path,
            @ToolParam(description = "Byte offset to start reading from (0 = beginning)") long offset,
            @ToolParam(description = "Maximum number of bytes/characters to read (default 10000)") int limit) {
        Path resolved = resolve(path);
        log.debug("Reading chunk from file: {} offset={} limit={}", resolved, offset, limit);

        // Default limit if not specified or too small
        if (limit <= 0) {
            limit = 10000;
        }

        return vfs.readChunk(resolved, offset, limit);
    }

    @Tool(description = """
        Write content to a file. Creates the file if it doesn't exist, overwrites if it does.
        Parent directories are created automatically.
        Use this to save analysis results, intermediate data, or the final comparison output.
        Returns confirmation message with the file path.
        """)
    public String writeFile(
            @ToolParam(description = "Relative path to the file from the working directory") String path,
            @ToolParam(description = "Content to write to the file") String content) {
        Path resolved = resolve(path);
        log.debug("Writing file: {} ({} chars)", resolved, content.length());
        vfs.writeFile(resolved, content);
        return "File written: " + path + " (" + content.length() + " characters)";
    }

    @Tool(description = """
        List files and directories in a directory.
        Returns list of {name, type, size} for each entry.
        Type is "file" or "directory".
        Use empty string or "." for the current working directory.
        """)
    public List<FileInfo> listDirectory(
            @ToolParam(description = "Relative path to the directory (empty or '.' for current)") String path) {
        Path resolved = (path == null || path.isBlank() || ".".equals(path))
                ? workingDirectory
                : resolve(path);
        log.debug("Listing directory: {}", resolved);
        return vfs.listDirectory(resolved);
    }

    @Tool(description = """
        Search for a text pattern in files (like grep).
        Pattern is a regular expression.
        Can search a single file or all files in a directory recursively.
        Returns list of matches with file path, line number, matching line, and context.
        """)
    public List<GrepResult> grep(
            @ToolParam(description = "Regular expression pattern to search for") String pattern,
            @ToolParam(description = "File or directory path to search (relative to working directory)") String path,
            @ToolParam(description = "Number of lines of context to show before each match (0-5)") int beforeContext,
            @ToolParam(description = "Number of lines of context to show after each match (0-5)") int afterContext) {
        Path resolved = resolve(path);
        log.debug("Grep pattern='{}' in path={}", pattern, resolved);

        // Limit context to reasonable bounds
        beforeContext = Math.max(0, Math.min(5, beforeContext));
        afterContext = Math.max(0, Math.min(5, afterContext));

        return vfs.grep(resolved, pattern, beforeContext, afterContext);
    }

    /**
     * Resolve a relative path against the working directory.
     *
     * @param relativePath The relative path
     * @return Absolute path within the working directory
     * @throws IllegalStateException if working directory is not set
     */
    private Path resolve(String relativePath) {
        if (workingDirectory == null) {
            throw new IllegalStateException("Working directory not set. Call setWorkingDirectory() first.");
        }
        return workingDirectory.resolve(relativePath).normalize();
    }
}

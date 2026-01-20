package com.tosspaper.models.service;

import com.tosspaper.models.domain.FileObject;
import com.tosspaper.models.storage.DownloadResult;
import com.tosspaper.models.storage.UploadResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for storing email attachments in external storage (e.g., AWS S3).
 * Handles file upload, metadata extraction, and storage URL generation.
 */
public interface StorageService {
    
    /**
     * Upload multiple file objects with a specified key and return their upload results.
     * 
     * @param fileObjects list of file objects to upload
     * @return list of upload results in the same order
     */
    List<UploadResult> uploadFiles(List<FileObject> fileObjects);

    UploadResult uploadFile(FileObject fileObject);
    
    /**
     * Asynchronously upload multiple file objects with a specified key.
     * Each file is uploaded in a separate virtual thread for maximum concurrency.
     * 
     * @param fileObjects list of file objects to upload
     * @return CompletableFuture containing list of upload results in the same order
     */
    CompletableFuture<List<UploadResult>> uploadFilesAsync( List<FileObject> fileObjects);

    /**
     * Download a file from storage using its key.
     * 
     * @param key the storage key of the file
     * @return DownloadResult containing the downloaded file data and operation status
     */
    DownloadResult download(String key);

}

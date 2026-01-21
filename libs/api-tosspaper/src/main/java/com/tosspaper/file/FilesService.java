package com.tosspaper.file;

import com.tosspaper.generated.model.CreatePresignedUrlRequest;
import com.tosspaper.generated.model.DeletePresignedUrlRequest;

/**
 * Service interface for file operations using AWS S3
 * Focus: Presigned upload and delete URLs only
 */
public interface FilesService {
    
    /**
     * Generate a presigned URL for uploading a file
     * 
     * @param companyId the company ID requesting the upload
     * @param request the upload request with filename, size, content type, and intent
     * @return presigned URL for upload
     */
    com.tosspaper.generated.model.PreSignedUrl createPresignedUploadUrl(Long companyId, String userId, CreatePresignedUrlRequest request);
    
    /**
     * Generate a presigned URL for deleting a file
     * 
     * @param companyId the company ID requesting the delete
     * @param request the delete request with key and intent
     * @return presigned URL for delete
     */
    com.tosspaper.generated.model.PreSignedUrl createPresignedDeleteUrl(Long companyId, String userId, DeletePresignedUrlRequest request);
    
    /**
     * Generate a presigned URL for downloading a file
     *
     * @param companyId the company ID requesting the download
     * @param key the S3 key of the file to download
     * @return presigned URL for download
     * @throws ForbiddenException if key doesn't belong to company
     */
    com.tosspaper.generated.model.PreSignedUrl createPresignedDownloadUrl(Long companyId, String key);

} 
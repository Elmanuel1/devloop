package com.tosspaper.file;

import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.api.FilesApi;
import com.tosspaper.generated.model.CreatePresignedUrlRequest;
import com.tosspaper.generated.model.DeletePresignedUrlRequest;
import com.tosspaper.generated.model.PreSignedUrl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import static com.tosspaper.common.security.SecurityUtils.getSubjectFromJwt;

/**
 * REST controller for file operations
 * Focus: Presigned upload URLs and delete functionality only
 */
@RestController
@RequiredArgsConstructor
public class FileController implements FilesApi {

    private final FilesService filesService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:upload')")
    public ResponseEntity<PreSignedUrl> createPresignedUrl(String xContextId, CreatePresignedUrlRequest createPresignedUrlRequest) {
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        String userId = getSubjectFromJwt();
        PreSignedUrl preSignedUrl = filesService.createPresignedUploadUrl(companyId, userId, createPresignedUrlRequest);
        return ResponseEntity.ok(preSignedUrl);
    }
    
    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:delete')")
    public ResponseEntity<PreSignedUrl> deletePresignedUrl(String xContextId, DeletePresignedUrlRequest deletePresignedUrlRequest) {
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        String userId = getSubjectFromJwt();
        PreSignedUrl preSignedUrl = filesService.createPresignedDeleteUrl(companyId, userId, deletePresignedUrlRequest);
        return ResponseEntity.ok(preSignedUrl);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<PreSignedUrl> createPresignedDownloadUrl(String xContextId, String key) {
        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        PreSignedUrl preSignedUrl = filesService.createPresignedDownloadUrl(companyId, key);
        return ResponseEntity.ok(preSignedUrl);
    }
} 
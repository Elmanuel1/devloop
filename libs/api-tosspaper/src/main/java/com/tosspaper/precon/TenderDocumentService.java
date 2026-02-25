package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.DownloadUrlResponse;
import com.tosspaper.precon.generated.model.PresignedUrlRequest;
import com.tosspaper.precon.generated.model.PresignedUrlResponse;
import com.tosspaper.precon.generated.model.TenderDocumentListResponse;

public interface TenderDocumentService {

    PresignedUrlResponse getUploadPresignedUrl(Long companyId, String tenderId, PresignedUrlRequest request);

    TenderDocumentListResponse listDocuments(Long companyId, String tenderId, String status,
                                              int limit, String cursorCreatedAt, String cursorId);

    void deleteDocument(Long companyId, String tenderId, String documentId);

    DownloadUrlResponse getDownloadPresignedUrl(Long companyId, String tenderId, String documentId);
}

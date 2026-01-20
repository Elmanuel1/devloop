package com.tosspaper.aiengine.vfs;

import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.domain.PurchaseOrder;
import lombok.Builder;

/**
 * Context for VFS operations containing all necessary identifiers.
 * Simplifies VFS method signatures by bundling related parameters.
 */
@Builder
public record VfsDocumentContext(
        Long companyId,
        String poNumber,
        String documentId,
        DocumentType documentType,
        String content
) {




}

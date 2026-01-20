package com.tosspaper.aiengine.vfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.domain.PurchaseOrder;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VFSContextMapper {
    private final ObjectMapper objectMapper;
    /**
     * Create context from ExtractionTask and PO number.
     */
    public static VfsDocumentContext from(ExtractionTask task) {
        return VfsDocumentContext.builder()
                .companyId(task.getCompanyId())
                .poNumber(task.getPoNumber())
                .documentId(task.getAssignedId())
                .documentType(task.getDocumentType())
                .content(task.getConformedJson())
                .build();
    }

    @SneakyThrows
    public VfsDocumentContext from(PurchaseOrder po) {
        return VfsDocumentContext.builder()
                .companyId(po.getCompanyId())
                .poNumber(po.getDisplayId())
                .documentId("po")
                .documentType(DocumentType.PURCHASE_ORDER)
                .content(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(po))
                .build();
    }
}

package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.EntityType;
import com.tosspaper.precon.generated.model.ExtractionCreateRequest;

import java.util.List;

public interface EntityExtractionAdapter {

    EntityType entityType();

    boolean verifyOwnership(String companyId, String entityId);

    List<String> resolveDocumentIds(String entityId, ExtractionCreateRequest request);

    List<String> validateFieldNames(List<String> fields);
}

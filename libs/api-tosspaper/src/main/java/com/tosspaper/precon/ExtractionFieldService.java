package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.ExtractionFieldBulkUpdateRequest;
import com.tosspaper.precon.generated.model.ExtractionFieldBulkUpdateResponse;
import com.tosspaper.precon.generated.model.ExtractionFieldListResponse;

import java.util.UUID;

public interface ExtractionFieldService {

    ExtractionFieldListResponse listExtractionFields(Long companyId, String extractionId,
                                                      String fieldName, UUID documentId,
                                                      Integer limit, String cursor);

    ExtractionFieldBulkUpdateResponse bulkUpdateFields(Long companyId, String extractionId,
                                                        String ifMatch,
                                                        ExtractionFieldBulkUpdateRequest request);
}

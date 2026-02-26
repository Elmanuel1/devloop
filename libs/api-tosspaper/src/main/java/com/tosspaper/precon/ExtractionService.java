package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.ExtractionCreateRequest;
import com.tosspaper.precon.generated.model.ExtractionListResponse;
import com.tosspaper.precon.generated.model.ExtractionStatus;

import java.util.UUID;

public interface ExtractionService {

    ExtractionResult createExtraction(Long companyId, ExtractionCreateRequest request);

    ExtractionListResponse listExtractions(Long companyId, UUID entityId, ExtractionStatus status,
                                            Integer limit, String cursor);

    ExtractionResult getExtraction(Long companyId, String extractionId);

    void cancelExtraction(Long companyId, String extractionId);
}

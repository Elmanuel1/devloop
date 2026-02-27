package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.Application;
import com.tosspaper.precon.generated.model.ApplicationCreateRequest;

import java.util.UUID;

public interface ExtractionApplicationService {

    Application apply(Long companyId, String extractionId, ApplicationCreateRequest request);
}

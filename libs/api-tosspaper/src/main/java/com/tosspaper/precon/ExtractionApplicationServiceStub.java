package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.Application;
import com.tosspaper.precon.generated.model.ApplicationCreateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnMissingBean(value = ExtractionApplicationService.class, ignored = ExtractionApplicationServiceStub.class)
public class ExtractionApplicationServiceStub implements ExtractionApplicationService {

    @Override
    public Application apply(Long companyId, String extractionId, ApplicationCreateRequest request) {
        throw new UnsupportedOperationException("Apply extraction not yet implemented");
    }
}

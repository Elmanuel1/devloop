package com.tosspaper.precon;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.models.exception.NotImplementedException;
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
        throw new NotImplementedException(ApiErrorMessages.NOT_IMPLEMENTED_CODE, ApiErrorMessages.NOT_IMPLEMENTED);
    }
}

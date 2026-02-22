package com.tosspaper.precon;

import com.tosspaper.generated.model.Tender;
import com.tosspaper.generated.model.TenderCreateRequest;
import com.tosspaper.generated.model.TenderListResponse;
import com.tosspaper.generated.model.TenderUpdateRequest;

public interface TenderService {

    Tender createTender(Long companyId, TenderCreateRequest request, String createdBy);

    TenderListResponse listTenders(Long companyId, TenderQuery query);

    Tender getTender(Long companyId, String tenderId);

    Tender updateTender(Long companyId, String tenderId, TenderUpdateRequest request, int expectedVersion);

    void deleteTender(Long companyId, String tenderId);
}

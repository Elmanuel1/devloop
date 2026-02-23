package com.tosspaper.precon;

import com.tosspaper.generated.model.Tender;
import com.tosspaper.generated.model.TenderCreateRequest;
import com.tosspaper.generated.model.TenderListResponse;
import com.tosspaper.generated.model.TenderSortDirection;
import com.tosspaper.generated.model.TenderSortField;
import com.tosspaper.generated.model.TenderStatus;
import com.tosspaper.generated.model.TenderUpdateRequest;

public interface TenderService {

    Tender createTender(Long companyId, TenderCreateRequest request);

    TenderListResponse listTenders(Long companyId, Integer limit, String cursor, String search,
                                   TenderSortField sort, TenderSortDirection direction, TenderStatus status);

    Tender getTender(Long companyId, String tenderId);

    Tender updateTender(Long companyId, String tenderId, TenderUpdateRequest request, String ifMatch);

    void deleteTender(Long companyId, String tenderId);
}

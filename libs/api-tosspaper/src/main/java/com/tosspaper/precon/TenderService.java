package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.SortDirection;
import com.tosspaper.precon.generated.model.SortField;
import com.tosspaper.precon.generated.model.TenderCreateRequest;
import com.tosspaper.precon.generated.model.TenderListResponse;
import com.tosspaper.precon.generated.model.TenderStatus;
import com.tosspaper.precon.generated.model.TenderUpdateRequest;

public interface TenderService {

    TenderResult createTender(Long companyId, TenderCreateRequest request);

    TenderListResponse listTenders(Long companyId, Integer limit, String cursor, String search,
                                   SortField sort, SortDirection direction, TenderStatus status);

    TenderResult getTender(Long companyId, String tenderId);

    TenderResult updateTender(Long companyId, String tenderId, TenderUpdateRequest request, String ifMatch);

    void deleteTender(Long companyId, String tenderId);
}

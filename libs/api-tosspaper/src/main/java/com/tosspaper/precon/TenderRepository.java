package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.TendersRecord;

import java.util.List;

public interface TenderRepository {

    TendersRecord insert(TendersRecord record);

    TendersRecord findById(String id);

    List<TendersRecord> findByCompanyId(String companyId, TenderQuery query);

    int update(String id, TendersRecord record, int expectedVersion);

    int softDelete(String id);
}

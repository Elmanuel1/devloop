package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.TendersRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TenderRepository {

    TendersRecord insert(String companyId, Map<String, Object> fields);

    Optional<TendersRecord> findById(String id);

    List<TendersRecord> findByCompanyId(String companyId, TenderQuery query);

    int update(String id, Map<String, Object> fields, int expectedVersion);

    int softDelete(String id);
}

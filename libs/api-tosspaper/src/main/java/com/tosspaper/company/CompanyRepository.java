package com.tosspaper.company;

import com.tosspaper.models.jooq.tables.records.CompaniesRecord;
import org.jooq.DSLContext;

import java.util.Optional;

import java.util.List;

public interface CompanyRepository {
    CompaniesRecord save(CompaniesRecord company);
    CompaniesRecord save(DSLContext dsl, CompaniesRecord company);
    CompaniesRecord findById(Long id);
    CompaniesRecord update(CompaniesRecord company);
    void deleteById(Long id);
    Optional<CompaniesRecord> findByEmail(String email);
    Optional<CompaniesRecord> findByAssignedEmail(String assignedEmail);

    /**
     * Find all companies where the user is authorized with their role
     * Uses a JOIN to fetch companies and roles in a single query
     *
     * @param email User email
     * @return List of companies with the user's role in each
     */
    List<CompanyWithRole> findAuthorizedCompaniesByEmail(String email);
} 
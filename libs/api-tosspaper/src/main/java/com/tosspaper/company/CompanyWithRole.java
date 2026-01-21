package com.tosspaper.company;

import com.tosspaper.models.jooq.tables.records.CompaniesRecord;

/**
 * Simple record to hold a company and the user's role in that company
 */
public record CompanyWithRole(
        CompaniesRecord company,
        String roleId
) {
}

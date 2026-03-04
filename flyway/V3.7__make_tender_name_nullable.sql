-- V3.7__make_tender_name_nullable.sql
-- Make tender name nullable to support AI-deferred name extraction.
-- A background job (or extraction pipeline) will populate names for tenders
-- created without one. Tenders are still created with status='pending' as normal.

ALTER TABLE tenders
    ALTER COLUMN name DROP NOT NULL;

-- The unique partial index uq_tenders_company_name uses LOWER(name) WHERE deleted_at IS NULL.
-- In PostgreSQL, NULL values are treated as DISTINCT in unique indexes by default, but
-- NULLS DISTINCT must be explicitly stated to ensure unambiguous intent now that
-- PostgreSQL 15 introduced the NULLS NOT DISTINCT option.
-- Under NULLS DISTINCT: LOWER(NULL) = NULL, and two rows with (company_id='x', name=NULL)
-- do NOT conflict — each NULL is treated as unique. Multiple unnamed tenders per
-- company are therefore permitted.
DROP INDEX IF EXISTS uq_tenders_company_name;
CREATE UNIQUE INDEX uq_tenders_company_name
    ON tenders (company_id, LOWER(name)) NULLS DISTINCT
    WHERE deleted_at IS NULL;

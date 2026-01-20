-- ============================================================================
-- Fix ON CONFLICT constraint for payment_terms table
-- ============================================================================
-- PostgreSQL's ON CONFLICT clause requires full unique constraints or unique
-- indexes without WHERE clauses. The previous migration (V1.9) created a
-- partial index with WHERE clause, which cannot be used with ON CONFLICT.
--
-- This migration replaces the partial index with a full unique constraint.
-- PostgreSQL unique constraints naturally handle NULLs correctly: multiple
-- rows can have NULL values in the constraint columns without violating
-- uniqueness (NULL != NULL in SQL).
-- ============================================================================

-- Drop partial index that cannot be used with ON CONFLICT
DROP INDEX IF EXISTS uq_payment_terms_provider_external_id;

-- Create full unique constraint for ON CONFLICT operations
-- Note: PostgreSQL unique constraints allow multiple NULLs (NULL != NULL)
-- This means local records (provider IS NULL) won't conflict with each other
ALTER TABLE payment_terms 
    ADD CONSTRAINT uq_payment_terms_company_provider_external 
    UNIQUE (company_id, provider, external_id);

COMMENT ON CONSTRAINT uq_payment_terms_company_provider_external ON payment_terms IS 
    'Enforces uniqueness of provider-synced payment terms. Multiple rows can have NULL provider/external_id (local records). Enables ON CONFLICT DO UPDATE for batched upserts.';

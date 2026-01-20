-- ============================================================================
-- Fix ON CONFLICT constraints for provider-synced records
-- ============================================================================
-- PostgreSQL's ON CONFLICT clause requires full unique constraints or unique
-- indexes without WHERE clauses. The previous migrations (V1.9, V1.11) created
-- partial indexes with WHERE clauses, which cannot be used with ON CONFLICT.
--
-- This migration replaces partial indexes with full unique constraints.
-- PostgreSQL unique constraints naturally handle NULLs correctly: multiple
-- rows can have NULL values in the constraint columns without violating
-- uniqueness (NULL != NULL in SQL).
-- ============================================================================

-- Drop partial indexes that cannot be used with ON CONFLICT
DROP INDEX IF EXISTS uq_contacts_provider_external_id;
DROP INDEX IF EXISTS idx_contacts_company_provider_external;
DROP INDEX IF EXISTS uq_purchase_orders_provider_external_id;
DROP INDEX IF EXISTS idx_purchase_orders_company_provider_external;

-- Create full unique constraints for ON CONFLICT operations
-- Note: PostgreSQL unique constraints allow multiple NULLs (NULL != NULL)
-- This means local records (provider IS NULL) won't conflict with each other
ALTER TABLE contacts 
    ADD CONSTRAINT uq_contacts_company_provider_external 
    UNIQUE (company_id, provider, external_id);

ALTER TABLE purchase_orders 
    ADD CONSTRAINT uq_purchase_orders_company_provider_external 
    UNIQUE (company_id, provider, external_id);

COMMENT ON CONSTRAINT uq_contacts_company_provider_external ON contacts IS 
    'Enforces uniqueness of provider-synced contacts. Multiple rows can have NULL provider/external_id (local records). Enables ON CONFLICT DO UPDATE for batched upserts.';

COMMENT ON CONSTRAINT uq_purchase_orders_company_provider_external ON purchase_orders IS 
    'Enforces uniqueness of provider-synced purchase orders. Multiple rows can have NULL provider/external_id (local records). Enables ON CONFLICT DO UPDATE for batched upserts.';

-- ============================================================================
-- Add partial unique indexes for provider-synced records
-- ============================================================================
-- These indexes enable efficient ON CONFLICT DO UPDATE operations for batched
-- upserts from external providers (QuickBooks, Xero, etc.).
-- 
-- Partial indexes (WHERE clause) ensure uniqueness only for provider-synced
-- records, avoiding conflicts with local records (where provider IS NULL).
-- ============================================================================

-- Unique constraint for contacts: (company_id, provider, external_id)
-- Only applies when provider and external_id are NOT NULL
CREATE UNIQUE INDEX IF NOT EXISTS idx_contacts_company_provider_external 
    ON contacts(company_id, provider, external_id) 
    WHERE provider IS NOT NULL AND external_id IS NOT NULL;

-- Unique constraint for purchase_orders: (company_id, provider, external_id)
CREATE UNIQUE INDEX IF NOT EXISTS idx_purchase_orders_company_provider_external 
    ON purchase_orders(company_id, provider, external_id) 
    WHERE provider IS NOT NULL AND external_id IS NOT NULL;

COMMENT ON INDEX idx_contacts_company_provider_external IS 
    'Ensures uniqueness of provider-synced contacts per company. Enables ON CONFLICT DO UPDATE for batched upserts.';

COMMENT ON INDEX idx_purchase_orders_company_provider_external IS 
    'Ensures uniqueness of provider-synced purchase orders per company. Enables ON CONFLICT DO UPDATE for batched upserts.';

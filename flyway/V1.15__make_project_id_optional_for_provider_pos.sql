-- ============================================================================
-- Make project_id optional for provider-synced purchase orders
-- ============================================================================
-- Provider-synced purchase orders from external systems (QuickBooks, Xero)
-- don't have a project_id. Display ID generation is moving from database
-- triggers to application logic.
--
-- Note: Provider-synced POs already have ON CONFLICT support via constraint
-- uq_purchase_orders_company_provider_external (company_id, provider, external_id)
-- created in V1.12. This migration preserves that constraint.
-- ============================================================================

-- Drop the trigger that generates display_id (no longer needed)
DROP TRIGGER IF EXISTS trigger_set_po_display_id ON purchase_orders;
DROP FUNCTION IF EXISTS set_po_display_id();

-- Make project_id nullable
ALTER TABLE purchase_orders 
    ALTER COLUMN project_id DROP NOT NULL;

-- Drop the old unique index
DROP INDEX IF EXISTS idx_purchase_orders_project_display_id;

-- Create a full unique constraint (not partial index) to support ON CONFLICT
-- PostgreSQL unique constraints allow multiple NULLs (NULL != NULL), so:
-- - Local POs: (project_id, display_id) must be unique (both non-NULL)
-- - Provider-synced POs: (NULL, display_id) - multiple rows can have NULL project_id
--   with same display_id (they're unique by (company_id, provider, external_id) anyway)
ALTER TABLE purchase_orders 
    ADD CONSTRAINT uq_purchase_orders_project_display_id 
    UNIQUE (project_id, display_id);

-- Add check constraint: local POs (provider IS NULL) must have project_id
ALTER TABLE purchase_orders 
    ADD CONSTRAINT check_local_po_has_project 
    CHECK ((provider IS NOT NULL) OR (project_id IS NOT NULL));

COMMENT ON CONSTRAINT uq_purchase_orders_project_display_id ON purchase_orders IS 
    'Ensures uniqueness of (project_id, display_id) for local purchase orders. Supports ON CONFLICT operations. Multiple provider-synced POs can have NULL project_id with same display_id (unique by company_id, provider, external_id).';

COMMENT ON CONSTRAINT check_local_po_has_project ON purchase_orders IS 
    'Local purchase orders (provider IS NULL) must have a project_id. Provider-synced POs (provider IS NOT NULL) can have NULL project_id.';

-- Note: Provider-synced POs use constraint uq_purchase_orders_company_provider_external
-- (company_id, provider, external_id) for ON CONFLICT upserts - already exists from V1.12

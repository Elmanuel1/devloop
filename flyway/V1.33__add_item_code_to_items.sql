-- ============================================================================
-- Add item_code column to items table
-- item_code is optional but must be unique per company when provided
-- Partial index excludes NULL codes, allowing multiple items without a code
-- ============================================================================

ALTER TABLE items ADD COLUMN code VARCHAR(100);

-- Unique constraint: code must be unique per company when NOT NULL
-- Partial index only includes rows where code IS NOT NULL, allowing multiple NULLs
CREATE UNIQUE INDEX idx_items_company_code ON items(company_id, code) WHERE code IS NOT NULL;

COMMENT ON COLUMN items.code IS 'Optional item code/SKU, unique per company when provided';


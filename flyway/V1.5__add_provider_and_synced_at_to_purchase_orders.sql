-- Add provider and synced_at columns to purchase_orders table
-- provider: nullable string indicating integration provider (e.g., "quickbooks", "xero")
-- synced_at: nullable timestamp indicating when the PO was last synced from external system

ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS provider VARCHAR(50),
    ADD COLUMN IF NOT EXISTS synced_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN purchase_orders.provider IS 'Integration provider identifier (null for platform-created POs)';
COMMENT ON COLUMN purchase_orders.synced_at IS 'Timestamp when PO was last synced from external system';

CREATE INDEX IF NOT EXISTS idx_purchase_orders_provider ON purchase_orders(provider)
    WHERE provider IS NOT NULL;

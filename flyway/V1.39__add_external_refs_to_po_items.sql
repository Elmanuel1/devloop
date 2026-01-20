-- Add external item and account ID columns to purchase_order_items
-- These cache the provider's external IDs for performance optimization
-- This allows us to avoid fetching items/accounts on every PO push

ALTER TABLE purchase_order_items
    ADD COLUMN external_item_id VARCHAR(255),
    ADD COLUMN external_account_id VARCHAR(255);

-- Create partial indexes for performance (only index non-null values)
CREATE INDEX idx_po_items_external_item_id
    ON purchase_order_items(external_item_id)
    WHERE external_item_id IS NOT NULL;

CREATE INDEX idx_po_items_external_account_id
    ON purchase_order_items(external_account_id)
    WHERE external_account_id IS NOT NULL;

-- Add comments explaining the purpose of these columns
COMMENT ON COLUMN purchase_order_items.external_item_id IS
    'Provider item ID (e.g., QuickBooks ItemRef.value) - cached for performance';
COMMENT ON COLUMN purchase_order_items.external_account_id IS
    'Provider account ID (e.g., QuickBooks AccountRef.value) - cached for performance';

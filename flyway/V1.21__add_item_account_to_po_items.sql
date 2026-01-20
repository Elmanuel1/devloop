-- ============================================================================
-- Add item_id and account_id to purchase_order_items for Item/Account selection
-- ============================================================================

ALTER TABLE purchase_order_items
    ADD COLUMN IF NOT EXISTS item_id UUID REFERENCES items(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS account_id UUID REFERENCES integration_accounts(id) ON DELETE SET NULL;

-- Index for item lookups
CREATE INDEX IF NOT EXISTS idx_po_items_item_id ON purchase_order_items(item_id) WHERE item_id IS NOT NULL;

-- Index for account lookups
CREATE INDEX IF NOT EXISTS idx_po_items_account_id ON purchase_order_items(account_id) WHERE account_id IS NOT NULL;

COMMENT ON COLUMN purchase_order_items.item_id IS 'Reference to Items table for item-based lines. Only one of item_id or account_id should be set.';
COMMENT ON COLUMN purchase_order_items.account_id IS 'Reference to IntegrationAccounts table for account-based lines. Only one of item_id or account_id should be set.';

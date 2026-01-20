-- Change foreign keys on purchase_order_items to fail if referenced item/account doesn't exist
-- Previously used ON DELETE SET NULL, now use ON DELETE RESTRICT to enforce referential integrity

-- Drop existing foreign key for item_id
ALTER TABLE purchase_order_items
    DROP CONSTRAINT IF EXISTS purchase_order_items_item_id_fkey;

-- Recreate with ON DELETE RESTRICT (fails if trying to delete referenced item)
ALTER TABLE purchase_order_items
    ADD CONSTRAINT purchase_order_items_item_id_fkey
    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE RESTRICT;

-- Drop existing foreign key for account_id
ALTER TABLE purchase_order_items
    DROP CONSTRAINT IF EXISTS purchase_order_items_account_id_fkey;

-- Recreate with ON DELETE RESTRICT (fails if trying to delete referenced account)
ALTER TABLE purchase_order_items
    ADD CONSTRAINT purchase_order_items_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES integration_accounts(id) ON DELETE RESTRICT;

COMMENT ON CONSTRAINT purchase_order_items_item_id_fkey ON purchase_order_items IS 'FK to items table - prevents insert with non-existent item_id and prevents deletion of referenced items';
COMMENT ON CONSTRAINT purchase_order_items_account_id_fkey ON purchase_order_items IS 'FK to integration_accounts table - prevents insert with non-existent account_id and prevents deletion of referenced accounts';


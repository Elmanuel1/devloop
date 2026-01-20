-- ============================================================================
-- Change integration_accounts.id from UUID to VARCHAR(26) to use ULID
-- ============================================================================

-- Drop foreign key constraint from purchase_order_items
ALTER TABLE purchase_order_items
    DROP CONSTRAINT IF EXISTS purchase_order_items_account_id_fkey;

-- Change purchase_order_items.account_id to text first
ALTER TABLE purchase_order_items
    ALTER COLUMN account_id TYPE TEXT USING account_id::text;

-- Create mapping table
CREATE TEMP TABLE account_id_mapping AS
SELECT id::text AS old_id, gen_ulid() AS new_id
FROM integration_accounts;

-- Update purchase_order_items with new ULIDs
UPDATE purchase_order_items poi
SET account_id = mapping.new_id
FROM account_id_mapping mapping
WHERE poi.account_id = mapping.old_id;

-- Add temp column for new ULIDs
ALTER TABLE integration_accounts ADD COLUMN temp_id VARCHAR(26);

-- Populate temp_id
UPDATE integration_accounts ia
SET temp_id = mapping.new_id
FROM account_id_mapping mapping
WHERE ia.id::text = mapping.old_id;

-- Drop primary key
ALTER TABLE integration_accounts DROP CONSTRAINT integration_accounts_pkey;

-- Change column type
ALTER TABLE integration_accounts ALTER COLUMN id TYPE VARCHAR(26) USING temp_id;

-- Drop temp column
ALTER TABLE integration_accounts DROP COLUMN temp_id;

-- Recreate primary key
ALTER TABLE integration_accounts ADD CONSTRAINT integration_accounts_pkey PRIMARY KEY (id);

-- Set default
ALTER TABLE integration_accounts ALTER COLUMN id SET DEFAULT gen_ulid();

-- Change purchase_order_items.account_id to VARCHAR(26)
ALTER TABLE purchase_order_items
    ALTER COLUMN account_id TYPE VARCHAR(26) USING account_id::text;

-- Recreate foreign key
ALTER TABLE purchase_order_items
    ADD CONSTRAINT purchase_order_items_account_id_fkey
    FOREIGN KEY (account_id) REFERENCES integration_accounts(id) ON DELETE SET NULL;

COMMENT ON COLUMN integration_accounts.id IS 'ULID identifier for the account';


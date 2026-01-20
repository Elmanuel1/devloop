-- ============================================================================
-- Change items.id from UUID to VARCHAR(26) to use ULID
-- ============================================================================

-- First, drop foreign key constraints that reference items.id
ALTER TABLE purchase_order_items
    DROP CONSTRAINT IF EXISTS purchase_order_items_item_id_fkey;

-- Create a temporary mapping table for old UUID -> new ULID
CREATE TEMP TABLE item_id_mapping AS
SELECT 
    id::text AS old_id,
    gen_ulid() AS new_id
FROM items;

-- Update purchase_order_items.item_id with new ULIDs
-- First change purchase_order_items.item_id to text to allow the update
ALTER TABLE purchase_order_items
    ALTER COLUMN item_id TYPE TEXT USING item_id::text;

UPDATE purchase_order_items poi
SET item_id = mapping.new_id
FROM item_id_mapping mapping
WHERE poi.item_id = mapping.old_id;

-- Change purchase_order_items.item_id to VARCHAR(26)
ALTER TABLE purchase_order_items
    ALTER COLUMN item_id TYPE VARCHAR(26) USING item_id::text;

-- Add a temporary column for new ULIDs
ALTER TABLE items
    ADD COLUMN temp_id VARCHAR(26);

-- Populate temp_id with new ULIDs from mapping
UPDATE items i
SET temp_id = mapping.new_id
FROM item_id_mapping mapping
WHERE i.id::text = mapping.old_id;

-- Drop the primary key constraint
ALTER TABLE items
    DROP CONSTRAINT items_pkey;

-- Change the column type from UUID to VARCHAR(26)
ALTER TABLE items
    ALTER COLUMN id TYPE VARCHAR(26) USING temp_id;

-- Drop the temporary column
ALTER TABLE items
    DROP COLUMN temp_id;

-- Recreate the primary key constraint
ALTER TABLE items
    ADD CONSTRAINT items_pkey PRIMARY KEY (id);

-- Update the default to use gen_ulid()
ALTER TABLE items
    ALTER COLUMN id SET DEFAULT gen_ulid();

-- Recreate the foreign key constraint
ALTER TABLE purchase_order_items
    ADD CONSTRAINT purchase_order_items_item_id_fkey
    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE SET NULL;

COMMENT ON COLUMN items.id IS 'ULID identifier for the item';


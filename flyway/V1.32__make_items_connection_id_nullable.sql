-- ============================================================================
-- Make items.connection_id nullable to allow locally-created items
-- Locally-created items don't have a connection until they are synced
-- ============================================================================

ALTER TABLE items ALTER COLUMN connection_id DROP NOT NULL;

COMMENT ON COLUMN items.connection_id IS 'Integration connection this item was synced from (NULL for locally-created items)';


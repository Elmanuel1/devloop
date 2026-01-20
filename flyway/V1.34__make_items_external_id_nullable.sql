-- ============================================================================
-- Make items.external_id nullable to allow locally-created items
-- Locally-created items don't have an external_id until they are synced
-- ============================================================================

ALTER TABLE items ALTER COLUMN external_id DROP NOT NULL;

COMMENT ON COLUMN items.external_id IS 'External provider item ID (NULL for locally-created items until synced)';


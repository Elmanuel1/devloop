-- ============================================================================
-- Add provider column to items table for consistency with contacts
-- ============================================================================

ALTER TABLE items ADD COLUMN provider VARCHAR(50);

-- Backfill provider from connection for existing items
UPDATE items i
SET provider = ic.provider
FROM integration_connections ic
WHERE i.connection_id = ic.id
AND i.provider IS NULL;

-- Add index for provider queries (used by enrichers)
CREATE INDEX idx_items_provider ON items(provider);

COMMENT ON COLUMN items.provider IS 'Integration provider (e.g., quickbooks). Set during sync/push operations.';

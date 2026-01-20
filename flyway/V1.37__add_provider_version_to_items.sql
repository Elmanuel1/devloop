-- ============================================================================
-- Add provider_version column to items table for bidirectional sync
-- ============================================================================
--
-- Rationale:
-- - Items now support push to QuickBooks (bidirectional sync)
-- - provider_version stores QuickBooks SyncToken for optimistic concurrency control
-- - Follows same pattern as contacts and purchase_orders (V1.18)
--

ALTER TABLE items ADD COLUMN IF NOT EXISTS provider_version VARCHAR(255);

COMMENT ON COLUMN items.provider_version IS
    'Provider-specific version token for optimistic concurrency control (e.g., QuickBooks SyncToken).';

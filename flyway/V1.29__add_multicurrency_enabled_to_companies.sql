-- ============================================================================
-- Add multicurrency_enabled column to companies table
-- ============================================================================

ALTER TABLE companies ADD COLUMN multicurrency_enabled BOOLEAN;
COMMENT ON COLUMN companies.multicurrency_enabled IS 'Whether multicurrency is enabled in the connected integration provider. Synced from integration preferences.';


-- ============================================================================
-- Set default value for multicurrency_enabled column to TRUE
-- ============================================================================

ALTER TABLE companies ALTER COLUMN multicurrency_enabled SET DEFAULT TRUE;

-- Update existing NULL values to TRUE
UPDATE companies SET multicurrency_enabled = TRUE WHERE multicurrency_enabled IS NULL;

COMMENT ON COLUMN companies.multicurrency_enabled IS 'Whether multicurrency is enabled in the connected integration provider. Synced from integration preferences. Defaults to true for new companies.';


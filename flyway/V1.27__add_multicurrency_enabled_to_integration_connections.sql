-- ============================================================================
-- Add multicurrency_enabled column to integration_connections
-- ============================================================================

ALTER TABLE integration_connections ADD COLUMN multicurrency_enabled BOOLEAN;
COMMENT ON COLUMN integration_connections.multicurrency_enabled IS 'Whether multicurrency is enabled in the provider (e.g., QBO MultiCurrencyEnabled preference).';


-- ============================================================================
-- Add category column to integration_connections
-- ============================================================================

-- Add column as nullable first
ALTER TABLE integration_connections ADD COLUMN category VARCHAR(50);

-- Set existing rows to 'financial' category
UPDATE integration_connections SET category = 'financial' WHERE category IS NULL;

-- Make column NOT NULL
ALTER TABLE integration_connections ALTER COLUMN category SET NOT NULL;

COMMENT ON COLUMN integration_connections.category IS 'Category of integration: financial, accounting, files, etc. Only one connection per category can be enabled at a time.';

-- Create index for efficient lookup of enabled connections by category
CREATE INDEX idx_integration_connections_category_status ON integration_connections(category, status) WHERE status = 'enabled';


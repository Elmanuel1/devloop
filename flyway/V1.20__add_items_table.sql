-- ============================================================================
-- Add items table for QuickBooks Items (products/services)
-- Items are PULL-ONLY (synced from QuickBooks, not pushed)
-- ============================================================================

CREATE TABLE items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    connection_id VARCHAR(26) NOT NULL REFERENCES integration_connections(id) ON DELETE CASCADE,
    external_id VARCHAR(255) NOT NULL,
    name VARCHAR(500) NOT NULL,
    description TEXT,
    type VARCHAR(50),
    unit_price DECIMAL(12, 2),
    purchase_cost DECIMAL(12, 2),
    active BOOLEAN DEFAULT true,
    taxable BOOLEAN DEFAULT false,
    quantity_on_hand DECIMAL(12, 4),
    external_metadata JSONB DEFAULT '{}'::jsonb,
    provider_created_at TIMESTAMP WITH TIME ZONE,
    provider_last_updated_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(connection_id, external_id)
);

-- Index for company queries
CREATE INDEX idx_items_company ON items(company_id);

-- Index for connection queries
CREATE INDEX idx_items_connection ON items(connection_id);

-- Index for incremental sync queries (PULL)
CREATE INDEX idx_items_connection_updated ON items(connection_id, provider_last_updated_at);

-- Index for filtering by type
CREATE INDEX idx_items_type ON items(type);

-- Partial index for active items only (most common query pattern)
CREATE INDEX idx_items_active ON items(company_id, active) WHERE active = true;

COMMENT ON TABLE items IS 'Items (products/services) pulled from QuickBooks. Used for ItemBasedExpenseLineDetail in PO lines.';
COMMENT ON COLUMN items.company_id IS 'Company that owns this item (required)';
COMMENT ON COLUMN items.connection_id IS 'Integration connection this item was synced from';
COMMENT ON COLUMN items.external_id IS 'QuickBooks Item ID';
COMMENT ON COLUMN items.type IS 'Item type: Inventory, Service, NonInventory';
COMMENT ON COLUMN items.active IS 'false = item made inactive in QBO (items cannot be permanently deleted in QBO)';
COMMENT ON COLUMN items.external_metadata IS 'Provider-specific fields from QuickBooks';
COMMENT ON COLUMN items.provider_created_at IS 'Creation timestamp from QuickBooks MetaData.CreateTime';
COMMENT ON COLUMN items.provider_last_updated_at IS 'Last update timestamp from QuickBooks MetaData.LastUpdatedTime';

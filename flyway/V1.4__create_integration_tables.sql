-- Integration tables for multi-provider financial system connections
-- Supports QuickBooks Online, Xero, Sage, etc.

-- Integration connections: stores OAuth tokens per company per provider
CREATE TABLE integration_connections (
    id VARCHAR(26) DEFAULT gen_ulid() NOT NULL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,  -- 'QUICKBOOKS', 'XERO', 'SAGE'
    status VARCHAR(50) NOT NULL DEFAULT 'disabled',  -- 'disabled', 'enabled', 'expired', 'revoked'

    -- OAuth tokens (encrypted at application level)
    access_token TEXT NOT NULL,
    refresh_token TEXT,
    token_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Provider-specific identifiers
    realm_id VARCHAR(255),  -- QuickBooks company/realm ID
    external_company_id VARCHAR(255),  -- Generic external ID
    external_company_name VARCHAR(255),

    -- Metadata
    scopes TEXT,  -- Comma-separated scopes granted
    last_sync_at TIMESTAMP WITH TIME ZONE,
    last_sync_started_at TIMESTAMP WITH TIME ZONE,
    last_sync_completed_at TIMESTAMP WITH TIME ZONE,
    
    -- Cursor tracking for push sync
    last_push_cursor VARCHAR(26),       -- ID of last processed document
    last_push_cursor_at TIMESTAMP WITH TIME ZONE, -- Approved timestamp of last processed document
    sync_from TIMESTAMP WITH TIME ZONE, -- Configurable start date for sync

    error_message TEXT,
    metadata JSONB DEFAULT '{}'::jsonb,

    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,

    -- One connection per provider per company
    CONSTRAINT uq_company_provider UNIQUE (company_id, provider)
);

COMMENT ON TABLE integration_connections IS 'OAuth connections to external financial systems (QBO, Xero, etc.)';
COMMENT ON COLUMN integration_connections.realm_id IS 'QuickBooks-specific: The company/realm ID in QBO';
COMMENT ON COLUMN integration_connections.provider IS 'Integration provider: QUICKBOOKS, XERO, SAGE';
COMMENT ON COLUMN integration_connections.status IS 'Connection status: disabled (sync off), enabled (sync on), expired, revoked';
COMMENT ON COLUMN integration_connections.last_push_cursor IS 'ULID of the last successfully processed document approval for push sync';
COMMENT ON COLUMN integration_connections.last_push_cursor_at IS 'Approved timestamp of the last processed document, used for cursor-based pagination';
COMMENT ON COLUMN integration_connections.sync_from IS 'Start date for syncing documents. If null, defaults to connection creation time.';

CREATE INDEX idx_integration_connections_company ON integration_connections(company_id);
CREATE INDEX idx_integration_connections_status ON integration_connections(status) WHERE status = 'enabled';
CREATE INDEX idx_integration_connections_provider ON integration_connections(provider);

-- Integration sync status: tracks sync state for each document
CREATE TABLE integration_sync_status (
    id VARCHAR(26) DEFAULT gen_ulid() NOT NULL PRIMARY KEY,
    connection_id VARCHAR(26) NOT NULL REFERENCES integration_connections(id) ON DELETE CASCADE,

    -- What we're syncing
    document_type VARCHAR(50) NOT NULL,  -- 'INVOICE', 'DELIVERY_SLIP', 'PURCHASE_ORDER'
    document_id VARCHAR(255) NOT NULL,  -- ID in our system
    sync_direction VARCHAR(20) NOT NULL,  -- 'OUTBOUND', 'INBOUND'

    -- External system reference
    external_id VARCHAR(255),  -- Bill ID, PO ID in external system
    external_doc_number VARCHAR(255),  -- Doc number in external system

    -- Sync state
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',  -- 'PENDING', 'SYNCING', 'COMPLETED', 'FAILED', 'MANUAL_REVIEW'
    attempts INTEGER DEFAULT 0,
    max_attempts INTEGER DEFAULT 5,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    next_retry_at TIMESTAMP WITH TIME ZONE,

    -- Error tracking
    error_message TEXT,
    error_details JSONB,

    -- Timestamps
    synced_at TIMESTAMP WITH TIME ZONE,  -- When successfully synced
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE,

    -- Prevent duplicate syncs
    CONSTRAINT uq_sync_document UNIQUE (connection_id, document_type, document_id, sync_direction)
);

COMMENT ON TABLE integration_sync_status IS 'Tracks sync status for documents between local and external systems';
COMMENT ON COLUMN integration_sync_status.sync_direction IS 'OUTBOUND = local to external, INBOUND = external to local';
COMMENT ON COLUMN integration_sync_status.status IS 'PENDING, SYNCING, COMPLETED, FAILED, MANUAL_REVIEW';

CREATE INDEX idx_sync_status_pending ON integration_sync_status(status, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_sync_status_connection ON integration_sync_status(connection_id);
CREATE INDEX idx_sync_status_document ON integration_sync_status(document_type, document_id);

-- Add external sync tracking to existing document tables
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS external_provider VARCHAR(50),
    ADD COLUMN IF NOT EXISTS external_synced_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS external_provider VARCHAR(50),
    ADD COLUMN IF NOT EXISTS external_synced_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS external_doc_number VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_invoices_external ON invoices(external_provider, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_purchase_orders_external ON purchase_orders(external_provider, external_id)
    WHERE external_id IS NOT NULL;

-- Add company settings for integrations and auto-approval
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS currency VARCHAR(3) DEFAULT 'CAD',
    ADD COLUMN IF NOT EXISTS auto_approval_enabled BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS auto_approval_threshold DECIMAL(12, 2);

COMMENT ON COLUMN companies.currency IS 'Company default currency for all financial operations';
COMMENT ON COLUMN companies.auto_approval_enabled IS 'Whether auto-approval is enabled for documents below threshold';
COMMENT ON COLUMN companies.auto_approval_threshold IS 'Documents below this amount are auto-approved (in company currency)';

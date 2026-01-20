-- ============================================================================
-- Add provider tracking columns and create integration tables for PULL/PUSH sync
-- ============================================================================

-- ============================================================================
-- 1. Contacts (TWO-WAY: PULL + PUSH)
-- ============================================================================
ALTER TABLE contacts
    ADD COLUMN IF NOT EXISTS provider VARCHAR(50),
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS external_metadata JSONB DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS provider_created_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS provider_last_updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS pushed_version BIGINT DEFAULT 0 NOT NULL;

-- Update unique constraints to consider provider
ALTER TABLE contacts DROP CONSTRAINT IF EXISTS contacts_company_id_email_key;
ALTER TABLE contacts DROP CONSTRAINT IF EXISTS contacts_company_id_phone_key;

-- Synced contacts: unique by company + provider + external_id
CREATE UNIQUE INDEX IF NOT EXISTS uq_contacts_provider_external_id 
    ON contacts(company_id, provider, external_id) 
    WHERE provider IS NOT NULL;

-- Local contacts: email/phone unique per company when provider is NULL
CREATE UNIQUE INDEX IF NOT EXISTS uq_contacts_local_email 
    ON contacts(company_id, email) 
    WHERE provider IS NULL AND email IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_contacts_local_phone 
    ON contacts(company_id, phone) 
    WHERE provider IS NULL AND phone IS NOT NULL;

-- Index for incremental sync queries (PULL)
CREATE INDEX IF NOT EXISTS idx_contacts_provider_updated 
    ON contacts(provider, provider_last_updated_at) 
    WHERE provider IS NOT NULL;

-- Index for push sync queries (PUSH)
CREATE INDEX IF NOT EXISTS idx_contacts_needs_push 
    ON contacts(company_id, provider, version, pushed_version) 
    WHERE version > pushed_version OR provider IS NULL;

COMMENT ON COLUMN contacts.provider IS 'Integration provider (QUICKBOOKS, XERO, etc). NULL = locally created contact.';
COMMENT ON COLUMN contacts.external_id IS 'External vendor/contact ID from provider system';
COMMENT ON COLUMN contacts.external_metadata IS 'Provider-specific fields (e.g., QB terms, balance, account number)';
COMMENT ON COLUMN contacts.provider_created_at IS 'Creation timestamp from provider (e.g., QB MetaData.CreateTime)';
COMMENT ON COLUMN contacts.provider_last_updated_at IS 'Last update timestamp from provider (e.g., QB MetaData.LastUpdatedTime)';
COMMENT ON COLUMN contacts.version IS 'Increments on every update';
COMMENT ON COLUMN contacts.pushed_version IS 'Snapshot of version at last successful push to provider';

-- ============================================================================
-- 2. Purchase Orders (TWO-WAY: PULL + PUSH)
-- ============================================================================
-- Note: purchase_orders already has provider (V1.5) and external_id (V1.4)
ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS external_metadata JSONB DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS provider_created_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS provider_last_updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS pushed_version BIGINT DEFAULT 0 NOT NULL;

-- Update constraint to use company_id + provider + external_id
CREATE UNIQUE INDEX IF NOT EXISTS uq_purchase_orders_provider_external_id 
    ON purchase_orders(company_id, provider, external_id) 
    WHERE provider IS NOT NULL;

-- Index for incremental sync queries (PULL)
CREATE INDEX IF NOT EXISTS idx_purchase_orders_provider_updated 
    ON purchase_orders(provider, provider_last_updated_at) 
    WHERE provider IS NOT NULL;

-- Index for push sync queries (PUSH)
CREATE INDEX IF NOT EXISTS idx_purchase_orders_needs_push 
    ON purchase_orders(company_id, provider, version, pushed_version) 
    WHERE version > pushed_version OR provider IS NULL;

COMMENT ON COLUMN purchase_orders.provider IS 'Integration provider (QUICKBOOKS, XERO, etc). NULL = locally created PO.';
COMMENT ON COLUMN purchase_orders.external_metadata IS 'Provider-specific fields from external system';
COMMENT ON COLUMN purchase_orders.provider_created_at IS 'Creation timestamp from provider (e.g., QB MetaData.CreateTime)';
COMMENT ON COLUMN purchase_orders.provider_last_updated_at IS 'Last update timestamp from provider (e.g., QB MetaData.LastUpdatedTime)';
COMMENT ON COLUMN purchase_orders.version IS 'Increments on every update';
COMMENT ON COLUMN purchase_orders.pushed_version IS 'Snapshot of version at last successful push to provider';

-- ============================================================================
-- 3. Invoices (PUSH-ONLY)
-- ============================================================================
-- Note: invoices already has external_id and external_provider columns (V1.4)
ALTER TABLE invoices
    ADD COLUMN IF NOT EXISTS provider VARCHAR(50),
    ADD COLUMN IF NOT EXISTS external_metadata JSONB DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL,
    ADD COLUMN IF NOT EXISTS pushed_version BIGINT DEFAULT 0 NOT NULL;

-- Index for push sync queries (PUSH)
CREATE INDEX IF NOT EXISTS idx_invoices_needs_push 
    ON invoices(company_id, provider, version, pushed_version) 
    WHERE version > pushed_version OR provider IS NULL;

COMMENT ON COLUMN invoices.provider IS 'Integration provider (QUICKBOOKS, XERO, etc). NULL = not yet synced.';
COMMENT ON COLUMN invoices.external_metadata IS 'Provider-specific fields';
COMMENT ON COLUMN invoices.version IS 'Increments on every update';
COMMENT ON COLUMN invoices.pushed_version IS 'Snapshot of version at last successful push to provider';

-- ============================================================================
-- 4. Payment Terms (PULL-ONLY)
-- ============================================================================
CREATE TABLE payment_terms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    provider VARCHAR(50),
    external_id VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    due_days INTEGER,
    discount_percent DECIMAL(5, 2),
    discount_days INTEGER,
    active BOOLEAN DEFAULT true,
    external_metadata JSONB DEFAULT '{}'::jsonb,
    provider_created_at TIMESTAMP WITH TIME ZONE,
    provider_last_updated_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE
);

-- Synced terms: unique by company + provider + external_id
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_terms_provider_external_id 
    ON payment_terms(company_id, provider, external_id) 
    WHERE provider IS NOT NULL;

-- Local terms: unique by company + name when provider is NULL
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_terms_local_name 
    ON payment_terms(company_id, name) 
    WHERE provider IS NULL;

-- Index for incremental sync queries (PULL)
CREATE INDEX IF NOT EXISTS idx_payment_terms_provider_updated 
    ON payment_terms(provider, provider_last_updated_at) 
    WHERE provider IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_terms_company ON payment_terms(company_id);

COMMENT ON TABLE payment_terms IS 'Payment terms from integrations or created locally. NULL provider = local.';
COMMENT ON COLUMN payment_terms.provider IS 'Integration provider (QUICKBOOKS, XERO, etc). NULL = locally created term.';
COMMENT ON COLUMN payment_terms.external_id IS 'External term ID from provider system';
COMMENT ON COLUMN payment_terms.external_metadata IS 'Provider-specific fields';
COMMENT ON COLUMN payment_terms.provider_created_at IS 'Creation timestamp from provider (e.g., QB MetaData.CreateTime)';
COMMENT ON COLUMN payment_terms.provider_last_updated_at IS 'Last update timestamp from provider (e.g., QB MetaData.LastUpdatedTime)';

-- ============================================================================
-- 5. Integration Accounts (PULL-ONLY, connection-specific)
-- ============================================================================
CREATE TABLE integration_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(26) NOT NULL REFERENCES integration_connections(id) ON DELETE CASCADE,
    external_id VARCHAR(255) NOT NULL,
    name VARCHAR(500) NOT NULL,
    account_type VARCHAR(100) NOT NULL,
    account_sub_type VARCHAR(100),
    classification VARCHAR(50),
    active BOOLEAN DEFAULT true,
    current_balance DECIMAL(12, 2),
    external_metadata JSONB DEFAULT '{}'::jsonb,
    provider_created_at TIMESTAMP WITH TIME ZONE,
    provider_last_updated_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(connection_id, external_id)
);

-- Index for incremental sync queries (PULL)
CREATE INDEX IF NOT EXISTS idx_accounts_connection_updated 
    ON integration_accounts(connection_id, provider_last_updated_at);

CREATE INDEX IF NOT EXISTS idx_accounts_connection ON integration_accounts(connection_id);
CREATE INDEX IF NOT EXISTS idx_accounts_type ON integration_accounts(account_type);

COMMENT ON TABLE integration_accounts IS 'Chart of accounts pulled from QuickBooks/Xero. Connection-specific, not company-wide.';
COMMENT ON COLUMN integration_accounts.external_metadata IS 'Provider-specific account fields';
COMMENT ON COLUMN integration_accounts.provider_created_at IS 'Creation timestamp from provider (e.g., QB MetaData.CreateTime)';
COMMENT ON COLUMN integration_accounts.provider_last_updated_at IS 'Last update timestamp from provider (e.g., QB MetaData.LastUpdatedTime)';

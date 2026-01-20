-- ============================================================================
-- Replace version/pushed_version tracking with provider_version token tracking
-- ============================================================================
--
-- Rationale:
-- - We no longer use local version counters (version/pushed_version) for push tracking.
-- - We store provider-specific concurrency tokens in provider_version (e.g., QBO SyncToken).
-- - This is provider-agnostic and supports optimistic concurrency control on update.
--

-- ============================================================================
-- 1) Contacts
-- ============================================================================
DROP INDEX IF EXISTS idx_contacts_needs_push;

ALTER TABLE contacts
    DROP COLUMN IF EXISTS version,
    DROP COLUMN IF EXISTS pushed_version,
    ADD COLUMN IF NOT EXISTS provider_version VARCHAR(255);

COMMENT ON COLUMN contacts.provider_version IS
    'Provider-specific version token for optimistic concurrency control (e.g., QuickBooks SyncToken, Xero version).';

-- ============================================================================
-- 2) Purchase Orders
-- ============================================================================
DROP INDEX IF EXISTS idx_purchase_orders_needs_push;

ALTER TABLE purchase_orders
    DROP COLUMN IF EXISTS version,
    DROP COLUMN IF EXISTS pushed_version,
    ADD COLUMN IF NOT EXISTS provider_version VARCHAR(255);

COMMENT ON COLUMN purchase_orders.provider_version IS
    'Provider-specific version token for optimistic concurrency control (e.g., QuickBooks SyncToken, Xero version).';

-- ============================================================================
-- 3) Invoices
-- ============================================================================
DROP INDEX IF EXISTS idx_invoices_needs_push;

ALTER TABLE invoices
    DROP COLUMN IF EXISTS version,
    DROP COLUMN IF EXISTS pushed_version,
    ADD COLUMN IF NOT EXISTS provider_version VARCHAR(255);

COMMENT ON COLUMN invoices.provider_version IS
    'Provider-specific version token for optimistic concurrency control (e.g., QuickBooks SyncToken, Xero version).';



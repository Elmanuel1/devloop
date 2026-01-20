-- ============================================================================
-- Relax email/phone constraint for provider-synced contacts
-- ============================================================================
-- Provider-synced contacts from external systems (QuickBooks, Xero, etc.)
-- may not always have email or phone. The constraint should only apply to
-- locally-created contacts to ensure data quality.
-- ============================================================================

-- Drop existing constraint
ALTER TABLE contacts DROP CONSTRAINT IF EXISTS contacts_email_phone_check;

-- Add modified constraint: provider-synced contacts are exempt
-- Using NOT VALID to avoid full-table scan under strong locks, then VALIDATE separately
ALTER TABLE contacts
    ADD CONSTRAINT contacts_email_phone_check
    CHECK ((provider IS NOT NULL) OR (tag = 'ship_to') OR (phone IS NOT NULL) OR (email IS NOT NULL))
    NOT VALID;

ALTER TABLE contacts
    VALIDATE CONSTRAINT contacts_email_phone_check;

COMMENT ON CONSTRAINT contacts_email_phone_check ON contacts IS 
    'Provider-synced contacts (provider IS NOT NULL) do not require email/phone. Local contacts (provider IS NULL) require tag=ship_to OR phone OR email.';

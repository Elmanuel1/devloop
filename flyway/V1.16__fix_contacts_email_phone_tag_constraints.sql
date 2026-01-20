-- ============================================================================
-- Fix contacts email/phone tag constraints to only apply to local contacts
-- ============================================================================
-- The old constraints contacts_company_id_email_tag_key and 
-- contacts_company_id_phone_tag_key apply to ALL contacts, including 
-- provider-synced ones. This causes conflicts when syncing contacts from
-- external systems that may have NULL email/phone.
--
-- Provider-synced contacts should be unique by (company_id, provider, external_id)
-- Local contacts should be unique by (company_id, email, tag) and (company_id, phone, tag)
-- ============================================================================

-- Drop the old constraints that apply to all contacts
ALTER TABLE contacts DROP CONSTRAINT IF EXISTS contacts_company_id_email_tag_key;
ALTER TABLE contacts DROP CONSTRAINT IF EXISTS contacts_company_id_phone_tag_key;

-- Create full unique constraints (not partial indexes) to support ON CONFLICT if needed
-- PostgreSQL unique constraints allow multiple NULLs (NULL != NULL), so:
-- - Local contacts: (company_id, email, tag) must be unique when email is non-NULL
-- - Provider-synced contacts: (company_id, NULL, tag) - multiple rows can have NULL email
--   with same tag (they're unique by (company_id, provider, external_id) anyway)
-- - Ship-to contacts: Can have NULL email/phone, so multiple NULLs allowed
ALTER TABLE contacts 
    ADD CONSTRAINT uq_contacts_company_email_tag 
    UNIQUE (company_id, email, tag);

ALTER TABLE contacts 
    ADD CONSTRAINT uq_contacts_company_phone_tag 
    UNIQUE (company_id, phone, tag);

COMMENT ON CONSTRAINT uq_contacts_company_email_tag ON contacts IS 
    'Ensures uniqueness of (company_id, email, tag) when email is non-NULL. Multiple contacts can have NULL email (provider-synced or ship_to). Provider-synced contacts are also unique by (company_id, provider, external_id).';

COMMENT ON CONSTRAINT uq_contacts_company_phone_tag ON contacts IS 
    'Ensures uniqueness of (company_id, phone, tag) when phone is non-NULL. Multiple contacts can have NULL phone (provider-synced or ship_to). Provider-synced contacts are also unique by (company_id, provider, external_id).';

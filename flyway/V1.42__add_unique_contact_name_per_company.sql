-- Add unique constraint on contact name per company
-- This prevents duplicate contact names within the same company, matching QuickBooks' requirement

-- First, identify and mark any existing duplicates as permanently failed
UPDATE contacts SET push_permanently_failed = true, push_failure_reason = 'Duplicate name within company'
WHERE id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY company_id, name ORDER BY created_at) as rn
        FROM contacts
        WHERE name IS NOT NULL
    ) t WHERE rn > 1
);

-- Now add the unique constraint
-- Note: Excludes permanently failed contacts to allow duplicates that are marked as failed
CREATE UNIQUE INDEX idx_contacts_unique_name_per_company
    ON contacts(company_id, LOWER(TRIM(name)))
    WHERE name IS NOT NULL AND push_permanently_failed = false;

COMMENT ON INDEX idx_contacts_unique_name_per_company IS
    'Ensures contact names are unique per company (case-insensitive, trimmed). Matches QuickBooks requirement that vendor/customer names must be unique.';

-- ============================================================================
-- Change payment_terms.id from UUID to VARCHAR(26) to use ULID
-- ============================================================================

-- Add a temporary column for new ULIDs
ALTER TABLE payment_terms
    ADD COLUMN temp_id VARCHAR(26);

-- Generate new ULIDs for existing records
UPDATE payment_terms
SET temp_id = gen_ulid();

-- Drop the primary key constraint
ALTER TABLE payment_terms
    DROP CONSTRAINT payment_terms_pkey;

-- Change the column type from UUID to VARCHAR(26)
ALTER TABLE payment_terms
    ALTER COLUMN id TYPE VARCHAR(26) USING temp_id;

-- Drop the temporary column
ALTER TABLE payment_terms
    DROP COLUMN temp_id;

-- Recreate the primary key constraint
ALTER TABLE payment_terms
    ADD CONSTRAINT payment_terms_pkey PRIMARY KEY (id);

-- Update the default to use gen_ulid()
ALTER TABLE payment_terms
    ALTER COLUMN id SET DEFAULT gen_ulid();

COMMENT ON COLUMN payment_terms.id IS 'ULID identifier for the payment term';


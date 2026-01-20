-- ============================================================================
-- Add country_of_incorporation column to companies table
-- ============================================================================

ALTER TABLE companies ADD COLUMN country_of_incorporation VARCHAR(2);
COMMENT ON COLUMN companies.country_of_incorporation IS 'ISO 3166-1 alpha-2 country code (e.g., US, CA, GB) indicating where the company is incorporated. Used to determine default currency.';


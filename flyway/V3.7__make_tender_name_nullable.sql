-- Make tender name nullable to support AI-deferred name extraction
ALTER TABLE tenders ALTER COLUMN name DROP NOT NULL;

-- Drop unique index — AI extraction means we have no control over name uniqueness
DROP INDEX IF EXISTS uq_tenders_company_name;

-- Remove draft status — tenders now start as pending.
ALTER TABLE tenders ALTER COLUMN status SET DEFAULT 'pending';

-- Migrate any existing draft tenders to pending.
UPDATE tenders SET status = 'pending' WHERE status = 'draft';

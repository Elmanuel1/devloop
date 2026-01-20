-- Add retry tracking to invoices table to align with other entities (contacts, items, purchase_orders)
-- This enables proper failure handling and prevents infinite retries for invoice-to-bill push operations
-- Note: provider and external_id columns already exist from V1.4 and V1.9

-- Add retry tracking columns
ALTER TABLE invoices
    ADD COLUMN push_retry_count INTEGER DEFAULT 0 NOT NULL,
    ADD COLUMN push_retry_last_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN push_permanently_failed BOOLEAN DEFAULT false NOT NULL,
    ADD COLUMN push_failure_reason TEXT;

-- Create indexes for performance
CREATE INDEX idx_invoices_push_failed
    ON invoices(company_id, push_permanently_failed)
    WHERE push_permanently_failed = true;

CREATE INDEX idx_invoices_retry_tracking
    ON invoices(company_id, push_retry_count, push_permanently_failed, last_sync_at);

-- Add helpful column comments
COMMENT ON COLUMN invoices.push_retry_count IS 'Number of times push has failed (0 = new/successful, >0 = has failed)';
COMMENT ON COLUMN invoices.push_permanently_failed IS 'True if push failed with non-retryable error or exceeded max retries';
COMMENT ON COLUMN invoices.push_failure_reason IS 'Last error message from failed push attempt';

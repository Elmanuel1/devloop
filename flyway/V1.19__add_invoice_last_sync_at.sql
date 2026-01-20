-- ============================================================================
-- V1.19__add_invoice_last_sync_at.sql
-- Add last_sync_at column to invoices table for tracking QuickBooks Bill sync
-- ============================================================================

-- Add last_sync_at column to track when invoice was last pushed to external provider
ALTER TABLE invoices 
    ADD COLUMN IF NOT EXISTS last_sync_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN invoices.last_sync_at IS 'Timestamp when this invoice was last successfully pushed to external provider (e.g., QuickBooks Bill)';


-- V1.38__add_last_sync_at_for_push_tracking.sql
-- Add last_sync_at columns to items, contacts, and purchase_orders for tracking successful pushes to external providers

-- Add last_sync_at to items table
ALTER TABLE items
    ADD COLUMN IF NOT EXISTS last_sync_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN items.last_sync_at IS
    'Timestamp when this item was last successfully pushed to external provider (e.g., QuickBooks). NULL indicates item needs to be pushed.';

-- Add last_sync_at to contacts table (vendors)
ALTER TABLE contacts
    ADD COLUMN IF NOT EXISTS last_sync_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN contacts.last_sync_at IS
    'Timestamp when this contact was last successfully pushed to external provider (e.g., QuickBooks Vendor). NULL indicates contact needs to be pushed.';

-- Add last_sync_at to purchase_orders table
ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS last_sync_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN purchase_orders.last_sync_at IS
    'Timestamp when this purchase order was last successfully pushed to external provider (e.g., QuickBooks). NULL indicates PO needs to be pushed.';

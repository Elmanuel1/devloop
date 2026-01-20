-- Add retry tracking columns to contacts table
ALTER TABLE contacts
    ADD COLUMN push_retry_count INTEGER DEFAULT 0 NOT NULL,
    ADD COLUMN push_retry_last_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN push_permanently_failed BOOLEAN DEFAULT false NOT NULL,
    ADD COLUMN push_failure_reason TEXT;

-- Add retry tracking columns to items table
ALTER TABLE items
    ADD COLUMN push_retry_count INTEGER DEFAULT 0 NOT NULL,
    ADD COLUMN push_retry_last_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN push_permanently_failed BOOLEAN DEFAULT false NOT NULL,
    ADD COLUMN push_failure_reason TEXT;

-- Add retry tracking columns to purchase_orders table
ALTER TABLE purchase_orders
    ADD COLUMN push_retry_count INTEGER DEFAULT 0 NOT NULL,
    ADD COLUMN push_retry_last_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN push_permanently_failed BOOLEAN DEFAULT false NOT NULL,
    ADD COLUMN push_failure_reason TEXT;

-- Indexes for contacts
CREATE INDEX idx_contacts_push_failed
    ON contacts(company_id, push_permanently_failed)
    WHERE push_permanently_failed = true;

CREATE INDEX idx_contacts_retry_tracking
    ON contacts(company_id, push_retry_count, push_permanently_failed, last_sync_at);

-- Indexes for items
CREATE INDEX idx_items_push_failed
    ON items(company_id, push_permanently_failed)
    WHERE push_permanently_failed = true;

CREATE INDEX idx_items_retry_tracking
    ON items(company_id, push_retry_count, push_permanently_failed, last_sync_at);

-- Indexes for purchase_orders
CREATE INDEX idx_purchase_orders_push_failed
    ON purchase_orders(company_id, push_permanently_failed)
    WHERE push_permanently_failed = true;

CREATE INDEX idx_purchase_orders_retry_tracking
    ON purchase_orders(company_id, push_retry_count, push_permanently_failed, last_sync_at);

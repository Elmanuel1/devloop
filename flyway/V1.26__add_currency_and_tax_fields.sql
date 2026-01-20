-- ============================================================================
-- Add currency and tax fields for multi-provider support
-- ============================================================================

-- Default currency on integration connections (from provider's company settings)
ALTER TABLE integration_connections ADD COLUMN default_currency VARCHAR(3);
COMMENT ON COLUMN integration_connections.default_currency IS 'Default currency from provider (e.g., QBO HomeCurrency). Used for provider sync context only.';

-- Currency on contacts (vendors)
ALTER TABLE contacts ADD COLUMN currency_code VARCHAR(3);
COMMENT ON COLUMN contacts.currency_code IS 'ISO 4217 currency code (e.g., USD, CAD). Immutable after transactions in some providers.';

-- Currency on purchase orders (required from frontend)
ALTER TABLE purchase_orders ADD COLUMN currency_code VARCHAR(3) NOT NULL DEFAULT 'USD';
COMMENT ON COLUMN purchase_orders.currency_code IS 'ISO 4217 currency code for this PO. Required field provided by frontend.';

-- Remove default after adding column (for new records)
ALTER TABLE purchase_orders ALTER COLUMN currency_code DROP DEFAULT;

-- Taxable flag on PO line items (default true = assume taxable)
ALTER TABLE purchase_order_items ADD COLUMN taxable BOOLEAN DEFAULT true;
COMMENT ON COLUMN purchase_order_items.taxable IS 'Whether this line item is expected to be taxed.';

-- Total price column if missing
ALTER TABLE purchase_order_items ADD COLUMN IF NOT EXISTS total_price NUMERIC(12,2);
COMMENT ON COLUMN purchase_order_items.total_price IS 'Total price for this line item (quantity * unit_price or direct amount).';


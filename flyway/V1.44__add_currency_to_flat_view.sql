-- Add currency_code to purchase_order_flat_items view
-- Using CREATE OR REPLACE to preserve grants and dependencies

CREATE OR REPLACE VIEW purchase_order_flat_items AS
SELECT
    po.id AS purchase_order_id,
    po.display_id,
    po.project_id,
    po.vendor_contact,
    po.ship_to_contact,
    po.order_date,
    po.due_date,
    po.status,
    po.metadata AS po_metadata,
    po.created_at,
    po.updated_at,
    po.change_log,
    po.company_id,
    po.notes,
    poi.id AS item_id,
    poi.name,
    poi.quantity,
    poi.unit_price,
    poi.expected_delivery_date,
    poi.delivery_status,
    poi.notes AS item_notes,
    poi.metadata AS item_metadata,
    poi.unit,
    poi.unit_code,
    po.deleted_at,
    poi.item_id AS line_item_ref_id,      -- FK to items table
    poi.account_id AS line_account_ref_id, -- FK to integration_accounts table
    poi.taxable,
    poi.total_price,
    po.currency_code                       -- Added currency_code at the end
FROM purchase_orders po
LEFT JOIN purchase_order_items poi ON po.id = poi.purchase_order_id;

COMMENT ON VIEW purchase_order_flat_items IS 'Flattened view of purchase orders with their line items. line_item_ref_id references items table, line_account_ref_id references integration_accounts table.';

-- Add deleted_at column to purchase_orders table
ALTER TABLE purchase_orders 
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- Create partial index for efficient filtering of non-deleted records
CREATE INDEX IF NOT EXISTS idx_purchase_orders_deleted_at 
ON purchase_orders (deleted_at) 
WHERE deleted_at IS NULL;

-- Add comment
COMMENT ON COLUMN purchase_orders.deleted_at IS 'Timestamp when this purchase order was soft deleted (NULL = active). Deleted records are filtered from frontend queries.';

-- Update purchase_order_flat_items view to include deleted_at
-- Add deleted_at at the end to maintain existing column order
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
    po.deleted_at
FROM purchase_orders po
LEFT JOIN purchase_order_items poi ON po.id = poi.purchase_order_id;

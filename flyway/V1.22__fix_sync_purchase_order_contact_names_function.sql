-- ============================================================================
-- Remove sync_purchase_order_contact_names() function
-- The function was referencing non-existent columns vendor_contact_id and ship_to_contact_id
-- Purchase orders now use JSONB fields vendor_contact and ship_to_contact
-- Contact names in purchase orders are managed at the application level
-- ============================================================================

-- Drop the trigger first (it depends on the function)
DROP TRIGGER IF EXISTS trigger_sync_purchase_order_contact_names ON public.contacts;

-- Drop the function
DROP FUNCTION IF EXISTS public.sync_purchase_order_contact_names() CASCADE;


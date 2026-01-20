-- ============================================================================
-- Drop integration_sync_status table and deprecate cursor-based pagination
-- ============================================================================
-- Since we now use version-based tracking (version > pushed_version) in source
-- tables to determine what needs to be pushed, the integration_sync_status table
-- is redundant and can be removed.
-- ============================================================================

-- Drop integration_sync_status table and all references
DROP TABLE IF EXISTS integration_sync_status CASCADE;

-- Mark deprecated columns (will be removed in next migration)
COMMENT ON COLUMN integration_connections.last_push_cursor IS 'Deprecated - will be removed in next migration';
COMMENT ON COLUMN integration_connections.last_push_cursor_at IS 'Deprecated - will be removed in next migration';




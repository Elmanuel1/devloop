ALTER TABLE document_approvals 
ADD COLUMN sync_status VARCHAR(20) DEFAULT NULL;

ALTER TABLE document_approvals 
ADD COLUMN last_sync_attempt TIMESTAMP WITH TIME ZONE DEFAULT NULL;

CREATE INDEX idx_document_approvals_sync_status 
ON document_approvals(sync_status) 
WHERE sync_status IS NULL;

COMMENT ON COLUMN document_approvals.sync_status IS 'Sync status: NULL (not synced), pending, synced, failed';
COMMENT ON COLUMN document_approvals.last_sync_attempt IS 'Timestamp of last sync attempt (for retry tracking)';



DROP INDEX IF EXISTS extractions_external_task_id_uq;

ALTER TABLE extractions
    DROP COLUMN IF EXISTS external_task_id;

ALTER TABLE extractions
    ADD COLUMN IF NOT EXISTS document_external_ids JSONB NOT NULL DEFAULT '{}';

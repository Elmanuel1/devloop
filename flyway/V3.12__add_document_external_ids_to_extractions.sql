DROP INDEX IF EXISTS extractions_external_task_id_uq;

ALTER TABLE extractions
    DROP COLUMN IF EXISTS external_task_id;

ALTER TABLE tender_documents
    ADD COLUMN IF NOT EXISTS external_task_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS external_file_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS tender_documents_external_task_id_uq
    ON tender_documents (external_task_id)
    WHERE deleted_at IS NULL
      AND external_task_id IS NOT NULL;

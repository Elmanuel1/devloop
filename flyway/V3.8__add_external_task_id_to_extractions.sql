-- V3.8__add_external_task_id_to_extractions.sql
-- Add external_task_id to the extractions table.
--
-- This column stores the opaque task identifier returned by the Reducto AI
-- service when an asynchronous extraction task is submitted. The pipeline
-- worker uses it to check back on task progress via the Reducto API.
--
-- A unique partial index is added so that no two non-deleted extractions can
-- share the same external task ID, while still allowing NULLs (rows that
-- have not yet been submitted to Reducto).

ALTER TABLE extractions
    ADD COLUMN IF NOT EXISTS external_task_id VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS extractions_external_task_id_uq
    ON extractions (external_task_id)
    WHERE deleted_at IS NULL
      AND external_task_id IS NOT NULL;

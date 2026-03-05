-- V3.11__remove_external_ids_from_tender_documents.sql
-- Compensating migration for V3.9 and V3.10.
--
-- external_task_id and external_file_id do not belong on tender_documents.
-- Per-document Reducto submission tracking is stored on the extraction as a
-- JSONB map (see V3.12). Remove both columns from tender_documents.

ALTER TABLE tender_documents
    DROP COLUMN IF EXISTS external_task_id,
    DROP COLUMN IF EXISTS external_file_id;

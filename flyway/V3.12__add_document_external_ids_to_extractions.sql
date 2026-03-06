-- V3.12__add_document_external_ids_to_extractions.sql
--
-- Replaces the single external_task_id VARCHAR column with a JSONB map that
-- tracks one Reducto task ID per document within the extraction. This allows
-- multi-document extractions to be correlated back to individual webhook
-- callbacks without a separate join table.
--
-- external_task_id is retained in this migration because the jOOQ generated
-- classes (flyway-jooq-classes 0.1.8) still reference it. The column and its
-- index will be dropped in a subsequent migration once the new jOOQ classes
-- (0.1.9) that omit the field are published and deployed.
--
-- Also adds external_file_id to tender_documents to store the Reducto file
-- identifier returned when a document is uploaded for extraction.

ALTER TABLE extractions
    ADD COLUMN IF NOT EXISTS document_external_ids JSONB NOT NULL DEFAULT '{}';

ALTER TABLE tender_documents
    ADD COLUMN IF NOT EXISTS external_file_id TEXT;

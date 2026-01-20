-- Simplify document_part_comparisons to store full result as JSONB
-- Keep metadata columns for indexing/querying

-- Step 1: Add new columns
ALTER TABLE document_part_comparisons
    ADD COLUMN IF NOT EXISTS document_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS po_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS result_data JSONB,
    ADD COLUMN IF NOT EXISTS overall_status VARCHAR(20);

-- Step 2: Delete duplicate rows - keep only one per extraction_id
-- (old schema had multiple rows per extraction, new schema has one row with JSONB array)
DELETE FROM document_part_comparisons a
    USING document_part_comparisons b
WHERE a.id > b.id
  AND a.extraction_id = b.extraction_id;

-- Step 3: Drop old detailed columns (no longer needed - full result in JSONB)
ALTER TABLE document_part_comparisons
    DROP COLUMN IF EXISTS part_type,
    DROP COLUMN IF EXISTS extracted_item_index,
    DROP COLUMN IF EXISTS extracted_part_name,
    DROP COLUMN IF EXISTS extracted_part_description,
    DROP COLUMN IF EXISTS matched_po_id,
    DROP COLUMN IF EXISTS matched_part_type,
    DROP COLUMN IF EXISTS matched_item_index,
    DROP COLUMN IF EXISTS matched_part_name,
    DROP COLUMN IF EXISTS match_score,
    DROP COLUMN IF EXISTS confidence,
    DROP COLUMN IF EXISTS match_reasons,
    DROP COLUMN IF EXISTS discrepancy_details;

-- Step 4: Add unique constraint on extraction_id for upsert support
ALTER TABLE document_part_comparisons
    DROP CONSTRAINT IF EXISTS uk_doc_part_comp_extraction;
ALTER TABLE document_part_comparisons
    ADD CONSTRAINT uk_doc_part_comp_extraction UNIQUE (extraction_id);

-- Step 5: Drop old indexes
DROP INDEX IF EXISTS idx_doc_part_comp_extraction;
DROP INDEX IF EXISTS idx_doc_part_comp_part_type;
DROP INDEX IF EXISTS idx_doc_part_comp_po;
DROP INDEX IF EXISTS idx_doc_part_comp_unmatched;

-- Step 6: Add new indexes for common queries
CREATE INDEX IF NOT EXISTS idx_doc_comp_po_id ON document_part_comparisons(po_id);
CREATE INDEX IF NOT EXISTS idx_doc_comp_doc_id ON document_part_comparisons(document_id);
CREATE INDEX IF NOT EXISTS idx_doc_comp_status ON document_part_comparisons(overall_status);

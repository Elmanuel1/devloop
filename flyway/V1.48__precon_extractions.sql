-- V1.48__precon_extractions.sql
-- Create extraction tables for the precon extraction pipeline.
-- Extractions read documents attached to an entity (e.g., tender), extract
-- structured fields via AI, and allow user review before applying values
-- back to the entity.

-- ──────────────────── extractions ────────────────────

CREATE TABLE IF NOT EXISTS extractions (
    id                VARCHAR        PRIMARY KEY,
    company_id        VARCHAR        NOT NULL,
    entity_type       VARCHAR        NOT NULL,
    entity_id         VARCHAR        NOT NULL,
    status            VARCHAR        NOT NULL DEFAULT 'pending',
    document_ids      JSONB          NOT NULL,
    field_names       JSONB,
    version           INTEGER        NOT NULL DEFAULT 0,
    error_reason      TEXT,
    created_by        VARCHAR        NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMPTZ
);

-- Lookup extractions by company + entity
CREATE INDEX IF NOT EXISTS idx_extractions_company_entity
    ON extractions (company_id, entity_type, entity_id);

-- Find active extractions for a given entity
CREATE INDEX IF NOT EXISTS idx_extractions_entity_status
    ON extractions (entity_id, status);

-- ──────────────────── extraction_fields ────────────────────

CREATE TABLE IF NOT EXISTS extraction_fields (
    id                VARCHAR        PRIMARY KEY,
    extraction_id     VARCHAR        NOT NULL REFERENCES extractions(id) ON DELETE CASCADE,
    field_name        VARCHAR        NOT NULL,
    field_type        VARCHAR        NOT NULL,
    proposed_value    JSONB,
    edited_value      JSONB,
    confidence        NUMERIC(5,4),
    has_conflict      BOOLEAN        NOT NULL DEFAULT FALSE,
    competing_values  JSONB,
    citations         JSONB,
    status            VARCHAR        NOT NULL DEFAULT 'extracted',
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- One field per extraction (e.g., only one 'closing_date' per extraction)
CREATE UNIQUE INDEX IF NOT EXISTS uq_extraction_fields_extraction_field
    ON extraction_fields (extraction_id, field_name);

-- Query fields by extraction and status
CREATE INDEX IF NOT EXISTS idx_extraction_fields_extraction_status
    ON extraction_fields (extraction_id, status);

-- ──────────────────── extraction_applications ────────────────────

CREATE TABLE IF NOT EXISTS extraction_applications (
    id                     VARCHAR        PRIMARY KEY,
    extraction_id          VARCHAR        NOT NULL REFERENCES extractions(id),
    entity_type            VARCHAR        NOT NULL,
    entity_id              VARCHAR        NOT NULL,
    applied_by             VARCHAR        NOT NULL,
    applied_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    entity_version_before  INTEGER,
    entity_version_after   INTEGER,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- ──────────────────── applied_fields ────────────────────

CREATE TABLE IF NOT EXISTS applied_fields (
    id                VARCHAR        PRIMARY KEY,
    application_id    VARCHAR        NOT NULL REFERENCES extraction_applications(id) ON DELETE CASCADE,
    field_name        VARCHAR        NOT NULL,
    old_value         JSONB,
    new_value         JSONB          NOT NULL,
    source            VARCHAR        NOT NULL DEFAULT 'proposed',
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Join applied_fields to their parent application
CREATE INDEX IF NOT EXISTS idx_applied_fields_application
    ON applied_fields (application_id);

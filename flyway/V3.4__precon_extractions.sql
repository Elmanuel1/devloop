-- V3.4__precon_extractions.sql
-- Create extraction tables for the precon extraction pipeline.
-- Extractions read documents attached to an entity (e.g., tender), extract
-- structured fields via AI, and allow user review before applying values
-- back to the entity.

-- ──────────────────── extractions ────────────────────

CREATE TABLE IF NOT EXISTS extractions (
    id                VARCHAR(50)    PRIMARY KEY,
    company_id        VARCHAR(50)    NOT NULL,
    entity_type       VARCHAR(50)    NOT NULL,
    entity_id         VARCHAR(50)    NOT NULL,
    status            VARCHAR(50)    NOT NULL DEFAULT 'pending',
    document_ids      JSONB          NOT NULL,
    field_names       JSONB,
    version           INTEGER        NOT NULL DEFAULT 0,
    error_reason      TEXT,
    created_by        VARCHAR(50)    NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    deleted_at        TIMESTAMPTZ
);

-- ──────────────────── extraction_fields ────────────────────

CREATE TABLE IF NOT EXISTS extraction_fields (
    id                VARCHAR(50)    PRIMARY KEY,
    extraction_id     VARCHAR(50)    NOT NULL REFERENCES extractions(id) ON DELETE CASCADE,
    field_name        VARCHAR(255)   NOT NULL,
    field_type        VARCHAR(50)    NOT NULL,
    proposed_value    JSONB,
    edited_value      JSONB,
    confidence        NUMERIC(5,4),
    has_conflict      BOOLEAN        NOT NULL DEFAULT FALSE,
    competing_values  JSONB,
    citations         JSONB,
    status            VARCHAR(50)    NOT NULL DEFAULT 'extracted',
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- ──────────────────── extraction_applications ────────────────────

CREATE TABLE IF NOT EXISTS extraction_applications (
    id                     VARCHAR(50)    PRIMARY KEY,
    extraction_id          VARCHAR(50)    NOT NULL REFERENCES extractions(id),
    entity_type            VARCHAR(50)    NOT NULL,
    entity_id              VARCHAR(50)    NOT NULL,
    applied_by             VARCHAR(50)    NOT NULL,
    applied_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    entity_version_before  INTEGER,
    entity_version_after   INTEGER,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- ──────────────────── applied_fields ────────────────────

CREATE TABLE IF NOT EXISTS applied_fields (
    id                VARCHAR(50)    PRIMARY KEY,
    application_id    VARCHAR(50)    NOT NULL REFERENCES extraction_applications(id) ON DELETE CASCADE,
    field_name        VARCHAR(255)   NOT NULL,
    old_value         JSONB,
    new_value         JSONB          NOT NULL,
    source            VARCHAR(50)    NOT NULL DEFAULT 'proposed',
    applied_by        VARCHAR(50)    NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

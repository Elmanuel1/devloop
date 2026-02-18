-- V1.46__create_tenders_and_tender_documents.sql
-- Create tenders and tender_documents tables for precon-service.
-- Idempotency keys are handled via Redis (SETEX with 24h TTL), not a DB table.

-- ──────────────────── tenders ────────────────────

CREATE TABLE tenders (
    id                   VARCHAR        PRIMARY KEY,
    company_id           VARCHAR        NOT NULL,
    name                 VARCHAR(255)   NOT NULL,
    platform             VARCHAR(255),
    status               VARCHAR        NOT NULL DEFAULT 'draft',
    currency             VARCHAR,
    reference_number     VARCHAR(255),
    location             JSONB,
    scope_of_work        TEXT,
    delivery_method      VARCHAR,
    closing_date         TIMESTAMPTZ,
    site_visit_date      TIMESTAMPTZ,
    site_visit_mandatory BOOLEAN,
    completion_date      DATE,
    inquiry_deadline     TIMESTAMPTZ,
    submission_method    VARCHAR(500),
    submission_url       VARCHAR,
    bonds                JSONB,
    conditions           JSONB,
    liquidated_damages   VARCHAR(255),
    parties              JSONB,
    metadata             JSONB,
    created_by           VARCHAR        NOT NULL,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMPTZ
);

-- Business constraint: tender name must be unique per company (case-insensitive, non-deleted only)
CREATE UNIQUE INDEX uq_tenders_company_name
    ON tenders (company_id, LOWER(name))
    WHERE deleted_at IS NULL;

-- ──────────────────── tender_documents ────────────────────

CREATE TABLE tender_documents (
    id                  VARCHAR      PRIMARY KEY,
    tender_id           VARCHAR      NOT NULL REFERENCES tenders(id),
    company_id          VARCHAR      NOT NULL,
    file_name           VARCHAR(255) NOT NULL,
    content_type        VARCHAR      NOT NULL,
    file_size           BIGINT       NOT NULL,
    s3_key              VARCHAR      NOT NULL,
    status              VARCHAR      NOT NULL DEFAULT 'uploading',
    error_reason        TEXT,
    metadata            JSONB,
    parent_document_id  VARCHAR      REFERENCES tender_documents(id),
    uploaded_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ
);

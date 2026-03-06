# Changelog

All notable changes to this module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.9] - 2026-03-05
### Added
- V3.12: `extractions.document_external_ids` JSONB column — maps `documentId → externalTaskId` so the webhook handler can locate an extraction by Reducto task ID
- V3.12: `tender_documents.external_file_id` TEXT column — stores the Reducto upload file ID for retry deduplication

### Deferred
- Drop of `extractions.external_task_id` column deferred to a future release pending jOOQ 0.1.9 publication; the column is retained in this release to keep jOOQ 0.1.8 generated classes compilable

## [0.1.8]

### Added
- Regenerate jOOQ classes for V3.8 schema: add `external_task_id` column (VARCHAR 255, nullable) to `extractions` table, with a unique partial index excluding NULLs and soft-deleted rows

## [0.1.7]

### Changed
- Add V3.7 migration: make tenders.name nullable; drop uq_tenders_company_name index (TOS-44)

## [0.1.6]

### Fixed
- Bump version to resolve GitHub Packages 409 publish conflict (0.1.5 already published)

## [0.1.5]

### Changed
- Regenerate jOOQ classes for V3.1–V3.3 schema (tenders table: version, events, start_date, tender_documents, additional columns)

## [0.1.4]

### Changed
- Regenerate jOOQ classes for V1.48 schema (events JSONB, start_date columns on tenders)

## [0.1.3]

### Fixed
- Add compileJava dependency on generateJooq so published jar includes generated jOOQ classes

## [0.1.2]

### Changed
- Migrate to unified publish-modules workflow

## [0.1.1]

### Fixed
- Bump version to resolve GitHub Packages 409 publish conflict

## [0.1.0]

### Added
- Initial extraction of jOOQ code generation from libs/models into dedicated module
- TsvectorBinding for PostgreSQL tsvector column type
- TestContainers + Flyway-based jOOQ class generation pipeline
- Maven publishing to GitHub Packages
- CI workflow for build and publish on flyway/schema changes

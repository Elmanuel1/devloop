# Changelog

All notable changes to this module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0]

### Added
- Extraction API: flat, self-contained extraction jobs for AI-powered field extraction from tender documents
- ExtractionField model with `proposed_value` (AI immutable) and `edited_value` (user override)
- Conflict resolution via `competing_values[]` with per-candidate citations
- Application endpoints for applying extraction results to target entities with old/new value audit trail
- Staleness detection at apply time (`tender.updated_at > extraction.created_at` → 409)
- Optimistic concurrency via version counter / ETag / If-Match on extractions and tenders
- Cursor-based pagination, idempotency keys, multi-tenancy (`X-Context-Id`), rate limiting (429 + Retry-After)

### Changed
- OpenAPI spec upgraded to 3.1.0

## [0.2.0]

### Added
- Semantic versioning via `gradle.properties` as single source of truth
- CI validation for version and changelog on every build
- CHANGELOG.md to track module changes

### Changed
- Maven and npm artifacts now publish with aligned semantic versions

## [0.1.0]

### Added
- OpenAPI specification for Tenders and TenderDocuments endpoints
- Java Spring server stubs generated via OpenAPI Generator
- TypeScript-Axios client generated via OpenAPI Generator
- CI workflow for validation, build, and publish to GitHub Packages

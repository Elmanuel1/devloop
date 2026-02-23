# Changelog

All notable changes to this module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

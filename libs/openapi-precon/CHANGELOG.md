# Changelog

All notable changes to this module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

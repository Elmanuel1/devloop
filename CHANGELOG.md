# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.1] - 2026-01-23

### Added
- CI/CD workflows for automated build and deployment
- Temporal dynamic configuration support
- GHCR token retrieval from AWS Secrets Manager for secure deployments

### Changed
- Docker Compose files parameterized with environment variables
- Sync interval default changed to 1800s (30 minutes)
- Consolidated jar output to `app.jar` for consistent Docker builds

### Security
- Required `REDIS_PASSWORD` in Docker Compose (fail-fast on missing password)
- Removed hardcoded credentials from Docker Compose files
- GHCR token no longer embedded in SSM command payloads

## [1.0.0] - 2025-01-22

### Added
- Initial release of TossPaper Email Engine
- Email engine with IMAP/SMTP support
- AI engine for email processing
- Integrations module with OAuth2 support
- Temporal workflows for background processing

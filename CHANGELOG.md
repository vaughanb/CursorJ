# Changelog

All notable changes to this project are documented in this file.

## [0.2.0] - 2026-03-09

### Changed

- Beta release updates to publishing and release automation.
- Added explicit publishing token/channel wiring for Marketplace uploads.
- Added release workflow preflight token validation to fail fast on misconfiguration.
- Improved GitHub Actions stability with runner disk cleanup and split validation/publish jobs.

## [0.1.0] - 2026-03-07

### Added

- Initial public release of CursorJ for JetBrains IDEs.
- Agentic coding via Cursor ACP (file editing, terminal execution, codebase context).
- Active file and selection context injection, drag-and-drop context, and multiple chat sessions.
- Native permission prompts and per-turn rollback using Local History.

### Security

- API key storage migrated from plugin state to JetBrains Password Safe.
- Removed API key command-line argument usage and redacted startup/model-fetch logging.

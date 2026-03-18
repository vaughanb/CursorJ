# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Added

- Concurrency stress test coverage for SQLite-backed indexing writes.

### Changed

- Consolidated global and project rules management into one settings page under **Settings > Tools > CursorJ**.

### Fixed

- SQLite index-store write operations are now serialized to avoid transaction-state errors during concurrent updates.

## [0.7.0] - 2026-03-12

### Added

- Chat history dropdown with search and persistence; restore previous chats.
- Global user rules support (injected into every prompt).
- Unit tests for DragDropProvider, TerminalHandler, and ProjectRulesService.

### Changed

- Upgraded Gradle to 9.0.0.
- Updated documentation.

### Fixed

- Race condition on model switching.
- Chat history bugs.

## [0.6.0] - 2026-03-10

### Added

- Project indexing with SQLite-backed lexical search, symbol lookup, and optional semantic reranking.
- Prompt history (recall previous prompts).
- Intelligent tab naming.
- Compatibility for 2025.3.* IDE builds.

### Changed

- Improved tool settings UI.

## [0.5.0] - 2026-03-09

### Changed

- Build tooling and Makefile updates.

## [0.4.0] - 2026-03-09

### Changed

- Release pipeline refinements.

## [0.3.0] - 2026-03-09

### Changed

- Version bump and release metadata preparation.

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

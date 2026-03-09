# Changelog

All notable changes to this project are documented in this file.

## [0.1.0] - 2026-03-07

### Added

- Initial public release of CursorJ for JetBrains IDEs.
- Agentic coding via Cursor ACP (file editing, terminal execution, codebase context).
- Active file and selection context injection, drag-and-drop context, and multiple chat sessions.
- Native permission prompts and per-turn rollback using Local History.

### Security

- API key storage migrated from plugin state to JetBrains Password Safe.
- Removed API key command-line argument usage and redacted startup/model-fetch logging.

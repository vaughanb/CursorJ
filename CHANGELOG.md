# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Added

- Concurrency stress test coverage for SQLite-backed indexing writes.
- ACP model metadata parsing from `session/new` to keep model state in sync with the active session.
- Unit tests covering agent plan path detection, session handling of plan edit diffs and “Plan saved” tool text, and filesystem notifications for plan writes under `.cursor/plans`.

### Changed

- Consolidated global and project rules management into one settings page under **Settings > Tools > CursorJ**.
- Deferred CursorJ startup indexing warmup until the IDE exits dumb mode to reduce contention with JetBrains indexing.
- Disabled PSI-driven bulk index invalidations by default to avoid repeated full rebuilds during active editing.
- Moved **Add to CursorJ Chat** to the bottom of the editor context menu with a separator for clearer placement.
- Renamed the chat action button from **Rollback** to **Undo All** to align with Cursor wording.
- Model selection now follows ACP-native model options and applies changes via `session/set_config_option`.
- Session load requests now include workspace context fields expected by recent agent builds.
- Simplified chat input controls to focus on stable ACP-backed mode/model behavior.
- Greatly improved markdown rendering in chat, including richer support for headings, tables, nested blockquotes, task lists, strikethrough, autolinks, emoji aliases, and indented code blocks.

### Fixed

- SQLite index-store write operations are now serialized to avoid transaction-state errors during concurrent updates.
- Restored chat message wrapping in narrow chat panels (including long lines and code blocks).
- Reduced flicker during streaming/typing updates by minimizing full-list relayout and scroll churn.
- Prevented model picker states that could imply unsupported model transitions.
- Plan mode: **Build** did not reappear after the agent updated an existing plan by editing the on-disk file under `.cursor/plans` (e.g. edit diffs without a second `create_plan`); plan UI state now tracks those updates.
- Plan mode: an open plan document could stay stale while the agent wrote changes to disk; the IDE refreshes the virtual file and reloads open editors when the tracked plan path is touched.
- Plan mode: improved recognition of the agent’s plan file (`cursor/create_plan` / `_cursor/create_plan`, “Plan saved to …” in tool updates, and markdown paths under `.cursor/plans`) for **View Plan** and related behavior.

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

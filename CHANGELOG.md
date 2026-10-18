# Changelog

All notable changes to this project are documented in this file.

## [1.7.0] - 2026-10-17

### Added

- **Cursor-style model picker with per-model parameters (effort, thinking, context, fast)**: CursorJ now advertises Cursor's `_meta.parameterizedModelPicker` ACP extension on `initialize`, so the agent lists plain model ids and exposes each model's parameters as separate ACP config options (`thought_level` / `model_config` categories; the effort option is `effort`, `reasoning` or `reasoning_effort` depending on the model). The prompt bar's model dropdown and MAX checkbox are replaced by one compact trigger that mirrors Cursor IDE's picker (`Claude Opus 4.7  300K Extra High ▾`, suffix in Cursor's short-name convention). It opens a popup with a **Model** submenu (Auto first, **MAX Mode** when the model has no context option, type-to-filter model list) followed by the current model's parameters as sections in Cursor's order: Effort / Reasoning, Options (Thinking, Fast, with the agent's descriptions as tooltips), then Context. Values are applied with `session/set_config_option` without a reconnect; radio values close the popup, switches keep it open. A status line confirms each change (`Model: Claude Opus 4.7 300K Medium`) and the status bar shows the same summary. The agent persists changes to `~/.cursor/cli-config.json`, so they also apply to the Cursor CLI's `/model` picker, and choosing 1M context turns Max Mode on automatically. A **Settings > Tools > CursorJ > Agent** checkbox turns the parameters off, which reconnects open chats and lists the agent's single-variant model strings in the Model submenu instead. Manual integration test `ModelParameterCliIntegrationTest` (gated by `CURSORJ_INTEGRATION=1`; runs against a scratch copy of `cli-config.json` via `CURSOR_CONFIG_DIR`) covers both picker modes and checks that `agent about` reports the model and effort chosen over ACP. Effort and Fast availability follows the account's Cursor plan (Cursor documents these controls for Premium and higher).
- Keyboard actions `CursorJ.OpenModelPicker` and `CursorJ.CycleEffort` (unbound by default; assign shortcuts in Settings > Keymap).

### Changed

- `AcpSession` accepts the ACP-spec `config_option_update` session update in addition to the `config_options_update` name it matched before.
- The chat input's control row wraps in a narrow tool window instead of clipping.

### Fixed

- Stale config options after a model switch: the `session/set_config_option` response is now treated as the complete option list, so options the new model does not support (e.g. Effort after switching to Composer) disappear instead of lingering and failing with `Unknown model config option`.
- `WrapFlowLayout` reported a preferred height one vertical gap too short, clipping the bottom of the last wrapped row.
- ACP error details: the agent reports invalid config values as the generic JSON-RPC `Invalid params` with the actual reason in `error.data.message` (e.g. `Invalid value for effort: bogus`, `Unknown model config option: effort`). `AcpClient` now surfaces that detail, so the chat status line shows the real reason instead of `Invalid params`.

## [1.6.0] - 2026-08-07

### Added

- **Debug mode (simulated)**: a fourth mode in the chat dropdown that reproduces Cursor's Debug workflow (hypothesize → instrument → reproduce → analyze runtime evidence → targeted fix → clean up). Because ACP has no `debug` session mode, CursorJ keeps the session in `agent`, injects a Debug prompt prefix, and provisions a per-session log file under `<project>/.cursorj/debug/` plus a localhost token-gated HTTP log sink. Post-turn actions: **I reproduced it**, **Clean up**, and **View logs**. Manual real-agent integration tests (`DebugModeIntegrationTest`, gated by `CURSORJ_INTEGRATION=1`) cover ACP mode rejection, agent read-back of HTTP-captured evidence, and Debug-prefixed workflow behavior.
- **Edit & resend**: an inline **Edit** affordance on your earlier messages loads the text back into the input; resending branches the conversation from that point by discarding later replies and replaying the prior turns as context into a fresh session (the agent exposes no native `session/fork`). `Esc` or **Cancel edit** abandons the edit. Backed by new `ChatTranscriptManager.buildCarryoverContext(list)` / `resetSession` APIs with unit tests.
- Documented Cursor ACP limitations in the README (runtime model parameter tiers; native Debug session mode is not exposed by the agent, so CursorJ simulates it).

### Changed

- **Integration test harness**: `integrationTest` now runs through the IntelliJ Platform test support (`intellijPlatformTesting.testIde`) so the whole suite can be discovered and executed together. Previously the full-suite run aborted during JUnit discovery (`NoClassDefFoundError: com.intellij.openapi.Disposable`) because the platform was absent from the task's runtime classpath. Tests requiring a live IntelliJ `Application` self-skip when one is not bootstrapped; real-CLI tests still run. It remains manual-only and excluded from `test`/`check`/`build`.

### Fixed

- **Windows drag-and-drop of `file://` paths**: dropping/pasting a `file://` URI that wraps a Windows path (backslashes and a drive-letter "authority", e.g. the GNOME copied-files clipboard format) no longer silently fails. `DragDropProvider` now falls back to manual `file:` URI parsing when the strict `java.net.URI` parser rejects such paths.
- **Faster IDE shutdown**: closing the IDE (or a project) no longer hangs ~5 seconds per open CursorJ tab. On teardown, `AgentConnection` now enters a shutdown path that cancels its coroutine scope and marks the connection as shutting down before its child disposables run, so disconnect handlers no longer schedule reconnect attempts during shutdown, and the ACP process is stopped with a short (500 ms) wait instead of the normal 5 s wait. `SessionTabManager` is now a `Disposable` whose coroutine scope is cancelled on dispose. Based on a contribution from @ghackett ([#2](https://github.com/vaughanb/CursorJ/pull/2)).

## [1.5.1] - 2026-06-16

### Changed

- Replaced deprecated IntelliJ Platform APIs.

## [1.5.0] - 2026-06-11

### Added

- **Cursor Skills (plugin UX)**: Recursive discovery of `SKILL.md` across standard project and global skill directories; **Settings > Tools > CursorJ** panel to list/add/edit/remove project skills; inline chat input `/` and `@` completion for skills merged with ACP `available_commands_update`, shown in a non-focus-stealing popup so the user can keep typing to filter (arrow keys navigate, Enter/Tab accept, Esc dismisses); `@` skill picks insert a file-style reference resolved to `SKILL.md` for `ResourceLinkContent`; optional manual integration test documents whether the real agent surfaces skills in ACP available commands.
- **Interactive agent questions**: CursorJ now handles the Cursor `cursor/ask_question` ACP extension and renders multiple-choice prompts as native chat cards. Plan-mode prose questions also get a conservative fallback parser so lettered/numbered choices can be answered without manually typing the option text.

### Changed

- **Central machine-local storage**: SQLite lexical index, chat history index, chat transcripts, and prompt history now live under `~/.cursorj/projects/<sanitizedName>-<hash8>/` instead of `<workspace>/.cursorj/`. Legacy `<workspace>/.cursorj/` content is migrated once when safe (targets that already exist are not overwritten). Project rules and skills remain under in-repo `.cursor/` as before.
- **Richer tool-call activity labels**: Chat now shows the file name (and line range when available) for reads, the search pattern/glob for searches, and the URL for web fetches, instead of generic labels like "Reading File..." or "Searching grep...". Detail is sourced from the tool call's `rawInput` arguments with a fallback to the ACP `locations` field.

## [1.4.0] - 2026-06-05

### Added

- **Custom Index Exclusions (`indexExcludePatterns`)**: Exclude specific files and directories from project indexing using glob patterns (e.g., `*.log`, `**/dist/**`), customizable in the settings panel.
- **Subagent tasks (`cursor/task`)**: Handle Cursor ACP subagent notifications and server requests; show a collapsible **Background tasks** list in chat (type, description, running vs completed duration) with tooltips for prompt summary, model, and agent id. JSON-RPC responses return `outcome: completed` so the agent does not block. Unit tests cover `CursorTaskPayload` parsing.
- Token and cost usage display in chat when the Cursor agent reports ACP usage: per-assistant-message token breakdown from `session/prompt` results, a session context/cost bar from `usage_update` notifications, and a **Settings > Tools > CursorJ** toggle to show or hide usage UI.
- **Inline `@file` references in chat input**: Drag-and-drop project files into the prompt as Cursor-style inline references with pill highlighting; on send, references become markdown file links and are attached as `ResourceLinkContent` blocks for the agent.
- **Image attachments in chat input**: Paste or drop image files (including clipboard images and Linux screenshot file paths) as removable chips above the input; images are sent to the agent via ACP `ImageContent`.
- Clickable `file://` links in user chat messages open the referenced file in the editor.

### Changed

- **Concurrent SQLite Indexing (WAL)**: Separated SQLite reads and writes to separate connections and locks, allowing concurrent WAL reading/writing to prevent database lock contention.
- **Index Throttling & Yielding**: Workspace indexing warmup scans now yield and delay every 25 files to prevent CPU/disk IO saturation.
- **VFS Event Debouncing & Batching**: Coalesce and debounce incoming file-system change tasks (500ms quiet window, 2s max) to batch updates and avoid performance bottlenecks.
- Hardened ACP session stop logic.
- Deferred CursorJ startup indexing warmup until the IDE exits dumb mode to reduce contention with JetBrains indexing.
- Disabled PSI-driven bulk index invalidations by default to avoid repeated full rebuilds during active editing.
- Moved **Add to CursorJ Chat** to the bottom of the editor context menu with a separator for clearer placement.
- Session load requests now include workspace context fields expected by recent agent builds.
- Simplified chat input controls to focus on stable ACP-backed mode/model behavior.
- Greatly improved markdown rendering in chat, including richer support for headings, tables, nested blockquotes, task lists, strikethrough, autolinks, emoji aliases, and indented code blocks.
- Improved symbol index bridge with safer PSI access patterns.
- Non-image drag-and-drop in chat inserts inline `@path` references at the drop caret instead of separate attachment chips above the input.

### Fixed

- Restored chat message wrapping in narrow chat panels (including long lines and code blocks).
- Reduced flicker during streaming/typing updates by minimizing full-list relayout and scroll churn.
- Prevented model picker states that could imply unsupported model transitions.
- File reference detection in chat input matches paths with extensions (e.g. `@main.go`, `@src/App.kt`).
- Multiple image attachment chips wrap to additional rows instead of clipping in a single-line chip area.
- Image paste on Linux (file-path clipboard data) attaches images instead of inserting raw paths into the prompt.
- Fixed IDE `BadLocationException` when pasting images by intercepting paste at the IntelliJ editor-action layer.

## [1.3.0] - 2026-04-16

### Added

- Max Mode toggle with CLI-config sync and reconnect handling.
- Diagnostic integration test (canary) for agent model-routing behavior.

## [1.2.2] - 2026-04-13

### Added

- Manual-only real-CLI integration test suite (`src/integrationTest/`) gated by `CURSORJ_INTEGRATION=1` and hard-blocked on CI. Covers ACP process lifecycle, connection/auth handshake, session creation, model switching, terminal/filesystem handler dispatch, index search, per-session isolation, and reconnect recovery.
- Post-switch verification: after a model switch, the plugin compares the ACP-confirmed model against the requested model and logs structured diagnostics.
- Model-name context hint injected into the next prompt after a model switch so the agent can accurately self-report the active model.
- ACP model metadata parsing from `session/new` to keep model state in sync with the active session.
- Unit tests for config-option merge behavior (partial model option list preservation), per-session config isolation, model display-name mapping priority, and `connectedStatusDetail` sourcing from ACP-confirmed state.

### Changed

- Model switching now uses in-place `session/set_config_option` per ACP spec; no longer creates a new session on every model change.
- Status bar and `connectedStatusDetail` now prefer the ACP-confirmed session model (`configOptions.currentValue`) over stale local `selectedModel`.
- Structured model-switch diagnostic logging in `AcpSession` and `ChatPanel` with session/model correlation fields.

## [1.2.1] - 2026-04-09

### Changed

- Removed plugin IDE version restrictions for broader JetBrains IDE compatibility.

## [1.2.0] - 2026-04-01

### Added

- Plan mode enhancements: agent plan file tracking, **Build** after plan edits, and editor sync.
- Unit tests covering agent plan path detection, session handling of plan edit diffs and "Plan saved" tool text, filesystem notifications for plan writes under `.cursor/plans`, chat markdown palette selection (`MarkdownRenderer`), and editor inserted-line highlight fallbacks (`EditorInsertedDiffHighlight`).

### Changed

- Chat embedded HTML (messages and collapsible diffs) picks text colors from the bubble surface and refreshes when the LaF or editor color scheme changes.

### Fixed

- Plan mode: **Build** did not reappear after the agent updated an existing plan by editing the on-disk file under `.cursor/plans` (e.g. edit diffs without a second `create_plan`); plan UI state now tracks those updates.
- Plan mode: an open plan document could stay stale while the agent wrote changes to disk; the IDE refreshes the virtual file and reloads open editors when the tracked plan path is touched.
- Plan mode: improved recognition of the agent's plan file (`cursor/create_plan` / `_cursor/create_plan`, "Plan saved to ..." in tool updates, and markdown paths under `.cursor/plans`) for **View Plan** and related behavior.
- "Added line" highlights when opening a file from chat no longer follow the UI theme alone when the editor uses a different color scheme (unreadable contrast in mixed light/dark setups).

## [1.1.2] - 2026-03-25

### Changed

- Moved the plugin status indicator to below the chat input.

### Fixed

- Large prompt handling.

## [1.1.1] - 2026-03-24

### Changed

- Improved indexing performance.

### Fixed

- Tab rename / history sync, with added regression coverage.

## [1.1.0] - 2026-03-23

### Added

- MAX mode toggle via the `--max-mode` CLI flag.

### Changed

- Aligned ACP model handling and refreshed release documentation.

### Fixed

- Model selection and chat history save/restore bugs.

## [1.0.1] - 2026-03-21

### Fixed

- Improved MAX model detection and added a MAX badge to the model dropdown.

## [1.0.0] - 2026-03-20

### Changed

- Switched to the default JetBrains Marketplace channel for stable releases.

## [0.9.1] - 2026-03-18

### Changed

- Renamed the chat action button from **Rollback** to **Undo All** to align with Cursor wording.
- Improved chat rendering and action UX.

## [0.9.0] - 2026-03-18

### Added

- Concurrency stress test coverage for SQLite-backed indexing writes.

### Changed

- Improved plugin performance during indexing.
- Consolidated global and project rules management into one settings page under **Settings > Tools > CursorJ**.
- Improved ACP config handling for MAX-tier model switching.

### Fixed

- SQLite index write concurrency: index-store write operations are serialized to avoid transaction-state errors during concurrent updates.
- Restored the missing `settings.section.globalRules` bundle key.

## [0.8.1] - 2026-03-13

### Fixed

- Font and sizing issues in chat.

## [0.8.0] - 2026-03-13

### Added

- Input message queue: queue follow-up prompts while the agent is working.

### Changed

- Improved shell command support and updated documentation.

### Fixed

- Shell command execution bugs.

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

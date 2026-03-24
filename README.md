# CursorJ



An IntelliJ plugin that brings Cursor's AI agent into JetBrains IDEs via the [Agent Client Protocol (ACP)](https://agentclientprotocol.com/).

## Features

- **Full Agentic Coding**: File editing, terminal commands, and codebase understanding powered by Cursor's AI agent
- **Active File Context**: Optionally attach the currently open file as context
- **Selection Context**: Add selected text to chat via `Ctrl+Shift+J` or **Add to CursorJ Chat** at the bottom of the editor context menu (with a divider)
- **Drag-and-Drop**: Drop files onto the chat panel to add them as context
- **Multiple Chat Sessions**: Open multiple concurrent chat tabs, each with independent sessions
- **Native IntelliJ UI**: Consistent look and feel with syntax-highlighted code blocks and diff rendering
- **Project Indexing**: Local-first retrieval for lexical search, symbol lookup, references, and optional semantic reranking
- **Permission Control**: Approve or reject agent tool calls with native IntelliJ dialogs; choose "Ask Every Time" or "Run Everything"
- **Rules**: Global user rules (injected into every prompt) and project rules (`.cursor/rules/`)
- **Model Selection**: Choose from ACP-provided model options directly in the dropdown for reliable per-session switching
- **Mode Switching**: Switch between Agent, Plan, and Ask modes (Plan mode includes Build and View Plan)
- **Undo All (Rollback Last Turn)**: Revert files to the state before the most recent agent turn using Local History
- **Chat History**: Searchable chat history grouped by time; restore previous chats; clear history
- **Status Bar**: Connection and indexing status (Connected, Processing, Indexing, Index ready, etc.)

## Prerequisites

- A JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, Rider, etc.) version 2025.1+
- [Cursor CLI](https://cursor.com/docs/cli/overview) installed (`agent` binary available in PATH)
- A paid [Cursor plan](https://cursor.com/docs/account/pricing)

## Installation

### From Source

```bash
./gradlew buildPlugin
```

The plugin zip will be at `build/distributions/cursorj-<version>.zip`. Install it in your IDE via **Settings > Plugins > Install Plugin from Disk**.

### Authentication

Before using CursorJ, authenticate with Cursor:

```bash
agent login
```

## Usage

1. Open the **CursorJ** tool window from the right sidebar
2. Type a prompt and press Enter
3. The agent will read your project, edit files, and run commands

### Keyboard Shortcuts


| Shortcut       | Action                    |
| -------------- | ------------------------- |
| `Alt+C`        | Focus CursorJ tool window |
| `Ctrl+Shift+J` | Send selection to CursorJ |
| `Enter`        | Send prompt               |
| `Shift+Enter`  | New line in input         |


## Architecture

CursorJ communicates with Cursor's agent via the Agent Client Protocol (ACP). It spawns `agent acp` as a subprocess and exchanges JSON-RPC 2.0 messages over stdio.

The plugin declares client capabilities for:

- **File system**: Read/write files through CursorJ filesystem handlers, with Local History labels for per-turn rollback
- **Search**: `fs/find_text_in_files` for lexical project search
- **Editor indexing**: `editor/get_open_files`, `editor/find_symbol`, `editor/list_file_symbols`, `editor/find_references`
- **Terminal**: Execute shell commands

## Project Indexing

CursorJ uses a hybrid local-first indexing model:

- **Lexical index (default)**: File-content indexing with SQLite persistence for warm starts and incremental updates
- **Symbol index**: IntelliJ PSI/index-based symbol and reference lookup
- **Semantic reranking (optional, experimental)**: In-memory chunk index for semantic reranking of retrieval results
- **Hybrid ranking**: Retrieval results are fused and ranked before prompt context is assembled

Persistence details:

- Lexical metadata and hits are stored under `.cursorj/index/index-v1.db` in the workspace
- Semantic vectors/chunks are currently memory-only
- Index lifecycle states include startup build, incremental build, stale rebuild, ready, and failed

### Configuration

Settings are available in **Settings > Tools > CursorJ**:

- **Agent**: Path to agent binary (auto-detect from PATH when empty), default model
- **Global User Rules**: Inject custom rules into every prompt
- **Project Rules**: Manage rule files in `.cursor/rules/` from the same settings page (shown when an open project is available)
- **Context & Indexing**: Auto-attach active file; enable project indexing; lexical persistence; semantic reranking; retrieval limits (max candidates, snippet budget, timeout); index retention days and max DB size; manual "Rebuild index now"
- **Permissions**: Tool permission mode (Ask Every Time / Run Everything); approved tools allowlist; protect writes outside workspace
- **Advanced**: ACP raw JSON debug logging (diagnostics)

The status bar shows connection and indexing lifecycle (Connected, Disconnected, Processing, Indexing, Index ready, Indexing failed).

## Building

Requires JDK 21.

```bash
./gradlew build        # Compile and test
./gradlew buildPlugin  # Package as installable zip
./gradlew runIde       # Launch a sandboxed IDE with the plugin
```

## Testing

Run all tests:

```bash
./gradlew build
```

Current tests validate:

- **TerminalHandler**: process command building (args vs shell), timeout extraction
- **DragDropProvider**: file list and string flavor extraction, deduplication, install
- **ProjectRulesService**: getRulesDirectory and listRuleFiles when rules dir is absent
- lexical search behavior (matching, scope filters, case sensitivity, truncation, binary/size skipping)
- SQLite persistence behavior (migration, reopen persistence, pruning, normalization, ordering, concurrent-write safety)
- orchestrator lifecycle/queue behavior (startup, incremental updates, reconcile, telemetry)
- hybrid retrieval behavior in orchestrator (lexical + symbol + semantic fusion, disabled/timeout fallbacks)
- ACP handler routing and serialization for indexing methods
- freshness callback mapping (notify paths and attach-time event mapping via seams)

Thin IntelliJ fixture-based tests for PSI symbol/reference behavior are still recommended as a final integration safety net when targeting parity-level confidence across IDE/platform updates.

## Releasing

For release steps and publishing instructions, see [RELEASING.md](RELEASING.md).

Maintainer note: before every release, update JetBrains Marketplace metadata in `build.gradle.kts` (`pluginConfiguration.description` and `pluginConfiguration.changeNotes`) so Marketplace text matches `CHANGELOG.md`.

## Privacy

For data handling details, see [PRIVACY.md](PRIVACY.md).

## License

MIT License - see [LICENSE](LICENSE) for details.
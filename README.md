# CursorJ

<p align="center">
  <img src="CursorJ_logo.png" alt="CursorJ logo" width="320" />
</p>

An IntelliJ plugin that brings Cursor's AI agent into JetBrains IDEs via the [Agent Client Protocol (ACP)](https://agentclientprotocol.com/).

## Features

- **Full Agentic Coding**: File editing, terminal commands, and codebase understanding powered by Cursor's AI agent
- **Active File Context**: Automatically attaches the currently open file as context
- **Drag-and-Drop**: Drop files onto the chat panel to add them as context
- **Multiple Chat Sessions**: Open multiple concurrent chat tabs, each with independent sessions
- **Native IntelliJ UI**: Consistent look and feel with syntax-highlighted code blocks and diff rendering
- **Project Indexing**: Local-first retrieval for lexical search, symbol lookup, references, and optional semantic chunks
- **Indexing Lifecycle Indicators**: Startup and incremental indexing status surfaced in the status bar and optional chat updates
- **Permission Control**: Approve or reject agent tool calls with native IntelliJ dialogs
- **Mode Switching**: Switch between Agent, Plan, and Ask modes
- **Rollback Last Turn**: Revert files to the state before the most recent agent turn using Local History

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

| Shortcut | Action |
|---|---|
| `Alt+C` | Focus CursorJ tool window |
| `Ctrl+Shift+J` | Send selection to CursorJ |
| `Enter` | Send prompt |
| `Shift+Enter` | New line in input |

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
- **Semantic index (optional)**: In-memory chunk index for semantic retrieval (memory-only storage)
- **Hybrid ranking**: Retrieval results are fused and ranked before prompt context is assembled

Persistence details:

- Lexical metadata and hits are stored under `.cursorj/index/index-v1.db` in the workspace
- Semantic vectors/chunks are currently memory-only
- Index lifecycle states include startup build, incremental build, stale rebuild, ready, and failed

Configuration is available in **Settings > Tools > CursorJ**, including:

- Enable/disable project indexing
- Enable lexical persistence
- Enable semantic indexing
- Retrieval limits and timeout
- Retention days and max DB size
- Manual "Rebuild index now"
- Optional chat-surface indexing status messages

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

Current indexing-focused tests validate:

- lexical search behavior (matching, scope filters, case sensitivity, truncation, binary/size skipping)
- SQLite persistence behavior (migration, reopen persistence, pruning, normalization, ordering)
- orchestrator lifecycle/queue behavior (startup, incremental updates, reconcile, telemetry)
- hybrid retrieval behavior in orchestrator (lexical + symbol + semantic fusion, disabled/timeout fallbacks)
- ACP handler routing and serialization for indexing methods
- freshness callback mapping (notify paths and attach-time event mapping via seams)

Thin IntelliJ fixture-based tests for PSI symbol/reference behavior are still recommended as a final integration safety net when targeting parity-level confidence across IDE/platform updates.

## Releasing

For release steps and publishing instructions, see [RELEASING.md](RELEASING.md).

## Privacy

For data handling details, see [PRIVACY.md](PRIVACY.md).

## License

MIT License - see [LICENSE](LICENSE) for details.

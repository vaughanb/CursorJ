# CursorJ

An IntelliJ plugin that brings Cursor's AI agent into JetBrains IDEs via the [Agent Client Protocol (ACP)](https://agentclientprotocol.com/).

## Features

- **Full Agentic Coding**: File editing, terminal commands, and codebase understanding powered by Cursor's AI agent
- **Active File Context**: Automatically attaches the currently open file as context
- **Drag-and-Drop**: Drop files onto the chat panel to add them as context
- **Multiple Chat Sessions**: Open multiple concurrent chat tabs, each with independent sessions
- **Native IntelliJ UI**: Consistent look and feel with syntax-highlighted code blocks and diff rendering
- **Permission Control**: Approve or reject agent tool calls with native IntelliJ dialogs
- **Mode Switching**: Switch between Agent, Plan, and Ask modes
- **Rollback Last Turn**: Revert files to the state before the most recent agent turn using Local History

## Prerequisites

- A JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, Rider, etc.) version 2024.2+
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

Or configure an API key in **Settings > Tools > CursorJ**.

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
- **Terminal**: Execute shell commands

## Building

Requires JDK 21.

```bash
./gradlew build        # Compile and test
./gradlew buildPlugin  # Package as installable zip
./gradlew runIde       # Launch a sandboxed IDE with the plugin
```

## License

MIT License - see [LICENSE](LICENSE) for details.

# CursorJ



An IntelliJ plugin that brings Cursor's AI agent into JetBrains IDEs via the [Agent Client Protocol (ACP)](https://agentclientprotocol.com/).

## Features

- **Full Agentic Coding**: File editing, terminal commands, and codebase understanding powered by Cursor's AI agent
- **Active File Context**: Optionally attach the currently open file as context
- **Selection Context**: Add selected text to chat via `Ctrl+Shift+J` or **Add to CursorJ Chat** at the bottom of the editor context menu (with a divider)
- **Drag-and-Drop**: Drop files onto the chat panel to add them as context
- **Multiple Chat Sessions**: Open multiple concurrent chat tabs, each with independent sessions
- **Edit & Resend**: Edit any earlier message you sent and resend it to branch the conversation from that point (later replies are discarded and the prior context is replayed into a fresh session)
- **Native IntelliJ UI**: Consistent look and feel with syntax-highlighted code blocks and diff rendering
- **Enhanced Markdown Rendering**: Improved support for tables, nested blockquotes, task lists, strikethrough, autolinks, emoji aliases, and indented code blocks
- **Project Indexing**: Local-first retrieval for lexical search, symbol lookup, references, and optional semantic reranking
- **Permission Control**: Approve or reject agent tool calls with native IntelliJ dialogs; choose "Ask Every Time" or "Run Everything"
- **Rules**: Global user rules (injected into every prompt) and project rules (`.cursor/rules/`)
- **Cursor Skills**: Discovers `SKILL.md` under `.cursor/skills/`, `.agents/skills/`, `.claude/skills/`, `.codex/skills/` (and `~/` equivalents); manage scaffolds in **Settings > Tools > CursorJ**; type `/` in chat for an inline command/skill completion list (merged with ACP slash commands) and `@` for skill file references; the popup keeps focus in the input so you can keep typing to filter, and `@` inserts a path reference so the agent receives `ResourceLinkContent` for the skill file
- **Model Picker**: A compact Cursor-style picker in the prompt bar (`Claude Opus 4.7  300K Extra High ▾`) with a Model submenu (Auto first, MAX Mode, type-to-filter model list) and the current model's parameters as sections (Effort / Reasoning, Options such as Thinking and Fast, Context); values stay in sync with the Cursor CLI's `/model` picker (changes are CLI-wide; the 1M context option implies Max Mode)
- **Mode Switching**: Switch between Agent, Plan, Ask, and Debug modes (Plan mode includes Build and View Plan, plus native multiple-choice cards for agent questions; Debug mode simulates Cursor's Debug workflow with runtime-evidence capture)
- **Undo All (Rollback Last Turn)**: Revert files to the state before the most recent agent turn using Local History
- **Chat History**: Searchable chat history grouped by time; restore previous chats; clear history
- **Status Bar**: Connection and indexing status (Connected, Processing, Indexing, Index ready, etc.)
- **Token usage (optional)**: When the agent reports usage over ACP, show per-assistant-message token breakdown and a session context/cost bar in chat (toggle in **Settings > Tools > CursorJ**).
- **Background tasks**: When the Cursor agent runs subagents (`cursor/task`), tasks appear in a collapsible list in the chat tool window (running vs completed, duration, tooltips).

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
| `Esc`          | Cancel an in-progress edit |

### Model picker

The prompt bar shows one compact trigger next to the mode dropdown, like Cursor's own picker:
`Claude Opus 4.7  300K Extra High ▾` (the muted suffix follows Cursor's short-name convention: context,
effort, then `Fast` and `No Thinking` when set). Click it, or press Space on it, to open the picker: a
**Model** submenu (Auto first, **MAX Mode** when the model has no context option, then a type-to-filter
list of models) followed by the current model's parameters as sections in Cursor's order: **Effort** /
**Reasoning**, **Options** (Thinking, Fast), then **Context**. Picking a value sends it to the agent with
`session/set_config_option` and closes the menu; switches keep it open. Two actions are unbound by default
and can be given shortcuts in **Settings > Keymap**: **Open CursorJ Model Picker**
(`CursorJ.OpenModelPicker`) and **Cycle CursorJ Effort Level** (`CursorJ.CycleEffort`).

### Edit & Resend

Click **Edit** on an earlier message you sent to load it back into the input. Adjust the
text and press Enter to resend: the conversation branches from that message — everything after it
is discarded and the prior turns are replayed as context into a fresh agent session (the agent
provides no native `session/fork`). Press `Esc` or **Cancel edit** to abandon the edit without
changing the conversation.

### Debug mode

Select **Debug** in the mode dropdown when you need to find a root cause with runtime evidence
rather than guessing at a fix. Because ACP does not expose a native `debug` session mode, CursorJ
keeps the agent session in `agent` and simulates Cursor's Debug workflow locally:

1. A hidden Debug prompt instructs the agent to hypothesize, add temporary instrumentation, ask you
   to reproduce, analyze logs, apply a targeted fix, then clean up.
2. Each Debug turn provisions a session log file under `<project>/.cursorj/debug/` and (when
   possible) a localhost-only HTTP endpoint (`127.0.0.1`) so instrumented code can append structured
   lines even from browser/remote runtimes.
3. After a Debug turn, chat offers **I reproduced it**, **Clean up**, and **View logs** actions.

This is functionally equivalent to Cursor's IDE Debug mode for typical repros; it is not the same
server-side mode Cursor uses in its own agent panel.


## Architecture

CursorJ communicates with Cursor's agent via the Agent Client Protocol (ACP). It spawns `agent acp` as a subprocess and exchanges JSON-RPC 2.0 messages over stdio.

The plugin declares client capabilities for:

- **File system**: Read/write files through CursorJ filesystem handlers, with Local History labels for per-turn rollback
- **Search**: `fs/find_text_in_files` for lexical project search
- **Editor indexing**: `editor/get_open_files`, `editor/find_symbol`, `editor/list_file_symbols`, `editor/find_references`
- **Terminal**: Execute shell commands

Cursor extension methods from the agent (for example `cursor/task` for subagents, `_cursor/update_todos`, `cursor/create_plan`, and `cursor/ask_question`) are handled so the agent can show todos, plans, interactive questions, and background task progress in the CursorJ UI where supported.

## Known Limitations (Cursor ACP)

Some capabilities are constrained by what Cursor's agent exposes over the Agent Client Protocol. The following were verified empirically against the Cursor CLI agent (`agent acp`) and are **agent/protocol limitations, not plugin bugs**:

- **Model parameters depend on a Cursor extension, not core ACP.** The per-model effort / thinking / context / fast sections of the model picker only appear because CursorJ sends `clientCapabilities._meta.parameterizedModelPicker: true` on `initialize` (an undocumented agent flag, verified on agent builds 2026.07.23 through 2026.10.16). Without it the agent offers each model as one opaque variant string (e.g. `claude-opus-4-7[thinking=true,context=300k,effort=xhigh,fast=false]`) and rejects any other value with `Invalid model value`. CursorJ falls back to listing those variants in the picker's Model submenu if a future agent ignores the flag, and **Show per-model parameters** in settings turns the flag off. Parameter changes are global Cursor CLI state (the agent writes them to `~/.cursor/cli-config.json`), so they also become the defaults for `agent` in a terminal and for other CursorJ tabs, and choosing the 1M context option makes the agent turn Max Mode on.
- **No native Debug session mode.** The agent advertises exactly three session modes — `agent`, `plan`, and `ask` — and rejects any other mode id (e.g. `debug`) with `Invalid mode ID: … Valid modes: agent, plan, ask`. CursorJ therefore keeps the ACP session in `agent` for Debug, and simulates Cursor's Debug workflow locally (see [Debug mode](#debug-mode) below).
- **No native conversation forking.** The agent does not advertise the ACP `session/fork` capability. Editing an earlier message and resending is therefore implemented by starting a fresh session and replaying the prior conversation as context, rather than a server-side branch.

To re-verify against a newer agent build, enable **ACP raw JSON debug logging** in settings and inspect the `initialize` request (`clientCapabilities._meta`) and the `session/new` response (the `modes` block, the `model` config option and any `thought_level` / `model_config` options) along with any `session/set_config_option` / `session/set_mode` errors. The manual `ModelParameterCliIntegrationTest` checks both picker modes against the installed agent.

## Project Indexing

CursorJ uses a hybrid local-first indexing model:

- **Lexical index (default)**: File-content indexing with SQLite persistence for warm starts and incremental updates
- **Symbol index**: IntelliJ PSI/index-based symbol and reference lookup
- **Semantic reranking (optional, experimental)**: In-memory chunk index for semantic reranking of retrieval results
- **Hybrid ranking**: Retrieval results are fused and ranked before prompt context is assembled

Persistence details:

- Lexical SQLite DB, chat history index, chat transcripts, and prompt history are stored under **`~/.cursorj/projects/<projectDirName>-<hash>/`** (per canonical workspace path), not in the project tree, so no `.gitignore` entry is needed. On first use, existing data under `<workspace>/.cursorj/` is moved into that directory when the central copy is absent.
- Semantic vectors/chunks are currently memory-only
- Index lifecycle states include startup build, incremental build, stale rebuild, ready, and failed

### Configuration

Settings are available in **Settings > Tools > CursorJ**:

- **Agent**: Path to agent binary (auto-detect from PATH when empty), default model, and whether the model picker shows per-model parameters (effort, thinking, context, fast; changing it reconnects open chats)
- **Global User Rules**: Inject custom rules into every prompt
- **Project Rules**: Manage rule files in `.cursor/rules/` from the same settings page (shown when an open project is available)
- **Skills**: List, add, edit, and remove project skills (writes under `.cursor/skills/`); global skills remain read-only in the list
- **Context & Indexing**: Auto-attach active file; enable project indexing; lexical persistence; semantic reranking; retrieval limits (max candidates, snippet budget, timeout); index retention days and max DB size; manual "Rebuild index now"
- **Permissions**: Tool permission mode (Ask Every Time / Run Everything); approved tools allowlist; protect writes outside workspace
- **Advanced**: ACP raw JSON debug logging (diagnostics); optional **Show token usage and context bar in chat** when the agent reports per-turn and session usage over ACP

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

Run manual real-CLI integration tests (local only, never CI):

```bash
CURSORJ_INTEGRATION=1 CURSOR_AGENT_PATH=/absolute/path/to/agent ./gradlew integrationTest
```

Manual integration guardrails:

- `integrationTest` is **manual-only** and intentionally excluded from `test`/`check`/`build`.
- CI is hard-blocked from running `integrationTest` even if it is requested.
- If `CURSORJ_INTEGRATION` is not set to `1`, the integration task is skipped.
- The task runs through the IntelliJ Platform test harness (`intellijPlatformTesting.testIde`) so the full suite can be discovered and executed together; tests that require a live IntelliJ `Application` self-skip when one is not bootstrapped, while real-CLI tests (e.g. Debug mode, model routing) run.

Model-switch troubleshooting:

- Treat ACP config state as authoritative: after switching, inspect `session/set_config_option` response `configOptions` and confirm `category: "model"` `currentValue`.
- Enable ACP raw logging in settings and verify switch flow includes:
  - `session/set_config_option` request with the selected model value
  - response containing updated model `currentValue`
  - subsequent prompt requests on the expected session id

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
- model config-option merge behavior (the agent's full option list is authoritative; the model dropdown keeps previously seen ids)
- model picker: `_meta` capability serialization, `thought_level` / `model_config` option classification, Cursor's parameter suffix (`300K Extra High`), the picker's section order and selection behaviour, the prompt-bar trigger, and effort cycling
- per-session config isolation (two sessions maintain independent model state)
- model display-name mapping (session label > CLI label > raw id, case-insensitive)
- `connectedStatusDetail` sources model from ACP-confirmed session config
- **CursorTaskPayload** parsing for `cursor/task` (string and custom `subagentType`, completion payloads, log truncation)

Integration tests (manual-only, `CURSORJ_INTEGRATION=1`) additionally cover:

- ACP process start/stop and model-list fetch against real agent CLI
- AgentConnection connect/auth handshake and session creation
- model switch via `session/set_config_option` with ACP-authoritative confirmation
- parameterized model picker (`ModelParameterCliIntegrationTest`): a Claude model's `thought_level` option is listed, settable and confirmed by the agent, a parameter the current model lacks is rejected, and the agent stays in variants mode without the `_meta` flag; runs against a scratch `CURSOR_CONFIG_DIR` copy of `cli-config.json` so the user's CLI defaults are untouched
- terminal and filesystem handler dispatch through ACP server request pipeline
- index search handler with real lexical index and file-update propagation
- per-session isolation with distinct session ids and independent model config
- reconnect recovery after ACP process exit
- canary test for agent model-routing behavior (detects if/when the agent fixes model routing)
- optional check whether a workspace `SKILL.md` appears in ACP `available_commands_update` (skipped if the agent does not advertise it)
- **Debug mode (simulated)**: ACP rejects mode id `debug`; Debug keeps the session on `agent`; `DebugLogServer` evidence posted over HTTP is readable by the agent via fs tools; a Debug-prefixed workflow prompt completes and shows instrumentation / reproduce / evidence behavior. These tests talk to `agent acp` over raw JSON-RPC (same approach as the max-mode diagnostic) and do not require an IntelliJ Application.

To run only the Debug-mode integration suite:

```bash
CURSORJ_INTEGRATION=1 ./gradlew integrationTest --tests "com.cursorj.integration.DebugModeIntegrationTest"
```

To run only the model parameter integration test:

```bash
CURSORJ_INTEGRATION=1 ./gradlew integrationTest --tests "com.cursorj.integration.ModelParameterCliIntegrationTest"
```

Thin IntelliJ fixture-based tests for PSI symbol/reference behavior are still recommended as a final integration safety net when targeting parity-level confidence across IDE/platform updates.

## Releasing

For release steps and publishing instructions, see [RELEASING.md](RELEASING.md).

Maintainer note: before every release, update JetBrains Marketplace metadata in `build.gradle.kts` (`pluginConfiguration.description` and `pluginConfiguration.changeNotes`) so Marketplace text matches `CHANGELOG.md`.

## Privacy

For data handling details, see [PRIVACY.md](PRIVACY.md).

## License

MIT License - see [LICENSE](LICENSE) for details.
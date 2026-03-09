# Privacy and Data Handling

This document explains what CursorJ stores locally and what data it sends to external services.

## Data sent externally

When you send a prompt, CursorJ communicates with the Cursor agent CLI (`agent acp`), which may send data to Cursor services according to your Cursor account settings and plan.

Depending on your prompt and tool usage, data may include:

- Prompt text and conversation context.
- Selected file snippets or active-file context you explicitly or implicitly attach.
- Tool call inputs/outputs (for example file paths, command text, command output).

## Local storage

CursorJ stores plugin settings in IDE configuration storage, including:

- Agent binary path.
- Default model.
- UI/session preferences.

API keys are stored in JetBrains Password Safe, not in plaintext plugin settings files.

## Logging

CursorJ writes operational logs to IDE log files for diagnostics.

- Logs include status and error details.
- API keys are not logged.

## User controls

You control what data is shared by:

- Choosing what prompts and files to send.
- Approving or rejecting permission-gated tool calls.
- Clearing or rotating credentials in JetBrains Password Safe / Cursor CLI.

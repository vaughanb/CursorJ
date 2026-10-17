# Effort / model-parameter selection over ACP — research and implementation plan

Date: 2026-10-17. Agent builds verified: Cursor CLI `2026.07.23-e383d2b`, `2026.08.04-aaa8809`, `2026.10.16-e3cb207`
(all present under `%LOCALAPPDATA%\cursor-agent\versions\`). CursorJ at `1.6.0` (commit `038e487`).

## TL;DR

- **Yes, it is possible, and it already works with every agent build on this machine, including the one
  CursorJ 1.6.0 was tested against.** The agent switches to a "parameterized" model picker when the client's
  `initialize` request carries `clientCapabilities._meta.parameterizedModelPicker: true`. In that mode the `model`
  config option lists plain model ids and the agent adds separate per-model config options for **effort**,
  **thinking**, **context** and **fast**, all settable through the standard ACP `session/set_config_option`.
- The README's "Model parameter tiers cannot be switched at runtime" limitation is **wrong as written**. The
  earlier conclusion that the capability "makes this worse (the agent then omits parameters entirely)" was a
  misreading: the parameters do not disappear, they move out of the `model` value and into their own options.
- CursorJ's input bar already has a generic renderer for non-model config options, so the minimal change is
  one capability flag. The real work is (1) fixing a merge bug that would leave stale controls after a model
  switch, (2) UI polish (labels, tooltips, layout, Max Mode coupling), (3) tests, README and changelog.
- Estimated size: ~1 day for phases 0–2 and 4; phase 3 (multi-tab propagation) is optional.

## 1. Research findings

### 1.1 The agent has two ACP model-picker modes

The agent bundle (`versions/2026.10.16-e3cb207/6489.index.js`, `cursor-acp-agent.ts`) contains:

```js
clientSupportsParameterizedModelPicker() { return true === this.clientCapabilities?._meta?.parameterizedModelPicker }
getModelPickerMode()                     { return this.clientSupportsParameterizedModelPicker() ? "parameterized" : "variants" }
isThoughtLevelParameter(e) { id in [thinking, reasoning, effort, thought_level] or name contains thinking/reasoning/thought/effort }
```

| Mode | How it is selected | `model` option values | Extra config options | `set_config_option` on `model` |
| --- | --- | --- | --- | --- |
| **variants** (what CursorJ sends today) | no `_meta` | 46 fixed strings such as `claude-opus-4-7[thinking=true,context=300k,effort=xhigh,fast=false]` | none | accepts only one of the 46 exact strings; everything else → `-32602 Invalid model value: …` |
| **parameterized** | `clientCapabilities._meta.parameterizedModelPicker: true` | 46 plain ids such as `claude-opus-4-7`, `default` | one option per parameter the selected model supports | accepts plain ids; parameter options are set individually |

Observed parameter options per model in parameterized mode (build 2026.10.16, live account):

| model | options returned (`id(category)=current [values]`) |
| --- | --- |
| `claude-opus-4-7` | `thinking(thought_level)=true [false,true]`, `context(model_config)=300k [300k,1m]`, `effort(thought_level)=xhigh [low,medium,high,xhigh,max]`, `fast(model_config)=false [false,true]` |
| `claude-opus-5-5` | `context`, `effort(thought_level)=medium [low…max]`, `fast` |
| `claude-sonnet-5-5` | `context`, `reasoning_effort(thought_level)=high [low…max]` |
| `claude-haiku-5-5` | `thinking`, `context`, `reasoning_effort(thought_level)` |
| `gpt-5.5` | `context [272k,1m]`, `reasoning(thought_level)=medium [none,low,medium,high,extra-high]`, `fast` |
| `composer-2.5` | `fast` only |
| `default` (Auto), `gemini-3.1-pro` | none |

Important consequences for the UI:

- **The effort option id is not stable across models** (`effort`, `reasoning`, `reasoning_effort`); the stable
  signal is `category == "thought_level"` combined with "not a boolean toggle" (the `thinking` on/off option
  shares the `thought_level` category).
- The value vocabulary also differs per model (`xhigh` vs `extra-high`, `none` exists only for GPT). The UI must
  render whatever `options` the agent sends and never hard-code the list.
- Option descriptions are useful tooltips: effort = "Effort the model uses to generate its response.",
  fast = "6x more expensive, but significantly faster speeds.", context = "Context size the model has available."

### 1.2 `session/set_config_option` behaviour in parameterized mode

- `{configId:"effort", value:"medium"}` → success; the result's `configOptions` is the **complete** list (mode,
  model, and the parameter options for the now-current model), as the ACP spec requires.
- Switching `model` returns the new model's parameter options and **drops** the previous model's options from the
  list (e.g. Opus → Composer leaves only `fast`).
- Errors are precise: `Invalid value for reasoning: bogus`, `Unknown model config option: effort` (when the
  current model has no such parameter), `Invalid model value: …` for ids not in the list.
- No `session/update` notification is emitted for config changes; the response is the only signal.
- `session/set_model` with a plain id also works in this mode, but offers nothing extra; keep using
  `set_config_option`.

### 1.3 Persistence and side effects (global state)

Parameter changes are written immediately by the agent to `~/.cursor/cli-config.json`:

- `modelParameters["<modelId>"]` (per-model memory: setting `reasoning=high` on `gpt-5.5`, switching to Composer
  and back restored `high`), and `selectedModel { modelId, parameters }` for the current model.
- The values survive new sessions in the same process and fresh agent processes, i.e. they become the user's
  CLI-wide default (same semantics the existing **MAX** checkbox already has).
- Choosing `context=1m` makes the agent set `maxMode: true` and `maxModeAutoEnabled: true`; turning Max Mode off
  makes the agent re-sanitize the parameters back to a non-max variant on its next selection.
- `~/.cursor/acp-config.json` (`selectedModelVariantId`) is only used by variants mode; parameterized mode clears it.
- `CURSOR_CONFIG_DIR=<dir>` redirects both files (authentication still succeeds), which lets integration tests
  run against a scratch copy instead of the user's real config.

### 1.4 Version coverage and detection

- All three builds on disk (July, August, October 2026) behave identically in parameterized mode; the October
  build was the one probed in depth.
- `initialize` returns **no** `agentInfo`, so a version gate would need `agent --version`. Not needed: if an
  older/future agent ignores the `_meta` flag, the session simply comes back in variants mode and CursorJ behaves
  exactly as today (graceful degradation), so the plan uses feature detection on the returned options instead.

### 1.5 ACP spec alignment

- `thought_level` and `model_config` are reserved `category` values in the spec; the spec asks clients to render
  `model_config` options near the model selector and to respect agent order.
- The spec notification name is `config_option_update`; `AcpSession` currently matches `config_options_update`
  (plural). Cursor sends neither today, so accept both.
- `_meta` on capability objects is the sanctioned extension point. The spec suggests vendor-namespaced keys
  (`_meta["zed.dev"]`), but Cursor reads the un-namespaced key `_meta.parameterizedModelPicker`, so that exact
  shape must be sent.

### 1.6 Avenues that do **not** work (and why the plan does not use them)

- `agent acp --model …` is ignored by the `acp` subcommand (existing note in `AcpProcessManager.buildCommand`).
- Hand-built variant strings (`claude-opus-4-7[effort=medium]`) are rejected in both modes.
- The slugs printed by `agent models` (`claude-opus-5-5-high`, `gpt-5.3-codex-xhigh-fast`) are a third naming
  scheme that ACP never accepts (this is what `ModelNameValidityIntegrationTest` already demonstrates). The
  **Default model** setting is validated against those slugs but is never sent to the agent; it only seeds the
  display name (`AgentConnection.selectedModel`).
- Editing `cli-config.json` directly and restarting the agent (the MAX approach) would work but is strictly
  worse than `set_config_option`: it needs a reconnect, loses the session, and bypasses the agent's parameter
  sanitization.

## 2. Relevant plugin code today

| Area | File | Notes |
| --- | --- | --- |
| Initialize payload | `src/main/kotlin/com/cursorj/acp/messages/AcpMessages.kt` (`ClientCapabilities`, `InitializeParams`), `AcpClient.initialize()` (`AcpClient.kt:82`) | No `_meta` field exists; `InitializeParams()` uses defaults. |
| Session config state | `AcpSession.kt:917` `setConfigOption`, `:976` `mergeConfigOption`, `:1003` `mergeConfigOptions`, `:1042` `updateConfigOptions`, `:345` `config_options_update` | `mergeConfigOptions` keeps every existing option that is missing from an incoming payload (see §2.1). |
| Option classification | `ConfigOptionUiSupport.kt` | Has model/mode/boolean/generic-select helpers; nothing for `thought_level` / `model_config`. |
| Input bar rendering | `InputPanel.kt` `updateConfigOptions` (~`:519`), `configControlsRow` (`:157`, plain `FlowLayout`), `maxModeToggle` (`:150`) | Generic path already renders two-value selects as checkboxes and others as unlabeled combos. |
| Change dispatch | `ChatPanel.kt:811` `handleConfigOptionChange` | Non-model options already go straight to `session.setConfigOption`; failures show `chat.config.option.failed`. |
| Max Mode | `MaxModeManager.kt`, `ChatPanel.kt:256` `handleMaxModeToggle`, `CursorToolWindowFactory.kt:131` polling, `SessionTabManager.kt:508` `reconnectTab` | Writes `maxMode` to `cli-config.json` and restarts the agent. |
| Settings | `CursorJSettings.kt:17` `State`, `CursorJConfigurable.kt:252-276` default-model validation | `defaultModel` is display-only (see §1.6). |
| Tests | `AcpSessionTest.kt:682` and `:744` (merge semantics), `ConfigOptionUiSupportTest.kt`, `InputPanelMaxModeTest.kt`, `AcpMessagesSerializationTest.kt`, `src/integrationTest/...` | Patterns to extend. |

### 2.1 Bug that must be fixed before enabling the capability

`AcpSession.mergeConfigOptions` walks the *existing* list and keeps any option the incoming payload does not
mention. In variants mode that never mattered (the list is always `mode` + `model`). In parameterized mode a
model switch from Opus 4.7 to Composer 2.5 returns only `mode`, `model`, `fast`; the merge would keep the stale
`thinking`, `context` and `effort` rows, the input bar would keep showing an Effort combo for Composer, and
using it would fail with `Unknown model config option: effort`. The ACP spec states the `set_config_option`
response is the complete list, so the response must be authoritative.

The test `model config merge preserves prior options on partial updates` (`AcpSessionTest.kt:682`) pins the
*option-list* merge for the model selector (a reduced option list keeps previously seen models). That behaviour can
stay; only the retention of whole options that are absent from a full payload must go.

## 3. Implementation plan

### Phase 0 — Prerequisite fixes (independent, safe to ship alone)

1. `AcpSession.mergeConfigOptions`: treat the incoming list as the full set. Keep `mergeConfigOption`'s
   model-option-list union, but do not re-add options that are absent from the incoming payload. Add a unit test
   "options absent from a full payload are removed" (Opus → Composer fixture) and keep the two existing merge tests.
2. `AcpSession.handleSessionUpdate`: accept `config_option_update` in addition to `config_options_update`
   (one-line change plus a test case in `updates mode plan and config options from session updates`).

### Phase 1 — Advertise the capability (the actual feature switch)

1. `AcpMessages.kt`: add `@SerialName("_meta") val meta: JsonObject? = null` to `ClientCapabilities`
   (`encodeDefaults`/null handling must keep the key out of the payload when null; verify with
   `AcpMessagesSerializationTest`).
2. `AcpClient.initialize(parameterizedModelPicker: Boolean)`: build
   `ClientCapabilities(meta = buildJsonObject { put("parameterizedModelPicker", true) })` when enabled.
3. `CursorJSettings.State`: `useParameterizedModelPicker: Boolean = true` with the usual synchronized accessor.
   `CursorJConfigurable`: checkbox under **Agent** ("Show per-model parameter controls (effort, thinking, context,
   fast) in chat"), bundle strings in `CursorJBundle.properties`. Keep the escape hatch because the flag is an
   undocumented Cursor extension.
4. `AgentConnection.connect()` passes the setting. Changing the setting requires a reconnect: reuse the Max Mode
   pattern (`SessionTabManager.reconnectTab` for every tab, driven by `CursorJSettings.addChangeListener`), and show
   the existing "reconnecting" status.
5. Feature detection, not version gating: after `session/new`, `AcpSession.configOptions` either contains
   `thought_level`/`model_config` options (parameterized mode worked) or only `mode`+`model` (agent ignored the
   flag). Nothing else needs to branch on it.

Tests: serialization with/without `_meta`; settings default; a `AgentConnection`-level test is not practical
(process spawn), so cover the parameter plumbing with the existing `AcpClientProtocolTest` style if it stubs the
wire, otherwise rely on the integration test in Phase 4.

### Phase 2 — Input-bar UX

1. `ConfigOptionUiSupport`: add
   - `isThoughtLevel(opt)` (category `thought_level`, fallback on ids `effort|reasoning|reasoning_effort|thinking`),
   - `isModelConfig(opt)` (category `model_config`),
   - `isEffortSelector(opt)` = thought level && !boolean toggle,
   - `labelFor(opt)` = `name ?: id` (title-cased id as last resort),
   - keep `optionsForInputBar` returning agent order (spec), but place `model_config` and `thought_level` options
     immediately after the model combo (they already are in Cursor's order).
   Unit tests for each with the real option shapes from §1.1 (including the `thinking` vs `effort` ambiguity).
2. `InputPanel.updateConfigOptions`:
   - Render generic selects as `JBLabel(labelFor) + JComboBox` (or a combo whose renderer prefixes the label) so
     "Medium" is not ambiguous between effort and context; set `toolTipText` from `description`.
   - Checkbox labels from `name` ("Thinking", "Fast"), tooltip from `description` (the Fast "6x more expensive"
     warning matters).
   - Hide the row entirely for models without parameters (Auto, Gemini) — already the natural result.
   - Switch `configControlsRow` from `FlowLayout` to the existing `WrapFlowLayout` so Mode + Model + MAX + up to
     four parameter controls wrap in a narrow tool window instead of clipping; check `lastKnownRootWidth`
     relayout logic still works.
   - Keep the **MAX** checkbox, but in parameterized mode give it a tooltip explaining that 1M context (and some
     fast variants) switch Max Mode on automatically; the 10 s `cli-config.json` poll already syncs the checkbox
     when the agent flips `maxMode`.
   - `InputPanel` tests: given the Opus 4.7 option set, the row contains model combo, two checkboxes and two
     labeled combos; given Composer, only `fast`; changing the effort combo fires `onConfigOptionChanged("effort",
     "medium")` and does not fire during programmatic updates.
3. `ChatPanel.handleConfigOptionChange`: after a successful non-model change, show a status such as
   "Effort: Medium" built from the option's `name` and the chosen value's `name` (new bundle key). Keep the error
   path as is; the agent's messages are already user-readable.
4. `AgentConnection.connectedStatusDetail()` / status bar: append the thought-level summary when present,
   e.g. `Connected (Claude Opus 4.7 · Effort: High)`, because the model display name no longer encodes the tier in
   parameterized mode. Extend `CursorJServiceModelSelectionTest` / `CursorJConnectionStatusTest`.
5. `CursorJConfigurable` default-model warning: validate against the ACP model ids seen in the last session (plain
   ids) in addition to CLI slugs, or drop the warning; optional follow-up — the setting does nothing on the wire
   either way. (A separate, small improvement would be to actually apply `defaultModel` with
   `set_config_option("model", id)` after `session/new`, which parameterized mode makes feasible because the ids are
   stable.)

### Phase 3 — Multi-tab consistency (optional)

Parameter changes are global CLI state, but each chat tab owns its own agent process and only learns about
changes made in other tabs when it next calls `set_config_option` or creates a session. Options:

- Minimal: document it (same limitation the MAX toggle already has).
- Better: when one tab changes a parameter, `SessionTabManager` replays the same `set_config_option` on every
  other connected tab whose current model matches (each agent process then updates its in-memory selection and the
  UI stays consistent). Needs a guard against echo loops and a test in `SessionTabManagerPromptSubmissionTest`
  style.

### Phase 4 — Docs, changelog, integration test

1. README: replace the "Model parameter tiers cannot be switched at runtime" limitation with a feature bullet
   ("Model parameters: effort / reasoning level, thinking, context window and fast mode per model, synced with the
   Cursor CLI's `/model` picker") and a short note that changes are CLI-wide and that 1M context implies Max Mode.
   Update the "To re-verify…" paragraph to mention the `_meta` flag.
2. CHANGELOG `[1.7.0]`: Added (parameter controls), Fixed (stale config options after model switch), Changed
   (notification name tolerance).
3. New manual integration test `ModelParameterCliIntegrationTest` (gated by `CURSORJ_INTEGRATION=1`, hard-blocked on
   CI like the others): spawn `agent acp` with `CURSOR_CONFIG_DIR` pointing at a temp copy of
   `~/.cursor/cli-config.json`, initialize with the flag, assert a `thought_level` select exists for a Claude model,
   set it to another value and assert the confirmed value, assert `Unknown model config option` for a parameter
   the current model lacks, and assert variants mode still returns bracketed ids without the flag (regression canary
   for the day Cursor changes the extension). Also add the `_meta` flag as a parameter to
   `ModelSwitchingCliIntegrationTest` so both modes stay covered.
4. Bump `pluginVersion` and `pluginUntilBuild`-independent metadata per `RELEASING.md` when releasing.

## 4. Verification checklist

- Unit: `./gradlew test` (new tests in `AcpSessionTest`, `ConfigOptionUiSupportTest`, `AcpMessagesSerializationTest`,
  new `InputPanelConfigOptionsTest`, settings tests).
- Integration (manual): `CURSORJ_INTEGRATION=1 ./gradlew integrationTest --tests '*ModelParameter*'`.
- Manual in the IDE: switch Opus 4.7 effort Low → Max and confirm `~/.cursor/cli-config.json`
  `selectedModel.parameters` follows; switch to Composer and confirm the effort control disappears; pick 1M context
  and confirm the MAX checkbox flips on within ~10 s; toggle MAX off and confirm the agent falls back to 300k;
  open a second tab and confirm it starts with the new values; disable the setting and confirm the old variant
  dropdown returns after reconnect.

## 5. Risks and open questions

- **Undocumented extension.** `_meta.parameterizedModelPicker` comes from the agent bundle, not public docs; Cursor
  can rename it. Mitigation: settings toggle + graceful fallback + the integration canary.
- **Global side effects.** Changing effort in CursorJ changes the user's terminal `agent` default too (already
  true for MAX and for model switches). Document it; Phase 3 reduces the in-IDE inconsistency.
- **Billing.** `fast=true` is "6x more expensive" and 1M context enables Max Mode; surface both in tooltips so a
  one-click change is not a surprise.
- **Layout.** Up to seven controls in the row; `WrapFlowLayout` handles narrow widths, but check the input area
  height recalculation in `InputPanel.adjustTextAreaHeight`.
- **Spec drift.** ACP now allows grouped options and `boolean` option types (only if the client advertises
  `session.configOptions.boolean`). `ConfigOption.options` is a flat list in `AcpMessages.kt`; Cursor sends flat
  lists today, but a grouped list would fail to deserialize — worth a defensive custom serializer as a follow-up.

## 6. Evidence (abridged probe output, build 2026.10.16)

Variants mode, `set_config_option model=…`:

```text
claude-opus-4-7[thinking=true,context=300k,effort=low,fast=false]   -> ERR -32602 Invalid model value
claude-opus-4-7[thinking=true,context=300k,effort=xhigh,fast=false] -> OK (only the advertised string works)
claude-opus-4-7                                                    -> ERR -32602 Invalid model value
```

Parameterized mode (`clientCapabilities._meta.parameterizedModelPicker=true`), `session/new` config options:

```text
mode(mode)=agent [agent, plan, ask]
model(model)=claude-opus-4-7 [default, grok-4.7, grok-4.6, composer-2.5, claude-opus-5-5, … 46 ids]
thinking(thought_level)=true  [false, true]
context(model_config)=300k    [300k, 1m]
effort(thought_level)=xhigh   [low, medium, high, xhigh, max]
fast(model_config)=false      [false, true]
```

```text
set_config_option effort=low    -> OK  effort(thought_level)=low
set_config_option model=composer-2.5 -> OK  only fast(model_config)=true remains
set_config_option reasoning=bogus (on gpt-5.5) -> ERR Invalid value for reasoning: bogus
set_config_option effort=high (on gpt-5.5)      -> ERR Unknown model config option: effort
```

Older builds: `2026.08.04` and `2026.07.23` return the same parameterized option set and accept `effort=medium`.

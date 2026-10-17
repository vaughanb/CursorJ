# Model picker UI: mirror Cursor's own picker

Date: 2026-10-17. Supersedes the input-bar layout shipped in `e1ceb27` (seven inline controls). Reference UI: the
"parameters-first" model picker in Cursor IDE **3.25.9** (the version installed on this machine), reconstructed from
its workbench bundle (`resources/app/out/vs/workbench/workbench.desktop.main.js`, modules `ModelPickerTrigger`,
`SelectedModelParametersMenuContent`, `CompactModelFlyoutRows`, `NestedModelListSubmenu`, `ModelListRowLabel`) and
from Cursor forum threads describing the same picker. Confirm against the real thing before building; the exact
order of the parameter sections is the one inference in here (see §1.2).

## 1. What Cursor renders

### 1.1 Trigger in the prompt bar

One compact button: `<model display name> <variant suffix> ▾`. The variant suffix is rendered in a muted style
(`ui-model-picker__trigger-variant-suffix`). The agent's own short-name convention, visible in
`~/.cursor/cli-config.json` as `displayNameShort`, is `<Model> <Context> <Effort> [Fast] [No Thinking]`, for
example `Claude Opus 4.7 300K Extra High` or `Claude Opus 4.7 1M Low Fast No Thinking`. There is no separate MAX
checkbox and no per-parameter control in the bar.

### 1.2 The menu that opens from the trigger (`aria-label: "<model> parameters"`)

```
┌──────────────────────────────────────────────┐
│ Model                     Claude Opus 4.7  ▸ │  ← hover/click opens the model submenu (§1.3)
├──────────────────────────────────────────────┤
│ Effort                                       │  ← section title = parameter name
│   ○ Low                                      │     radio items; selecting one closes the menu
│   ○ Medium                                   │
│   ○ High                                     │
│   ● Extra High                               │
│   ○ Max                                      │
├──────────────────────────────────────────────┤
│ Options                                      │  ← boolean parameters as switches; menu stays open
│   [x] Thinking                               │
│   [ ] Fast        (tooltip: 6x more expensive…) │
├──────────────────────────────────────────────┤
│ Context                                      │  ← remaining enum parameters
│   ● 300K                                     │
│   ○ 1M                                       │
└──────────────────────────────────────────────┘
```

- Enum parameters are rendered as titled radio sections (`Section{title: parameter.name}` + `RadioGroup`), boolean
  parameters are grouped under one section titled **Options** as switch items. Item descriptions become tooltips.
- The bundle renders three groups in order `[enumGroupA, Options, enumGroupB]`. Forum reports say "Effort and
  Options" sit at the top, so group A is the thought-level parameter (Effort / Reasoning) and group B the rest
  (Context). Verify on screen.
- For **Auto** the menu is just the Model row plus a description line; no parameter sections.
- GPT models show a **Reasoning** section (None / Low / Medium / High / Extra High), Composer shows only
  **Options: Fast**.

### 1.3 Model submenu (`data-testid: selected-model-list-submenu`)

Opens to the right of the Model row, keeps keyboard focus in a search field (type-to-filter), and lists in this
order:

1. toggles section: `Auto ▸`, `MAX Mode` (toggle, only when the account has it), `Use Multiple Models` (toggle)
2. named model rows: `<name>` with a trailing muted label of that model's saved parameters, e.g. `1M Medium`;
   hovering the selected row reveals an **Edit** affordance that opens the same parameter sections for that model
3. `Other models` (expandable, with a usage label), `Show all models…`, `Add Models`

### 1.4 Keyboard

`Ctrl+/` (`Cmd+/`) opens the model list directly; a "Cycle Effort" shortcut exists in the trigger tooltip.

## 2. CursorJ design

### 2.1 Prompt bar

`[Agent ▾]  [Claude Opus 4.7  300K Extra High ▾]` and nothing else model-related. Remove the MAX checkbox and the
Thinking / Context / Effort / Fast controls from `configControlsRow`. The trigger is a borderless `JButton`
(or `ActionButtonWithText`) whose text is `labelFor(model value)` and whose suffix is drawn in
`JBUI.CurrentTheme.ContextHelp.FOREGROUND` (muted). Suffix composition from the session's ACP config options:

| part | source | shown when |
| --- | --- | --- |
| context | `model_config` option whose values look like sizes (`300k`, `1m`, `272k`), value `name` upper-cased | option present |
| effort | thought-level select (`effort` / `reasoning` / `reasoning_effort`), value `name` | option present |
| `Fast` | `fast` option is truthy | truthy |
| `No Thinking` | `thinking` option is falsy | falsy |

Unknown extra parameters are appended as `<Name>: <value name>` so a future agent parameter never disappears.

### 2.2 Popup

Build it with `JBPopupFactory.createActionGroupPopup` over a `DefaultActionGroup`; this gives nested submenus,
check marks, titled separators, keyboard navigation and speed-search for free and looks native in every theme.

```
DefaultActionGroup
├─ "Model    Claude Opus 4.7"  (DefaultActionGroup, popup = true)      ← §2.3
├─ Separator("Effort")                                                  ← title = option.name
│    ToggleAction("Low") … ToggleAction("Max")                          ← radio semantics: selecting sets the value and closes
├─ Separator("Options")
│    ToggleAction("Thinking"), ToggleAction("Fast")                     ← boolean options; popup stays open
└─ Separator("Context")
     ToggleAction("300K"), ToggleAction("1M")
```

Rules:

- Section order: thought-level selects first, then **Options** (all boolean options), then every other select, in
  agent order inside each group. This matches §1.2; if the on-screen check shows a different order, change the
  order here, not the classification.
- Boolean option = `ConfigOptionUiSupport.isBooleanToggle` (exactly the true/false style values). `context`'s
  `300k`/`1m` is therefore a select, as in Cursor.
- Each item's tooltip is the option value's `description`; the section's tooltip is the option's `description`
  (Fast: "6x more expensive, but significantly faster speeds.").
- Selecting a value calls `ChatPanel.handleConfigOptionChange(configId, value)` (unchanged). After the response,
  the popup's actions re-read `AcpSession.configOptions`, so dependent changes (a model switch replacing the
  sections) are reflected if the popup is still open.
- Auto / models without parameters: only the Model row and a muted description line (`option.description` of
  the model value when present, else "Cursor picks the model.").
- Disabled state while a prompt is streaming: keep the trigger enabled but mark items disabled, as Cursor does.

### 2.3 Model submenu

`DefaultActionGroup(popup = true)` titled `Model` with the current model name appended in the row text
(`Model    Claude Opus 4.7`), containing:

1. `Auto` (first; value `default`), then `MAX Mode` as a `ToggleAction` wired to the existing
   `MaxModeManager` + reconnect flow (shown only when `useParameterizedModelPicker` is off or the agent did not
   return a `context` option; with a `context` option the 1M entry is the Max Mode control and the toggle is hidden).
   `Use Multiple Models` has no ACP equivalent and is omitted.
2. One `ToggleAction` per `model` option value in agent order, check mark on the current one. Trailing saved-parameter
   labels (`1M Medium`) are only known for the current model over ACP; show the suffix from §2.1 on the current row
   and leave other rows plain (first version). A follow-up can read `modelParameters` from `cli-config.json` for
   the other rows.
3. No `Other models` / `Show all models…` / `Add Models`: those are IDE account features.

Speed-search is on (`ActionSelectionAid.SPEEDSEARCH`), which gives Cursor's type-to-filter behaviour.

### 2.4 Keyboard

Two actions in `plugin.xml`, unbound by default: `CursorJ.OpenModelPicker` and `CursorJ.CycleEffort` (steps
through the thought-level values in order and shows the new value in the status line). `Ctrl+/` is IntelliJ's
comment shortcut, so do not claim it.

### 2.5 Variants mode (setting off, or an agent that ignores the capability)

Same trigger and popup. The Model submenu lists the agent's variant strings by their display names and there are
no parameter sections; the suffix is empty. The MAX Mode toggle keeps today's behaviour.

## 3. Code changes

| File | Change |
| --- | --- |
| `ui/chat/InputPanel.kt` | Remove the inline parameter controls and MAX checkbox from `configControlsRow`; add the trigger button; keep `updateConfigOptions` as the single entry point that refreshes trigger text and the popup model. |
| new `ui/chat/ModelPickerPopup.kt` | Builds the action group from `List<ConfigOption>` (+ current MAX state) and shows it anchored to the trigger. Pure function `buildSections(options)` returns a testable structure (title, items, kind, selected). |
| `acp/ConfigOptionUiSupport.kt` | Add `isContextOption`, `variantSuffix(options)`, `sectionsFor(options)` (ordering rules of §2.2). |
| `ui/chat/ChatPanel.kt` | Status text after a change uses the same `variantSuffix`. |
| `acp/AgentConnection.kt` | `connectedStatusDetail()` shows `<model> <suffix>`. |
| `actions/` + `plugin.xml` | `OpenModelPickerAction`, `CycleEffortAction`. |
| `resources/messages/CursorJBundle.properties` | `chat.modelPicker.modelRow`, `chat.modelPicker.options`, `chat.modelPicker.auto.description`, action texts. |
| README / CHANGELOG | Describe the picker; replace the inline-controls description added in `e1ceb27`. |

Tests: `ConfigOptionUiSupportTest` (suffix composition for Opus 4.7 at xhigh/300k, at low/1m/fast/no-thinking,
GPT reasoning, Composer, Auto), `ModelPickerPopupTest` (section order and item selection for the fixture option
sets, Auto has no sections, boolean vs select classification of `context`), `InputPanelConfigOptionsTest` rewritten
for the trigger (text and suffix, no inline controls), `CycleEffortAction` test over a fake session. Manual check
against Cursor 3.25.9 side by side.

# Fix Report — Review/Apply-dialog formatting polish

Repo: `IronLog-V2-Client`, branch `fix/review-apply-formatting`

## FIX 1 — strip `[CODE]` bracket from movement display names

Added a pure helper in `ReviewLogic.kt`:

```kotlin
private val TRAILING_CODE_BRACKET = Regex("""\s*\[[^\]]*\]\s*$""")
fun displayMovementName(name: String): String = name.replace(TRAILING_CODE_BRACKET, "").trim()
```

Only a bracket anchored at the *end* of the string is stripped — internal brackets
(`"Hip [Thrust] Variant"`) are left untouched, matching the spec.

Applied at every place a movement name renders to the user:
- `ReviewScreen.kt`: `applyWizardTitle` (dialog title), `slotLabel` (slot line + source-slot
  picker list, since both share the function), `overrideSummaryLine` (Active-adjustments
  base/target names for MOVEMENT/LOAD/REPS/else branches), and the replacement-movement
  picker list (`MovementPickerDialog`, `m.name`).
- `ReviewLogic.kt`: `proposedChangeLine` (note's `proposed_change.movement`).
- `CaptureScreen.kt`: `ReadOnlyExerciseBlock`'s exercise-name `Text` (capture screen header).
- `TodayScreen.kt`: `ReadOnlyExerciseBlock`'s exercise-name `Text` in the generate-preview
  read-only group card.

One helper, no duplicated regex; both `CaptureScreen.kt` and `TodayScreen.kt` import it from
`ui.screens.review`.

### Unit tests (`ReviewLogicTest.kt`)
- `displayMovementName_strips_trailing_code_bracket`
- `displayMovementName_no_bracket_returns_unchanged`
- `displayMovementName_internal_bracket_not_at_end_is_left_alone`
- `displayMovementName_trims_and_handles_blank`

## FIX 2 — Apply dialog layout (`ApplyWizardDialog` in `ReviewScreen.kt`)

- Title: `applyWizardTitle` now renders `"Change ${displayMovementName(name)}"`.
- Slot row split into two lines: the "Slot: day · tier · movement" `Text` on its own line,
  and a `TextButton("Change slot")` on a separate line below it (was squeezed into a
  `SpaceBetween` Row with the label, causing "Ch/an/ge" wrap on narrow widths). Both live in
  a `Column(spacedBy(4.dp))` instead of a `Row`.
- Load-delta buttons (`-10/-5/+5/+10`): swapped the fixed `Row` for `FlowRow` (added
  `@OptIn(ExperimentalLayoutApi::class)` on `ReviewCard` and `ApplyWizardDialog`) so the
  button set wraps to a second line instead of clipping `+10` on a narrow (cover-display)
  width.
- "Set exact (lb)" field: paired with a real `Button("Set")` (was a bare `TextButton`);
  same treatment for the REPS "Apply" action (`Button` instead of `TextButton`).
- Vertical rhythm: outer dialog `Column` and the LOAD/REPS sub-columns now use a consistent
  `Arrangement.spacedBy(12.dp)` instead of the previous uneven 4dp/8dp mix.
- Whole dialog `text` content wrapped in `Modifier.verticalScroll(rememberScrollState())` so
  it scrolls if content exceeds the available height on a small display.

FlowRow (`androidx.compose.foundation.layout.FlowRow`) was available in-BOM
(compose-bom 2024.12.01 → foundation-layout 1.7.6) — used directly, no new dependency. It's
still `@ExperimentalLayoutApi` at this Foundation version, so both composables that use it
now carry that opt-in alongside the existing `@ExperimentalMaterial3Api`.

## FIX 3 — Review note card + Active-adjustments card polish

- `ReviewCard`'s Dismiss/Apply/Confirm action row changed from a plain `Row` (which could
  overflow off-screen with 3 buttons on a narrow display) to a `FlowRow` with
  `Arrangement.spacedBy(8.dp, Alignment.End)` — wraps instead of clipping.
- `OverrideCard`'s `overrideSummaryLine` (MOVEMENT/LOAD/REPS/else branches) now runs both the
  base and target movement names through `displayMovementName` — the Active-adjustments
  summary line ("Base → Target", "Movement +10 lb", etc.) no longer shows the internal code.
- No other layout changes — existing `Text`/`Card` wrapping was already unconstrained
  (no `maxLines`), so headers/note-text/action-line truncation wasn't otherwise a problem;
  left as-is per "don't over-redesign."

## Files changed
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/review/ReviewLogic.kt`
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/review/ReviewScreen.kt`
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt`
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayScreen.kt`
- `app/src/test/java/com/jauschua/ironlogv2/ui/review/ReviewLogicTest.kt`

`app/build.gradle.kts` (pre-existing local base-URL change) and
`.superpowers/sdd/task-7-report.md` (pre-existing unrelated modification) were both already
dirty in the working tree before this task started — left untouched and NOT committed.

## Test results

- `./gradlew :app:testDebugUnitTest --tests "*ReviewLogic*"` — BUILD SUCCESSFUL (all
  `ReviewLogicTest` cases pass, including the 4 new `displayMovementName` tests).
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
- `./gradlew :app:testDebugUnitTest` (full suite) — BUILD SUCCESSFUL, all green.

## Concerns

- No device available to visually confirm the dialog on a real narrow/cover display — the
  fix is build- and unit-test-gated per instructions. `FlowRow` wrapping behavior for the
  load-delta buttons and the card action row is standard Compose behavior, not independently
  screenshot-verified this session.
- `displayMovementName` was also applied to the swap-target movement picker list
  (`MovementPickerDialog`), which wasn't explicitly named in the spec's list but renders
  movement names the same way — consistent with "everywhere they're shown to the user."

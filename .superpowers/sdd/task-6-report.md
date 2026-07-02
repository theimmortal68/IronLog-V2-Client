# Task 6 — Rest timer (client) — Completion Report

**Status:** completed
**Commit:** `c1f02bd` — `feat(capture): RPE-adaptive T1 rest timer + fixed T2-T4`
**Branch:** `feat/in-gym-logging` (was HEAD `c824202`, now `c1f02bd`)

> Note: an earlier chunk also numbered a task "Task 6" (wizard DTOs + WizardRepo, branch
> `feat/wizard`) and wrote its report to this same path. That report is superseded here per the
> current in-gym-logging chunk's brief, which names this same file for the rest-timer report.

## Duration function

`restSeconds(baseRest: Int, tierLabel: String, tap: FeedbackTap, isGiantSet: Boolean): Int`
in `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/RestTimer.kt`.

- `tierLabel in {"T1", "T1b"}` → adaptive: `(baseRest * multiplier).roundToInt()` where
  `multiplier` is `0.75` (TOO_EASY) / `1.0` (ON_TARGET) / `1.5` (TOO_HARD).
- Any other tier → returns `baseRest` unchanged; `tap` and `isGiantSet` are ignored
  (`isGiantSet` is kept in the signature only for symmetry with the trigger call site — giant-set
  rounds are never T1 so it never actually branches on it).

`FeedbackTap` is the existing `@Serializable enum` in `data/api/dto/Models.kt`
(`TOO_EASY`/`ON_TARGET`/`TOO_HARD`) — reused as-is rather than inventing a parallel `Tap` type.

## Trigger

`shouldStartRest(group: GroupOut, exercise: ExerciseOut): Boolean` — `true` unless
`group.group_type == "GIANT_SET"` and `exercise` isn't the group's last exercise. This works
without any round-index bookkeeping because `flattenPrescription` (existing VM logic) already
flattens GIANT_SET groups round-major — one set per exercise per round, in exercise order — so
the group's last exercise is always the last item logged within each round.

`restContextByPlannedSetId(groups): Map<Int, SetRestContext>` precomputes, once per session
load, each planned set's `(baseRestSeconds, tierLabel, isGiantSet, triggersRest)` from its
group. Groups with a null `rest_seconds` are skipped (no trigger possible). Wired into
`CaptureViewModel`:
- Populated in `load()` and `initPrescriptionForTestFromGroups()` alongside the existing
  `flattenedPrescription`/`unilateralSetIds` derivation.
- Consulted in `logWorkingSet`'s cursor-advance branch (fires only once the planned set is
  fully complete — for a unilateral set that's after side 2, not side 1 — and only after the
  Room write already committed, consistent with the existing write-before-advance contract).
- The logged `tap: String?` is parsed via `FeedbackTap.valueOf` (`runCatching`, so an
  unexpected/null string — e.g. a warmup set with no tap — safely falls back to `ON_TARGET`,
  which is also correct for fixed tiers where the tap is ignored anyway).

Countdown state lives in the VM (testable, no Compose dependency):
`restRemainingSeconds: StateFlow<Int?>` (null = not running), `startRest(seconds)` (private,
one-second `viewModelScope` ticker, cancels any prior job so back-to-back triggers don't stack),
`skipRest()`, `addRestTime(extraSeconds = 30)`.

## UI

`RestTimerBar` composable in `RestTimer.kt` — a `Card` row showing `"Rest: m:ss"`
(`formatRestTime`) plus `+30s` and `Skip` `TextButton`s, matching the existing
`ErrorRetryBox`/`SetCard` Material3 patterns already in the capture package. Wired into
`CaptureScreen.kt`: `restRemainingSeconds` collected from the VM and rendered as a `LazyColumn`
item right after the session header, only while non-null; `onSkip = vm::skipRest`,
`onAddTime = { vm.addRestTime(30) }`.

## Tests

New: `app/src/test/java/com/jauschua/ironlogv2/ui/capture/RestTimerTest.kt` — 10 tests covering
the exact brief mapping (`restSeconds` T1/T1b adaptive at all three taps, T2/T3 GS fixed with
tap ignored) plus `shouldStartRest` (STRAIGHT every set, GIANT_SET only the last exercise) and
`formatRestTime` (mm:ss padding).

`./gradlew :app:testDebugUnitTest --tests '*Capture*' --tests '*Rest*'` → **BUILD SUCCESSFUL**,
37 tests / 0 failures / 0 errors across all 5 suites (CaptureDurabilityTest 1,
CaptureRepoTest 2, CaptureScreenLogicTest 17, CaptureViewModelTest 7, RestTimerTest 10 — the
existing 27 were unaffected, confirming no regression from the VM wiring).

## TDD flow

`RestTimerTest.kt` written first (imports `restSeconds`/`shouldStartRest`/`formatRestTime` from
`ui.screens.capture`, which didn't exist yet) → ran `--tests '*Rest*'`, confirmed RED
(`compileDebugUnitTestKotlin FAILED`, 16 "Unresolved reference" errors) → implemented
`RestTimer.kt` → ran again, confirmed GREEN → wired the trigger + UI into
`CaptureViewModel.kt`/`CaptureScreen.kt` → full Capture+Rest suite green → commit.

## Files changed

- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/RestTimer.kt` (new)
- `app/src/test/java/com/jauschua/ironlogv2/ui/capture/RestTimerTest.kt` (new)
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt` (modified —
  rest-context state + trigger + timer control functions)
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt` (modified —
  collect + render `RestTimerBar`)

`app/build.gradle.kts` (the pre-existing local base-URL diff) was left untouched and NOT staged
or committed, per the task constraint.

## Concerns

- The rest countdown does not survive process death / config change beyond normal
  `ViewModelScope` lifetime (no `SavedStateHandle`/foreground-service backing) — acceptable for
  an in-gym MVP timer but worth flagging if a "keep counting while phone locks" requirement
  shows up later.
- Warmup sets (and any set logged without a tap) trigger rest at the group's `ON_TARGET`/fixed
  duration by default, since the brief didn't carve out a warmup exception and `restSeconds`
  requires a non-null `tap`. If warmups should skip the rest timer entirely, that's a follow-up
  scope decision, not something this task's brief specified.
- No Compose UI test for `RestTimerBar` itself (per brief: "Compose animation itself needn't be
  unit-tested") — only the pure functions and VM state are unit-tested.

# Spec: Real finisher timer (honest work/rest, EMOM, Tabata) + weight-log UI

## Objective

`IntervalTimerService.kt`'s finisher timer has two real bugs, confirmed by
direct investigation:

1. `TimeBased` derives rest as `60 - workSeconds` — a hardcoded 60-second
   grid. The server's `rest_seconds_per_minute` param is never read anywhere
   client-side; it just happens to look right for farmer_carry (40+20=60) by
   coincidence, and is silently wrong for sled_push (which now has an
   explicit `rest_seconds_per_minute: 30` param the client still ignores).
2. `RepBased` reads `target_reps_per_minute` only to SELECT this mode, then
   never uses it — it's a generic 60s "Minute X of Y" countdown with zero
   real cadence logic.

The server now sends two new `scheme` values in a finisher's `params` JSON
that this client has never seen: `"emom"` (D4 sandbag_load_to_utility_seat —
real reps-at-top-of-minute pacing, rest the remainder) and `"tabata"` (D6
jump_rope — 8 rounds of `work_seconds`/`rest_seconds`, an
`inter_block_rest_seconds` break, then `blocks` total blocks). The server
also added `last_logged_weight_lb`/`last_logged_resistance_level` to the
finisher payload and a new `POST /sessions/{session_id}/finisher/log`
endpoint (previously finishers had ZERO write-back — no way to record what
weight/resistance was actually used).

This spec: (1) fixes `TimeBased` to use a real, server-provided rest value;
(2) adds real `Emom` and `TabataBlocks` timer modes; (3) adds a weight/
resistance input + log action to the finisher UI, wired to the new endpoint.

## Files to touch

1. `app/src/main/java/com/jauschua/ironlogv2/service/IntervalTimerService.kt`
2. `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/CaptureModels.kt`
3. `app/src/main/java/com/jauschua/ironlogv2/data/repo/CaptureRepo.kt`
4. `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt`
5. `app/src/test/java/com/jauschua/ironlogv2/service/IntervalTimerServiceLogicTest.kt`
   (existing test file — extend it)
6. Any other existing test file under `app/src/test/` that already covers
   `finisherTimerMode`/`FinisherSection`/`CaptureRepo` (grep for these names
   under `app/src/test/` first — extend what exists, don't create a
   duplicate parallel test file for something already covered).

Read ALL of these files in full before writing anything — this is a
tightly-coupled rewrite across a real Android foreground Service, its state
machine, DTOs, a repo, and a Compose screen; guessing any of their current
shapes will produce a diff that doesn't compile or doesn't match this
codebase's established patterns.

## 1. `IntervalTimerService.kt` — the core rewrite

### 1a. Fix `TimeBased`: real rest, not `60 - work`

Add a `restSeconds: Int` field to `IntervalTimerState.TimeBased` (currently
`totalMinutes, workSeconds, label, leadInSeconds`). In `IntervalTimerSequence`
(both `init` and `tick()`), replace every `remainingInPhase = 60 - workSec`
computation with `remainingInPhase = clampedIntervalWorkSeconds(state.restSeconds)`
— reuse the existing `clampedIntervalWorkSeconds` helper (it just clamps
`1..59`, the name is generic despite saying "work"; if you find its name
actively confusing once both work and rest go through it, you may rename it
to something like `clampedIntervalPhaseSeconds`, but only if you also update
every call site consistently — don't leave a half-renamed helper).

`totalMinutes` currently gates the round-count for `TimeBased` (`if
(!isWorkPhase && currentRound >= state.totalMinutes)` = finished). Keep this
gating mechanism unchanged — `TimeBased` still means "N total minutes of
work+rest cycling," just with an honest, server-provided rest duration
instead of a derived one.

### 1b. Add `Emom` state + sequence logic

New `IntervalTimerState.Emom` variant: `totalMinutes: Int, repsPerMinute:
Int, label: String, leadInSeconds: Int = 0`.

EMOM semantics: each round is exactly 60 seconds (real EMOM convention —
"every minute on the minute"), during which the athlete does `repsPerMinute`
reps then rests the remainder. Since this app's timer can't detect actual
reps performed, model it as: a 60-second countdown per round (same shape as
the OLD fake `RepBased`, but this time the phase label MUST show the target
rep count, e.g. `"${state.repsPerMinute} reps — Round X of Y"` — read
`intervalTimerRepBasedLabel`'s current implementation for the label-string
style to match, then write an analogous `intervalTimerEmomLabel(round,
totalMinutes, repsPerMinute)` function). `totalMinutes` gates total rounds
exactly like `RepBased` currently does (reuse that same round-counting
structure — the round-boundary tick logic in `tick()`'s `is
IntervalTimerState.RepBased ->` branch is the pattern to mirror for the new
`is IntervalTimerState.Emom ->` branch, just with the new label function and
60-second phase duration, which EMOM already IS by definition so no separate
work/rest phase split is needed within a round).

### 1c. Add `TabataBlocks` state + sequence logic

New `IntervalTimerState.TabataBlocks` variant: `workSeconds: Int, restSeconds:
Int, roundsPerBlock: Int, blocks: Int, interBlockRestSeconds: Int, label:
String, leadInSeconds: Int = 0`. (No `totalMinutes` — total duration is
derived from `roundsPerBlock * blocks * (workSeconds + restSeconds) +
(blocks - 1) * interBlockRestSeconds`, computed implicitly by the round/block
counters below, not needed as an explicit field.)

Sequence needs THREE nested counters: current round within the current block
(1..roundsPerBlock), current block (1..blocks), and work-vs-rest-vs-
inter-block-rest phase. Tick logic, in order of phase transitions:
- Work phase (workSeconds) → Rest phase (restSeconds), same round, same
  block.
- Rest phase ends, round < roundsPerBlock → increment round, back to Work
  phase, same block.
- Rest phase ends, round == roundsPerBlock, block < blocks → reset round to
  1, increment block, enter Inter-Block-Rest phase (interBlockRestSeconds).
- Inter-Block-Rest phase ends → round = 1 (already set), Work phase, new
  block.
- Rest phase ends, round == roundsPerBlock, block == blocks → finished
  (mirrors the existing `isFinished = true` / `remainingSeconds = null`
  pattern used by every other terminal case in this file).

Phase labels (mirror this file's existing label-string conventions, e.g.
`"Work"` / `"Rest"` used by `TimeBased`): `"Work"` during work phase,
`"Rest"` during rest phase, and something like `"Block Rest"` for the
inter-block-rest phase — include the block number progress in whichever
label makes sense given how `intervalTimerRepBasedLabel` formats round
progress today, for UI consistency (e.g. `"Work — Round 3/8, Block 1/2"` is
a reasonable shape, but match this file's actual existing label terseness/
style rather than inventing a verbose new convention).

Zero out this counter-and-phase logic carefully in `init` (lead-in handling
mirrors every other state variant's `init` block exactly — same lead-in
countdown pattern, then transition into the first real phase) and in the
lead-in-just-ended branch of `tick()`.

### 1d. Wire the new states through the Service/Controller plumbing

`IntervalTimerController` interface gains `startEmomIntervals(totalMinutes:
Int, repsPerMinute: Int, label: String, leadInSeconds: Int = 0)` and
`startTabataIntervals(workSeconds: Int, restSeconds: Int, roundsPerBlock:
Int, blocks: Int, interBlockRestSeconds: Int, label: String, leadInSeconds:
Int = 0)`, mirroring `startTimeBasedIntervals`'s exact signature style. Also
update `startTimeBasedIntervals`'s signature to add the new `restSeconds:
Int` parameter (matching 1a above) — this is a breaking signature change,
find every call site in this file AND in `CaptureScreen.kt` (section 4
below) and update them all.

Implement in BOTH controller implementations in this file — the in-memory
test/default one AND `AndroidIntervalTimerController` (which routes through
Intent actions to the real `IntervalTimerService`) — read both existing
implementations of `startTimeBasedIntervals`/`startRepBasedIntervals` first,
they're right next to each other, and mirror their exact structure for the
two new methods (same StateFlow-driven ticking pattern for the in-memory
one, same `Intent`-extras + `ACTION_START_*` dispatch pattern for the
Android one).

Add new `ACTION_START_EMOM` / `ACTION_START_TABATA` action constants
(mirror `ACTION_START_TIME_BASED`'s exact declaration style — find where
these action constants and their companion `EXTRA_*` constants are declared,
likely near the bottom of the file with `ACTION_START_COUNTDOWN` etc.), new
`EXTRA_*` constants for `repsPerMinute`, `workSeconds`/`restSeconds` (may be
able to reuse existing `EXTRA_WORK_SECONDS` if its semantics already fit;
add `EXTRA_REST_SECONDS` if it doesn't exist, and add `EXTRA_ROUNDS_PER_
BLOCK`, `EXTRA_BLOCKS`, `EXTRA_INTER_BLOCK_REST_SECONDS`), and update
`IntervalTimerService.onStartCommand`'s `when (intent?.action)` to build
the two new `IntervalTimerState` variants from Intent extras, mirroring the
existing `ACTION_START_TIME_BASED ->` branch's exact extraction style
(`intent.getIntExtra(...)`, `.coerceAtLeast(0)` where the existing code
does that). Also update the existing `ACTION_START_TIME_BASED` branch to
read the new rest-seconds extra.

## 2. `CaptureModels.kt` — DTO updates

`FinisherOut` gains two new nullable fields matching the server's payload
exactly: `val last_logged_weight_lb: Double? = null` and `val
last_logged_resistance_level: Int? = null` (both already present in the
server's `build_finisher_payload` response — this client was simply never
updated to declare them, so Kotlinx Serialization silently dropped them;
`FinisherOut`'s existing fields, e.g. `current_duration_seconds`, show the
exact declaration style to match).

Add two new `@Serializable` data classes mirroring this file's existing
request/response DTO conventions (look at how other simple request/response
pairs are declared in this file or a sibling DTO file):

```kotlin
@Serializable data class FinisherLogRequest(
    val movement_id: Int,
    val actual_weight_lb: Double? = null,
    val actual_resistance_level: Int? = null,
    val notes: String? = null,
)
@Serializable data class FinisherLogResponse(
    val id: Int,
    val movement_id: Int,
    val actual_weight_lb: Double?,
    val actual_resistance_level: Int?,
)
```

`FinisherOut` needs a `movement_id: Int` field too if it doesn't already
carry one (check — the server's `build_finisher_payload` dict does NOT
currently include a raw `movement_id` key, only `exercise_name` as a
string). If `movement_id` is genuinely absent from the server payload, do
NOT invent a client-side way to guess it — flag this as a blocking gap in
your summary instead of guessing, since `POST /sessions/{id}/finisher/log`
requires a real `movement_id` in its request body and the client has no
other source for it in the current payload shape. (If you find a
`movement_id` IS already present under some other key you missed on first
read, use that instead and note it in your summary.)

## 3. `CaptureRepo.kt` — new write method

Add a method mirroring `skipExercise`/`swapExercise`'s existing pattern
(direct `ApiClient` POST call wrapped in `runCatchingApi`, not a Room DAO
call like `logSet` — this write goes straight to the server, there's no
local Room table for finisher logs):

```kotlin
suspend fun logFinisher(
    sessionId: Int,
    movementId: Int,
    actualWeightLb: Double? = null,
    actualResistanceLevel: Int? = null,
): Result<FinisherLogResponse> = runCatchingApi {
    apiClient.http.post("/sessions/$sessionId/finisher/log") {
        contentType(ContentType.Application.Json)
        setBody(FinisherLogRequest(movementId, actualWeightLb, actualResistanceLevel))
    }.body()
}
```

Verify the exact imports/pattern against `skipExercise`'s real current
implementation before writing this — match it exactly, including whatever
`ContentType`/`contentType` import convention this file already uses.

## 4. `CaptureScreen.kt` — dispatcher, UI wiring, weight input

### 4a. `finisherTimerMode()` — route by `scheme` first

Currently this function only branches on presence of
`PARAM_TARGET_REPS_PER_MINUTE` vs `PARAM_WORK_SECONDS_PER_MINUTE`. Add a new
`PARAM_SCHEME = "scheme"` constant (mirror the existing
`PARAM_TARGET_REPS_PER_MINUTE`/`PARAM_WORK_SECONDS_PER_MINUTE` declaration
style) and check it FIRST, before falling back to the existing heuristic
(for finishers whose params predate the `scheme` field, though after this
session's server-side deploy all 5 live finishers now have real, correct
params — the fallback is defensive, not load-bearing):

- `params["scheme"] == "tabata"` → build `FinisherTimerMode.Tabata` (new
  sealed variant, mirror `RepBased`/`TimeBased`'s existing declaration
  style) carrying `workSeconds`, `restSeconds`, `roundsPerBlock`, `blocks`,
  `interBlockRestSeconds`, `label` — read these via new `JsonObject`
  extension helpers mirroring the existing `intParam` helper (add
  `doubleParam` only if actually needed; these are all ints per the server
  schema). Missing/malformed fields → fall through to the next case rather
  than crash (this function must never throw on a malformed but non-null
  params dict — check how the existing code already guards against missing
  ints via `?.let`/nullable chaining, and match that defensiveness).
- `params["scheme"] == "emom"` → build `FinisherTimerMode.Emom` carrying
  `totalMinutes` (from `finisher.duration_minutes`), `repsPerMinute` (from
  `target_reps_per_minute`), `label`.
- Otherwise, existing fallback logic (repsPerMinute → RepBased, workSeconds →
  TimeBased) — BUT `TimeBased`'s constructed value must now also carry a
  `restSeconds` (needed by the state class change in 1a) — read
  `PARAM_REST_SECONDS_PER_MINUTE` (new constant, add it) from params, and if
  absent, fall back to the OLD `60 - workSeconds` derivation ONLY in this
  client-side fallback path (not in the service itself, which should never
  do that derivation anymore per 1a) — this preserves old behavior for any
  finisher whose params genuinely lack a rest field, without resurrecting
  the bug for the params that now correctly provide one.

### 4b. Wire the two new modes into the interval-start call site

The `onStartInterval = { mode -> ... when (mode) { ... } }` block (~line
801-816) needs two new `is FinisherTimerMode.Emom ->` /
`is FinisherTimerMode.Tabata ->` branches calling
`intervalTimerController.startEmomIntervals(...)` /
`.startTabataIntervals(...)` with the new mode's fields, `leadInSeconds =
5` matching the existing branches' convention exactly.

### 4c. Weight/resistance log UI on `FinisherSection`

Add a compact input + "Log" button to `FinisherSection` (the composable
around line 977) for finishers whose movement uses a load field this
endpoint can capture — i.e., render the input whenever `finisher.params`
contains a numeric `weight_lb` OR `resistance_level` key (both already
present in the payload's `params` dict for the relevant finishers — check
via the existing `intParam`/similar helpers), prefilled from
`finisher.last_logged_weight_lb` / `finisher.last_logged_resistance_level`
when non-null (empty/hint text when null — mirror how `prefillWeight` in
this same file already handles "blank when null, needs-calibration" for
regular sets, same UX principle applies here). On submit, call
`captureRepo.logFinisher(...)` (thread the repo/viewModel call the same way
other write actions in this screen are wired — check how `onStartInterval`/
`onStopInterval`'s callback-from-parent-composable pattern is structured
and add an analogous `onLogFinisher: (Double?, Int?) -> Unit` callback
parameter to `FinisherSection`, wired at the call site the same way
`onStartInterval` is). Requires `finisher.movement_id` for the actual API
call (see the section 2 note above about verifying this field exists in the
payload — if it's genuinely missing server-side, stub the UI with a
`NEEDS_INPUT`-style comment explaining the gap rather than guessing a
movement id).

Keep this UI minimal — a single numeric text field + a small "Log" button,
matching this screen's existing dense/compact visual style (look at how
other small inline inputs on this screen, e.g. the weight input around
`loadInputLabel`'s call sites, are styled) — this is not a new full sheet
or dialog, just an inline row.

## Explicitly out of scope

- Do NOT touch the server repo (`IronLog-V2`) — already deployed, this
  dispatch is client-only.
- Do NOT add local Room persistence for finisher logs — this write goes
  straight to the server via `logFinisher`, no offline queue, matching how
  `skipExercise`/`swapExercise` already work (server-write-only, no local
  DAO).
- Do NOT remove or rename `RepBased`/`TimeBased` — `RepBased` stays as the
  defensive fallback for finishers with `target_reps_per_minute` but no
  `scheme` field.

## Verification

Build: `cd /home/jstout/projects/IronLog-V2-Client-wt-finisher-timer &&
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew :app:assembleDebug -q`
(this worktree doesn't need a separate JDK setup — JAVA_HOME must be passed
explicitly, the host's default JDK 21 is gone, only JDK 25 exists; this is
a known, already-solved environment quirk, not something to "fix").

Tests: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew
:app:testDebugUnitTest -q` — must be fully green. Extend
`IntervalTimerServiceLogicTest.kt` with real coverage for: `TimeBased`'s
corrected rest computation (assert it no longer derives `60 - work`, uses
the new `restSeconds` field directly, including a case where
`work + rest != 60` to prove the old bug is actually gone, not just
coincidentally still passing), `Emom`'s round-boundary and label-content
behavior, and `TabataBlocks`'s full state machine (work → rest → next round
→ ... → round==roundsPerBlock → inter-block-rest → next block → ... →
finished after the last round of the last block) — this is the most
complex new logic in the diff and needs the most thorough test coverage,
including at least one full walk from start to finish for a small
config (e.g. 2 rounds/block, 2 blocks) asserting the exact phase/label
sequence, not just spot-checking a couple of ticks.

Commit when done (`git commit`, one commit, conventional-commit message).
Scope check: only the files listed in "Files to touch" above should appear
in `git diff main..HEAD --stat`.

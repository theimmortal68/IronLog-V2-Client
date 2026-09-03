## Routing Plan
Generated: 2026-08-12

- `.specs/01-giant-set-round-major-rendering.md` → gemini, worktree wt-01, depends on: none. Real logic restructuring (round-major card sequencing) in an interactive screen with a lot of state to preserve exactly (HT reconfigure cues, carry-forward, unilateral handling, edit mode) — broad/cross-cutting within the file, not a bounded 1-3 file mechanical edit, so gemini per "Choosing a provider for generation." No schema/API change, no Forbidden-list hit — client-only UI fix, server contract untouched.

Delegation ratio: 1/1 (100%)
Merge order: wt-01 standalone.

Notes:
- **Opus review mandatory, not exempt** — this touches the live logging screen's set-recording flow (`vm.logWorkingSet`, `vm.editLoggedSet`) for a giant-set day; a subtle reordering bug here could misattribute a logged set to the wrong exercise/movement_id, corrupting real training data. Non-trivial logic, not additive/mechanical.
- No HUMAN GATE — pure client-side rendering fix, no auth/schema/API-surface change.
- Deploy: build APK (`./gradlew :app:assembleDebug`) + `adb install -r` to the athlete's phone once merged — Class 3 (client install) per the project-ops CLAUDE.md Deploy Gate, athlete installs when convenient (user already explicitly requested this fix and the install).

## 2026-08-26 batch: carry-forward warmup boundary fix

Athlete-reported live during today's D2 (Lower A) session: logging warmup set 1's
weight bled into warmup set 2, then set 3, then the first working set — chain-carried
across a boundary carry-forward was never supposed to cross. Root-caused by Tier A
before speccing (see spec 40): `effectiveLoadPrefill`/`effectiveRepsPrefill` apply the
working-set-only `planIsFlat` gate to ANY current set including warmups, and
`withCarriedLoad`/`withCarriedReps` write into the movement-keyed carry map with no
`is_warmup` check on either the read or write side. Dispatch withheld — hold for
explicit go-ahead once the athlete's workout is done (standing instruction this batch).

- `.specs/40-carry-forward-warmup-boundary-fix.md` → codex, worktree wt-40, depends on:
  none. Bounded single-file fix (`CaptureScreen.kt` + its test file) — codex per
  "Choosing a provider for generation" (opencode retired, all codegen → codex/gemini
  per 2026-07-09 directive).

Delegation ratio: 1/1 (100%)
Merge order: wt-40 standalone.

Notes:
- **NOT YET DISPATCHED** — user directive: spec now, implement only once told the
  workout is finished. Do not create the worktree or call `consensus_delegate` until
  that go-ahead arrives.
- Opus review: route through — this touches the live capture screen's prefill logic
  for a lift the athlete trains today; a wrong fix could silently reintroduce the bug
  in a different shape (e.g. breaking legitimate working-set carry-forward instead of
  just excluding warmups). Non-trivial logic, not additive/mechanical.
- No HUMAN GATE — pure client-side logic fix, no auth/schema/API-surface change.
- Deploy: build APK + `adb install -r` once merged — Class 3 (client install) per the
  Deploy Gate — install timing is the athlete's call, not urgent same-day.

## 2026-08-26 batch (cont'd): assist_lb display falls through to plain "lb"

Athlete-reported live during today's D2 (Lower A) session: Nordic Curl Max [Ares]
(band-assisted, correctly configured server-side) shows its per-set value as plain
"lb" during logging, same as a real weight-loaded lift, so it reads as "using weight"
instead of band assistance. Root-caused by Tier A before speccing (see spec 41):
`loadDisplayLabel` in `CaptureScreen.kt` has suffix cases for `assist_degrees`,
`assist_bands`, `assist_reps` but never got one for `assist_lb` — it falls through to
the bare `"lb"` else-branch. `loadInputLabel` (the input field's own label) already
handles `assist_lb` correctly ("Assist (lb)") — only the value-display function is
missing the case. Dips [TOWER + TUBES] uses the same `CABLE_LB` unit and is affected
identically. Dispatch withheld — hold for explicit go-ahead once the athlete's workout
is done (standing instruction this batch).

- `.specs/41-assist-lb-display-missing-suffix.md` → codex, worktree wt-41, depends on:
  **40 merged** (corrected 2026-08-26 via `/verify-plan`: both specs list
  `CaptureScreen.kt` as a file target, which is an automatic FAIL on the mechanical
  literal-overlap check for a "parallel" pair, even though the two specs touch
  non-overlapping functions — spec 40's targets are `effectiveLoadPrefill`/
  `effectiveRepsPrefill`/`withCarriedLoad`/the carry-map state/`reconstructCarriedLoad`/
  `reconstructCarriedReps` (~lines 208-289, 1841-1949); spec 41 touches only
  `loadDisplayLabel` (~line 1777), earlier in the file and unreferenced by spec 40's
  targets. No semantic collision, but same-file concurrent worktrees are an avoidable
  risk for zero benefit here — sequenced instead of dispatched-parallel-then-rebased).
  Bounded single-function fix + test — codex per "Choosing a provider for generation."

Delegation ratio: 1/1 (100%)
Merge order: wt-40 first (create, generate, review, merge to main) — THEN create
wt-41 off the now-updated main and dispatch it. Do not create wt-41 until wt-40 is
merged; this replaces the earlier "dispatch both, rebase the second" plan.

Notes:
- **NOT YET DISPATCHED** — user directive: spec now, implement only once told the
  workout is finished.
- Opus review: not clearly required by the Review Gate's criteria (additive display
  suffix, no logic restructuring) — but given wt-40 and wt-41 land in the same file the
  same day, route both through review before either merges rather than deciding
  in isolation; final call at dispatch time.
- No HUMAN GATE — pure client-side display fix, no auth/schema/API-surface change.
- Deploy: build APK + `adb install -r` once merged — Class 3 (client install) per the
  Deploy Gate.

## 2026-09-02 batch: ALT_PAIR (T1/T1b superset) planned_set_order fix

Athlete-reported: on Upper A (D1), T1/T1b (bench/rows superset) shows a rows working
set before bench's warmup ramp instead of all of bench's 3 ramp sets first, then
alternating working sets. Root-caused by Tier A before speccing (see spec 21): server
already computes correct order and exposes it as `GroupOut.planned_set_order` for
ALT_PAIR groups (`ironlog/api/app.py` in IronLog-V2, ~line 1042-1073), but the client's
`GroupOut` DTO never declares that field (silently dropped on parse), and
`flattenPrescription` in `CaptureViewModel.kt` only special-cases `GIANT_SET`, so
`ALT_PAIR` falls through to exercise-major flattening — same bug shape as the
already-fixed GIANT_SET round-major issue (spec 01), just never extended to ALT_PAIR
when spec 58's pairing feature landed server-side. Confirmed via DB: Upper A's T1/T1b
tiers are correctly paired (`pair:1:21`, symmetric `paired_tier_id`), so this is not a
data/config issue — purely the client's missing consumption of the field.

- `.specs/21-alt-pair-planned-set-order.md` → opencode, worktree wt-21, depends on:
  none. Bounded 3-file change (1 DTO addition, 1 function's `when` arm, 1 test file) —
  opencode per "Choosing a provider for generation" (bounded 1-3 file work).

Delegation ratio: 1/1 (100%)
Merge order: wt-21 standalone.

Notes:
- Opus/Fable review: route through — this touches the live capture screen's set
  sequencing (`flattenPrescription`, which drives `flattenedPrescription` and the
  cursor logic used to advance through real logged sets); a wrong fix could silently
  misorder or drop sets during an actual workout. Non-trivial logic, not
  additive/mechanical, despite the small diff size.
- No HUMAN GATE — pure client-side fix consuming an already-shipped, already-correct
  server field; no auth/schema/API-surface change (server untouched, out of scope per
  spec 21's constraints).
- Deploy: build APK (`./gradlew :app:assembleDebug`) + `adb install -r` once merged —
  Class 3 (client install) per the Deploy Gate. User is mid-session on this exact bug
  (asked "right now" during today's Upper workout) — flag for prompt install once
  merged rather than routine timing, but confirm with user before pushing to their
  phone if they're still using the app.

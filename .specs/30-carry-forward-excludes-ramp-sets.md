# Spec 30: Carry-forward flatness check must exclude ramp/warmup sets

## Objective
Bench Press's reps carry-forward (Fix F, `f20e7ac`/`7240e5c`) doesn't work: entering 8 reps on set 1 reverts to the prescribed 6 on set 2, even though all 3 WORKING sets share the identical target (6-8 reps). Same underlying bug also silently breaks LOAD carry-forward for any ramp-eligible movement (not yet reported by the athlete, but provably the same code path).

## Root cause (confirmed via direct data trace, not guessed)
`CaptureScreen.kt` lines 175-178:
```kotlin
val loadPlanIsFlat = isFlatAcrossSets(currentExercise?.planned_sets?.map { it.target_load } ?: emptyList())
val repsPlanIsFlat = isFlatAcrossRepTargets(
    currentExercise?.planned_sets?.map { it.target_reps_low to it.target_reps_high } ?: emptyList(),
)
```
`currentExercise?.planned_sets` includes EVERY `PlannedSetOut` for the exercise, including RAMP/warmup sets (`is_warmup=true`) — not just the WORKING sets the lifter is actually logging against. Confirmed live data (today's D1 Bench Press, `movement_id=4`, session 12):

| set_role | is_warmup | target_load | target_reps_low/high |
|---|---|---|---|
| RAMP | true | 67.5 | 5/5 |
| RAMP | true | 102.5 | 3/3 |
| RAMP | true | 135.0 | 2/2 |
| WORKING | false | 170.0 | 6/8 |
| WORKING | false | 170.0 | 6/8 |
| WORKING | false | 170.0 | 6/8 |

The 3 WORKING sets are perfectly flat (170.0 load; 6/8 reps every time) — carry-forward SHOULD apply. But `isFlatAcrossRepTargets` receives all 6 sets' pairs, sees `{(5,5), (3,3), (2,2), (6,8)}` — 4 distinct values — and returns `false`, disabling reps carry-forward for the WHOLE exercise. The identical mechanism breaks `isFlatAcrossSets` for load (`{67.5, 102.5, 135.0, 170.0}` — 4 distinct values, `false`), for any ramp-eligible T1 movement.

`isFlatAcrossSets`/`isFlatAcrossRepTargets` themselves are correct, pure, and already well-tested (`CaptureScreenLogicTest.kt` lines 682-714) — the bug is entirely in what the CALLER hands them: it must exclude ramp/warmup sets before computing flatness, since a ramp is *deliberately* non-flat (that's the whole point of a ramp) and must never be evaluated as part of "is this exercise's WORKING-set plan uniform."

## File targets (touch only these)
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt`
- Modify: `app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureScreenLogicTest.kt`

## The fix

In `CaptureScreen.kt`, change lines 175-178 to filter out warmup sets before mapping (do NOT touch `isFlatAcrossSets`/`isFlatAcrossRepTargets` themselves — they are correct pure functions, only their caller's input is wrong):

```kotlin
val workingPlannedSets = currentExercise?.planned_sets?.filter { !it.is_warmup } ?: emptyList()
val loadPlanIsFlat = isFlatAcrossSets(workingPlannedSets.map { it.target_load })
val repsPlanIsFlat = isFlatAcrossRepTargets(
    workingPlannedSets.map { it.target_reps_low to it.target_reps_high },
)
```

`PlannedSetOut.is_warmup: Boolean` already exists (`CaptureModels.kt` line 27) — this is the same field the server's `_build_session_perf` already filters on (`if sl.movement_id != mid or sl.is_warmup: continue`, `ironlog/persistence/run_analysis.py`), so this fix brings the client in line with an already-established server-side convention, not inventing a new one.

## Edge cases
- An exercise with ONLY ramp/warmup sets and no working sets (should not occur in practice, but `workingPlannedSets` would be empty — `isFlatAcrossSets`/`isFlatAcrossRepTargets` both already return `true` for an empty list per their existing docstrings/tests, so this degrades safely to "flat" with no carry-forward value to apply, same as today's behavior for a plan with no sets at all).
- A non-ramp-eligible movement (no RAMP sets at all) must see ZERO behavior change — `workingPlannedSets` becomes identical to the old unfiltered list when there are no warmup sets to remove.
- Do NOT change `RAMP` sets' own pre-fill behavior (they're not editable/loggable in the normal flow the same way, or if they are, they must keep pre-filling from their OWN prescribed target, never from `carriedLoadByMovement`/`carriedRepsByMovement`) — this spec only fixes the FLATNESS CHECK's input scope, not which sets are eligible to receive a carried-forward value. Do not touch `effectiveLoadPrefill`/`effectiveRepsPrefill`/`withCarriedLoad`/`withCarriedReps` — they are unaffected and correct.

## Dependencies
None.

## Verification
- **New test**: add a test proving the bug is fixed — construct an exercise with 3 RAMP sets (differing target_load/reps, `is_warmup=true`) followed by 3 WORKING sets (identical target_load/reps, `is_warmup=false`), and assert that filtering to non-warmup sets before calling `isFlatAcrossSets`/`isFlatAcrossRepTargets` yields `true` for both — mirroring the exact shape of today's live Bench Press data (RAMP: 67.5/5, 102.5/3, 135.0/2; WORKING: 170.0/6-8 ×3). Name it something like `carryForwardFlatness_ignoresRampSets_whenWorkingSetsAreFlat`.
- Existing tests `isFlatAcrossSets_*`/`isFlatAcrossRepTargets_*` (lines 682-714) must still pass UNCHANGED — those test the pure functions directly with hand-built lists, not the call site, so this fix should not require touching them at all.
- Full local test suite green: `./gradlew testDebugUnitTest --rerun` (use `--rerun`, this repo's gradle cache has repeatedly reported stale UP-TO-DATE results this session — independently verify via `app/build/test-results/testDebugUnitTest/TEST-*.xml`'s `<testsuite tests="N" failures="0">` attributes and a fresh `timestamp=`, not just the console "BUILD SUCCESSFUL").
- Manual/live verification (not part of the merge gate): after deploy, on a ramp-eligible T1 movement (e.g. Bench Press), enter a working-set rep count different from the prescribed target on set 1, advance to set 2, confirm it now pre-fills with the carried value instead of reverting to the plan's target.

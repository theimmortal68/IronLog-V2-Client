# Spec 35: Cardio log form hardening (two Low findings from spec 32's review)

## Objective
Close two Low-severity gaps from spec 32's Opus review: (1) submitting with `modality="WALK"` can still carry a stale `incline_pct`/`backward_walk_done` from an earlier Treadmill selection, and (2) a non-numeric optional field (e.g. `avg_hr="132x"`) silently maps to `null` instead of surfacing a validation error.

## File targets
- Modify: `app/src/main/java/com/jauschua/ironlogv2/ui/screens/cardio/CardioLogScreen.kt`
- Modify: `app/src/test/java/com/jauschua/ironlogv2/ui/cardio/CardioLogScreenLogicTest.kt`

## The fix

### 1. Gate incline/backward-walk to WALK modality at submit time
In `buildCardioLogCreate` (the pure, file-level function), force `incline_pct`/`backward_walk_done` to their empty defaults whenever `modality != "TREADMILL"`, regardless of what the caller passed in — so a leftover value from an earlier Treadmill selection can never leak into a WALK submission:

```kotlin
internal fun buildCardioLogCreate(
    date: String,
    durationMinutes: String,
    avgHr: String,
    modality: String,
    inclinePct: String,
    backwardWalkDone: Boolean,
): CardioLogCreate? {
    if (date.isBlank() || modality.isBlank()) return null
    val duration = durationMinutes.toIntOrNull() ?: return null
    if (duration <= 0) return null
    val isTreadmill = modality == "TREADMILL"
    return CardioLogCreate(
        date = date,
        duration_minutes = duration,
        avg_hr = avgHr.trim().toIntOrNull(),
        modality = modality,
        incline_pct = if (isTreadmill) inclinePct.trim().toDoubleOrNull() else null,
        backward_walk_done = if (isTreadmill) backwardWalkDone else false,
    )
}
```
(Only the `incline_pct`/`backward_walk_done` lines change — gated on `isTreadmill`. Everything else in the function is unchanged.)

### 2. Reject non-numeric optional-field input instead of silently discarding it
Currently `avgHr.trim().toIntOrNull()` and `inclinePct.trim().toDoubleOrNull()` treat BOTH a blank string AND a genuinely malformed string (e.g. `"132x"`) identically as `null` — this is correct for blank (intentionally empty), but wrong for malformed (the athlete typed something that failed to parse, which should be flagged, not silently dropped). Change `buildCardioLogCreate` to return `null` (the existing "invalid form, don't submit" signal already used for blank date/duration) when a NON-BLANK optional field fails to parse:

```kotlin
internal fun buildCardioLogCreate(
    date: String,
    durationMinutes: String,
    avgHr: String,
    modality: String,
    inclinePct: String,
    backwardWalkDone: Boolean,
): CardioLogCreate? {
    if (date.isBlank() || modality.isBlank()) return null
    val duration = durationMinutes.toIntOrNull() ?: return null
    if (duration <= 0) return null

    val avgHrTrimmed = avgHr.trim()
    val parsedAvgHr = if (avgHrTrimmed.isEmpty()) null else avgHrTrimmed.toIntOrNull() ?: return null

    val isTreadmill = modality == "TREADMILL"
    val inclineTrimmed = inclinePct.trim()
    val parsedIncline = if (!isTreadmill || inclineTrimmed.isEmpty()) null else inclineTrimmed.toDoubleOrNull() ?: return null

    return CardioLogCreate(
        date = date,
        duration_minutes = duration,
        avg_hr = parsedAvgHr,
        modality = modality,
        incline_pct = parsedIncline,
        backward_walk_done = if (isTreadmill) backwardWalkDone else false,
    )
}
```
(This combines both fixes into one final version of the function — do not apply fix 1 and fix 2 as two separate edits to the same lines, write the function once in this final form.)

No UI-visible error message is required for this pass (matching spec 32's own precedent: "optionally show a lightweight inline validation hint -- not required for this pass") — returning `null` from `buildCardioLogCreate` already causes the existing submit-button handler to simply not call `vm.submit(...)`, which is a safe, non-crashing no-op. A future spec may add a visible inline error; out of scope here.

## Edge cases
- A non-numeric `duration_minutes` was ALREADY correctly rejected before this spec (existing behavior, unchanged) — this spec only extends the same "malformed non-blank input = reject" principle to the two OPTIONAL fields, which previously had a gap.
- Blank `avg_hr`/`incline_pct` must still map to `null` cleanly (unchanged from before) — only a NON-BLANK, UNPARSEABLE string is newly rejected.
- Switching modality Treadmill→Walk→Treadmill again: the incline/backward-walk fields are still NOT cleared in the UI (spec 32's own explicit non-requirement, unchanged) — this spec only prevents them from being SUBMITTED while WALK is selected; if the athlete switches back to Treadmill before submitting, their prior incline entry is still there and will submit normally.

## Dependencies
None (spec 32 already merged).

## Required test additions
Add to `CardioLogScreenLogicTest.kt` (read the existing 6 tests first to match style exactly):
1. `buildCardioLogCreate_walkModality_ignoresStaleInclineAndBackwardWalk` — call with `modality="WALK"`, `inclinePct="8.5"`, `backwardWalkDone=true` (simulating a stale leftover from an earlier Treadmill selection) — assert the result has `incline_pct=null` and `backward_walk_done=false`.
2. `buildCardioLogCreate_nonNumericAvgHr_returnsNull` — call with `avgHr="132x"`, otherwise valid — assert `null`.
3. `buildCardioLogCreate_nonNumericInclinePct_returnsNull` — call with `modality="TREADMILL"`, `inclinePct="steep"`, otherwise valid — assert `null`.
4. Confirm existing tests `buildCardioLogCreate_blankOptionalInputs_mapToNull` and the valid WALK/TREADMILL tests still pass unchanged (blank stays blank→null, valid numeric stays valid).

## Verification
Full local test suite green: `./gradlew testDebugUnitTest --rerun`, cross-checked via `app/build/test-results/testDebugUnitTest/TEST-*.xml` per this repo's established gradle-cache-distrust convention. Expect 3 new tests added to `CardioLogScreenLogicTest.kt` (from 6 to 9), zero regressions elsewhere.

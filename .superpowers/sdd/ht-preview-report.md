# HT setup line in the Generate Preview — report

## Objective
Fix Today screen's GENERATE PREVIEW so Hip Thrust (band-composite) planned sets show their
plates/bands/peak setup instead of a blank/wrong `target_load` line, matching what Capture already
renders correctly.

## Root cause
`TodayScreen.kt`'s `PreviewContent → ReadOnlySetRow → targetSummary(set)` only read
`set.target_load`. HT sets carry `target_plates` + `band_config` + `target_felt_peak` and leave
`target_load` null/meaningless, so the preview rendered nothing useful for those sets.
`CaptureScreen.kt`'s `SetCard` already had the correct logic via `htSetupLine(...)`, gated by
`isHtSet = target_plates != null || band_config != null`.

## Fix
1. **Extracted** the "Task 6: HT band-composite — pure helpers" block (`BAND_NAMES`, `bandNames`,
   `composePlatesAndBands`, `htSetupLine`, `htReconfigure`, `htObservedPeak`) out of
   `CaptureScreen.kt` into a new file, same package (`ui.screens.capture`):
   `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/HtLoadLogic.kt`.
   Same-package placement means `CaptureScreen.kt`'s existing call sites (`SetCard`,
   `SessionContent`) needed **zero** changes, and `CaptureScreenLogicTest.kt`'s existing imports
   (`import com.jauschua.ironlogv2.ui.screens.capture.htSetupLine` etc.) kept working unmodified.
2. **TodayScreen.kt**: added `import com.jauschua.ironlogv2.ui.screens.capture.htSetupLine` and
   updated `targetSummary(set: PlannedSetOut)`:
   - `isHtSet = set.target_plates != null || set.band_config != null`
   - HT sets render `htSetupLine(set.target_plates, set.band_config, set.target_felt_peak)` in
     place of the raw `target_load` line; non-HT sets keep the original `target_load` behavior.
   - Reps/RPE parts are unchanged and still appended after the load/HT part, same `" · "` join,
     same `bodyMedium` text style in `ReadOnlySetRow` — no visual/style changes beyond the HT line
     content itself.
   - `targetSummary` changed from `private` to `internal` to allow direct unit testing.

No changes to generation, logging, the DTO, or `CaptureScreen.kt`'s behavior — display-only.

## Testing
- `htSetupLine`/`bandNames`/`htReconfigure`/`htObservedPeak` already have full unit-test coverage
  in `CaptureScreenLogicTest.kt` (unchanged, still passing — imports are package-qualified so the
  file move required no edits there).
- Added two new tests in `TodayLogicTest.kt`:
  - `target_summary_renders_ht_setup_line_for_band_composite_set` — HT set (180 plates, band 0 =
    Orange, peak 225, RPE 8) → `"180 plates + Orange · peak ~225 · RPE 8.0"`.
  - `target_summary_falls_back_to_target_load_for_non_ht_set` — plain set (135 load, 8 reps) →
    `"135.0 · 8 reps"` (unchanged prior behavior).

## Build/test gates
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (150 tests, all green after fixing an
  RPE-formatting expectation in the new test — `target_rpe` renders as raw `"$it"`, i.e.
  `"RPE 8.0"`, not through `formatWeight`; that's pre-existing `targetSummary` behavior, left as-is
  since fixing it was out of scope for this display fix).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

## Files changed
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt` — removed the
  HT-helpers block (moved, not deleted).
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/HtLoadLogic.kt` — new file, holds
  the extracted HT helpers verbatim.
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/today/TodayScreen.kt` — import +
  `targetSummary` HT branch + visibility change to `internal`.
- `app/src/test/java/com/jauschua/ironlogv2/ui/today/TodayLogicTest.kt` — two new regression
  tests.

## Concerns / notes
- `app/build.gradle.kts` had a pre-existing uncommitted modification on this branch before this
  task started (unrelated to this fix) — left untouched and **not** included in the commit per
  instructions.
- `.superpowers/sdd/task-7-report.md` also shows as modified in `git status` from before this
  session started — left untouched, not part of this commit.

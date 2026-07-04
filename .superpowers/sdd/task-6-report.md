# Task 6 — HT band-composite setup display + reconfigure cue + felt-peak capture — completion report

**Status:** completed
**Commit:** `c5e95f6` — `feat(capture): HT band-composite setup display + reconfigure cue + felt-peak capture`
**Branch:** `feat/ht-band-composite`

> Note: this report supersedes the prior "Today tab" Task 6 report (branch
> `feat/today-generate-history`, commit `df8f50b`), which itself superseded an even earlier
> "Task 6" report (rest timer, branch `feat/in-gym-logging`, commit `c1f02bd`). All three chunks
> independently used this same `.superpowers/sdd/task-6-report.md` path — each chunk's own task
> numbering restarted at "Task 6" — per the precedent already established in this file's history.
> This report is for the HT (banded/plate "band-composite" resistance) client work: DTO field +
> two pure helpers (TDD) + `bandNames`/`htSetupLine` composer helpers + three UI wiring points in
> `CaptureScreen.kt`.

## Files changed

| File | Change |
|---|---|
| `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/CaptureModels.kt` | Added `band_config: List<Int>? = null` to `PlannedSetOut`, field-for-field matching the server's shape (list of band ids), alongside the existing `target_plates`/`band_pair_id`/`target_felt_peak`. |
| `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt` | New pure helpers in the "Pure display/pre-fill logic" section: `bandNames`, `composePlatesAndBands` (private, shared), `htSetupLine`, `htReconfigure`, `htObservedPeak`. UI wiring: `SetCard` renders the HT setup line (`Target:`-row style) when `isHtSet`; `SetCard` gained a `setFeltPeak`/`onFeltPeakChange` param pair and a "Felt peak (lb)" `OutlinedTextField`, shown only when `isCurrent && isHtSet && tapRequired`; `SessionContent` now tracks a running `prevHtSetup: Pair<Double?, List<Int>?>?` across the WHOLE session (declared above `session.groups.forEachIndexed`, updated inside the restructured `group.exercises.forEachIndexed`, independent of each group's expand/collapse state) and renders an `"ht-reconfigure-$gi-$ei"` banner item (`titleSmall`, primary color, `padding(top=12.dp)`) immediately before an HT exercise's `"ex-$gi-$ei"` item when `htReconfigure` fires. |
| `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt` | `logWorkingSet` gained a trailing `feltPeak: Double? = null` param, passed into the `SetLogDraft(...)` constructor as `feltPeak = feltPeak`. Default keeps every existing named-arg call site (`CaptureViewModelTest.kt`, `CaptureScreen.kt`'s pre-existing calls) compiling unchanged. |
| `app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureScreenLogicTest.kt` | 20 new `@Test` methods (no new file, per the current-instruction override of the stale `HtSetupLogicTest.kt` draft filename) covering `htReconfigure` (6 cases incl. the brief's 3 + 3 extra null-safety edges), `htObservedPeak` (5 cases, brief's 3 test methods = 5 assertions), `bandNames` (3 cases), `htSetupLine` (4 cases). |

`app/build.gradle.kts` and `.superpowers/sdd/task-7-report.md` had pre-existing uncommitted diffs (unrelated to this task) — left untouched and unstaged, per the task constraint.

## TDD flow (RED → GREEN)

1. Added `band_config` to `PlannedSetOut` (trivial DTO field, no test needed for a plain data
   class field addition — mirrors the existing `target_plates`/`band_pair_id` fields exactly).
2. **RED:** Added imports for `bandNames`/`htObservedPeak`/`htReconfigure`/`htSetupLine` and all
   20 new `@Test` methods to `CaptureScreenLogicTest.kt` (implementation not yet written), ran:

   ```
   ./gradlew :app:testDebugUnitTest --tests "*CaptureScreenLogicTest"
   ```

   Failed at `compileDebugUnitTestKotlin` with the expected "Unresolved reference" errors for
   `bandNames`, `htObservedPeak`, `htReconfigure`, `htSetupLine` (repeated at every call site) —
   confirms RED for the right reason (missing feature, not a typo). Tail:

   ```
   > Task :app:compileDebugUnitTestKotlin FAILED
   e: .../CaptureScreenLogicTest.kt:7:50 Unresolved reference 'bandNames'.
   e: .../CaptureScreenLogicTest.kt:11:50 Unresolved reference 'htObservedPeak'.
   e: .../CaptureScreenLogicTest.kt:12:50 Unresolved reference 'htReconfigure'.
   e: .../CaptureScreenLogicTest.kt:13:50 Unresolved reference 'htSetupLine'.
   [... repeated at every call site in the new test bodies ...]

   FAILURE: Build failed with an exception.
   > Task :app:compileDebugUnitTestKotlin.
   BUILD FAILED in 13s
   ```

3. **GREEN:** Implemented the four helpers (`bandNames`, `composePlatesAndBands` private shared
   composer, `htSetupLine`, `htReconfigure`, `htObservedPeak`) in `CaptureScreen.kt`'s pure-logic
   section. Re-ran the same command:

   ```
   > Task :app:testDebugUnitTest
   BUILD SUCCESSFUL in 11s
   24 actionable tasks: 6 executed, 18 up-to-date
   ```

   40 tests in the suite (20 pre-existing + 20 new), 0 failures, 0 errors (confirmed via the
   JUnit XML: `tests="40" skipped="0" failures="0" errors="0"`).

4. Wired the three UI pieces into `CaptureScreen.kt` (setup line render, felt-peak input,
   reconfigure banner + tracking var) and the `feltPeak` param through `CaptureViewModel` — pure
   glue/wiring around already-tested pure functions, no new untested logic branches introduced
   (the branch conditions — `isHtSet`, `tapRequired`, `expanded`, `htSet != null` — are boolean
   compositions of existing tested inputs, not novel business logic).

## Full unit-test-suite tail (no filter)

```
./gradlew :app:testDebugUnitTest
...
> Task :app:compileDebugUnitTestKotlin
w: .../CaptureViewModelTest.kt:175:9 This declaration needs opt-in. ... ExperimentalCoroutinesApi ...
w: .../CaptureViewModelTest.kt:186:9 This declaration needs opt-in. ... ExperimentalCoroutinesApi ...
   (pre-existing warnings, unrelated to this task — same two lines present before this change)

> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 12s
26 actionable tasks: 7 executed, 19 up-to-date
```

Aggregate across all JUnit XML reports (`app/build/test-results/testDebugUnitTest/*.xml`):
**86 tests total, 0 failures, 0 errors** (no regressions in the pre-existing suites —
`CaptureDurabilityTest`, `CaptureRepoTest`, `CaptureViewModelTest`, `RestTimerTest`, plus any
`today`/`history`/`generate`/`wizard` suites already in the tree).

## `assembleDebug` tail

```
./gradlew :app:assembleDebug
...
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:assembleDebug

BUILD SUCCESSFUL in 2s
38 actionable tasks: 3 executed, 35 up-to-date
```

## Commit

- Message subject: `feat(capture): HT band-composite setup display + reconfigure cue + felt-peak capture`
- SHA: `c5e95f6a5a52e7880c2b03c32665859710be138e`
- Files staged: `CaptureModels.kt`, `CaptureScreen.kt`, `CaptureViewModel.kt`,
  `CaptureScreenLogicTest.kt`, plus this report file. `app/build.gradle.kts` and
  `.superpowers/sdd/task-7-report.md` were left out of the commit (pre-existing unrelated dirty
  state — see constraints).

## Design notes / judgment calls

1. **Within-session (not cross-session) "prior setup" interpretation for the reconfigure
   banner.** `CaptureScreen`'s `SessionContent` only has access to the current session's
   `SessionDetailResponse` — there is no prior-session data loaded on this screen at all (no
   history lookup, no cross-session state). So "prior HT setup" can only mean "the most recently
   *encountered* HT exercise earlier in this same session's exercise list," walked in
   `session.groups` / `group.exercises` order. This is consistent with the shoe-swap-style
   sequential-comparison pattern already present in this loop for other features (per the brief:
   match the *style*, not the code). The tracking var (`prevHtSetup`) is a plain local `var`
   recomputed fresh every recomposition of `SessionContent`'s body (not `remember`-scoped) since
   it's a deterministic function of `session` data, not independent UI state — no staleness risk
   across recompositions.

2. **`htReconfigure` OR-semantics, and how it corrects the brief's placeholder test.** The brief's
   literal `htReconfigure_fires_when_only_plate_count_differs` test originally had its expected
   value marked as an unresolved TODO. The brief's own prose resolves this ambiguity explicitly:
   the design decision is an OR (config differs OR plates differ), not an AND and not
   config-only. So that test now asserts `assertNotNull(...)` — plates changed 205→210 with the
   *same* band config still fires the banner, because ANY exact difference in either dimension
   means the physical station needs to change. I implement the comparison with plain Kotlin
   `List<Int>? / Double?` equality (`config != prevConfig || plates != prevPlates`) — no
   tolerance/rounding on the plate delta, and order-sensitive on the band list (a genuinely
   reordered band selection is a genuinely different band choice, so it should fire too, per the
   brief). I also added the "both current values null → null, even if prev was set" guard
   (don't recommend reconfiguring TO an empty setup) and a couple of extra null-safety edge cases
   beyond the brief's literal 3, per the brief's own invitation to add 2-3 more at my discretion.

3. **Felt-peak input scoping: HT + working-role + current-set only.** The field is gated by
   `isCurrent && isHtSet && tapRequired` in `SetCard`. `isCurrent` matches the existing
   load/reps fields (no point collecting input for a set that isn't the active cursor position —
   nothing would consume it). `isHtSet` (`target_plates != null || band_config != null`) scopes
   it to sets that actually prescribe a band-composite setup — a plain barbell/plate set has no
   "felt peak" concept (there's no band tension curve to distinguish from the plate load).
   `tapRequired` (`WORKING`/`TOP`/`BACKOFF`) reuses the exact same role gate as the existing tap
   selector, per the brief's explicit instruction to keep it "consistent with how the existing
   tap selector is scoped" — warmup/other roles don't collect a felt-peak reading, matching how
   they don't collect a tap either.

4. **`composePlatesAndBands` factored out as a private shared helper**, called by both
   `htSetupLine` (which appends the peak suffix) and `htReconfigure` (banner text, no peak
   suffix) — per the brief's explicit instruction not to duplicate the plates+bands formatting
   logic in two places. It's `private` (not `internal`) since only `htSetupLine`/`htReconfigure`
   in the same file need it and the two public helpers are what the tests and other files
   consume; no direct test for the private helper — it's exercised transitively via
   `htSetupLine`'s and `htReconfigure`'s own tests (plates-only / bands-only / both-present
   cases cover its branches).

5. **`bandNames` uses `getOrNull` defensively**, per the brief's explicit instruction, rather than
   throwing on an out-of-range band id — a server sending an id past the local `BAND_NAMES` list
   (currently 6 entries, ids 0-5) degrades to silently dropping that one band's name from the
   composed string rather than crashing the screen. Test
   `bandNames_skips_out_of_range_ids_defensively` guards this.

6. **`SetLogDraft`/`SetLogIn.feltPeak` entity fields were untouched**, as instructed — both
   already existed with `feltPeak: Double? = null` (write-side, entity `feltPeak`, DTO
   `felt_peak`) before this task; only `logWorkingSet`'s new trailing param and the
   `SetLogDraft(...)` call site needed wiring.

## Concerns / open items

- **No phone/device available to verify visually** — this is a build+unit-test-only gate,
  matching the precedent set by the sibling shoe-cue task's report (per the task brief's own
  framing). The reconfigure banner, felt-peak input visibility gating, and setup-line rendering
  are exercised only through their extracted pure functions (`htReconfigure`, `htSetupLine`,
  `bandNames`) plus `assembleDebug` compiling the Compose call sites — not through an on-device
  or Compose UI test render. If an HT-exercise fixture becomes available for a future
  `SessionDetailResponse`-driven integration/screenshot test, that would close this gap.
- The server-side `band_config` shape (list of band ids, e.g. `[0]` / `[0,1]`) was taken as given
  from the task brief, not independently re-verified against the live server response — if the
  server ever sends more than the 6 named colors (`Orange, Red, Blue, Green, Black, Purple`),
  `bandNames` silently drops the unmapped id(s) rather than surfacing an error; this seemed
  correct per instruction #5 above but is worth flagging as a place where a future band-color
  addition on the server needs a matching client-side `BAND_NAMES` update or it will silently
  under-render.
- No new Gradle dependency was added, and `app/build.gradle.kts` was not touched, per constraint.

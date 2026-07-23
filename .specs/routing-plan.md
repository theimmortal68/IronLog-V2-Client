## Routing Plan
Generated: 2026-07-11

- .specs/01-autoregulated-background-rest-timer.md → gemini, worktree wt-01, depends on: none. Broader cross-module surface (RestTimer.kt, CaptureViewModel.kt, new RestTimerService.kt, AndroidManifest.xml, CaptureScreen.kt, RestAudio.kt, 2+ test files — 7+ files touched, new foreground-service infrastructure introduced from scratch): gemini per "broad cross-module" guidance.

Delegation ratio: 1/1 → gemini (100%)
Merge order: wt-01 standalone.

Notes:
- Combines build-plan items G (autoregulated rest) and D+E (background rest timer) — they're one feature client-side, not two (G's "hardest set governs duration" logic lives inside the same rest-timer path D+E's foreground service wraps).
- No server/DTO changes required — 100% client-side, standalone from IronLog-V2 (server) work.
- No existing foreground-service precedent in this repo — this introduces manifest permissions (`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` + type-specific permission) for the first time; review should pay particular attention to graceful degradation if `POST_NOTIFICATIONS` is denied (rest timing must still work in-app, not crash or silently break).
- No forbidden-boundary hits expected (no dependency upgrades, no build-logic changes beyond the manifest's own permission/service declarations, which are additive not structural) — confirm at `/verify-plan` regardless, don't assume.
- ~~H ("AI acts on programming notes")~~ — brainstorming session completed 2026-07-12; see the 2026-07-12 addendum below for the resulting client spec.

## 2026-07-11 addendum: finisher + warmup rendering

- ~~.specs/07-render-session-finisher.md~~ — MERGED + LIVE 2026-07-12 (codex-generated, Tier A committed after codex exited uncommitted). Pure client-side additive render.
- ~~.specs/08-render-session-warmup.md~~ — MERGED + LIVE 2026-07-12 (codex-generated, Tier A committed after codex exited uncommitted). Depended on server spec 11, dispatched only after confirmed live.

Notes:
- Both are pure display additions (no logging/submit-flow interaction) — same "informational block" pattern, placed at opposite ends of the Capture screen's LazyColumn (warmup first, finisher last).
- `params`/item dicts are heterogeneous per exercise/drill — both specs use `kotlinx.serialization.json.JsonObject` rather than forcing a rigid shape.

## 2026-07-12 addendum: side-aware unilateral edit

- .specs/09-side-aware-unilateral-edit.md → codex, worktree wt-09, depends on: none. Touches CaptureDao/CaptureRepo/CaptureViewModel/CaptureScreen (4 files, real logic — key-shape change from `Int` to `Pair<Int,Int>` for the logged-actuals map, editLoggedSet gains a sideIndex param, per-side card rendering). Not decomposed further: the data-flow is one connected thread (DAO query → repo → viewmodel state → screen rendering) that would just produce constant merge conflicts if split into separate worktrees — matches CLAUDE.md's "run as one sequential worktree" guidance for tightly-coupled work.

Delegation ratio: 1/1 → codex (100%)
Merge order: wt-09 standalone.

Notes:
- Real logic change (not mechanical) touching an existing safety-net refusal — routed through Opus review, not review-exempt.
- Bilateral sets (the common case) must see zero behavior change; the spec calls this out explicitly as a required regression check.

## 2026-07-12 addendum: "H" apply-wizard resolved proposals (post-brainstorming)

- .specs/10-apply-wizard-resolved-proposals.md → codex, worktree wt-10, depends on: `IronLog-V2/.specs/17-notes-review-resolved-proposals.md` merged + live (server `resolved_proposals` field doesn't exist yet). **Do not dispatch until confirmed live via a real curl against `/notes/review`.**

Delegation ratio: 1/1 → codex (100%)
Merge order: wt-10, after the full server batch (13→14→15→16→17) is live.

Notes:
- Pre-fills the existing `ApplyWizardDialog` from server-resolved proposals; `defaultSourceSlot`'s client-side heuristic stays as the fallback for when the server finds nothing — this spec does not delete it.
- A `valid=false` proposal must never be silently pre-selected as if safe — this is the client-side half of the resolver's core safety property (spec 16, server-side) and should get the same scrutiny at review.

## 2026-07-14 addendum: jump-rope warmup + finisher interval timers

- ~~.specs/14-interval-timer-service.md~~ → gemini, worktree wt-14 — MERGED + LIVE 2026-07-14 (097c4bb). New standalone `IntervalTimerService`/`IntervalTimerController` file + manifest entry + tests, no UI wiring. Opus review: no Critical/High; one Medium + two Low folded into a same-branch fix-up commit, re-verified green.
- ~~.specs/15-interval-timer-ui-wiring.md~~ → codex, worktree wt-15 — MERGED + LIVE 2026-07-14 (ad95508), depended on wt-14 merged. Bounded UI wiring (CaptureScreen.kt, CaptureViewModel.kt, test file). Opus review (2 passes): no Critical/High either pass; one Medium test-gap + one Low folded into a fix-up commit; two cosmetic Lows explicitly dismissed by Tier A (logged in the fix-up commit message).

Delegation ratio: 2/2 (100%)
Merge order: wt-14 → wt-15

## 2026-07-15 addendum: Capture screen vertical density

- ~~.specs/16-capture-screen-vertical-density.md~~ → codex, worktree wt-16 — MERGED + LIVE 2026-07-15 (63ebfe8). Single-file (CaptureScreen.kt) padding/spacing/line-merging pass. Opus review: no Critical/High/Medium; three Low notes accepted as-is (RPE line merge is an intentional density trade-off, not an oversight). Note "spec 16" here is this repo's client spec 16 and is unrelated to IronLog-V2 server's own spec 16 (note-resolver) referenced above — same number, different repo, different specs.

Delegation ratio: 1/1 (100%)
Merge order: wt-16 standalone.

## 2026-07-15 addendum: vertical density, remaining 9 screens (parallel batch)

Same density formula as spec 16, applied per-screen. All 9 specs are file-disjoint (each touches exactly one file, no two specs share a file) — genuinely parallel-safe, dispatched concurrently, merged serially with build/test before each merge.

- ~~.specs/17-today-screen-vertical-density.md~~ → codex, worktree wt-17 — MERGED + LIVE 2026-07-15 (04c04fd). `today/TodayScreen.kt`.
- ~~.specs/18-autoregulate-screen-vertical-density.md~~ → codex, worktree wt-18 — MERGED + LIVE 2026-07-15 (47fe5ad). `autoregulate/AutoregulateScreen.kt`.
- ~~.specs/19-history-screen-vertical-density.md~~ → codex, worktree wt-19 — MERGED + LIVE 2026-07-15 (62c17b3). `history/HistoryScreen.kt`.
- ~~.specs/20-history-detail-screen-vertical-density.md~~ → codex, worktree wt-20 — MERGED + LIVE 2026-07-15 (eb2a938). `history/HistoryDetailScreen.kt`.
- ~~.specs/21-movements-list-screen-vertical-density.md~~ → codex, worktree wt-21 — MERGED + LIVE 2026-07-15 (c669cc7). `movements/MovementsListScreen.kt`.
- ~~.specs/22-movement-detail-screen-vertical-density.md~~ → codex, worktree wt-22 — MERGED + LIVE 2026-07-15 (c24e024). `movement_detail/MovementDetailScreen.kt` — highest-impact of the batch (merges every `Field` row's label+value from 2 lines to 1, ~20 fields on this screen).
- ~~.specs/23-bands-screen-vertical-density.md~~ → codex, worktree wt-23 — MERGED + LIVE 2026-07-15 (cd36073). `bands/BandsScreen.kt`.
- ~~.specs/24-group-review-sheet-vertical-density.md~~ → codex, worktree wt-24 — MERGED + LIVE 2026-07-15 (5333d1f). `capture/GroupReviewSheet.kt` — same `capture` package as spec 16 but a different, standalone file; no overlap.
- ~~.specs/25-wizard-screen-vertical-density.md~~ → codex, worktree wt-25 — MERGED + LIVE 2026-07-15 (ae47b86). `wizard/WizardScreen.kt`.

Delegation ratio: 9/9 (100%)
Merge order: wt-17 → wt-18 → wt-19 → wt-20 → wt-21 → wt-22 → wt-23 → wt-24 → wt-25, all merged, build/tests green throughout. All 9 review-exempt (mechanical padding/spacing + one already-reviewed-pattern AnnotatedString restructure), routing reason logged in each merge/feat commit rather than running 9 near-identical Opus reviews.

Notes:
- Standalone from `RestTimerService`/`RestTimer.kt`/`RestAudio.kt` — neither spec modifies those files, both explicitly forbid it and instead reuse specific `internal fun`s and the `RestToneCue` class.
- No server/DTO changes required — all data (warmup item `seconds`, finisher `duration_minutes`/`target_reps_per_minute`/`work_seconds_per_minute`) already ships in the session response.
- New foreground-service manifest entry (second `<service>`, own channel id) — additive, mirrors the already-approved rest-timer service pattern from the 2026-07-11 addendum; confirm at `/verify-plan` it doesn't trip a forbidden-boundary hit regardless.

## 2026-07-15 addendum: ReviewScreen.kt (missed from the 9-screen batch)

User caught this: `ReviewScreen.kt` (653 lines — pending-change-proposals / apply-wizard screen, reachable from Today's "Review" action) was never enumerated into specs 17-25's screen list. Confirmed via `grep -rl "Scaffold(" ...` across the whole `ui/` tree that every other Composable-bearing file (`MainActivity.kt` — nav shell only, no dense content; `ErrorRetryBox.kt` — centered non-scrolling error view; `theme/Theme.kt` — no UI) is now accounted for; `ReviewScreen.kt` was the only real gap.

- ~~.specs/26-review-screen-vertical-density.md~~ → codex, worktree wt-26 — MERGED + LIVE 2026-07-15 (7ccc452). `review/ReviewScreen.kt` only (does not touch `ReviewLogic.kt`/`ReviewViewModel.kt` in the same package). Review-exempt, same formula as specs 16-25.

Delegation ratio: 1/1 (100%)
Merge order: wt-26 standalone.

## 2026-07-15 addendum: correction pass — the padding-only formula wasn't enough

Athlete feedback after installing specs 17-26: on-device it read as no visible change. Root cause: specs 19/21/23 (History/Movements/Bands) only trimmed padding/spacer dp values and left every `MaterialTheme.typography.*` token and every "optional" line-merge untouched — the actual dominant driver of row height (2-3 full text lines) never shrank. The original brainstorming conversation had approved "all of the above — be aggressive" including shrinking text sizes; that lever was dropped unilaterally when writing spec 21 ("do not change any typography token") without surfacing the narrowing back to the athlete.

- .specs/27-movements-bands-history-density-followup.md → codex, worktree wt-27, depends on: none (spec 27 supersedes/extends specs 19/21/23's work in the same three files). Steps a typography token down one level on the dominant secondary lines + forces the previously-optional subtitle/floor-cap merge in `MovementsListScreen.kt` (the clearest visible offender in the athlete's screenshot).

Delegation ratio: 1/1 (100%)
Merge order: wt-27 standalone.

## 2026-07-16 addendum: jump-rope lead-in countdown

- ~~.specs/28-jumprope-lead-in-countdown.md~~ → codex, worktree wt-28 — MERGED + LIVE 2026-07-16 (9e100c6). Adds an optional 5s "Get Ready" lead-in phase to `IntervalTimerService`'s single-countdown mode + wires the jump-rope Start call site. Opus review: no Critical/High/Medium findings.

Delegation ratio: 1/1 (100%)
Merge order: wt-28 standalone.

## 2026-07-16 addendum: extend lead-in to finisher timers

- ~~.specs/29-finisher-lead-in-countdown.md~~ → codex, worktree wt-29 — MERGED + LIVE 2026-07-16 (68c6395), depended on spec 28 merged (reused its `isLeadInPhase`/`COUNTDOWN_LEAD_IN_LABEL` mechanism for `RepBased`/`TimeBased`). Opus review: zero Critical/High/Medium findings.

Delegation ratio: 1/1 (100%)
Merge order: wt-29 standalone.

## 2026-07-21 batch: cardio-log client follow-on (server shipped 2026-07-21, docs/superpowers/specs (server repo) 2026-07-20-cardio-interval-day-design.md)

Client-side surface for the standalone cardio/interval day-type: logging a Z2 session (walk/treadmill), a weekly rollup on Today, and a simple history list. Verified live endpoint shapes via `curl` against production before writing these specs (`GET /cardio-log` → `[]`, `GET /cardio-log/weekly-summary` → `{"count":0,"target":2,"week_start":"2026-07-20"}`), matching this session's established pattern for client follow-ons on server-first features.

- ~~.specs/31-cardio-log-data-layer.md~~ → codex, worktree wt-31 — MERGED + LIVE 2026-07-21 (95de284). DTOs (`CardioModels.kt`) + `CardioLogRepo` + `AppContainer` wiring. Review-exempt (thin DTOs/repo, no branching logic, mirrors `NotesRepo`'s established pattern). Compile + tests clean, zero regressions.
- ~~.specs/32-cardio-log-entry-screen.md~~ → codex, worktree wt-32 — MERGED + LIVE 2026-07-21 (291bc51). Log-entry form + ViewModel + registers `Routes.CARDIO_LOG`. **Opus review**: no Critical/High. Two Low notes, not fixed: (1) toggling Treadmill→Walk before submit still sends any already-entered `incline_pct`/`backward_walk_done` alongside `modality="WALK"` — a spec gap (didn't address submit-time gating), not a code defect, filed as a future follow-up; (2) non-numeric optional fields (e.g. `avg_hr="132x"`) silently map to `null` rather than surfacing a validation error — acceptable for a simple log. 6/6 new tests passing (95 total).
- ~~.specs/33-today-cardio-rollup.md~~ → codex, worktree wt-33 — MERGED + LIVE 2026-07-21 (fa9ed4a). Today-screen "🏃 Cardio: N/2 this week" rollup line, tappable → `Routes.CARDIO_LOG`. Review-exempt (thin ViewModel wiring + a rendering condition, mirrors the existing `reviewCount` pattern exactly — verified directly against the spec by Tier A). Known accepted limitation: rollup doesn't auto-refresh on return from the log screen, only on the next natural `load()`. Zero regressions (252 tests).
- ~~.specs/34-cardio-log-history-screen.md~~ → codex, worktree wt-34 — MERGED + LIVE 2026-07-21 (6676e7e). History list screen + `Routes.CARDIO_HISTORY`; adds a "History" top-bar button to `CardioLogScreen.kt`. Review-exempt (mirrors `HistoryScreen`/`HistoryViewModel` exactly, no novel logic). Zero regressions (252 tests).

**All 4 specs in this batch are MERGED and LIVE as of 2026-07-21 (main @ e365693, 252 tests passing). The standalone cardio/interval day-type feature is now fully end-to-end — server model+endpoints (specs 44-45 in the server repo) + client data layer, log entry form, Today-screen rollup, and history list.**

**Deliberately fully sequential, not parallel-then-serial-merge** — unlike most batches this session. `Nav.kt` and `MainActivity.kt` are touched by 3 of these 4 specs (32, 33, 34) to register/wire routes; running any of them concurrently would race the same nav-graph edits even though each touches a different region of the file. Per CLAUDE.md's decomposition guidance ("if you can't find a decomposition that avoids constant merge conflicts, run it as one sequential worktree"), the correct call here is sequential dispatch of 32→33→34 (each waits for the prior to merge before its OWN worktree is even created), not concurrent generation with a hoped-for clean rebase.

Delegation ratio: 4/4 (100%)
Merge order: 31 → 32 → 33 → 34 (strictly sequential — no parallel generation in this batch).

Notes:
- No HUMAN GATE anywhere in this batch — pure client UI/data-layer work, no schema/auth/build-logic/public-API-surface changes (the server-side public surface was already finalized in specs 44-45).
- Review routing: spec 31 (thin DTOs/repo, no branching logic) is likely review-exempt; specs 32-34 (real UI state machines + user input validation) should get at least a light Opus pass given this session's carry-forward-bug history in this exact `ui/screens/` tree — use judgment at dispatch time based on the actual diff, but default to reviewing rather than skipping for anything with non-trivial logic (spec 32's `buildCardioLogCreate` validation function especially).
- No Room/local-DB, no interval-timer, no offline-capture needed for this feature — confirmed in spec 31's own edge-cases section.

## 2026-07-22 addendum: cardio-log form hardening (spec 32's deferred Low findings)

- ~~.specs/35-cardio-log-form-hardening.md~~ → codex, worktree wt-35 — MERGED + LIVE 2026-07-22 (296d5fe). Fixes both deferred Low findings from spec 32's Opus review: (1) gates incline/backward-walk-done to TREADMILL modality at submit time; (2) non-numeric optional-field input now correctly rejects instead of silently discarding as null. Review-exempt (mechanical extension of an already-reviewed pure function). 9/9 `CardioLogScreenLogicTest` passing (up from 6), zero regressions.

Delegation ratio: 1/1 (100%)
Merge order: wt-35 standalone. No HUMAN GATE (no Forbidden-list hit).

## 2026-07-23 batch: Phase-1 client parity (design approved via brainstorming, docs/superpowers/specs/2026-07-23-phase1-client-parity-design.md)

Four server features shipped over the past week with zero client visibility: daily readiness + phase-gate confirmation, weak-point assessment, missed-workout handling, and goal-driven phase thresholds. One combined design doc (not four separate brainstorming cycles) since all four share the identical shape already established this session (Today-rollup StateFlow + a repo mirroring CardioLogRepo + a detail screen pushed from Today).

- .specs/36-readiness-checkin-phase-gate.md → codex, worktree wt-36, depends on: none. New `ReadinessRepo`/DTOs, a check-in card on Today, a phase-transition-confirmation banner driven by `SubmitResponse.phase_transition_available` (a field the client's DTO doesn't even declare yet — a real gap, not just an unused field). Touches `CaptureViewModel.kt` (new constructor param) in addition to the usual `TodayViewModel.kt`/`AppContainer.kt`.
- .specs/37-weak-points-display.md → codex, worktree wt-37, depends on: 36 merged (shared-file sequencing, not a data dependency — see below). New `WeakPointsRepo`/DTOs, Today rollup badge, muscle-group-grouped detail screen.
- .specs/38-missed-days-display.md → codex, worktree wt-38, depends on: 37 merged (same reason). New `MissedDaysRepo`/DTOs, Today rollup badge, detail screen with acknowledge/reschedule actions (full parity with the server's write endpoints, per the user's choice — not display-only).
- .specs/39-goals-settings-screen.md → codex, worktree wt-39, depends on: 38 merged (same reason). New `GoalsRepo`/DTOs, a new 7th bottom-nav tab ("Settings") housing a Goals view/edit form.

Delegation ratio: 4/4 (100%)
Merge order: 36 → 37 → 38 → 39 — strictly sequential, not parallel-then-serial-merge. All four touch `TodayViewModel.kt`/`TodayScreen.kt`/`AppContainer.kt` for their own rollup/route wiring (36 additionally touches `CaptureViewModel.kt`; 39 additionally touches `MainActivity.kt`'s `TABS` list) — no data dependency between the four features themselves, but concurrent generation would race the same shared files, mirroring this repo's own established call on the cardio-log batch (31→32→33→34, sequential for the identical reason).

Notes:
- No HUMAN GATE anywhere in this batch — pure client UI/data-layer work, no schema/auth/build-logic/public-API-surface changes (all four server-side surfaces are already finalized and live).
- Review routing: spec 36 touches a cross-screen state bridge (`AppContainer.pendingPhaseTransition`, mirroring the existing `autoregPrefill` pattern) and a `CaptureViewModel` constructor change on a screen with this session's own carry-forward-bug history — route through Opus review, not review-exempt. Specs 37/38/39 are closer to the cardio-log batch's own shape (repo/DTOs + a display screen, one with write actions) — use judgment at dispatch time based on the actual diff, default to reviewing anything with non-trivial logic (spec 39's `buildGoalSettingsUpdate` validation function especially, given its direct lineage from spec 32/35's own review history).
- Two open questions flagged directly in the specs themselves for the implementer to resolve against the real server code before finalizing (not left as guesses): whether `POST /goals`'s partial-upsert treats an explicit JSON `null` differently from an omitted field (spec 39), and whether Ktor's `.body<GoalSettingsOut?>()` cleanly deserializes a bare `null` response (spec 39).

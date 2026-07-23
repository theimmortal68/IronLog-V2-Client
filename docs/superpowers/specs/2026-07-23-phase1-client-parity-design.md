# Phase-1 Client Parity — Design

## Problem

Four server features have shipped and been live for days-to-weeks with zero client visibility:

- **Daily readiness + phase-gate** (server: 2026-07-18) — `GET /readiness/today`, `POST /readiness`, `POST /engine-state/confirm-phase`, and `SubmitResponse.phase_transition_available` make the CUT→STAB/STAB→REBUILD phase gate real, but nothing in the app lets the athlete check in or confirm a transition.
- **Weak-point assessment** (server: 2026-07-19) — `GET /weak-points` computes a real stall/lagging signal per movement, but nothing displays it.
- **Missed-workout handling** (server: 2026-07-20) — `GET /missed-days` plus acknowledge/reschedule actions, invisible in-app.
- **Goal-driven phase thresholds** (server: 2026-07-19) — `GET`/`POST /goals` lets the athlete set weight/body-fat targets, but there's no screen to do it.

All four are read-mostly display gaps in the same app (`IronLog-V2-Client`, Kotlin/Compose). Confirmed via live `curl` against production before writing this doc (see each feature section for the real shapes returned).

## Why one combined design

All four share the identical shape already established three times in this app this session: a best-effort rollup `StateFlow` fetched in `TodayViewModel.load()` (never surfaces an error, mirrors `reviewCount`/`cardioWeeklySummary`), a thin per-feature repo mirroring `CardioLogRepo` exactly, and a detail screen pushed from Today via its own `Routes` entry. Running four separate full brainstorming cycles would re-confirm the identical approach four times; this doc settles the shared architecture once and the feature-specific decisions (already made via clarifying questions) once each, then each feature ships as its own spec batch in the existing dispatch pipeline (`/spec` → `/verify-plan` → `/route-plan`), not as one giant spec.

## Shared Architecture

- **Repo per feature**: `ReadinessRepo`, `WeakPointsRepo`, `MissedDaysRepo`, `GoalsRepo` — each a thin Ktor wrapper (`get`/`post` returning `Result<T>` via `runCatchingApi`), same shape as `CardioLogRepo`.
- **DTOs**: mirror the server's Pydantic schemas field-for-field (confirmed exact shapes below per feature).
- **Wiring**: each repo added to `AppContainer`, constructed the same way `cardioLogRepo` is.
- **Today rollups**: `TodayViewModel` gains one new `StateFlow` per feature (readiness check-in state, weak-point count, missed-days count), fetched alongside the existing `refreshReviewCount()`/`refreshCardioSummary()` calls in `load()` — best-effort, no error surfaced, consistent with the existing two.
- **Detail screens**: new `Routes` entries, reached by tapping the relevant Today rollup — not new bottom-nav tabs (matching how Cardio/History/Review already work), except Settings (below).
- **New bottom-nav tab**: `Settings` (7th tab), added to `MainActivity.kt`'s `TABS` list, housing Goals as its first section — room for future settings without another one-off screen.

## Feature 1: Readiness Check-In + Phase-Gate Confirmation

**Live shape** (`GET /readiness/today`): `{"date":"2026-07-23","bodyweight":216.68,"resting_hr":null,"sleep_ok":null,"subjective_ok":null}`. `bodyweight` is already populated (Withings sync); `resting_hr`/`sleep_ok`/`subjective_ok` have no automatic source and stay permanently `null` without a real input path — this is why a display-only view was rejected in favor of an actual check-in form (confirmed with the user).

`DailyReadinessIn` (submitted via `POST /readiness`): `{bodyweight?, resting_hr?, sleep_ok?, subjective_ok?}` — all optional, partial-upsert semantics (matches the server's established pattern for this endpoint).

**UI**: an inline expandable card directly on the Today screen (not a separate screen — the fields are small: two toggles + one optional number). Shows today's check-in state; if `sleep_ok`/`subjective_ok` are both already non-null for today's date, the card collapses to a compact "checked in" summary; otherwise it's expanded with input controls. Submitting calls `POST /readiness` with whatever fields are set.

**Phase-transition banner**: `SubmitResponse.phase_transition_available` (a string naming the target phase, e.g. `"REBUILD"`, or `null` if none) is returned by the Capture screen's submit call, not by a GET — so `CaptureViewModel` stores it into a new `AppContainer.pendingPhaseTransition: MutableStateFlow<String?>` on a successful submit, mirroring the existing `autoregPrefill` cross-screen-state pattern already in this codebase (`container.autoregPrefill.value = id` in `MainActivity.kt`). Today reads this flow directly (no repo call needed — it's already in memory) and shows a dismissible banner "Ready to move to `<phase>` — Confirm?" the next time Today loads. Tapping Confirm calls `POST /engine-state/confirm-phase` with `{"to_phase": "<phase>"}` (`ConfirmPhaseRequest`), then clears the container flow. Dismissing without confirming also just clears the flow, without calling the endpoint — nothing auto-applies. Since this is a purely in-memory flag with no server-side persistence of "pending," a dismissed (or app-killed) banner won't reappear until the athlete's next qualifying session submit re-derives `phase_transition_available` — the underlying gate condition itself doesn't disappear, only this specific banner instance. Flagged here explicitly as a known characteristic, not a silent limitation.

## Feature 2: Weak-Points Display

**Live shape** (`GET /weak-points`): `{"muscle_groups":[{"muscle":"ABS","weak_count":0,"total_count":2,"weak_movements":[]}, ...], "movements":[{"movement_id":3,"name":"Belt Squat [GHR + FT]","stalled":false,"lagging":false,"growth_rate":null}, ...]}`.

**UI**: Today rollup badge, shown only when `sum(weak_count across muscle_groups) > 0` (currently 0 everywhere — the badge simply doesn't render until something's actually flagged). Detail screen groups movements under muscle-group headers matching the API's own `muscle_groups` shape (per the user's choice over a flat list) — each header shows `weak_count`/`total_count`, with a sub-list of that group's `weak_movements` showing `stalled`/`lagging` tags. The top-level `movements` array (the full unfiltered list) is not separately displayed — the muscle-group `weak_movements` sub-lists are the only movement-level detail surfaced, since that's what the athlete needs to act on.

## Feature 3: Missed-Days Display + Actions

**Live shape** (`GET /missed-days`): `[]` currently; each record when present is `{id, program_day_id, day_role, week_start_date, detected_at, status}`.

**UI**: Today rollup badge, count of records with `status` not yet resolved (i.e. excluding `"RESOLVED"`). Detail screen lists each record (`day_role`, `week_start_date`) with two buttons: Acknowledge (`POST /missed-days/{id}/acknowledge`) and Reschedule (`POST /missed-days/{id}/reschedule`) — both simple no-body POSTs (the server's reschedule endpoint doesn't take a new date, just flips status to `RESCHEDULED`; no date picker needed). Per the user's choice, this includes the write actions, not just display.

## Feature 4: Goals Settings Screen

**Live shape** (`GET /goals`): `null` currently (no goal configured) — the screen must handle this empty state, not assume a row always exists. `GoalSettingsOut`: `{target_bodyweight, target_bodyweight_tolerance, target_body_fat_pct?, target_body_fat_pct_tolerance?, updated_at}`. `GoalSettingsIn` (submitted via `POST /goals`): all four numeric fields optional, partial-upsert.

**UI**: new **Settings** bottom-nav tab (7th tab in `MainActivity.kt`'s `TABS`), Goals as its first section. Shows the current goal values (or an explicit "No goal set" empty state) with an edit form for all four fields, submitting via `POST /goals`. No other settings sections exist yet — this tab exists so future settings-like features (if any) have a home without another one-off screen, per the user's own reasoning for this choice over a standalone Goals screen.

## Edge Cases

- **Readiness**: submitting the check-in form twice in one day is a partial-upsert (server-side, already established) — no client-side dedup needed. A `null` `bodyweight` (Withings sync hasn't run yet today) still allows submitting `sleep_ok`/`subjective_ok` independently.
- **Phase-transition banner**: if the app is killed before the athlete acts on the banner, `AppContainer.pendingPhaseTransition` is in-memory only and resets to `null` on next launch — the banner simply won't reappear until the next qualifying submit. Acceptable (matches the in-memory `autoregPrefill` precedent, which has the same characteristic).
- **Weak-points**: a muscle group with `weak_count == 0` still appears in `muscle_groups` (it's a complete rollup, not filtered) — the detail screen should skip rendering a header for groups with `weak_count == 0`, showing only groups with something to report.
- **Missed-days**: acknowledging or rescheduling a record already in a terminal state (`RESOLVED`) is allowed server-side (harmless churn, the nightly job is authoritative) — the client doesn't need to hide the buttons for resolved records, though the detail list itself only shows non-resolved records per the rollup's own filter, so this mostly can't arise from normal use.
- **Goals**: the edit form must not silently submit `0` for an intentionally-blanked optional field (`target_body_fat_pct`) — blank means "don't set this," not "set to zero," mirroring the cardio-log form-hardening fix (spec 35) shipped earlier tonight for the same class of bug.

## Testing

- Unit tests per repo (mirroring `CardioLogRepo`'s own test coverage, if any exists — verify at spec-writing time) and per new pure logic function (e.g. a `hasCheckedInToday(readiness: DailyReadinessOut): Boolean` helper, a `weakPointBadgeCount(assessment: WeakPointAssessmentOut): Int` helper) — these are exactly the kind of pure, file-level, unit-testable functions this codebase already establishes (`classifyGenerate`, `reviewButtonLabel`).
- Integration/ViewModel tests for the new `TodayViewModel` StateFlows (best-effort, no error surfaced — mirroring the existing `refreshReviewCount`/`refreshCardioSummary` test coverage pattern).
- Form-validation tests for the Goals edit form and the Readiness check-in card, following spec 35's established pattern (reject non-numeric input, gate optional fields correctly).

## Scope Split (each ships as its own spec batch)

1. **Readiness + phase-gate** — repo/DTOs, Today check-in card, phase-transition banner + confirm wiring.
2. **Weak-points** — repo/DTOs, Today badge, muscle-group-grouped detail screen.
3. **Missed-days** — repo/DTOs, Today badge, detail screen with acknowledge/reschedule actions.
4. **Goals** — repo/DTOs, new Settings tab + Goals section (view + edit form).

Order is not strictly dependent between the four (no shared files except `TodayViewModel.kt`/`MainActivity.kt`, which every batch touches for its own rollup/route — sequenced, not parallelized, same call as the cardio-log batch's own "touches shared nav/wiring files → run sequential, not concurrent" decision).

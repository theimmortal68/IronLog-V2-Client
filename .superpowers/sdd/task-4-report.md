# Task 4 Report — Client: apply confirm-wizard + Active-adjustments rewrite

**Note:** the pre-existing `.superpowers/sdd/task-4-brief.md` in this repo is stale — it documents an
unrelated earlier feature (GroupReviewSheet for the capture flow, already shipped). The real Task 4
for the note-apply redesign lives in `~/projects/IronLog-V2/docs/superpowers/plans/2026-07-05-note-apply-redesign.md`
(§Task 4) + `docs/superpowers/specs/2026-07-05-note-apply-redesign-design.md` (§4/§5), and that's what
this report covers. Server tasks 1-3 were already merged (d0b6c00, 991cff0, ef2975a) before this task started.

## What changed

**DTOs** (`data/api/dto/NotesModels.kt`):
- `NoteReviewOut` gains `action_type: String? = null` (forward-compat — see caveat below).
- New `ProgramSlotOut` (matches `GET /programs/{id}/slots` verbatim).
- New `ApplyOverrideRequest` (matches `POST /notes/{id}/apply` body verbatim) replaces the old
  `ApplyNoteRequest(target_movement_id)`.
- `OverrideOut` generalized: `override_type` + `movement_name` (base) + type-specific nullable
  fields (`to_movement_name`, `load_delta`, `load_absolute`, `rep_low`, `rep_high`) +
  `source_note_text`. Replaces the old MOVEMENT-only `from_movement_name`/`to_movement_name` shape.

**Repo** (`data/repo/NotesRepo.kt`): `applyOverride(noteId, ApplyOverrideRequest)` replaces `apply(id, targetMovementId)`;
added `programSlots(programId)`; `overrides()` unchanged in signature (return type generalized via the DTO).

**Pure logic + tests** (`ui/screens/review/ReviewLogic.kt` + `ui/review/ReviewLogicTest.kt`, 23 tests, all new/updated):
- `AdjustmentKind` enum (SWAP/LOAD/REPS/NONE).
- `adjustmentKind(actionType, actionText)` — maps the classifier enum when present; keyword
  fallback on free-text `action` when absent; explicit `"OTHER"` is NONE with no fallback.
- `defaultSourceSlot(subject, slots)` — case-insensitive substring match of the subject against
  slot movement names, ties broken by longest (most specific) match; null/blank/no-match → null.
- `showApply`/`showConfirm` re-gated: CONFIG_CHANGE + kind != NONE → Apply only; CONFIG_CHANGE +
  NONE → neither (Dismiss only, per design §4 "OTHER/unclassifiable → no Apply (Dismiss only)");
  non-CONFIG_CHANGE → Confirm only (unchanged).
- Updated the old `ReviewLogicTest` fixtures/assertions to match the new gating (the old "swap
  always shows Apply" test is superseded — Apply is now adjustment-kind-gated, not just
  classification-gated) and extended `NotesDtoTest` for the generalized `OverrideOut` (MOVEMENT/
  LOAD/REPS decode + defaults).

**ViewModel** (`ReviewViewModel.kt`): new `ApplyWizardState(note, kind, slots, selectedSlot,
slotsLoading, submitting)` + `wizard: StateFlow<ApplyWizardState?>`. `openApply(note)` routes the
kind, fetches `/programs/{DEFAULT_PROGRAM_ID}/slots`, pre-selects via `defaultSourceSlot` (falls
back to the first slot if no match, so the wizard is never stuck slot-less), warms the movement
list for SWAP. `selectSlot`/`closeWizard`/`submitSwap`/`submitLoad`/`submitReps` build the explicit
`ApplyOverrideRequest` and POST via `applyOverride`; success reloads both the pending list and
overrides and closes the wizard.

**Screen** (`ReviewScreen.kt`): `ApplyWizardDialog` — title "Change &lt;movement&gt;", a "Slot: D·T·Movement
[Change]" row opening a `SlotPickerDialog` (lists all `ProgramSlotOut`), then the action-routed body:
SWAP → "Pick movement" reusing the existing `MovementPickerDialog`; LOAD → `[+5][+10][+15]` delta
buttons + a "set exact" field/button (mutually exclusive, matches the server's
exactly-one-of-delta/absolute constraint); REPS → low/high fields + Apply. `OverrideCard` rewritten
to render `overrideSummaryLine` per type ("Bench → Incline DB", "Hip Thrust +10 lb" / "Hip Thrust
set 225 lb", "Squat 5–8 reps") plus `source_note_text` provenance and Revert. Section header renamed
"Active swaps" → "Active adjustments".

## Adaptations from the plan

- **`action_type` is not actually exposed by `/notes/review` yet** — verified directly against the
  live server code (`ironlog/api/app.py`, `NoteReviewOut` model, lines ~338-346): it extracts
  `proposed_change` and `confidence` from `classification_meta` but not `action_type`, even though
  the classifier (server Task 2) does persist `action_type` into `classification_meta`. Per the
  design doc's own back-compat clause ("the client falls back to a keyword heuristic... or simply
  offers the source-slot picker + all three adjustment types"), I kept the client's `action_type`
  field (nullable, currently always null in practice) and rely on the keyword fallback for all
  live notes today. This is a server-side gap outside this task's scope (client-only) — flagging it
  as a concern below rather than editing app.py.
- Chose an `AlertDialog`-based wizard (consistent with the existing `MovementPickerDialog` pattern
  in this file) rather than a `ModalBottomSheet` — the brief didn't mandate a specific component and
  this repo's existing Review dialogs are all `AlertDialog`.
- `defaultSourceSlot` falls back to `slots.firstOrNull()` in the ViewModel (not the pure function
  itself) when there's no subject match, so the wizard always has *some* slot selected rather than
  stranding the athlete with an unusable "none selected" state — the athlete can still change it via
  the slot picker.

## Follow-up: harden `adjustmentKind` keyword fallback (review finding)

Review flagged that the keyword fallback (used only when `action_type` is absent — old/pre-Task-2
notes) mis-scored rep-target phrasing: the server's own REP_CHANGE example "drop OHP to 3x8" hit no
REPS keyword → fell through to NONE (Apply hidden entirely); "increase reps" was swallowed by the
LOAD "increase" keyword; "change to X" could catch rep phrasing via SWAP. Fixed (TDD — tests added
first, confirmed the current fallback failed "drop OHP to 3x8", then fixed):
- Added `REP_SCHEME_REGEX` (`\d+\s*[x×]\s*\d+`, e.g. "3x8", "3 x 8", "5X5") + "sets" to the REPS
  keyword set.
- Reordered `keywordAdjustmentKind` so REPS (NxM / "rep" / "sets") is checked **before** LOAD and
  SWAP, so rep phrasing isn't swallowed. The required LOAD/SWAP phrasings ("too light", "increase
  weight", "switch to X", "swap for X") carry no rep signal and fall through correctly.
- Verified: "drop OHP to 3x8"/"3 x 8"/"5X5"/"increase reps"/"more reps"/"change the rep target" →
  REPS; "too light"/"increase weight"/"too heavy" → LOAD; "switch to incline"/"swap for X"/"change
  to incline press" → SWAP. `action_type` (the enum) remains the unchanged FIRST-priority path.
- ReviewLogicTest: 23 → 25 tests.

## Follow-up: two apply-wizard UX gaps (whole-branch review)

1. **LOAD had no negative-delta option.** `ReviewScreen.kt` LOAD block offered only `[+5][+10][+15]`
   — a LOAD_DECREASE note ("too heavy") had no quick way down. Changed the delta row to
   `[-10][-5][+5][+10]` (signed labels; server accepts negative `load_delta`); still sends exactly
   one of load_delta/load_absolute, set-exact field unchanged. (Dropped +15 to keep the four-button
   row balanced around zero.)
2. **No-subject-match silently pre-selected the first slot.** `ReviewViewModel.openApply` did
   `defaultSourceSlot(...) ?: slots.firstOrNull()`, so an unmatched subject defaulted to slot #1
   (often bench). Removed the fallback — on no match the source slot is left UNSELECTED; the wizard
   shows "No program slot selected." and gates Apply (the adjustment inputs render only when a slot
   is selected; `submitApply` also returns early on null slot) until the athlete picks via the
   always-present "Change" slot picker. A real subject match still pre-selects as before.
   `defaultSourceSlot`'s null-on-no-match is already covered by existing pure tests; the wiring
   change is build-gated.

## Build/test

- `./gradlew :app:testDebugUnitTest --tests "*ReviewLogic*"` → BUILD SUCCESSFUL, 25 tests, 0 failures.
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- `./gradlew :app:testDebugUnitTest` (full suite) → BUILD SUCCESSFUL, all green.

## Concern

The server's `/notes/review` endpoint does not surface `action_type` from `classification_meta`,
so every note currently routes through the client's keyword fallback rather than the deterministic
classifier enum. This still works (the fallback covers the SWAP/LOAD/REPS/NONE cases with
reasonable keyword sets) but is a latent inconsistency between what the classifier computes and
what the client can see — worth a small server follow-up (`NoteReviewOut.action_type` +
`meta.get("action_type")`) to fully realize the design's "deterministic routing" intent.

## Not done (out of scope / deferred)

- On-device smoke test (phone reachability not verified in this session — same deferral pattern as
  prior tasks in this repo).

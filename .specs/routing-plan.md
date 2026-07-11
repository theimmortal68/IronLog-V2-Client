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
- H ("AI acts on programming notes") is intentionally NOT in this routing plan — see `~/projects/IronLog-V2/.specs/10-ai-acts-on-notes-scoping.md` (a scoping doc, not a dispatch-ready spec; needs a brainstorming session first).

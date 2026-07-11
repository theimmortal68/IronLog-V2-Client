# Spec 04: Fix the ~20s notification delay + add a colored background to the countdown icon

## Objective
Fix a real on-device latency bug (the rest-timer notification/icon takes ~20 seconds to appear on the second-and-later rest of a session, confirmed via user report) and add a colored circular background behind the countdown digit to match the visual style of the Android Clock app's own timer icon (per user request/reference screenshot).

## Background — confirmed via research (2026-07-11)
Two independent, real mechanisms plausibly combine to produce the ~20s delay:

1. **Documented Android 12+ behavior**: the OS can delay the INITIAL display of a foreground service's notification by up to 10 seconds (a deliberate UX optimization for very-short-lived services), unless the app explicitly opts out via `NotificationCompat.Builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)`. This code currently does not set this — every `buildNotification()` call is subject to the default (delayable) behavior.
2. **Likely OEM/SystemUI-level throttling of frequent bitmap-icon updates specifically** (not officially documented as a fixed interval, but confirmed as a real, expected category of behavior: Android's own per-package notification update rate limit is ~10/sec with a recommended ceiling of ~5/sec, and OEM skins are free to apply additional throttling/coalescing on top for expensive redraws like a freshly-rendered bitmap every second — this is NOT guaranteed-reliable behavior on any OEM per official Android documentation). The notification's chronometer TEXT (shown in the pulled-down shade) is unaffected because it's rendered natively by the OS from a timestamp, not re-drawn by the app on each tick — this matches the user's own observation that the shade's countdown was accurate even when the status-bar icon lagged.

**The fix is NOT "make it faster" — official guidance is the opposite: treat per-second icon bitmap updates as inherently unreliable across OEMs, and design for a slower, more conservative update cadence for the ICON specifically**, while leaving the underlying countdown state (and the chronometer, which already ticks accurately every second) untouched.

## The fix
1. **Add `.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)`** to `buildNotification()` in `RestTimerService.kt` — a one-line, officially-documented fix for the up-to-10s initial-display delay.
2. **Decouple the icon-bitmap update cadence from the 1-second countdown tick.** The countdown itself (`_remainingSeconds`, the chronometer, the underlying coroutine loop) stays on its existing 1-second cadence — do not slow down the actual timer. Only the DECISION of when to regenerate the bitmap icon and call `notify()` should move to a more conservative interval (e.g., every 3 seconds, or on some other reasonable schedule — implementer's call, but justify the choice given the "no per-second guarantee, ≤5/sec ceiling, be well below that for icon-specific redraws" guidance). `shouldRefreshRestNotification`'s current `previous != current` (every second) needs to change to reflect this new, icon-appropriate cadence — but the LAST-SECONDS "urgency" window (final ~15s, matching the existing WARNING/TICK tone thresholds) may reasonably still update every second if desired, since that's a short, bounded burst rather than sustained per-second churn for an entire multi-minute rest.
3. **Add a colored circular background behind the countdown digit.** In `renderCountdownIcon()`, before drawing the text, draw a filled circle (or the full bitmap canvas, whichever looks correct at small icon size) using the app's existing brand color (`#1F3A93`, already used in `ic_launcher_background.xml` — reuse this exact value for visual consistency with the rest of the app, don't invent a new color) behind the white digit, rather than the current plain white-text-on-transparent rendering.

## File targets
- Modify: `app/src/main/java/com/jauschua/ironlogv2/service/RestTimerService.kt` — `buildNotification()` (add `setForegroundServiceBehavior`), `shouldRefreshRestNotification()` (revise the icon-update cadence per point 2 above), `renderCountdownIcon()` (add the colored circular background per point 3).
- Modify/extend: `app/src/test/java/com/jauschua/ironlogv2/service/RestTimerServiceLogicTest.kt` — update any test asserting the old per-second `shouldRefreshRestNotification` behavior to the new cadence; the actual Canvas circle-drawing is not independently unit-testable (Android-framework-coupled), but if the cadence logic is a separate pure function, test that directly.

## Edge cases
- **The chronometer and underlying countdown state must remain exactly per-second** — only the icon-bitmap regeneration/notify() cadence changes. Do not accidentally slow down `_remainingSeconds`'s own tick rate or the tone-trigger logic (`restTimerToneForTransition` at 15/3/2/1/0 seconds) — those must stay exactly as-is.
- **The final-seconds urgency window** (matching existing tone thresholds) may reasonably keep per-second icon updates if that's a deliberate design choice for the "almost done" moment — state clearly in code comments whichever cadence choice is made and why, don't leave it unexplained.
- **Colored circle at small icon size**: verify the circle-plus-digit combination is still legible at actual rendered notification-icon size (same concern as spec 03's text-fit logic) — the circle must not crowd out the digit or make it harder to read than the current plain-white version.
- **`FOREGROUND_SERVICE_IMMEDIATE` availability**: confirm this constant/method is available at this repo's `minSdk=26` (check `NotificationCompat`'s compat-library behavior on pre-Android-12 devices — it should be a safe no-op on OS versions where the underlying delay behavior doesn't exist, but verify rather than assume).

## Dependencies
Builds on spec 03 (already merged) — branch from spec 03's post-merge `main`. No schema/API change, no HUMAN GATE required.

## Verification
- Updated/new unit test(s) for the revised icon-update cadence logic (if extracted into a testable pure function).
- Full unit suite green: `./gradlew :app:testDebugUnitTest --rerun-tasks`.
- Manual (on-device, primary verification — cannot be substituted by a unit test): start a rest, confirm the notification/icon appears promptly (not ~20s late) on the SECOND and later rests of a session, not just the first; confirm the icon now shows a colored circular background behind the digit; confirm the chronometer in the pulled-down shade still ticks accurately every second regardless of the icon's own update cadence.

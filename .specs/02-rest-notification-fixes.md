# Spec 02: Fix rest-timer notification — drifting dual clocks + status-bar dot

## Objective
Fix two real bugs found in on-device testing of spec 01's rest-timer notification: (1) the notification shows two independent, visibly-drifting time displays instead of one countdown; (2) the notification shows as a bare dot in the status bar instead of a proper icon on at least one OEM skin (Samsung/One UI, confirmed via screenshot).

## Background — confirmed root cause (2026-07-11, on-device screenshot)
`RestTimerService.kt`'s `buildNotification()` sets `.setUsesChronometer(true)` + `.setChronometerCountDown(true)` with `.setWhen(...)` — Android's native chronometer widget, which self-ticks every second independent of any app code, rendered inline next to the notification title. Separately, `restTimerNotificationContent()` sets a `text` field (`"${formatRestTime(remainingSeconds)} remaining"`) that is a SECOND, manually-maintained time display — updated only when `refreshNotification()` actually calls `NotificationManager.notify()`, which is throttled by `shouldRefreshRestNotification()` (`current <= 15 || current % 5 == 0` — i.e. only every 5 seconds above the final 15-second window).

**Confirmed live via screenshot**: the notification showed `"Rest timer  01:03"` (title + native chronometer, correct, ticking every second) alongside `"1:05 remaining"` (the throttled custom text) — two seconds apart and visibly disagreeing. This is the "counts down by 5 seconds" symptom: the chronometer IS ticking correctly every second, but the athlete's eye lands on the OTHER number (`text`), which only updates every 5 seconds and now visibly contradicts the chronometer.

The dot-in-status-bar issue: `ensureNotificationChannel()` creates the channel at `NotificationManager.IMPORTANCE_LOW`. Some OEM notification-shade implementations (One UI/Samsung, per the device in the screenshot) suppress the status-bar icon for low-importance channels down to a bare dot rather than showing the app's icon, even though the notification itself is `setOngoing(true)` and fully functional once the shade is pulled down.

## The fix
1. **Remove the redundant custom "remaining" text entirely** — the chronometer already IS the correct, always-accurate, natively-ticking countdown; there is no need for a second, manually-throttled time display that can only ever drift from it. Replace `RestTimerNotificationContent.text` with a static, state-descriptive string (e.g. `"Tap to return to your workout"`) that doesn't attempt to duplicate the countdown. This eliminates the drift entirely rather than just tightening the throttle — the throttle itself (`shouldRefreshRestNotification`) can stay as-is (or be simplified) since it no longer needs to keep a visible number in sync, only handle structural notification refreshes (initial post, and whatever the DONE/skip end states need).
2. **Bump the notification channel importance from `IMPORTANCE_LOW` to `IMPORTANCE_DEFAULT`.** `IMPORTANCE_DEFAULT` still does NOT trigger a heads-up/peek alert (only `IMPORTANCE_HIGH`+ does) — this is a safe, minimal change that should restore a proper status-bar icon on OEM skins that suppress low-importance channels, without making the notification intrusive during a workout. Keep `NotificationCompat.PRIORITY_LOW`/`setOnlyAlertOnce(true)` as-is (those govern sound/vibration/priority display order, not icon suppression, and no bug was found in that behavior).

## File targets
- Modify: `app/src/main/java/com/jauschua/ironlogv2/service/RestTimerService.kt` — `restTimerNotificationContent()` (the `text` field), `ensureNotificationChannel()` (`IMPORTANCE_LOW` → `IMPORTANCE_DEFAULT`).
- Modify/extend: `app/src/test/java/com/jauschua/ironlogv2/service/RestTimerServiceLogicTest.kt` — update any test asserting the old `"X remaining"` text format to the new static text; the channel-importance change likely isn't directly unit-testable (Android-framework-coupled) but confirm whether any existing test touches `ensureNotificationChannel` and update if so.

## Edge cases
- **Do not remove or weaken the chronometer itself** — it is the one piece that was already working correctly and is now the SOLE visible countdown after this fix; verify `setUsesChronometer(true)`/`setChronometerCountDown(true)`/`setWhen(...)` are untouched.
- **`shouldRefreshRestNotification`'s throttle logic itself is not necessarily wrong** — it was built to avoid excessive `notify()` spam for a value that (before this fix) needed to look live-updating. Now that the visible number comes entirely from the chronometer, re-examine whether the throttle still serves a purpose (e.g. avoiding redundant `notify()` calls for a text field that no longer changes with the clock) — don't delete it reflexively if it's still doing useful work, but don't preserve behavior that only existed to serve the now-removed duplicate countdown.
- **The end-of-rest / DONE state**: confirm whatever the notification shows at 0 remaining (before the service tears down, per the already-fixed 450ms teardown delay from the prior review round) still makes sense with the new static text — it should not say something confusing like "Tap to return to your workout" if the rest is already over; check `buildNotification`'s call sites for whether a distinct "done" content variant is warranted.
- **`IMPORTANCE_DEFAULT` behavior verification**: this cannot be unit-tested (channel importance effects are OS/OEM-rendering behavior, not testable in a JVM unit test) — flag this clearly as needing on-device re-verification, same as the original spec's notification/audio behavior.

## Dependencies
None — standalone fix to already-merged spec 01 code. No schema/API change, no HUMAN GATE required.

## Verification
- Updated unit test(s) in `RestTimerServiceLogicTest.kt` asserting the new static notification text (not the old "X remaining" format).
- Full unit suite green: `./gradlew :app:testDebugUnitTest --rerun-tasks` (baseline 188 passing).
- Manual (on-device, cannot be substituted by a unit test): start a rest timer, background the app, confirm (a) the status bar shows a proper icon, not a dot, and (b) pulling down the shade shows only ONE time display (the chronometer, ticking every second) with no second, drifting "remaining" text.

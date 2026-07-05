# Capture screen: scroll current set into view on cursor advance

## Bug
`CaptureScreen.kt`'s `SessionContent` renders a `LazyColumn` accordion of group cards that
follows the logging cursor (`currentPlannedSetId`). When a set is logged, the cursor advances,
the completed group collapses and the next group expands, but the `LazyColumn` never scrolls —
so the newly-current set's input card (load/reps/tap/felt-peak) can end up off-screen, forcing
a manual scroll every time in the gym.

## Fix
Used `androidx.compose.foundation.relocation.BringIntoViewRequester` (stable API surface,
marked `@ExperimentalFoundationApi` in Compose Foundation 1.7.6 — confirmed via bytecode
inspection of the cached AAR — so `@OptIn(ExperimentalFoundationApi::class)` was added to
`SessionContent`).

- `SessionContent` creates one `remember { BringIntoViewRequester() }` and fires
  `LaunchedEffect(currentPlannedSetId) { runCatching { requester.bringIntoView() } }` — runs
  whenever the cursor changes; `runCatching` makes it a no-op when there's no current set
  (session fully logged) or the requester isn't attached to any laid-out node yet.
- `SetCard` gained a `modifier: Modifier = Modifier` parameter, applied to the `Card` root
  (`Card(modifier = modifier.fillMaxWidth().padding(start = 16.dp))`), defaulting to
  `Modifier` for non-current cards.
- At the call site, only the current set's `SetCard` gets
  `Modifier.bringIntoViewRequester(currentSetBringIntoViewRequester)`; all other sets pass
  `Modifier` (no-op).

This brings the whole current-set card (header, target line, and the input fields) into view,
robust to the accordion's dynamic item layout — no item-index / `animateScrollToItem` math
needed.

### Why this doesn't fight the keyboard or the rest timer
- The rest-timer bar is rendered in the outer `Column`, above and outside the `LazyColumn`
  (`restRemainingSeconds?.let { RestTimerBar(...) }`) — it never triggers a scroll and the
  `bringIntoView` call only fires on `currentPlannedSetId` changes, not on rest-timer ticks.
- No `imePadding()` is applied on this screen at all (checked — none present), so there's no
  double-adjustment between IME insets and the bring-into-view scroll.

## Files changed
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureScreen.kt`

## Build / test gate
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (no existing test broken; this is a
  pure UI-behavior change with no new pure-logic function, so no new unit test was added)

## Not touched
- Logging/cursor logic (`CaptureViewModel`), the accordion expand/collapse logic, the rest
  timer, the survey/review sheet — untouched.
- `app/build.gradle.kts` had a pre-existing local, uncommitted change
  (`SERVER_BASE_URL` pointed at a LAN IP instead of `myflix.media`) present before this task
  started — left as-is and NOT committed, per instructions.

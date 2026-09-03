# 21 — ALT_PAIR set ordering: consume server's planned_set_order

## Objective

Fix T1/T1b (and any other ALT_PAIR) superset set ordering during capture: the
client currently ignores the server's `planned_set_order` field and falls
back to plain exercise-major flattening for ALT_PAIR groups, which puts one
exercise's warmup ramp sets after the other exercise's working sets instead
of before all working sets in the pair.

## Root cause (diagnosed, do not re-investigate)

- Server (`~/projects/IronLog-V2/ironlog/api/app.py`, `SessionDetailResponse`
  assembly, ~line 1042-1073) already computes the correct play order for
  `ALT_PAIR` groups — all warmup sets across the pair first, then a
  round-robin of the non-warmup/working sets — and exposes it as
  `planned_set_order`: a list of
  `{exercise_id, movement_id, planned_set_id, set_index}` objects in that
  order. For every other `group_type` this list is empty (deliberately —
  see the comment above that block; do not extend it to `GIANT_SET`, which
  has its own already-correct round-major handling).
- Client DTO `GroupOut` in
  `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/CaptureModels.kt:43-44`
  has no field for `planned_set_order`, so kotlinx.serialization silently
  drops it on parse.
- `flattenPrescription(groups: List<GroupOut>)` in
  `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt:49-56`
  special-cases only `"GIANT_SET"` (round-major flatten); `"ALT_PAIR"` falls
  into the `else` branch and gets the same exercise-major flatten as
  `"STRAIGHT"` — wrong for ALT_PAIR, since the pair's two exercises are
  meant to interleave with warmups pulled to the front, not concatenated.

## File targets

- `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/CaptureModels.kt`
- `app/src/main/java/com/jauschua/ironlogv2/ui/screens/capture/CaptureViewModel.kt`
- `app/src/test/java/com/jauschua/ironlogv2/ui/capture/CaptureViewModelTest.kt`

## Changes

### CaptureModels.kt

1. Add a new `@Serializable data class PlannedSetOrderEntry`:
   ```kotlin
   @Serializable data class PlannedSetOrderEntry(
       val exercise_id: Int, val movement_id: Int,
       val planned_set_id: Int, val set_index: Int,
   )
   ```
   Field names must be raw snake_case matching the server JSON exactly (this
   file's established convention — no `@SerialName` used anywhere in it).
2. Add `val planned_set_order: List<PlannedSetOrderEntry> = emptyList()` to
   `GroupOut`. Default to `emptyList()` so older server responses / existing
   test fixtures that construct `GroupOut(...)` without this field keep
   compiling and behave via the fallback path below.

### CaptureViewModel.kt

Update `flattenPrescription` to add an `ALT_PAIR` branch:

```kotlin
internal fun flattenPrescription(groups: List<GroupOut>): List<PlannedSetOut> = groups.flatMap { g ->
    when {
        g.group_type == "GIANT_SET" -> (0 until g.rounds).flatMap { r ->
            g.exercises.mapNotNull { e -> e.planned_sets.getOrNull(r) }
        }
        g.group_type == "ALT_PAIR" && g.planned_set_order.isNotEmpty() -> {
            val byId = g.exercises.flatMap { it.planned_sets }.associateBy { it.id }
            g.planned_set_order.mapNotNull { entry -> byId[entry.planned_set_id] }
        }
        else -> g.exercises.flatMap { e -> e.planned_sets } // STRAIGHT (and ALT_PAIR fallback): exercise-major
    }
}
```

Notes:
- `mapNotNull` on the lookup, not a hard `!!`/index — if a `planned_set_id`
  in `planned_set_order` doesn't resolve to any set in `g.exercises` (should
  not happen given a consistent server response, but don't crash capture on
  a data mismatch), skip that entry rather than throwing.
- If `g.planned_set_order` is empty for an `ALT_PAIR` group (e.g. an older
  server build that hasn't deployed this field yet), fall through to the
  same exercise-major flatten `STRAIGHT` uses — this is the existing
  behavior today, so it's a safe degrade, not a regression, for that skew
  window.
- Do not change the `GIANT_SET` branch or the plain `else` branch beyond
  what's shown — this is an additive `when` arm.
- Preserve the existing doc comment above the function (it explains the
  GIANT_SET round-major rationale); extend it with a short note that
  ALT_PAIR now has its own branch driven by the server's authoritative
  `planned_set_order`, falling back to exercise-major when absent.

### CaptureViewModelTest.kt

Add a test following the existing `giant_set_group_flattens_round_major` /
`straight_group_flattens_exercise_major` pattern (see lines ~379-410 for the
`exercise(...)` test helper and assertion style). New test:

```kotlin
/**
 * ALT_PAIR group honors the server's planned_set_order (warmups across the
 * pair first, then round-robin working sets) instead of exercise-major
 * flattening.
 */
@Test
fun alt_pair_group_flattens_by_planned_set_order() {
    // Exercise B (rows) is exercises[0] — no warmups, 2 working sets.
    val exB = ExerciseOut(
        id = 1, movement_id = 1, movement_name = "rows", order_index = 0,
        scheme = "ALT_PAIR", objective = "", unilateral = false,
        planned_sets = listOf(
            PlannedSetOut(id = 10, set_index = 0, set_role = "WORKING", is_warmup = false),
            PlannedSetOut(id = 11, set_index = 1, set_role = "WORKING", is_warmup = false),
        ),
    )
    // Exercise A (bench) is exercises[1] — 3 ramp warmups, 2 working sets.
    val exA = ExerciseOut(
        id = 2, movement_id = 2, movement_name = "bench", order_index = 1,
        scheme = "ALT_PAIR", objective = "", unilateral = false,
        planned_sets = listOf(
            PlannedSetOut(id = 20, set_index = -3, set_role = "RAMP", is_warmup = true),
            PlannedSetOut(id = 21, set_index = -2, set_role = "RAMP", is_warmup = true),
            PlannedSetOut(id = 22, set_index = -1, set_role = "RAMP", is_warmup = true),
            PlannedSetOut(id = 23, set_index = 0, set_role = "WORKING", is_warmup = false),
            PlannedSetOut(id = 24, set_index = 1, set_role = "WORKING", is_warmup = false),
        ),
    )
    val order = listOf(
        PlannedSetOrderEntry(exercise_id = 2, movement_id = 2, planned_set_id = 20, set_index = -3),
        PlannedSetOrderEntry(exercise_id = 2, movement_id = 2, planned_set_id = 21, set_index = -2),
        PlannedSetOrderEntry(exercise_id = 2, movement_id = 2, planned_set_id = 22, set_index = -1),
        PlannedSetOrderEntry(exercise_id = 1, movement_id = 1, planned_set_id = 10, set_index = 0),
        PlannedSetOrderEntry(exercise_id = 2, movement_id = 2, planned_set_id = 23, set_index = 0),
        PlannedSetOrderEntry(exercise_id = 1, movement_id = 1, planned_set_id = 11, set_index = 1),
        PlannedSetOrderEntry(exercise_id = 2, movement_id = 2, planned_set_id = 24, set_index = 1),
    )
    val group = GroupOut(
        id = 1, order_index = 0, group_type = "ALT_PAIR", rounds = 1,
        exercises = listOf(exB, exA), planned_set_order = order,
    )
    val flat = flattenPrescription(listOf(group))
    assertEquals(listOf(20, 21, 22, 10, 23, 11, 24), flat.map { it.id })
}

/** Empty planned_set_order (server skew) degrades to exercise-major, not a crash. */
@Test
fun alt_pair_group_without_planned_set_order_falls_back_to_exercise_major() {
    val e1 = exercise(id = 1, idBase = 10, rounds = 2)
    val e2 = exercise(id = 2, idBase = 20, rounds = 2)
    val group = GroupOut(
        id = 1, order_index = 0, group_type = "ALT_PAIR", rounds = 1,
        exercises = listOf(e1, e2),
    )
    val flat = flattenPrescription(listOf(group))
    assertEquals(listOf(10, 11, 20, 21), flat.map { it.id })
}
```

Add the necessary import for `PlannedSetOrderEntry` alongside the existing
DTO imports at the top of the test file.

## Edge cases

- Empty `planned_set_order` on an `ALT_PAIR` group → exercise-major
  fallback (covered by the second test above).
- A `planned_set_id` in `planned_set_order` with no matching `PlannedSetOut`
  in `g.exercises` → skipped via `mapNotNull`, not a crash.
- `GIANT_SET` and `STRAIGHT` groups must be byte-for-byte unchanged —
  existing `giant_set_group_flattens_round_major` and
  `straight_group_flattens_exercise_major` tests must still pass unmodified.

## Dependencies

None — single self-contained change across 3 files in one repo.

## Verification

- `~/projects/IronLog-V2-Client/gradlew :app:testDebugUnitTest` (or the
  project's standard unit-test task) — all existing tests plus the two new
  ones pass.
- `~/projects/IronLog-V2-Client/gradlew :app:assembleDebug` builds clean.
- Manual: pull latest session for a day with a real ALT_PAIR pair (e.g. the
  user's Upper A day, T1/T1b) via the app and confirm the capture screen
  presents all of T1's warmup ramp sets before any T1/T1b working sets.

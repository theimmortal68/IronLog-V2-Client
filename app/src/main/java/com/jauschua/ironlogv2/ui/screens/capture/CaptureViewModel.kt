package com.jauschua.ironlogv2.ui.screens.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jauschua.ironlogv2.IronLogV2Application
import com.jauschua.ironlogv2.data.api.IronLogException
import com.jauschua.ironlogv2.data.api.humanMessage
import com.jauschua.ironlogv2.data.api.dto.GroupOut
import com.jauschua.ironlogv2.data.api.dto.PlannedSetOut
import com.jauschua.ironlogv2.data.api.dto.SessionDetailResponse
import com.jauschua.ironlogv2.data.local.SetLogDraft
import com.jauschua.ironlogv2.data.local.SurveyDraft
import com.jauschua.ironlogv2.data.repo.CaptureRepo
import com.jauschua.ironlogv2.service.AndroidRestTimerController
import com.jauschua.ironlogv2.service.InMemoryRestTimerController
import com.jauschua.ironlogv2.service.RestTimerController
import com.jauschua.ironlogv2.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val TAP_REQUIRED = setOf("WORKING", "TOP", "BACKOFF")

/**
 * Flatten a session's groups into cursor order.
 *
 * GIANT_SET groups are ROUND-MAJOR: for each round index, one planned set from each exercise
 * in the group, in exercise order — `[e1s1, e2s1, e3s1, e1s2, e2s2, e3s2, ...]`.  This is the
 * core fix: exercises in a giant set physically rotate (do one set of each, then repeat), so
 * the old exercise-major flatten (`g.exercises.flatMap { it.planned_sets }`, i.e. all of
 * exercise 1's sets before exercise 2's) produced the wrong logging order for supersets.
 *
 * STRAIGHT groups are unchanged: exercise-major (`g.exercises.flatMap { it.planned_sets }`).
 *
 * `PlannedSetOut.id` (globally unique) is the cursor key everywhere downstream — this function
 * only reorders entries, it never renumbers or drops non-round-major sets except via
 * [List.getOrNull] when an exercise has fewer planned sets than `g.rounds` (defensive; should
 * not occur for a well-formed giant set, where all exercises share the same round count).
 */
internal fun flattenPrescription(groups: List<GroupOut>): List<PlannedSetOut> = groups.flatMap { g ->
    if (g.group_type == "GIANT_SET") {
        (0 until g.rounds).flatMap { r ->
            g.exercises.mapNotNull { e -> e.planned_sets.getOrNull(r) }
        }
    } else {
        g.exercises.flatMap { e -> e.planned_sets } // STRAIGHT: exercise-major
    }
}

/**
 * IDs of every [PlannedSetOut] that belongs to a [com.jauschua.ironlogv2.data.api.dto.ExerciseOut]
 * marked `unilateral = true`.  Used by [CaptureViewModel] to decide whether a given cursor
 * position needs two [CaptureViewModel.logWorkingSet] calls (left + right) before advancing.
 */
internal fun unilateralPlannedSetIds(groups: List<GroupOut>): Set<Int> = groups
    .flatMap { it.exercises }
    .filter { it.unilateral }
    .flatMap { it.planned_sets }
    .map { it.id }
    .toSet()

/** The group whose review sheet is open, plus any existing drafts to prefill it. */
data class GroupReview(
    val group: GroupOut,
    val surveys: List<SurveyDraft>,
    val noteText: String?,
)

/**
 * What was actually logged for a planned set (as opposed to its target/prescription). Surfaced
 * by [CaptureViewModel.loggedSetActuals] so a logged set's card can show "165lb × 6 · ✓ on
 * target" instead of collapsing back to only the target — see the fix in
 * [CaptureViewModel.logWorkingSet] / [CaptureViewModel.editLoggedSet].
 */
data class LoggedSetActual(
    val actualLoad: Double?,
    val actualReps: Int?,
    val tap: String?,
)

/**
 * Map each group's cursor-order LAST planned-set id → that group. Groups are contiguous in
 * [flattenPrescription], so a group's final flattened entry marks its completion; when that set
 * is logged and the cursor advances past it, the group is done.
 */
internal fun lastSetIdByGroup(groups: List<GroupOut>): Map<Int, GroupOut> =
    groups.mapNotNull { g ->
        flattenPrescription(listOf(g)).lastOrNull()?.let { it.id to g }
    }.toMap()

/** True iff every planned set in [group] is in [pastIds] (used for the reopen affordance). */
fun groupIsComplete(group: GroupOut, pastIds: Set<Int>): Boolean =
    group.exercises.flatMap { it.planned_sets }.map { it.id }.all { it in pastIds }

class CaptureViewModel(
    private val repo: CaptureRepo,
    /** Mutable so [load] can set it from today's session; tests inject a known id directly. */
    private var sessionId: Int,
    private val restTimerController: RestTimerController = InMemoryRestTimerController(),
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<SessionDetailResponse?>>(UiState.Loading)
    val state: StateFlow<UiState<SessionDetailResponse?>> = _state.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    /**
     * Flattened ordered list of all planned sets in the session prescription.
     * Populated by [load] (in production) or [initPrescriptionForTest] (in tests) via
     * [flattenPrescription]: GIANT_SET groups round-major, STRAIGHT groups exercise-major.
     *
     * Using the globally-unique [PlannedSetOut.id] as the cursor key is CRITICAL:
     * [PlannedSetOut.set_index] resets to 0 at the start of each exercise, so comparing
     * set_index against a global counter (the original bug) caused exercise-2+ sets to always
     * appear "past" (set_index < global counter) with no input controls rendered.
     */
    private var flattenedPrescription: List<PlannedSetOut> = emptyList()

    /**
     * IDs of planned sets belonging to a unilateral exercise (see [unilateralPlannedSetIds]).
     * A unilateral exercise's `planned_sets[r]` entry is ONE cursor unit covering BOTH sides
     * (left + right) — the cursor does not advance to the next planned set until both sides
     * are logged. See [unilateralSideCount].
     */
    private var unilateralSetIds: Set<Int> = emptySet()

    /**
     * How many sides of the CURRENT cursor's planned set have been logged so far (0, 1, or 2).
     * Only meaningful while [_currentPlannedSetId] refers to a unilateral set (see
     * [unilateralSetIds]); ignored for bilateral sets, which always advance after one call.
     * Resets to 0 whenever the cursor advances. Logging is a simple two-tap-per-unilateral-set:
     * the first [logWorkingSet] call for a unilateral planned set commits sideIndex 0 and holds
     * the cursor; the second call commits sideIndex 1 and advances the cursor to the next entry
     * in [flattenedPrescription].
     */
    private var unilateralSideCount: Int = 0

    /**
     * ID of the planned set the user should log next.
     * null when all sets are done or no prescription has been loaded yet.
     */
    private val _currentPlannedSetId = MutableStateFlow<Int?>(null)
    val currentPlannedSetId: StateFlow<Int?> = _currentPlannedSetId.asStateFlow()

    private val _submitResult = MutableStateFlow<String?>(null)
    val submitResult: StateFlow<String?> = _submitResult.asStateFlow()

    /**
     * Rest-trigger context per planned-set id (see [restContextByPlannedSetId]), computed once
     * alongside [flattenedPrescription]. Empty for tests that only inject a flat set list via
     * [initPrescriptionForTest] — those have no group/tier info to derive rest from, so the
     * countdown simply never triggers, which is correct (nothing to base it on).
     */
    private var restContextBySetId: Map<Int, SetRestContext> = emptyMap()

    /**
     * Planned-set ids belonging to the same rest-governing round, keyed by planned-set id. For
     * giant sets this contains every exercise in the round; for straight work it is just self.
     */
    private var roundSetIdsBySetId: Map<Int, List<Int>> = emptyMap()

    private var lastSetIdByGroup: Map<Int, GroupOut> = emptyMap()

    private val _pendingReview = MutableStateFlow<GroupReview?>(null)
    val pendingReview: StateFlow<GroupReview?> = _pendingReview.asStateFlow()

    /**
     * Actual load/reps/tap logged for each planned-set side, keyed by
     * ([PlannedSetOut.id], sideIndex) — see [LoggedSetActual]. Bilateral sets always use
     * sideIndex 0. Populated from persisted drafts in [load] (so a resumed session shows real
     * actuals, not just targets) and kept current by [logWorkingSet] / [editLoggedSet] as sets
     * are logged or corrected. A missing key means that planned-set side has no logged actual yet.
     */
    private val _loggedSetActuals = MutableStateFlow<Map<Pair<Int, Int>, LoggedSetActual>>(emptyMap())
    val loggedSetActuals: StateFlow<Map<Pair<Int, Int>, LoggedSetActual>> = _loggedSetActuals.asStateFlow()

    /**
     * Seconds remaining on the current rest countdown; null when no countdown is running. The
     * service/controller is the single countdown owner; this ViewModel only exposes its state.
     */
    val restRemainingSeconds: StateFlow<Int?> = restTimerController.remainingSeconds

    /**
     * Load today's planned session.  Called from the screen's [LaunchedEffect] on entry.
     * Sets [sessionId] from the loaded session so [logWorkingSet]/[finish] use the correct id.
     * Tests inject [sessionId] directly and never call this, so they are unaffected.
     *
     * Resume-cursor fix (background-kill data-loss bug): the set drafts are already durably
     * persisted in Room (write-before-advance, see [logWorkingSet]), but when Android recreates
     * this ViewModel after a process kill, the OLD code reset [_currentPlannedSetId] to the
     * FIRST set in [flattenedPrescription] — and [pastSetIds] (in CaptureScreen.kt) derives
     * "logged" purely from cursor position, so the whole session appeared erased even though
     * nothing was lost. Fixed below by resuming the cursor at the first planned set that is NOT
     * yet fully logged (by row count — a bilateral set needs 1 row, a unilateral set needs 2),
     * instead of always the first set.
     */
    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            repo.today()
                .onSuccess { session ->
                    if (session != null) {
                        sessionId = session.id
                        flattenedPrescription = flattenPrescription(session.groups)
                        unilateralSetIds = unilateralPlannedSetIds(session.groups)
                        restContextBySetId = restContextByPlannedSetId(session.groups)
                        roundSetIdsBySetId = roundPlannedSetIdsBySetId(session.groups)
                        lastSetIdByGroup = lastSetIdByGroup(session.groups)

                        // Raw draft rows (not the collapsed loggedActualsFor map) so a
                        // unilateral set's row COUNT can be checked — a bilateral set is fully
                        // logged at 1 row, a unilateral set needs both sides (2 rows).
                        val rowCountByPlannedSetId = repo.setLogsForSession(session.id)
                            .mapNotNull { it.plannedSetId }
                            .groupingBy { it }
                            .eachCount()
                        val resumeSet = flattenedPrescription.firstOrNull { ps ->
                            val rows = rowCountByPlannedSetId[ps.id] ?: 0
                            val fullyLogged = if (ps.id in unilateralSetIds) rows >= 2 else rows >= 1
                            !fullyLogged
                        }
                        _currentPlannedSetId.value = resumeSet?.id
                        // Mid-set resume: a unilateral set with exactly side 1 logged must hold
                        // the cursor there and expect side 2 next (sideIndex 1), not restart it.
                        unilateralSideCount = if (resumeSet != null && resumeSet.id in unilateralSetIds) {
                            (rowCountByPlannedSetId[resumeSet.id] ?: 0).coerceIn(0, 1)
                        } else {
                            0
                        }

                        _loggedSetActuals.value = repo.loggedActualsFor(session.id).mapValues { (_, d) ->
                            LoggedSetActual(d.actualLoad, d.actualReps, d.feedbackTap)
                        }
                    }
                    _state.value = UiState.Success(session)
                }
                .onFailure { e ->
                    val msg = (e as? IronLogException)?.error?.humanMessage()
                        ?: e.message ?: "Unknown error"
                    _state.value = UiState.Error(msg)
                }
        }
    }

    /**
     * Inject a flattened prescription and initialise the cursor for unit tests.
     * Production code uses [load] instead (which computes the same flat list from the session).
     */
    internal fun initPrescriptionForTest(sets: List<PlannedSetOut>) {
        flattenedPrescription = sets
        unilateralSetIds = emptySet()
        unilateralSideCount = 0
        _currentPlannedSetId.value = sets.firstOrNull()?.id
    }

    /**
     * Inject a session's raw [GroupOut] list for unit tests, running it through the same
     * [flattenPrescription] / [unilateralPlannedSetIds] logic [load] uses in production.
     * Use this (rather than [initPrescriptionForTest] above) when a test needs to exercise
     * GIANT_SET round-major ordering or unilateral set-unit behavior. Distinct name (not an
     * overload) — `List<GroupOut>` and `List<PlannedSetOut>` erase to the same JVM signature.
     */
    internal fun initPrescriptionForTestFromGroups(groups: List<GroupOut>) {
        flattenedPrescription = flattenPrescription(groups)
        unilateralSetIds = unilateralPlannedSetIds(groups)
        unilateralSideCount = 0
        restContextBySetId = restContextByPlannedSetId(groups)
        roundSetIdsBySetId = roundPlannedSetIdsBySetId(groups)
        lastSetIdByGroup = lastSetIdByGroup(groups)
        _currentPlannedSetId.value = flattenedPrescription.firstOrNull()?.id
    }

    /**
     * Write-before-advance entry point.
     *
     * Mandatory-tap gate: a working role (WORKING / TOP / BACKOFF) with a null tap sets
     * [uiError] and returns early — no Room write, no cursor advance.
     *
     * Write-before-advance ordering: for valid sets, this suspend function *awaits*
     * [CaptureRepo.logSet] (which calls the Room @Insert suspend — commits before returning)
     * and only THEN advances [_currentPlannedSetId] to the next set in [flattenedPrescription].
     * There is no `launch` here; the Room commit is inline in this coroutine.  When this
     * function returns to the caller, the durable row is guaranteed to exist.  A process kill
     * after the caller resumes cannot lose the set.
     *
     * Cursor advance: [plannedSetId] is looked up by [PlannedSetOut.id] in [flattenedPrescription];
     * the cursor moves to the NEXT entry in the flat list, crossing exercise boundaries
     * automatically.  This fixes the original bug where `_nextSetIndex` (a global counter)
     * was compared against per-exercise [PlannedSetOut.set_index] (which resets to 0 each
     * exercise), causing only the first exercise to ever receive input controls.
     *
     * Unilateral set-unit: if [plannedSetId] is in [unilateralSetIds], the cursor holds on the
     * SAME planned set after the first call (side 1 logged, [unilateralSideCount] → 1) and only
     * advances on the second call for that same id (side 2 logged, [unilateralSideCount] resets
     * to 0). Bilateral sets always advance immediately, as before.
     */
    suspend fun logWorkingSet(
        plannedSetId: Int?,
        movementId: Int,
        setIndex: Int,
        setRole: String,
        actualLoad: Double?,
        actualReps: Int?,
        tap: String?,
        isWarmup: Boolean = false,
        feltPeak: Double? = null,
    ) {
        if (setRole in TAP_REQUIRED && tap == null) {
            _uiError.value = "Tap required before continuing"
            return
        }
        _uiError.value = null
        // Side discriminator for the idempotency key (see SetLogDraft.sideIndex): a unilateral
        // exercise logs side 1 (unilateralSideCount == 0 at this point) then side 2
        // (unilateralSideCount == 1); the side-count is incremented AFTER the write below, so it
        // still reflects the side being written here. Bilateral sets are always side 0.
        val sideIndex = if (plannedSetId != null && plannedSetId in unilateralSetIds) unilateralSideCount else 0
        // AWAIT the Room upsert — suspends until the SQLite transaction commits.
        repo.logSet(
            SetLogDraft(
                sessionId = sessionId,
                plannedSetId = plannedSetId,
                movementId = movementId,
                setIndex = setIndex,
                setRole = setRole,
                isWarmup = isWarmup,
                actualLoad = actualLoad,
                actualReps = actualReps,
                feedbackTap = tap,
                feltPeak = feltPeak,
                sideIndex = sideIndex,
            ),
        )
        // Surface the actual just committed (see LoggedSetActual) — a logged set's card shows
        // this instead of collapsing back to only the target. Keyed on plannedSetId + sideIndex;
        // rows with no planned set (null) have no card to surface actuals on.
        if (plannedSetId != null) {
            _loggedSetActuals.value = _loggedSetActuals.value +
                ((plannedSetId to sideIndex) to LoggedSetActual(actualLoad, actualReps, tap))
        }
        // Advance cursor ONLY after the commit — write-before-advance enforced.
        // Uses the globally-unique PlannedSetOut.id (not set_index, which resets per exercise).
        if (plannedSetId != null && plannedSetId in unilateralSetIds && unilateralSideCount == 0) {
            // Side 1 of a unilateral set: hold the cursor, wait for side 2.
            unilateralSideCount = 1
        } else {
            // Bilateral set, or side 2 of a unilateral set: advance to the next entry.
            unilateralSideCount = 0
            val completedPlannedSetId = plannedSetId
            val idx = flattenedPrescription.indexOfFirst { it.id == completedPlannedSetId }
            _currentPlannedSetId.value = flattenedPrescription.getOrNull(idx + 1)?.id

            // Auto-start the rest countdown for the set just completed (write already
            // committed above). STRAIGHT groups trigger after every set; GIANT_SET groups
            // only after the round's last item — see shouldStartRest / restContextByPlannedSetId.
            if (completedPlannedSetId != null) {
                restContextBySetId[completedPlannedSetId]?.let { ctx ->
                    if (ctx.triggersRest) {
                        val roundSetIds = roundSetIdsBySetId[completedPlannedSetId]
                            ?: listOf(completedPlannedSetId)
                        val tapEnum = hardestTapForRound(_loggedSetActuals.value, roundSetIds)
                        startRest(restSeconds(ctx.baseRestSeconds, ctx.tierLabel, tapEnum, ctx.isGiantSet))
                    }
                }

                // Group-review trigger: if the set just logged was this group's LAST cursor entry,
                // open the review sheet (prefilled from any existing drafts). Reads state AFTER the
                // Room commit + cursor advance — never gates the write.
                lastSetIdByGroup[completedPlannedSetId]?.let { group ->
                    val prefill = repo.reviewDraftsFor(
                        sessionId,
                        group.exercises.map { it.movement_id },
                        anchorMovementId = group.exercises.first().movement_id,
                    )
                    _pendingReview.value = GroupReview(group, prefill.surveys, prefill.noteText)
                }
            }
        }
    }

    /**
     * Correct an already-logged set IN PLACE — re-opens a past set's card, edits load/reps/tap,
     * re-saves without touching the forward cursor, rest timer, or group-review trigger (those
     * are [logWorkingSet]'s job for the CURRENT set; this is purely a correction to something
     * already logged).
     *
     * Idempotent for the same reason [logWorkingSet]'s write is: [CaptureRepo.logSet] →
     * [com.jauschua.ironlogv2.data.local.CaptureDao.upsertSetLog] replaces the prior row for this
     * [plannedSetId] rather than appending a second one, so this is safe to call repeatedly (e.g.
     * re-opening and re-saving without changing anything).
     *
     * Field preservation: the upsert is a full-row REPLACE, but the edit UI only surfaces
     * load/reps/tap. Any other actual on the original row that the UI doesn't show (felt-peak on
     * an HT/band-composite set, and the aux plates/band/assisted-rep fields) is read back from
     * the stored draft for the same [sideIndex] and carried into the corrected row, so correcting
     * load/reps never nulls felt-peak or collides with the other side of a unilateral set.
     *
     * Same mandatory-tap gate as [logWorkingSet]: a working role with a null tap is rejected and
     * neither the write nor [loggedSetActuals] are updated.
     */
    suspend fun editLoggedSet(
        plannedSetId: Int,
        sideIndex: Int = 0,
        movementId: Int,
        setIndex: Int,
        setRole: String,
        actualLoad: Double?,
        actualReps: Int?,
        tap: String?,
        isWarmup: Boolean = false,
        feltPeak: Double? = null,
    ) {
        if (setRole in TAP_REQUIRED && tap == null) {
            _uiError.value = "Tap required before continuing"
            return
        }
        _uiError.value = null
        // Read the original row so unsurfaced actuals (felt-peak + aux fields) survive the
        // full-row replace; fall back to plain defaults if it somehow isn't there.
        val existing = repo.existingLogForSide(sessionId, plannedSetId, sideIndex)
        repo.logSet(
            SetLogDraft(
                sessionId = sessionId,
                plannedSetId = plannedSetId,
                movementId = movementId,
                setIndex = setIndex,
                setRole = setRole,
                isWarmup = isWarmup,
                actualLoad = actualLoad,
                actualReps = actualReps,
                feedbackTap = tap,
                feltPeak = feltPeak ?: existing?.feltPeak,
                rpeNumeric = existing?.rpeNumeric,
                actualUnassistedReps = existing?.actualUnassistedReps,
                actualAssistedReps = existing?.actualAssistedReps,
                actualPlates = existing?.actualPlates,
                bandPairId = existing?.bandPairId,
                sideIndex = sideIndex,
            ),
        )
        _loggedSetActuals.value = _loggedSetActuals.value +
            ((plannedSetId to sideIndex) to LoggedSetActual(actualLoad, actualReps, tap))
    }

    /**
     * (Re)starts the service-owned rest countdown at [seconds]. Back-to-back triggers restart
     * the foreground service timer instead of running any ViewModel-local ticker.
     */
    private fun startRest(seconds: Int) {
        restTimerController.startRest(seconds)
    }

    /** Skip the current rest countdown immediately — the user is ready to go again. */
    fun skipRest() {
        restTimerController.skipRest()
    }

    /** Extend a running countdown by [extraSeconds] (default 30). No-op if none is running. */
    fun addRestTime(extraSeconds: Int = 30) {
        restTimerController.addRestTime(extraSeconds)
    }

    /**
     * Reopen a completed group's review sheet, prefilled from existing drafts.
     *
     * Suspend (not `viewModelScope.launch`-wrapped) — same convention as [logWorkingSet]: the
     * caller (the screen, via `scope.launch { }`) drives the coroutine, so awaiting this
     * function's completion is meaningful (and testable synchronously from a plain `runBlocking`)
     * instead of being a fire-and-forget dispatch the caller can't observe finishing.
     */
    suspend fun openReview(group: GroupOut) {
        val prefill = repo.reviewDraftsFor(
            sessionId,
            group.exercises.map { it.movement_id },
            anchorMovementId = group.exercises.first().movement_id,
        )
        _pendingReview.value = GroupReview(group, prefill.surveys, prefill.noteText)
    }

    /** Skip / close the review sheet without writing anything. */
    fun dismissReview() { _pendingReview.value = null }

    /**
     * Persist a group's review. [flags] maps movement_id → (asymmetry, technique); a missing
     * movement defaults to (false, false). Writes one SurveyDraft per exercise + an optional
     * note anchored to the group's first exercise. Idempotent (repo replaces prior rows).
     *
     * Suspend — see [openReview] for why this isn't `viewModelScope.launch`-wrapped.
     */
    suspend fun saveReview(group: GroupOut, flags: Map<Int, Pair<Boolean, Boolean>>, noteText: String?) {
        val surveys = group.exercises.map { e ->
            val (asym, tech) = flags[e.movement_id] ?: (false to false)
            SurveyDraft(
                sessionId = sessionId, movementId = e.movement_id,
                stickingPoint = null, asymmetryFlag = asym, techniqueFlag = tech,
            )
        }
        repo.saveGroupReview(
            sessionId, surveys,
            anchorMovementId = group.exercises.first().movement_id,
            noteText = noteText,
        )
        _pendingReview.value = null
    }

    /**
     * Batch-submit all pending drafts. Writes the session note (if any) first, then submits.
     *
     * Suspend — see [openReview] for why this isn't `viewModelScope.launch`-wrapped.
     */
    suspend fun finish(sessionNote: String? = null) {
        repo.saveSessionNote(sessionId, sessionNote)
        repo.submit(sessionId)
            .onSuccess { _submitResult.value = it.status }
            .onFailure { _submitResult.value = "RETRY" }
    }

    companion object {
        /**
         * No-arg factory for the Capture bottom-nav destination.
         * The real session id is resolved inside [load] once today's session is fetched.
         */
        val TodayFactory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                CaptureViewModel(
                    repo = app.container.captureRepo,
                    sessionId = 0,
                    restTimerController = AndroidRestTimerController(app.applicationContext),
                )
            }
        }

        /** Scoped factory — pass the session id from the nav arg. */
        fun factory(sessionId: Int): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as IronLogV2Application
                CaptureViewModel(
                    repo = app.container.captureRepo,
                    sessionId = sessionId,
                    restTimerController = AndroidRestTimerController(app.applicationContext),
                )
            }
        }
    }
}

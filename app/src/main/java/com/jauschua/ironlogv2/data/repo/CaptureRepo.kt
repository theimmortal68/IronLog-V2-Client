// CaptureRepo.kt
package com.jauschua.ironlogv2.data.repo
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.*
import com.jauschua.ironlogv2.data.api.runCatchingApi
import com.jauschua.ironlogv2.data.local.CaptureDao
import com.jauschua.ironlogv2.data.local.NoteDraft
import com.jauschua.ironlogv2.data.local.SetLogDraft
import com.jauschua.ironlogv2.data.local.SurveyDraft
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*

data class ReviewPrefill(val surveys: List<SurveyDraft>, val noteText: String?)

class CaptureRepo(private val apiClient: ApiClient, private val dao: CaptureDao) {

    suspend fun today(): Result<SessionDetailResponse?> = runCatchingApi {
        apiClient.http.get("/sessions/today").body()
    }

    suspend fun session(id: Int): Result<SessionDetailResponse> = runCatchingApi {
        apiClient.http.get("/sessions/$id").body()
    }

    /**
     * Per-set durable write (commits before returning — Room @Transaction suspend).
     * Idempotent: [CaptureDao.upsertSetLog] replaces any prior row for the same
     * (sessionId, plannedSetId) rather than appending a duplicate — see its doc comment.
     */
    suspend fun logSet(d: SetLogDraft) = dao.upsertSetLog(d)

    /**
     * Actuals already logged for [sessionId], keyed by (planned-set id, side index). Rows with a
     * null plannedSetId are excluded — nothing to key a display card on. Bilateral sets use
     * sideIndex 0; unilateral sets may have sideIndex 0 and 1. Used by
     * [com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel] to populate logged-set actuals
     * on [com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel.load], so a resumed session
     * shows what was actually entered instead of only the target.
     */
    suspend fun loggedActualsFor(sessionId: Int): Map<Pair<Int, Int>, SetLogDraft> =
        dao.setLogsForSession(sessionId)
            .filter { it.plannedSetId != null }
            .associateBy { it.plannedSetId!! to it.sideIndex }

    /**
     * ALL raw draft rows for [sessionId] — unlike [loggedActualsFor] (which collapses to one
     * "latest" row per plannedSetId), this preserves every row, including both side-rows of a
     * UNILATERAL set. Used by [com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel.load]
     * to count how many sides of each planned set are already logged when resuming a session
     * after process recreation (a bilateral set needs 1 row to be "fully logged"; a unilateral
     * set needs 2).
     */
    suspend fun setLogsForSession(sessionId: Int): List<SetLogDraft> =
        dao.setLogsForSession(sessionId)

    /**
     * The stored draft for (sessionId, plannedSetId), if any — used by
     * [com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel.editLoggedSet] to preserve
     * unsurfaced actual fields (e.g. felt-peak) across an in-place correction.
     */
    suspend fun existingLog(sessionId: Int, plannedSetId: Int): SetLogDraft? =
        dao.setLogForPlannedSet(sessionId, plannedSetId)

    suspend fun existingLogForSide(sessionId: Int, plannedSetId: Int, sideIndex: Int): SetLogDraft? =
        dao.setLogForPlannedSetSide(sessionId, plannedSetId, sideIndex)

    /** Batch submit. Idempotent + retryable: on success, clear local drafts. */
    suspend fun submit(sessionId: Int): Result<SubmitResponse> = runCatchingApi {
        val setLogs = dao.setLogsForSession(sessionId).map {
            SetLogIn(planned_set_id = it.plannedSetId, movement_id = it.movementId,
                set_index = it.setIndex, set_role = it.setRole, is_warmup = it.isWarmup,
                actual_load = it.actualLoad, actual_reps = it.actualReps,
                feedback_tap = it.feedbackTap, rpe_numeric = it.rpeNumeric,
                actual_unassisted_reps = it.actualUnassistedReps,
                actual_assisted_reps = it.actualAssistedReps, actual_plates = it.actualPlates,
                band_pair_id = it.bandPairId, felt_peak = it.feltPeak)
        }
        val surveys = dao.surveysForSession(sessionId).map {
            ExerciseSurveyIn(it.movementId, it.stickingPoint, it.asymmetryFlag, it.techniqueFlag)
        }
        val notes = dao.notesForSession(sessionId).map { NoteIn(it.movementId, it.text) }
        val resp: SubmitResponse = apiClient.http.post("/sessions/$sessionId/submit") {
            contentType(ContentType.Application.Json)
            setBody(SubmitRequest(setLogs, surveys, notes))
        }.body()
        dao.clearSetLogs(sessionId); dao.clearSurveys(sessionId); dao.clearNotes(sessionId)
        resp
    }

    /**
     * Save one group's review: one SurveyDraft per exercise + an optional note anchored to the
     * group's first exercise. Idempotent — deletes the group's prior survey rows and the anchor's
     * prior note first, so re-opening and re-saving replaces rather than duplicates. Local only.
     */
    suspend fun saveGroupReview(
        sessionId: Int,
        surveys: List<SurveyDraft>,
        anchorMovementId: Int,
        noteText: String?,
    ) {
        val movementIds = surveys.map { it.movementId }
        dao.deleteSurveysForMovements(sessionId, movementIds)
        dao.deleteNoteForMovement(sessionId, anchorMovementId)
        surveys.forEach { dao.insertSurvey(it) }
        val trimmed = noteText?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            dao.insertNote(NoteDraft(sessionId = sessionId, movementId = anchorMovementId, text = trimmed))
        }
    }

    /** Prefill for reopening a group's review sheet. */
    suspend fun reviewDraftsFor(
        sessionId: Int,
        movementIds: List<Int>,
        anchorMovementId: Int,
    ): ReviewPrefill = ReviewPrefill(
        surveys = dao.surveysForMovements(sessionId, movementIds),
        noteText = dao.noteForMovement(sessionId, anchorMovementId)?.text,
    )

    /** Upsert the session-level (movement_id = null) note; blank text clears it. Local only. */
    suspend fun saveSessionNote(sessionId: Int, text: String?) {
        dao.deleteSessionNote(sessionId)
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            dao.insertNote(NoteDraft(sessionId = sessionId, movementId = null, text = trimmed))
        }
    }

    suspend fun skipExercise(sessionId: Int, exerciseId: Int): Result<ExerciseOut> = runCatchingApi {
        apiClient.http.post("/sessions/$sessionId/exercises/$exerciseId/skip").body()
    }

    suspend fun swapExercise(
        sessionId: Int, exerciseId: Int, newMovementId: Int, makePermanent: Boolean,
    ): Result<ExerciseOut> = runCatchingApi {
        apiClient.http.post("/sessions/$sessionId/exercises/$exerciseId/swap") {
            contentType(ContentType.Application.Json)
            setBody(SwapExerciseRequest(newMovementId, makePermanent))
        }.body()
    }

    suspend fun substitutesFor(movementId: Int): Result<List<MovementSummary>> = runCatchingApi {
        apiClient.http.get("/movements/substitutes/$movementId").body()
    }
}

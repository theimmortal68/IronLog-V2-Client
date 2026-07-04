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

    /** Per-set durable write (commits before returning — Room @Insert suspend). */
    suspend fun logSet(d: SetLogDraft) = dao.insertSetLog(d)

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
}

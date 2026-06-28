// CaptureRepo.kt
package com.jauschua.ironlogv2.data.repo
import com.jauschua.ironlogv2.data.api.ApiClient
import com.jauschua.ironlogv2.data.api.dto.*
import com.jauschua.ironlogv2.data.api.runCatchingApi
import com.jauschua.ironlogv2.data.local.CaptureDao
import com.jauschua.ironlogv2.data.local.SetLogDraft
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*

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
}

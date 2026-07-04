package com.jauschua.ironlogv2.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CaptureDao {
    @Insert suspend fun insertSetLog(d: SetLogDraft)
    @Insert suspend fun insertSurvey(d: SurveyDraft)
    @Insert suspend fun insertNote(d: NoteDraft)

    @Query("SELECT * FROM setlog_draft WHERE sessionId = :sessionId ORDER BY draftId")
    suspend fun setLogsForSession(sessionId: Int): List<SetLogDraft>
    @Query("SELECT * FROM survey_draft WHERE sessionId = :sessionId ORDER BY draftId")
    suspend fun surveysForSession(sessionId: Int): List<SurveyDraft>
    @Query("SELECT * FROM note_draft WHERE sessionId = :sessionId ORDER BY draftId")
    suspend fun notesForSession(sessionId: Int): List<NoteDraft>

    @Query("DELETE FROM setlog_draft WHERE sessionId = :sessionId")
    suspend fun clearSetLogs(sessionId: Int)
    @Query("DELETE FROM survey_draft WHERE sessionId = :sessionId")
    suspend fun clearSurveys(sessionId: Int)
    @Query("DELETE FROM note_draft WHERE sessionId = :sessionId")
    suspend fun clearNotes(sessionId: Int)

    // ── Scoped upsert/query for the group-review sheet + session note ──────────────
    @Query("DELETE FROM survey_draft WHERE sessionId = :sessionId AND movementId IN (:movementIds)")
    suspend fun deleteSurveysForMovements(sessionId: Int, movementIds: List<Int>)

    @Query("SELECT * FROM survey_draft WHERE sessionId = :sessionId AND movementId IN (:movementIds) ORDER BY draftId")
    suspend fun surveysForMovements(sessionId: Int, movementIds: List<Int>): List<SurveyDraft>

    @Query("DELETE FROM note_draft WHERE sessionId = :sessionId AND movementId = :movementId")
    suspend fun deleteNoteForMovement(sessionId: Int, movementId: Int)

    @Query("SELECT * FROM note_draft WHERE sessionId = :sessionId AND movementId = :movementId ORDER BY draftId LIMIT 1")
    suspend fun noteForMovement(sessionId: Int, movementId: Int): NoteDraft?

    @Query("DELETE FROM note_draft WHERE sessionId = :sessionId AND movementId IS NULL")
    suspend fun deleteSessionNote(sessionId: Int)

    @Query("SELECT * FROM note_draft WHERE sessionId = :sessionId AND movementId IS NULL ORDER BY draftId LIMIT 1")
    suspend fun sessionNote(sessionId: Int): NoteDraft?
}

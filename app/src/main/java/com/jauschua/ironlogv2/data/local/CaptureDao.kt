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
}

package com.jauschua.ironlogv2.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CaptureDao {
    @Insert suspend fun insertSetLog(d: SetLogDraft)
    @Insert suspend fun insertSurvey(d: SurveyDraft)
    @Insert suspend fun insertNote(d: NoteDraft)

    @Query("DELETE FROM setlog_draft WHERE sessionId = :sessionId AND plannedSetId = :plannedSetId AND sideIndex = :sideIndex")
    suspend fun deleteSetLogForPlannedSetSide(sessionId: Int, plannedSetId: Int, sideIndex: Int)

    /**
     * Any existing draft row for (sessionId, plannedSetId), earliest first. Used by
     * [com.jauschua.ironlogv2.ui.screens.capture.CaptureViewModel.editLoggedSet] to carry an
     * unsurfaced actual (e.g. felt-peak on an HT/band-composite set) forward into a corrected
     * row, so editing load/reps never nulls a field the edit UI doesn't show.
     */
    @Query("SELECT * FROM setlog_draft WHERE sessionId = :sessionId AND plannedSetId = :plannedSetId ORDER BY draftId LIMIT 1")
    suspend fun setLogForPlannedSet(sessionId: Int, plannedSetId: Int): SetLogDraft?

    /**
     * Idempotent write, keyed on (sessionId, plannedSetId, sideIndex): re-logging the same set+side
     * (double-tap, retry, or an explicit correction) replaces its prior row in place rather than
     * appending a duplicate. The [SetLogDraft.sideIndex] is part of the key precisely so a
     * UNILATERAL exercise's two legitimate rows under one [SetLogDraft.plannedSetId] (side 1 =
     * sideIndex 0, side 2 = sideIndex 1) both survive — only a duplicate of the SAME side
     * collapses. Rows with a null [SetLogDraft.plannedSetId] have nothing to key on, so they
     * always insert as a new row (unchanged behavior). Wrapped in `@Transaction` so the
     * delete+insert pair commits atomically — a crash between the two can't leave the set
     * un-logged.
     *
     * This is THE fix for the Day-1 double-log incident: 7 planned sets were each submitted
     * twice (double-tap on "Log set"), producing 7 duplicate rows that double-counted volume on
     * submit. [insertSetLog] alone (plain `@Insert`) has no dedup, so every call always appended.
     */
    @Transaction
    suspend fun upsertSetLog(d: SetLogDraft) {
        if (d.plannedSetId != null) {
            deleteSetLogForPlannedSetSide(d.sessionId, d.plannedSetId, d.sideIndex)
        }
        insertSetLog(d)
    }

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

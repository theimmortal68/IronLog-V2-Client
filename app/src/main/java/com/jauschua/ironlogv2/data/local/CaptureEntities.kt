package com.jauschua.ironlogv2.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "setlog_draft")
data class SetLogDraft(
    @PrimaryKey(autoGenerate = true) val draftId: Long = 0,
    val sessionId: Int,
    val plannedSetId: Int?,
    val movementId: Int,
    val setIndex: Int,
    val setRole: String,
    val isWarmup: Boolean,
    /**
     * Side discriminator for a UNILATERAL exercise's set, which legitimately logs TWO rows under
     * the SAME [plannedSetId] — one per side (see `CaptureViewModel.logWorkingSet`). 0 = side 1
     * (and every bilateral set, which is always a single row); 1 = side 2. This is part of the
     * idempotency key for `CaptureDao.upsertSetLog`: a true double-submit of the same set+side
     * collapses to one row, while unilateral side-1 and side-2 both survive. Defaults to 0 so all
     * existing (bilateral) callers and rows are unaffected.
     */
    val sideIndex: Int = 0,
    val actualLoad: Double? = null,
    val actualReps: Int? = null,
    val feedbackTap: String? = null,
    val rpeNumeric: Double? = null,
    val actualUnassistedReps: Int? = null,
    val actualAssistedReps: Int? = null,
    val actualPlates: Double? = null,
    val bandPairId: Int? = null,
    val feltPeak: Double? = null,
)

@Entity(tableName = "survey_draft")
data class SurveyDraft(
    @PrimaryKey(autoGenerate = true) val draftId: Long = 0,
    val sessionId: Int,
    val movementId: Int,
    val stickingPoint: String? = null,
    val asymmetryFlag: Boolean? = null,
    val techniqueFlag: Boolean? = null,
)

@Entity(tableName = "note_draft")
data class NoteDraft(
    @PrimaryKey(autoGenerate = true) val draftId: Long = 0,
    val sessionId: Int,
    val movementId: Int? = null,
    val text: String,
)

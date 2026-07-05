package com.jauschua.ironlogv2.ui

object Routes {
    const val TODAY = "today"
    const val MOVEMENTS = "movements"
    const val MOVEMENT_DETAIL = "movement/{id}"
    const val BANDS = "bands"
    const val AUTOREGULATE = "autoregulate"
    const val CAPTURE = "capture"
    const val WIZARD = "wizard?programId={programId}"
    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/{id}"
    const val REVIEW = "review"

    /** Phase 1's program for the first-run wizard (beta has a single program). */
    const val DEFAULT_PROGRAM_ID = 1

    fun movementDetail(id: Int): String = "movement/$id"

    fun wizard(programId: Int = DEFAULT_PROGRAM_ID): String = "wizard?programId=$programId"

    fun historyDetail(id: Int): String = "history/$id"
}

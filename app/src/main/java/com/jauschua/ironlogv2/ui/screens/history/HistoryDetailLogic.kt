package com.jauschua.ironlogv2.ui.screens.history

import com.jauschua.ironlogv2.data.api.dto.LoggedSet
import com.jauschua.ironlogv2.data.api.dto.NoteOut
import com.jauschua.ironlogv2.data.api.dto.SurveyOut

/** Drop a trailing ".0" so 165.0 → "165" but 162.5 → "162.5". */
private fun fmtNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

/** Short glyph for a feedback tap; null when there's no tap. */
fun tapIndicator(tap: String?): String? = when (tap) {
    "ON_TARGET" -> "✓"
    "TOO_EASY" -> "↓ easy"
    "TOO_HARD" -> "↑ hard"
    else -> null
}

/**
 * One-line set summary: "load×reps" + optional " @rpe", tap glyph, " peak~felt", "(warmup)".
 * Absent load/reps render as "—"; absent rpe/peak/tap are omitted.
 */
fun formatSetLine(set: LoggedSet): String {
    val load = set.load?.let(::fmtNum) ?: "—"
    val reps = set.reps?.toString() ?: "—"
    val parts = mutableListOf("$load×$reps")
    set.rpe_numeric?.let { parts.add("@${fmtNum(it)}") }
    tapIndicator(set.tap)?.let { parts.add(it) }
    set.felt_peak?.let { parts.add("peak~${fmtNum(it)}") }
    if (set.is_warmup) parts.add("(warmup)")
    return parts.joinToString(" ")
}

/** The survey for a movement, or null. */
fun surveyFor(surveys: List<SurveyOut>, movementId: Int): SurveyOut? =
    surveys.firstOrNull { it.movement_id == movementId }

/** Notes attached to a specific movement (excludes the session note). */
fun notesFor(notes: List<NoteOut>, movementId: Int): List<NoteOut> =
    notes.filter { it.movement_id == movementId }

/** The session-level note text (movement_id == null), or null. */
fun sessionNoteText(notes: List<NoteOut>): String? =
    notes.firstOrNull { it.movement_id == null }?.text

/** Flag badge labels for a movement's survey. */
fun flagBadges(survey: SurveyOut?): List<String> {
    if (survey == null) return emptyList()
    val out = mutableListOf<String>()
    if (survey.asymmetry_flag == true) out.add("⚠ L/R")
    if (survey.technique_flag == true) out.add("⚠ tech")
    return out
}

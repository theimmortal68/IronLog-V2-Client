// ReviewLogic.kt
package com.jauschua.ironlogv2.ui.screens.review

import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.ProgramSlotOut

/** One-line summary of a proposed change, e.g. "Bench · switch · to incline"; empty if none. */
fun proposedChangeLine(n: NoteReviewOut): String =
    n.proposed_change?.let { pc ->
        listOfNotNull(pc.movement, pc.action, pc.params).joinToString(" · ")
    }.orEmpty()

/** Only CONFIG_CHANGE proposals name a concrete slot-level change — only these ever get an Apply
 *  affordance. PROGRAMMING_REQUEST etc. stay confirm/dismiss-only. */
fun isConfigChange(n: NoteReviewOut): Boolean = n.classification == "CONFIG_CHANGE"

/** The kind of adjustment Apply's confirm-wizard should route to. */
enum class AdjustmentKind { SWAP, LOAD, REPS, NONE }

private val SWAP_KEYWORDS = listOf("swap", "switch", "replace", "instead of", "change to", "sub in", "substitute")
private val LOAD_KEYWORDS = listOf("light", "heavy", "load", "weight", "increase", "decrease", "heavier", "lighter")
private val REPS_KEYWORDS = listOf("rep")

/** Maps the classifier's structured `action_type` (SWAP/LOAD_INCREASE/LOAD_DECREASE/REP_CHANGE/
 *  OTHER) to the adjustment the confirm-wizard should offer. Falls back to a keyword heuristic on
 *  the free-text `action` when `action_type` is null/absent (notes classified before it existed,
 *  or — currently — every note, since `/notes/review` does not surface `action_type` yet; see
 *  `NoteReviewOut`). An explicit `action_type` of "OTHER" is unclassifiable → NONE, no fallback. */
fun adjustmentKind(actionType: String?, actionText: String? = null): AdjustmentKind = when (actionType) {
    "SWAP" -> AdjustmentKind.SWAP
    "LOAD_INCREASE", "LOAD_DECREASE" -> AdjustmentKind.LOAD
    "REP_CHANGE" -> AdjustmentKind.REPS
    "OTHER" -> AdjustmentKind.NONE
    else -> keywordAdjustmentKind(actionText)
}

private fun keywordAdjustmentKind(actionText: String?): AdjustmentKind {
    val t = actionText?.lowercase()?.trim().orEmpty()
    if (t.isBlank()) return AdjustmentKind.NONE
    return when {
        SWAP_KEYWORDS.any { t.contains(it) } -> AdjustmentKind.SWAP
        LOAD_KEYWORDS.any { t.contains(it) } -> AdjustmentKind.LOAD
        REPS_KEYWORDS.any { t.contains(it) } -> AdjustmentKind.REPS
        else -> AdjustmentKind.NONE
    }
}

/** Apply is only offered for CONFIG_CHANGE notes whose action routes to a concrete adjustment
 *  (SWAP/LOAD/REPS). An unclassifiable CONFIG_CHANGE note (NONE) is Dismiss-only — Confirm would
 *  silently acknowledge a change nothing was actually applied for. Non-CONFIG_CHANGE notes
 *  (PROGRAMMING_REQUEST etc.) keep the plain Confirm/Dismiss path. */
fun showApply(n: NoteReviewOut): Boolean =
    isConfigChange(n) && adjustmentKind(n.action_type, n.proposed_change?.action) != AdjustmentKind.NONE

fun showConfirm(n: NoteReviewOut): Boolean = !isConfigChange(n)

/** Text to pre-seed the movement-picker search field with, from the note's proposed change. */
fun pickerSeedText(n: NoteReviewOut): String = n.proposed_change?.movement.orEmpty()

/** Case-insensitive substring filter over the movement library for the swap picker.
 *  Blank query returns the full list. */
fun filterMovements(all: List<MovementDto>, query: String): List<MovementDto> =
    if (query.isBlank()) all else all.filter { it.name.contains(query, ignoreCase = true) }

/** Defaults the Apply confirm-wizard's source slot by best case-insensitive substring match of
 *  the note's subject movement name (Gemini's extracted subject, e.g. "hip thrust") against the
 *  program's slots — so the wizard defaults to the Hip Thrust slot even if the note text attached
 *  to a different exercise (e.g. Dips) in the session. Null/blank subject or no match → null (the
 *  athlete must pick explicitly). Ties broken by the longest matching movement name (the most
 *  specific match). */
fun defaultSourceSlot(subject: String?, slots: List<ProgramSlotOut>): ProgramSlotOut? {
    val needle = subject?.trim()?.lowercase()
    if (needle.isNullOrBlank()) return null
    return slots
        .filter { slot ->
            val name = slot.movement_name?.trim()?.lowercase()
            !name.isNullOrBlank() && (name.contains(needle) || needle.contains(name))
        }
        .maxByOrNull { it.movement_name?.length ?: 0 }
}

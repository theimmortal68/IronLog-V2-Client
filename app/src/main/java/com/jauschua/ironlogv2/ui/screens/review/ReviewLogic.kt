// ReviewLogic.kt
package com.jauschua.ironlogv2.ui.screens.review

import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut

/** One-line summary of a proposed change, e.g. "Bench · switch · to incline"; empty if none. */
fun proposedChangeLine(n: NoteReviewOut): String =
    n.proposed_change?.let { pc ->
        listOfNotNull(pc.movement, pc.action, pc.params).joinToString(" · ")
    }.orEmpty()

/** Only CONFIG_CHANGE proposals name a concrete movement swap — only these get an Apply/picker
 *  affordance. PROGRAMMING_REQUEST etc. stay confirm/dismiss-only. */
fun isConfigChange(n: NoteReviewOut): Boolean = n.classification == "CONFIG_CHANGE"

/** A swap (CONFIG_CHANGE) shows Apply (which creates the override); a non-swap actionable note
 *  shows Confirm (acknowledge only). Both always keep Dismiss. Confirm on a swap is meaningless —
 *  it would leave the inbox without creating the override — so the two are mutually exclusive. */
fun showApply(n: NoteReviewOut): Boolean = isConfigChange(n)
fun showConfirm(n: NoteReviewOut): Boolean = !isConfigChange(n)

/** Text to pre-seed the movement-picker search field with, from the note's proposed change. */
fun pickerSeedText(n: NoteReviewOut): String = n.proposed_change?.movement.orEmpty()

/** Case-insensitive substring filter over the movement library for the swap picker.
 *  Blank query returns the full list. */
fun filterMovements(all: List<MovementDto>, query: String): List<MovementDto> =
    if (query.isBlank()) all else all.filter { it.name.contains(query, ignoreCase = true) }

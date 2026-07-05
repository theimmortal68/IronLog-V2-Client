// ReviewLogic.kt
package com.jauschua.ironlogv2.ui.screens.review

import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut

/** One-line summary of a proposed change, e.g. "Bench · switch · to incline"; empty if none. */
fun proposedChangeLine(n: NoteReviewOut): String =
    n.proposed_change?.let { pc ->
        listOfNotNull(pc.movement, pc.action, pc.params).joinToString(" · ")
    }.orEmpty()

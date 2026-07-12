package com.jauschua.ironlogv2.ui.review

import com.jauschua.ironlogv2.data.api.dto.LiftCategory
import com.jauschua.ironlogv2.data.api.dto.MovementDto
import com.jauschua.ironlogv2.data.api.dto.NoteReviewOut
import com.jauschua.ironlogv2.data.api.dto.ProgramSlotOut
import com.jauschua.ironlogv2.data.api.dto.ProposalOut
import com.jauschua.ironlogv2.data.api.dto.ProposedChange
import com.jauschua.ironlogv2.data.api.dto.Region
import com.jauschua.ironlogv2.ui.screens.review.AdjustmentKind
import com.jauschua.ironlogv2.ui.screens.review.adjustmentKind
import com.jauschua.ironlogv2.ui.screens.review.defaultSourceSlot
import com.jauschua.ironlogv2.ui.screens.review.displayMovementName
import com.jauschua.ironlogv2.ui.screens.review.filterMovements
import com.jauschua.ironlogv2.ui.screens.review.firstValidProposalIndex
import com.jauschua.ironlogv2.ui.screens.review.initialWizardState
import com.jauschua.ironlogv2.ui.screens.review.isConfigChange
import com.jauschua.ironlogv2.ui.screens.review.pickerSeedText
import com.jauschua.ironlogv2.ui.screens.review.proposalAdjustmentKind
import com.jauschua.ironlogv2.ui.screens.review.proposedChangeLine
import com.jauschua.ironlogv2.ui.screens.review.showApply
import com.jauschua.ironlogv2.ui.screens.review.showConfirm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLogicTest {
    private fun note(
        classification: String,
        proposedChange: ProposedChange? = null,
        actionType: String? = null,
    ) = NoteReviewOut(
        id = 1, session_id = 7, movement_id = 10, created_at = "2026-07-04T12:00:00",
        text = "switch bench to incline", classification = classification,
        proposed_change = proposedChange, confidence = 0.9, action_type = actionType,
    )

    private fun movement(id: Int, name: String) = MovementDto(
        id = id, name = name, base_name = name, region = Region.UPPER, lift_category = LiftCategory.BENCH,
    )

    private fun slot(teId: Int, movementName: String, dayRole: String = "D1", tierLabel: String = "T1") =
        ProgramSlotOut(
            tier_exercise_id = teId, slot_id = "slot-$teId", day_role = dayRole, tier_label = tierLabel,
            movement_id = teId, movement_name = movementName,
        )

    private fun proposal(overrideType: String, valid: Boolean = true) = ProposalOut(
        tier_exercise_id = 12,
        day_role = "D1 Upper Push",
        slot_label = "T1",
        override_type = overrideType,
        valid = valid,
    )

    @Test fun full_proposed_change_joins_with_dot_separator() {
        val n = note("CONFIG_CHANGE", ProposedChange(movement = "Bench", action = "switch", params = "to incline"))
        assertEquals("Bench · switch · to incline", proposedChangeLine(n))
    }

    @Test fun null_proposed_change_yields_empty_string() {
        val n = note("PROGRAMMING_REQUEST", proposedChange = null)
        assertEquals("", proposedChangeLine(n))
    }

    @Test fun programming_request_with_null_change_yields_empty_string() {
        val n = note("PROGRAMMING_REQUEST", proposedChange = null)
        assertEquals("", proposedChangeLine(n))
    }

    @Test fun isConfigChange_true_only_for_config_change_classification() {
        assertTrue(isConfigChange(note("CONFIG_CHANGE")))
        assertFalse(isConfigChange(note("PROGRAMMING_REQUEST")))
        assertFalse(isConfigChange(note("TRANSIENT_FLAG")))
        assertFalse(isConfigChange(note("JOURNAL")))
    }

    // --- adjustmentKind: action_type mapping ---

    @Test fun adjustmentKind_maps_action_type_enum_values() {
        assertEquals(AdjustmentKind.SWAP, adjustmentKind("SWAP"))
        assertEquals(AdjustmentKind.LOAD, adjustmentKind("LOAD_INCREASE"))
        assertEquals(AdjustmentKind.LOAD, adjustmentKind("LOAD_DECREASE"))
        assertEquals(AdjustmentKind.REPS, adjustmentKind("REP_CHANGE"))
        assertEquals(AdjustmentKind.REORDER, adjustmentKind("REORDER"))
        assertEquals(AdjustmentKind.NONE, adjustmentKind("OTHER"))
    }

    @Test fun proposalAdjustmentKind_maps_override_type_values() {
        assertEquals(AdjustmentKind.SWAP, proposalAdjustmentKind(proposal("MOVEMENT")))
        assertEquals(AdjustmentKind.LOAD, proposalAdjustmentKind(proposal("LOAD")))
        assertEquals(AdjustmentKind.REPS, proposalAdjustmentKind(proposal("REPS")))
        assertEquals(AdjustmentKind.REORDER, proposalAdjustmentKind(proposal("REORDER")))
        assertEquals(AdjustmentKind.NONE, proposalAdjustmentKind(proposal("UNKNOWN")))
    }

    @Test fun firstValidProposalIndex_selects_first_valid_candidate() {
        val proposals = listOf(
            proposal("LOAD", valid = false),
            proposal("REPS", valid = true),
        )

        assertEquals(1, firstValidProposalIndex(proposals))
    }

    @Test fun firstValidProposalIndex_empty_list_returns_null() {
        assertNull(firstValidProposalIndex(emptyList()))
    }

    @Test fun initialWizardState_empty_resolved_proposals_uses_legacy_kind_logic() {
        val n = note(
            "CONFIG_CHANGE",
            proposedChange = ProposedChange(movement = "Bench", action = "switch to incline"),
        )

        val state = initialWizardState(n)

        assertTrue(state.proposals.isEmpty())
        assertNull(state.selectedProposalIndex)
        assertEquals(adjustmentKind(n.action_type, n.proposed_change?.action), state.kind)
    }

    @Test fun firstValidProposalIndex_all_invalid_selects_first_invalid_proposal() {
        val proposals = listOf(
            proposal("LOAD", valid = false),
            proposal("REPS", valid = false),
        )

        assertEquals(0, firstValidProposalIndex(proposals))

        val state = initialWizardState(note("CONFIG_CHANGE").copy(resolved_proposals = proposals))
        assertEquals(false, state.selectedProposal?.valid)
    }

    @Test fun adjustmentKind_other_action_type_is_none_even_with_swap_like_text() {
        // An explicit OTHER classification is unclassifiable — no fallback to the free text.
        assertEquals(AdjustmentKind.NONE, adjustmentKind("OTHER", "switch to incline"))
    }

    // --- adjustmentKind: keyword fallback when action_type is null/absent ---

    @Test fun adjustmentKind_falls_back_to_keyword_heuristic_when_action_type_null() {
        assertEquals(AdjustmentKind.SWAP, adjustmentKind(null, "switch to incline"))
        assertEquals(AdjustmentKind.SWAP, adjustmentKind(null, "swap bench for incline"))
        assertEquals(AdjustmentKind.LOAD, adjustmentKind(null, "too light, bump the weight"))
        assertEquals(AdjustmentKind.LOAD, adjustmentKind(null, "too heavy"))
        assertEquals(AdjustmentKind.REPS, adjustmentKind(null, "do more reps"))
        assertEquals(AdjustmentKind.NONE, adjustmentKind(null, "felt great today"))
    }

    @Test fun adjustmentKind_null_action_type_and_blank_text_is_none() {
        assertEquals(AdjustmentKind.NONE, adjustmentKind(null, null))
        assertEquals(AdjustmentKind.NONE, adjustmentKind(null, ""))
    }

    @Test fun adjustmentKind_fallback_routes_rep_target_phrasing_to_reps() {
        // The server's own REP_CHANGE example — no "rep" word, an NxM scheme.
        assertEquals(AdjustmentKind.REPS, adjustmentKind(null, "drop OHP to 3x8"))
        assertEquals(AdjustmentKind.REPS, adjustmentKind(null, "3 x 8"))
        assertEquals(AdjustmentKind.REPS, adjustmentKind(null, "move to 5X5"))
        // "increase reps" / "more reps" must NOT be swallowed by the LOAD "increase" keyword.
        assertEquals(AdjustmentKind.REPS, adjustmentKind(null, "increase reps"))
        assertEquals(AdjustmentKind.REPS, adjustmentKind(null, "more reps"))
        assertEquals(AdjustmentKind.REPS, adjustmentKind(null, "change the rep target"))
    }

    @Test fun adjustmentKind_fallback_keeps_load_and_swap_for_their_phrasings() {
        assertEquals(AdjustmentKind.LOAD, adjustmentKind(null, "too light"))
        assertEquals(AdjustmentKind.LOAD, adjustmentKind(null, "increase weight"))
        assertEquals(AdjustmentKind.LOAD, adjustmentKind(null, "too heavy"))
        assertEquals(AdjustmentKind.SWAP, adjustmentKind(null, "switch to incline"))
        assertEquals(AdjustmentKind.SWAP, adjustmentKind(null, "swap for dumbbells"))
        assertEquals(AdjustmentKind.SWAP, adjustmentKind(null, "change to incline press"))
    }

    // --- Apply/Confirm gating ---

    @Test fun config_change_with_swap_kind_shows_apply_not_confirm() {
        val swap = note("CONFIG_CHANGE", ProposedChange(movement = "Bench", action = "switch"))
        assertTrue(showApply(swap))
        assertFalse(showConfirm(swap))
    }

    @Test fun config_change_with_load_kind_shows_apply_not_confirm() {
        val load = note("CONFIG_CHANGE", ProposedChange(movement = "Hip Thrust"), actionType = "LOAD_INCREASE")
        assertTrue(showApply(load))
        assertFalse(showConfirm(load))
    }

    @Test fun config_change_with_reps_kind_shows_apply_not_confirm() {
        val reps = note("CONFIG_CHANGE", ProposedChange(movement = "Squat"), actionType = "REP_CHANGE")
        assertTrue(showApply(reps))
        assertFalse(showConfirm(reps))
    }

    @Test fun config_change_with_resolved_reorder_proposal_shows_apply_not_confirm() {
        val reorder = note(
            "CONFIG_CHANGE",
            proposedChange = ProposedChange(movement = "Squat", action = "felt wrong"),
        ).copy(resolved_proposals = listOf(proposal("REORDER")))
        assertTrue(showApply(reorder))
        assertFalse(showConfirm(reorder))
    }

    @Test fun config_change_with_unclassifiable_action_shows_neither_apply_nor_confirm() {
        val unclear = note("CONFIG_CHANGE", ProposedChange(movement = "Bench"), actionType = "OTHER")
        assertFalse(showApply(unclear))
        assertFalse(showConfirm(unclear))
    }

    @Test fun non_config_change_shows_confirm_not_apply() {
        for (cls in listOf("PROGRAMMING_REQUEST", "TRANSIENT_FLAG", "JOURNAL")) {
            val n = note(cls)
            assertFalse("expected no Apply for $cls", showApply(n))
            assertTrue("expected Confirm for $cls", showConfirm(n))
        }
    }

    @Test fun pickerSeedText_uses_proposed_change_movement_or_blank() {
        val n = note("CONFIG_CHANGE", ProposedChange(movement = "Bench", action = "switch", params = "to incline"))
        assertEquals("Bench", pickerSeedText(n))
        assertEquals("", pickerSeedText(note("PROGRAMMING_REQUEST", proposedChange = null)))
    }

    @Test fun filterMovements_blank_query_returns_all() {
        val all = listOf(movement(1, "Bench"), movement(2, "Incline Bench"))
        assertEquals(all, filterMovements(all, ""))
        assertEquals(all, filterMovements(all, "   "))
    }

    @Test fun filterMovements_matches_case_insensitive_substring() {
        val all = listOf(movement(1, "Bench"), movement(2, "Incline Bench"), movement(3, "Squat"))
        val result = filterMovements(all, "bench")
        assertEquals(listOf(movement(1, "Bench"), movement(2, "Incline Bench")), result)
    }

    @Test fun filterMovements_no_match_returns_empty() {
        val all = listOf(movement(1, "Bench"))
        assertTrue(filterMovements(all, "zzz").isEmpty())
    }

    // --- defaultSourceSlot ---

    @Test fun defaultSourceSlot_matches_subject_even_when_other_slots_present() {
        val slots = listOf(slot(1, "Dips"), slot(2, "Hip Thrust"), slot(3, "Squat"))
        val result = defaultSourceSlot("hip thrust", slots)
        assertEquals(slots[1], result)
    }

    @Test fun defaultSourceSlot_is_case_insensitive() {
        val slots = listOf(slot(1, "Hip Thrust"))
        assertEquals(slots[0], defaultSourceSlot("HIP THRUST", slots))
        assertEquals(slots[0], defaultSourceSlot("Hip Thrust", slots))
    }

    @Test fun defaultSourceSlot_prefers_longest_most_specific_match() {
        val slots = listOf(slot(1, "Thrust"), slot(2, "Hip Thrust"))
        val result = defaultSourceSlot("hip thrust", slots)
        assertEquals(slots[1], result)
    }

    @Test fun defaultSourceSlot_null_or_blank_subject_returns_null() {
        val slots = listOf(slot(1, "Hip Thrust"))
        assertNull(defaultSourceSlot(null, slots))
        assertNull(defaultSourceSlot("   ", slots))
    }

    @Test fun defaultSourceSlot_no_match_returns_null() {
        val slots = listOf(slot(1, "Bench"), slot(2, "Squat"))
        assertNull(defaultSourceSlot("hip thrust", slots))
    }

    @Test fun defaultSourceSlot_empty_slots_returns_null() {
        assertNull(defaultSourceSlot("hip thrust", emptyList()))
    }

    // --- displayMovementName ---

    @Test fun displayMovementName_strips_trailing_code_bracket() {
        assertEquals("Hip Thrust", displayMovementName("Hip Thrust [HIP_THRUST]"))
        assertEquals("Light Reverse Hyper", displayMovementName("Light Reverse Hyper [REV_HYPER]"))
        assertEquals("Bench Press", displayMovementName("Bench Press [PB]"))
    }

    @Test fun displayMovementName_no_bracket_returns_unchanged() {
        assertEquals("Hip Thrust", displayMovementName("Hip Thrust"))
    }

    @Test fun displayMovementName_internal_bracket_not_at_end_is_left_alone() {
        assertEquals("Hip [Thrust] Variant", displayMovementName("Hip [Thrust] Variant"))
    }

    @Test fun displayMovementName_trims_and_handles_blank() {
        assertEquals("", displayMovementName(""))
        assertEquals("Squat", displayMovementName("  Squat [SQUAT]  "))
    }
}

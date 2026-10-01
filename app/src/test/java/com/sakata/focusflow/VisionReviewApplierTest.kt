package com.sakata.focusflow

import org.junit.Assert.*
import org.junit.Test

class VisionReviewApplierTest {
    private fun preview() = VisionGridPreview(
        VisionImageGeometry(
            100, 100, listOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),
            listOf(VisionGridAxis(2, 0.0, 1.0, "manual")),
            listOf(VisionGridAxis(3, 0.0, 1.0, "manual")),
            listOf("manual")
        ),
        cells = mapOf("confirmed" to VisionGridCell(2, 3, 4), "unknown" to null, "reject" to null),
        correctedBoxes = mapOf(
            "confirmed" to VisionCorrectedBox(VisionBox(0.1,0.1,0.2,0.2), VisionBox(0.1,0.1,0.2,0.2)),
            "unknown" to VisionCorrectedBox(VisionBox(0.3,0.3,0.4,0.4), VisionBox(0.3,0.3,0.4,0.4)),
            "reject" to VisionCorrectedBox(VisionBox(0.5,0.5,0.6,0.6), VisionBox(0.5,0.5,0.6,0.6))
        )
    )

    private fun candidate(id: String, day: Int? = null, start: Int? = null, end: Int? = null) =
        VisionCandidate(id, "课程 " + id, day, start, end, VisionBox(0.1,0.1,0.2,0.2), emptyList())

    @Test fun explicit_confirmation_uses_preview_cell_and_sets_user_state() {
        val result = VisionReviewApplier.apply(
            preview(), listOf(candidate("confirmed")), listOf(
                VisionReviewDecision("confirmed", VisionReviewAction.CONFIRM)
            )
        )
        assertEquals(VisionCandidateState.CONFIRMED_BY_USER, result.candidates.single().state)
        assertEquals(2, result.candidates.single().day)
        assertEquals(3, result.candidates.single().startPeriod)
        assertTrue(result.unresolvedIds.isEmpty())
    }

    @Test fun incomplete_edit_stays_unresolved() {
        val result = VisionReviewApplier.apply(
            preview(), listOf(candidate("unknown")), listOf(
                VisionReviewDecision("unknown", VisionReviewAction.EDIT, title = "改名")
            )
        )
        assertEquals(VisionCandidateState.NEEDS_REVIEW, result.candidates.single().state)
        assertEquals(setOf("unknown"), result.unresolvedIds)
        assertTrue(result.warnings.any { it.contains("信息不完整") })
    }

    @Test fun reject_is_explicit_and_duplicate_decisions_fail_closed() {
        val rejected = VisionReviewApplier.apply(
            preview(), listOf(candidate("reject")), listOf(
                VisionReviewDecision("reject", VisionReviewAction.REJECT, note = "非课程")
            )
        )
        assertEquals(VisionCandidateState.REJECTED, rejected.candidates.single().state)

        val duplicate = VisionReviewApplier.apply(
            preview(), listOf(candidate("confirmed")), listOf(
                VisionReviewDecision("confirmed", VisionReviewAction.CONFIRM, day = 1, startPeriod = 1, endPeriod = 1),
                VisionReviewDecision("confirmed", VisionReviewAction.REJECT)
            )
        )
        assertEquals(VisionCandidateState.NEEDS_REVIEW, duplicate.candidates.single().state)
        assertTrue(duplicate.warnings.any { it.contains("多个审核决定") })
    }

    @Test fun unknown_decision_does_not_create_a_candidate() {
        val result = VisionReviewApplier.apply(
            preview(), listOf(candidate("confirmed")), listOf(
                VisionReviewDecision("missing", VisionReviewAction.CONFIRM, day = 1, startPeriod = 1, endPeriod = 1)
            )
        )
        assertEquals(1, result.candidates.size)
        assertTrue(result.warnings.any { it.contains("未知候选") })
    }
}

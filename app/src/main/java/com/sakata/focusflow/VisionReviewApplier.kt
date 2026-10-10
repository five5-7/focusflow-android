package com.sakata.focusflow

/** Batch C review actions. Applying a decision never writes Course or Room directly. */
enum class VisionReviewAction { CONFIRM, EDIT, REJECT }

data class VisionReviewDecision(
    val candidateId: String,
    val action: VisionReviewAction,
    val day: Int? = null,
    val startPeriod: Int? = null,
    val endPeriod: Int? = null,
    val title: String? = null,
    val rawLocation: String? = null,
    val note: String? = null
)

data class VisionReviewResult(
    val candidates: List<VisionCandidate>,
    val unresolvedIds: Set<String>,
    val warnings: List<String>
)

object VisionReviewApplier {
    /**
     * Applies only explicit user decisions to the supplied preview candidates.
     * The caller remains responsible for passing the result through the existing
     * CourseImportBatch/Policy confirmation path.
     */
    fun apply(
        preview: VisionGridPreview,
        candidates: List<VisionCandidate>,
        decisions: List<VisionReviewDecision>
    ): VisionReviewResult {
        val source = candidates.associateBy { it.id }
        val duplicateIds = decisions.groupBy { it.candidateId }.filterValues { it.size > 1 }.keys
        val decisionById = decisions.associateBy { it.candidateId }
        val warnings = mutableListOf<String>()
        warnings += duplicateIds.map { "候选 " + it + " 存在多个审核决定，已保留未确认状态" }
        decisions.map { it.candidateId }.filter { it !in source }.distinct()
            .forEach { warnings += "审核决定引用了未知候选 " + it }

        val reviewed = candidates.map { original ->
            val decision = decisionById[original.id]
            if (original.id in duplicateIds) return@map original.copy(state = VisionCandidateState.NEEDS_REVIEW)
            if (decision == null) return@map original
            when (decision.action) {
                VisionReviewAction.REJECT -> original.copy(
                    state = VisionCandidateState.REJECTED,
                    note = decision.note
                )
                VisionReviewAction.CONFIRM, VisionReviewAction.EDIT -> {
                    val cell = preview.cells[original.id]
                    if (decision.action == VisionReviewAction.EDIT &&
                        (decision.day == null || decision.startPeriod == null || decision.endPeriod == null)) {
                        warnings += "候选 " + original.id + " 的审核信息不完整，仍需确认"
                        return@map original.copy(state = VisionCandidateState.NEEDS_REVIEW)
                    }
                    val edited = original.copy(
                        title = decision.title ?: original.title,
                        day = decision.day ?: cell?.day ?: original.day,
                        startPeriod = decision.startPeriod ?: cell?.startPeriod ?: original.startPeriod,
                        endPeriod = decision.endPeriod ?: cell?.endPeriod ?: original.endPeriod,
                        rawLocation = decision.rawLocation ?: original.rawLocation,
                        note = decision.note
                    )
                    if (!edited.valid() || edited.day == null || edited.startPeriod == null || edited.endPeriod == null) {
                        warnings += "候选 " + original.id + " 的审核信息不完整，仍需确认"
                        original.copy(state = VisionCandidateState.NEEDS_REVIEW)
                    } else edited.copy(state = VisionCandidateState.CONFIRMED_BY_USER)
                }
            }
        }
        val unresolved = reviewed.filter { it.state == VisionCandidateState.NEEDS_REVIEW }.map { it.id }.toSet()
        return VisionReviewResult(reviewed, unresolved, warnings)
    }
}

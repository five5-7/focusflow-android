package com.sakata.focusflow

/**
 * 多课次候选的**只读内存预览**。
 *
 * 授权边界（见 `docs/9.0-stage6-course-identity-review3.md`）：`xkkh` 只被允许作为
 * "同一请求学年＋学期内的候选分组键"进入只读设计与内存预览，**尚未证明等同教学班身份**。
 * 因此本组件：
 *
 * - 不写任何存储、不改 `Course.id`、不动提醒键与 `CourseLocationOverrides`、不碰 Room schema；
 * - 不做归并、不产生新的课程父记录、不触发任何闹钟；
 * - 只回答一个问题：**这一批响应里，哪些行看起来属于同一个候选组，以及哪些组必须先人工核对**。
 *
 * 分组键是完整三元组 `(schoolYearCode, termCode, xkkh)`——同号跨学期绝不合并。
 * 任何"无法证实同一性"的情形一律落在 [PreviewReviewReason]，不会被当成多课次结论。
 */
internal object CourseIdentityPreview {

    /** 需要人工核对的原因。每一个都对应"不能据此归并"的具体证据缺口。 */
    internal enum class PreviewReviewReason {
        /** 响应里没有可用的学年/学期代码：无法限定分组范围。 */
        MISSING_REQUEST_CODES,

        /** 候选键为空：真实样本里同一教学班可能出现空 `xkkh`。 */
        MISSING_SELECTION_KEY,

        /** 组内有行没有标题：无法证明是同一门课（**空标题不等于"标题一致"**）。 */
        MISSING_TITLE,

        /** 同键组的标题不一致（同号异标题）：不能假定是同一门课。 */
        CONFLICTING_TITLES,

        /** 组内存在缺星期/节次的行：无法判断是否为独立课次。 */
        INCOMPLETE_ROW,

        /** 星期或节次越界／倒置：该行不能作为有效课次的证据。 */
        INVALID_MEETING,

        /** 组内出现完全相同的星期＋节次：更像重复行，不足以支撑"多课次"结论。 */
        DUPLICATE_MEETING,

        /** 同一天的两行节次重叠，不能在没有班级证据时当作两个独立课次。 */
        OVERLAPPING_MEETINGS
    }

    /** 候选组的状态。只有 [MULTI_MEETING_CANDIDATE] 才是"看起来同班多课次"。 */
    internal enum class PreviewStatus {
        /** 同键、同标题、多行且课次互不相同：**候选**（仍需真实抽样核对后才可归并）。 */
        MULTI_MEETING_CANDIDATE,

        /** 同键只有一行：单课次，不构成多课次候选。 */
        SINGLE_ROW,

        /** 存在无法证实的点，必须先人工核对；不得据此归并。 */
        NEEDS_REVIEW
    }

    /**
     * 一个候选组。[rows] 按 [ZjuTimetableCandidateRow.sourceRowIndex] 升序，保持与原始响应行的可溯源关系。
     */
    internal data class PreviewGroup(
        val schoolYearCode: String,
        val termCode: String,
        val selectionKey: String,
        val title: String,
        val rows: List<ZjuTimetableCandidateRow>,
        val status: PreviewStatus,
        val reviewReasons: List<PreviewReviewReason>
    ) {
        /**
         * 供界面/日志使用的组合字符串；**不是**持久身份，不得据此写库。
         * 它**不保证唯一**（字段未转义：缺代码的行会得到形如 `//KEY` 的串），
         * 因此不可直接用作列表键或 Map 键；需要唯一键时请用 [rows] 的首个 `sourceRowIndex`。
         */
        val previewKey: String get() = "$schoolYearCode/$termCode/$selectionKey"
    }

    /**
     * 把一批候选行分成候选组。输出顺序按组内首行序号升序，保证同一输入产生同一输出。
     * 不抛异常：任何不完整输入都体现在 [PreviewGroup.reviewReasons] 里。
     */
    internal fun group(rows: List<ZjuTimetableCandidateRow>): List<PreviewGroup> {
        val usable = mutableListOf<ZjuTimetableCandidateRow>()
        val standalone = mutableListOf<PreviewGroup>()

        // 1) 先摘出"无法进入分组"的行：缺请求代码或缺候选键。它们各自成群、一律待核对，
        //    两个原因都成立时**都记**（审计轨迹完整，而不是只报第一个）。
        for (row in rows.sortedBy { it.sourceRowIndex }) {
            when {
                !row.hasRequestCodes || row.externalSelectionKeyCandidate.isBlank() ->
                    standalone += singleRowGroup(row)
                else -> usable += row
            }
        }

        // 2) 按完整三元组分组：同号跨学期不会相遇。
        val grouped = usable
            .groupBy { Triple(it.schoolYearCode, it.termCode, it.externalSelectionKeyCandidate) }
            .map { (key, rowsInKey) -> buildGroup(key.first, key.second, key.third, rowsInKey) }

        return (standalone + grouped).sortedBy { group -> group.rows.first().sourceRowIndex }
    }

    private fun singleRowGroup(row: ZjuTimetableCandidateRow): PreviewGroup {
        val reasons = buildList {
            if (!row.hasRequestCodes) add(PreviewReviewReason.MISSING_REQUEST_CODES)
            if (row.externalSelectionKeyCandidate.isBlank()) add(PreviewReviewReason.MISSING_SELECTION_KEY)
            if (row.title.isBlank()) add(PreviewReviewReason.MISSING_TITLE)
            if (row.hasInvalidMeeting()) add(PreviewReviewReason.INVALID_MEETING)
        }
        return PreviewGroup(
            schoolYearCode = row.schoolYearCode,
            termCode = row.termCode,
            selectionKey = row.externalSelectionKeyCandidate,
            title = row.title,
            rows = listOf(row),
            status = PreviewStatus.NEEDS_REVIEW,
            reviewReasons = reasons
        )
    }

    private fun buildGroup(
        schoolYearCode: String,
        termCode: String,
        selectionKey: String,
        rowsInKey: List<ZjuTimetableCandidateRow>
    ): PreviewGroup {
        val sorted = rowsInKey.sortedBy { it.sourceRowIndex }
        val reasons = mutableListOf<PreviewReviewReason>()

        if (sorted.any { it.weekday == null || it.startPeriod == null || it.endPeriod == null }) {
            reasons += PreviewReviewReason.INCOMPLETE_ROW
        }
        if (sorted.any { it.hasInvalidMeeting() }) {
            reasons += PreviewReviewReason.INVALID_MEETING
        }
        // 空标题必须待核对：把"没有标题"当成"标题一致"会凭空产出多课次结论（复验 D1）。
        if (sorted.any { it.title.isBlank() }) {
            reasons += PreviewReviewReason.MISSING_TITLE
        }
        val titles = sorted.map { it.title.trim() }.filter { it.isNotBlank() }.distinct()
        if (titles.size > 1) reasons += PreviewReviewReason.CONFLICTING_TITLES
        val meetings = sorted.map { Triple(it.weekday, it.startPeriod, it.endPeriod) }
        if (meetings.size > 1 && meetings.distinct().size < meetings.size) {
            reasons += PreviewReviewReason.DUPLICATE_MEETING
        }
        if (sorted.indices.any { left ->
                ((left + 1) until sorted.size).any { right ->
                    val a = sorted[left]
                    val b = sorted[right]
                    !a.hasInvalidMeeting() && !b.hasInvalidMeeting() &&
                        a.weekday != null && a.weekday == b.weekday &&
                        a.startPeriod != null && a.endPeriod != null &&
                        b.startPeriod != null && b.endPeriod != null &&
                        a.startPeriod <= b.endPeriod && b.startPeriod <= a.endPeriod &&
                        Triple(a.weekday, a.startPeriod, a.endPeriod) !=
                            Triple(b.weekday, b.startPeriod, b.endPeriod)
                }
            }) reasons += PreviewReviewReason.OVERLAPPING_MEETINGS

        // 待核对优先于"单行"：证据不足的行即使只有一条也不能被当成干净的结论。
        val status = when {
            reasons.isNotEmpty() -> PreviewStatus.NEEDS_REVIEW
            sorted.size == 1 -> PreviewStatus.SINGLE_ROW
            else -> PreviewStatus.MULTI_MEETING_CANDIDATE
        }
        return PreviewGroup(
            schoolYearCode = schoolYearCode,
            termCode = termCode,
            selectionKey = selectionKey,
            title = titles.firstOrNull().orEmpty(),
            rows = sorted,
            status = status,
            reviewReasons = reasons.toList()
        )
    }

    private fun ZjuTimetableCandidateRow.hasInvalidMeeting(): Boolean {
        val day = weekday ?: return false // 缺失由 INCOMPLETE_ROW 单独说明。
        val start = startPeriod ?: return false
        val end = endPeriod ?: return false
        return day !in 1..7 || start !in 1..20 || end !in start..20
    }
}

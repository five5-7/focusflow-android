package com.sakata.focusflow

import com.sakata.focusflow.CourseIdentityPreview.PreviewReviewReason
import com.sakata.focusflow.CourseIdentityPreview.PreviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多课次候选**内存预览**的分组规则测试。
 *
 * 全部用合成输入：真实 `kbList` 行不可入库（见 `AGENTS.md` 与字段证据文档）。
 */
class CourseIdentityPreviewTest {

    private fun row(
        index: Int,
        key: String = "KEY-A",
        year: String = "2026-2027",
        term: String = "1|秋",
        title: String = "高等数学",
        weekday: Int? = 1,
        start: Int? = 1,
        end: Int? = 2
    ) = ZjuTimetableCandidateRow(
        sourceRowIndex = index,
        schoolYearCode = year,
        termCode = term,
        externalSelectionKeyCandidate = key,
        weekday = weekday,
        startPeriod = start,
        endPeriod = end,
        title = title
    )

    @Test
    fun `rows sharing a selection key within one semester form a multi-meeting candidate`() {
        val groups = CourseIdentityPreview.group(
            listOf(
                row(0, weekday = 1, start = 1, end = 2),
                row(1, weekday = 3, start = 3, end = 4)
            )
        )

        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals(PreviewStatus.MULTI_MEETING_CANDIDATE, group.status)
        assertTrue("a candidate must carry no review reasons", group.reviewReasons.isEmpty())
        assertEquals(listOf(0, 1), group.rows.map { it.sourceRowIndex })
        assertEquals("2026-2027/1|秋/KEY-A", group.previewKey)
    }

    @Test
    fun `the same selection key in another semester is never merged`() {
        val groups = CourseIdentityPreview.group(
            listOf(
                row(0, term = "1|秋"),
                row(1, term = "2|短")
            )
        )

        assertEquals("different semesters must stay separate", 2, groups.size)
        assertTrue(groups.all { it.status == PreviewStatus.SINGLE_ROW })
    }

    @Test
    fun `an empty selection key needs review instead of grouping`() {
        val groups = CourseIdentityPreview.group(listOf(row(0, key = ""), row(1, key = "")))

        assertEquals(2, groups.size)
        groups.forEach { group ->
            assertEquals(PreviewStatus.NEEDS_REVIEW, group.status)
            assertEquals(listOf(PreviewReviewReason.MISSING_SELECTION_KEY), group.reviewReasons)
        }
    }

    @Test
    fun `rows without request codes need review`() {
        val groups = CourseIdentityPreview.group(listOf(row(0, year = "", term = "")))

        val group = groups.single()
        assertEquals(PreviewStatus.NEEDS_REVIEW, group.status)
        assertEquals(listOf(PreviewReviewReason.MISSING_REQUEST_CODES), group.reviewReasons)
    }

    @Test
    fun `a row missing both codes and key records both reasons`() {
        val group = CourseIdentityPreview.group(listOf(row(0, key = "", year = "", term = ""))).single()

        assertEquals(
            listOf(PreviewReviewReason.MISSING_REQUEST_CODES, PreviewReviewReason.MISSING_SELECTION_KEY),
            group.reviewReasons
        )
    }

    @Test
    fun `blank titles are never treated as a consistent title`() {
        // 复验 D1：空标题曾被当作"无冲突"，从而把同键多行判成多课次候选。
        val groups = CourseIdentityPreview.group(
            listOf(
                row(0, title = "", weekday = 1, start = 1, end = 2),
                row(1, title = "", weekday = 3, start = 3, end = 4)
            )
        )

        val group = groups.single()
        assertEquals(PreviewStatus.NEEDS_REVIEW, group.status)
        assertEquals(listOf(PreviewReviewReason.MISSING_TITLE), group.reviewReasons)
    }

    @Test
    fun `a partly blank title group needs review too`() {
        val groups = CourseIdentityPreview.group(
            listOf(
                row(0, title = "高等数学", weekday = 1, start = 1, end = 2),
                row(1, title = "", weekday = 3, start = 3, end = 4)
            )
        )

        val group = groups.single()
        assertEquals(PreviewStatus.NEEDS_REVIEW, group.status)
        assertEquals(listOf(PreviewReviewReason.MISSING_TITLE), group.reviewReasons)
    }

    @Test
    fun `review reasons take priority over the single row status`() {
        // 证据不足的行即使只有一条，也不是干净的 SINGLE_ROW 结论。
        val group = CourseIdentityPreview.group(listOf(row(0, weekday = null, start = null, end = null))).single()

        assertEquals(PreviewStatus.NEEDS_REVIEW, group.status)
        assertEquals(listOf(PreviewReviewReason.INCOMPLETE_ROW), group.reviewReasons)
    }

    @Test
    fun `one key with conflicting titles needs review`() {
        val groups = CourseIdentityPreview.group(
            listOf(
                row(0, title = "高等数学", weekday = 1, start = 1, end = 2),
                row(1, title = "线性代数", weekday = 3, start = 3, end = 4)
            )
        )

        val group = groups.single()
        assertEquals(PreviewStatus.NEEDS_REVIEW, group.status)
        assertEquals(listOf(PreviewReviewReason.CONFLICTING_TITLES), group.reviewReasons)
    }

    @Test
    fun `several reasons can apply to the same key at once`() {
        val groups = CourseIdentityPreview.group(
            listOf(
                row(0, title = "高等数学", weekday = 2, start = 5, end = 6),
                row(1, title = "线性代数", weekday = 2, start = 5, end = 6)
            )
        )

        val group = groups.single()
        assertEquals(PreviewStatus.NEEDS_REVIEW, group.status)
        assertEquals(
            listOf(PreviewReviewReason.CONFLICTING_TITLES, PreviewReviewReason.DUPLICATE_MEETING),
            group.reviewReasons
        )
    }

    @Test
    fun `identical meetings under one key are not sold as multiple meetings`() {
        val groups = CourseIdentityPreview.group(
            listOf(row(0, weekday = 2, start = 5, end = 6), row(1, weekday = 2, start = 5, end = 6))
        )

        val group = groups.single()
        assertEquals(PreviewStatus.NEEDS_REVIEW, group.status)
        assertEquals(listOf(PreviewReviewReason.DUPLICATE_MEETING), group.reviewReasons)
    }

    @Test
    fun `an incomplete row inside a key forces review`() {
        val groups = CourseIdentityPreview.group(
            listOf(row(0, weekday = 1, start = 1, end = 2), row(1, weekday = null, start = null, end = null))
        )

        val group = groups.single()
        assertEquals(PreviewStatus.NEEDS_REVIEW, group.status)
        assertEquals(listOf(PreviewReviewReason.INCOMPLETE_ROW), group.reviewReasons)
    }

    @Test
    fun `a single row is not a multi-meeting candidate`() {
        val group = CourseIdentityPreview.group(listOf(row(0))).single()

        assertEquals(PreviewStatus.SINGLE_ROW, group.status)
        assertTrue(group.reviewReasons.isEmpty())
    }

    @Test
    fun `groups are ordered by their first source row and stay deterministic`() {
        val input = listOf(
            row(5, key = "KEY-B", title = "大学物理"),
            row(0, key = "KEY-A"),
            row(2, key = "KEY-A", weekday = 4, start = 7, end = 8)
        )

        val first = CourseIdentityPreview.group(input)
        val second = CourseIdentityPreview.group(input.reversed())

        // 顺序是结论的一部分：不能靠 .sorted() 抹掉它。
        assertEquals(listOf(0, 2, 5), first.flatMap { it.rows }.map { it.sourceRowIndex })
        assertEquals("order must not depend on input order", first, second)
        assertEquals(listOf("KEY-A", "KEY-B"), first.map { it.selectionKey })
    }

    @Test
    fun `an empty batch produces no groups`() {
        assertTrue(CourseIdentityPreview.group(emptyList()).isEmpty())
    }
}

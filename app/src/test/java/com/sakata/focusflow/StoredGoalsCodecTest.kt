package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredGoalsCodecTest {
    @Test
    fun `loads legacy goal with defaults for fields added after first release`() {
        val raw = """[{"id":7,"title":"复习高数","weeklyTarget":3,"durationMinutes":30}]"""

        val goal = StoredGoalsCodec.decodeGoals(raw, defaultWeekKey = 1234L).single()

        assertEquals(7L, goal.id)
        assertEquals("复习高数", goal.title)
        assertEquals("时长", goal.metricType)
        assertEquals("", goal.resourceTitle)
        assertEquals("", goal.desiredOutcome)
        assertEquals("", goal.firstAction)
        assertEquals(1234L, goal.completionWeekKey)
    }

    @Test
    fun `round trips current goal fields without loss`() {
        val original = Goal(
            id = 9L,
            title = "科目一",
            weeklyTarget = 4,
            durationMinutes = 25,
            metricType = "成果",
            metricTarget = "完成一套题",
            minimumVersion = "先做 5 题",
            resourceTitle = "题库",
            resourceUnit = "错题章节",
            completedThisWeek = 2,
            minimumCompletionsThisWeek = 1,
            completionWeekKey = 5678L,
            desiredOutcome = "稳定通过模拟考试",
            firstAction = "先做一套模拟题并标记错题"
        )

        assertEquals(listOf(original), StoredGoalsCodec.decodeGoals(StoredGoalsCodec.encodeGoals(listOf(original))))
    }

    @Test
    fun `two goals keep independent resources and first actions`() {
        val goals = listOf(
            Goal(title = "高数", weeklyTarget = 2, durationMinutes = 30, resourceTitle = "高数讲义", firstAction = "看例题 1"),
            Goal(title = "科目一", weeklyTarget = 3, durationMinutes = 20, resourceTitle = "题库", firstAction = "做模拟题")
        )

        val restored = StoredGoalsCodec.decodeGoals(StoredGoalsCodec.encodeGoals(goals))

        assertEquals("高数讲义", restored[0].resourceTitle)
        assertEquals("看例题 1", restored[0].firstAction)
        assertEquals("题库", restored[1].resourceTitle)
        assertEquals("做模拟题", restored[1].firstAction)
    }

    @Test
    fun `loads legacy resource without selection or summary`() {
        val resource = StoredGoalsCodec.decodeResources("""[{"id":5,"title":"线代课程","url":"https://example.com"}]""").single()

        assertEquals("线代课程", resource.title)
        assertFalse(resource.selected)
        assertEquals("", resource.summary)
    }

    @Test
    fun `round trips current resources and rejects malformed payload`() {
        val original = LearningResource(11L, "概率论", "https://example.com/p", selected = true, summary = "条件概率")

        assertEquals(listOf(original), StoredGoalsCodec.decodeResources(StoredGoalsCodec.encodeResources(listOf(original))))
        assertTrue(StoredGoalsCodec.decodeGoals("not-json").isEmpty())
        assertTrue(StoredGoalsCodec.decodeResources("{").isEmpty())
    }
}

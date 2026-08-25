package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanGapModelsTest {
    @Test
    fun `specific activity location wins over broad exercise hint`() {
        assertEquals("游泳馆", locationHintFor("游泳与体能锻炼"))
    }

    @Test
    fun `learning and entertainment retain coarse location hints`() {
        assertEquals("图书馆/教学楼", locationHintFor("复习课程作业"))
        assertEquals("宿舍", locationHintFor("看剧放松"))
    }

    @Test
    fun `unknown activity does not invent a location`() {
        assertNull(locationHintFor("整理下周事项"))
    }
}

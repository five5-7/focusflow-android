package com.sakata.focusflow

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickInputParserTest {
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 30, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nowAt(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, 30, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 今天 + [offset] 天、当日第 [minuteOfDay] 分钟的毫秒（可超 1440，自动跨日）。 */
    private fun startAt(offset: Int, minuteOfDay: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, offset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.MINUTE, minuteOfDay)
    }.timeInMillis

    @Test
    fun `evening half hour studies`() {
        val result = QuickInputParser.parse("晚上看半小时高数", now)
        assertTrue(result.recognized)
        assertEquals("高数", result.title)
        assertEquals(30, result.durationMinutes)
        assertEquals("晚上", result.periodLabel)
        assertEquals(startAt(0, 1080), result.windowStartAt)
        assertEquals(startAt(0, 1440), result.windowEndAt)
        assertNull(result.exactAt)
    }

    @Test
    fun `today afternoon one hour report`() {
        val result = QuickInputParser.parse("今天下午写一小时报告", now)
        assertTrue(result.recognized)
        assertEquals("报告", result.title)
        assertEquals(60, result.durationMinutes)
        assertEquals("下午", result.periodLabel)
        assertEquals(startAt(0, 720), result.windowStartAt)
        assertEquals(startAt(0, 1080), result.windowEndAt)
    }

    @Test
    fun `sticky period adds twelve to hour`() {
        val result = QuickInputParser.parse("下午8点打球", nowAt(21))
        assertTrue(result.recognized)
        assertEquals("球", result.title)
        assertEquals(1200, result.exactMinute)
        assertEquals(1, result.exactDayOffset)
        assertEquals(startAt(1, 1200), result.exactAt)
    }

    @Test
    fun `half past eight this morning`() {
        val result = QuickInputParser.parse("8点半起床", nowAt(7))
        assertTrue(result.recognized)
        assertEquals("起床", result.title)
        assertEquals(startAt(0, 510), result.exactAt)
        assertEquals(0, result.exactDayOffset)
    }

    @Test
    fun `half past eight tomorrow when past today`() {
        val result = QuickInputParser.parse("8点半起床", nowAt(14))
        assertTrue(result.recognized)
        assertEquals(startAt(1, 510), result.exactAt)
        assertEquals(1, result.exactDayOffset)
    }

    @Test
    fun `tomorrow three o clock keeps three`() {
        val result = QuickInputParser.parse("明天3点开会", now)
        assertTrue(result.recognized)
        assertEquals("开会", result.title)
        assertEquals(180, result.exactMinute)
        assertEquals(1, result.exactDayOffset)
        assertEquals(startAt(1, 180), result.exactAt)
    }

    @Test
    fun `half hour recites vocabulary`() {
        val result = QuickInputParser.parse("半小时背单词", now)
        assertTrue(result.recognized)
        assertEquals("单词", result.title)
        assertEquals(30, result.durationMinutes)
    }

    @Test
    fun `one hour games`() {
        val result = QuickInputParser.parse("打一小时游戏", now)
        assertTrue(result.recognized)
        assertEquals("游戏", result.title)
        assertEquals(60, result.durationMinutes)
    }

    @Test
    fun `two and a half hours review`() {
        val result = QuickInputParser.parse("两个半小时复习", now)
        assertTrue(result.recognized)
        assertEquals("复习", result.title)
        assertEquals(150, result.durationMinutes)
    }

    @Test
    fun `afternoon tea stays literal`() {
        val result = QuickInputParser.parse("下午茶", now)
        assertFalse(result.recognized)
        assertEquals("下午茶", result.title)
        assertNull(result.durationMinutes)
        assertNull(result.exactAt)
        assertNull(result.periodLabel)
    }

    @Test
    fun `guard keeps learning verb`() {
        val result = QuickInputParser.parse("晚上学习高数", now)
        assertTrue(result.recognized)
        assertEquals("学习高数", result.title)
        assertNull(result.durationMinutes)
        assertEquals("晚上", result.periodLabel)
        assertNotNull(result.windowStartAt)
    }

    @Test
    fun `early morning shifts to next day when passed`() {
        val result = QuickInputParser.parse("凌晨做一小时运动", nowAt(23, 30))
        assertTrue(result.recognized)
        assertEquals("运动", result.title)
        assertEquals(60, result.durationMinutes)
        assertEquals(startAt(1, 0), result.windowStartAt)
        assertEquals(startAt(1, 300), result.windowEndAt)
    }

    @Test
    fun `duration accumulates within expression`() {
        val result = QuickInputParser.parse("1小时30分钟", now)
        assertTrue(result.recognized)
        assertEquals(90, result.durationMinutes)
        assertEquals("1小时30分钟", result.title)
    }

    @Test
    fun `sticky period applies to half past`() {
        val result = QuickInputParser.parse("晚上10点半打球", now)
        assertTrue(result.recognized)
        assertEquals(1350, result.exactMinute)
        assertEquals(0, result.exactDayOffset)
        assertEquals(startAt(0, 1350), result.exactAt)
    }

    @Test
    fun `empty input not recognized`() {
        val result = QuickInputParser.parse("", now)
        assertFalse(result.recognized)
        assertEquals("", result.title)
    }

    @Test
    fun `no metadata stays literal`() {
        val result = QuickInputParser.parse("今天天气不错", now)
        assertFalse(result.recognized)
        assertEquals("今天天气不错", result.title)
    }
}

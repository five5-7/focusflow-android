package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatHistorySummaryTest {
    @Test fun `rule facts separate outcomes and hide future pending instance`() {
        val rule = 11L
        val items = listOf(
            Item(id = 1, title = "完成", detail = "", kind = "任务", done = true, repeatTemplateId = rule),
            Item(id = 2, title = "跳过", detail = "主动跳过本次", kind = "重复历史", repeatTemplateId = rule),
            Item(id = 3, title = "未处理", detail = "本次未处理", kind = "重复历史", repeatTemplateId = rule),
            Item(id = 4, title = "改期", detail = "", kind = "任务", repeatTemplateId = rule, rescheduleCount = 2),
            Item(id = 5, title = "取消", detail = "本次已取消", kind = "重复历史", repeatTemplateId = rule),
            Item(id = 6, title = "无课", detail = "当天没有生效课程，条件未满足", kind = "重复历史", repeatTemplateId = rule),
            Item(id = 7, title = "待处理", detail = "", kind = "任务", repeatTemplateId = rule),
            Item(id = 8, title = "别的规则", detail = "本次未处理", kind = "重复历史", repeatTemplateId = 12)
        )
        val summary = RepeatHistorySummary.from(items, rule)
        assertTrue(summary.hasHistory)
        assertEquals(RepeatHistorySummary(1, 1, 1, 1, 1, 1), summary)
        assertFalse(RepeatHistorySummary.from(items, 99).hasHistory)
    }
}

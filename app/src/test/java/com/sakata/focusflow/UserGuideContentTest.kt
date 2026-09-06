package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserGuideContentTest {
    @Test fun `manual guide preserves the intended six blocks`() {
        assertEquals(
            listOf(
                "一、五分钟开始使用",
                "二、页面与功能速查",
                "三、日程、提醒与后台",
                "四、默认设置与可选能力",
                "五、课程、地点、通勤与目标",
                "六、数据、隐私、更新与常见问题"
            ),
            userGuideChapters.map { it.title }
        )
    }

    @Test fun `quick start points to a manual guide rather than replacing it`() {
        val copy = quickStartChapters.flatMap { it.lines }.joinToString("\n")
        assertTrue(copy.contains("设置 → 使用说明书"))
        assertTrue(userGuideChapters.flatMap { it.lines }.joinToString("\n").contains("不会自动展示"))
    }
}

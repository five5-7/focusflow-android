package com.sakata.focusflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserGuideContentTest {
    @Test fun `manual guide preserves the intended blocks`() {
        assertEquals(
            listOf(
                "一、五分钟开始使用",
                "二、页面与功能速查",
                "三、日程、提醒与后台",
                "四、默认设置与可选能力",
                "五、课程、地点、通勤与目标",
                "六、数据、隐私、更新与常见问题",
                "七、导航、返回与动效",
                "八、外观与主题"
            ),
            userGuideChapters.map { it.title }
        )
    }

    /** 8.2.0：外观系统的可选能力必须写进说明书（用户要求「做完改动要记得改文本」）。 */
    @Test fun `manual guide documents the appearance system`() {
        val guide = userGuideChapters.flatMap { it.lines }.joinToString("\n")
        for (keyword in listOf(
            "跟随主题", "主题渐变", "固定颜色", "自选图片",
            "渐变跟随内容", "不透明度", "从图片抽取主题色",
            "卡片材质", "只改底板", "对比度体检", "深色模式",
            // 第 7 项（自定义主题工具升级）新增的能力，同样必须写进说明书
            "实时预览", "保存预设", "同时记住当前外观", "恢复默认主题配色与外观"
        )) {
            assertTrue("说明书第八章应写明「$keyword」", guide.contains(keyword))
        }
    }

    /**
     * 仓库规矩（AGENTS.md）：每个新版本都必须在 `updateHighlightsFor` 写 2–3 条**用户可感知**的变化，
     * 禁止落入兜底文案，且与 CHANGELOG / 路线图同步。
     */
    @Test fun `update highlights never fall back for known versions`() {
        val fallback = updateHighlightsFor("0.0.1").single()
        for (version in listOf("8.2.0", "8.1.1", "8.1.0", "7.12.0", "7.9.0-rc.2", "7.9.0")) {
            val highlights = updateHighlightsFor(version)
            assertTrue("$version 的更新说明不该是兜底文案", highlights.none { it == fallback })
            assertTrue("$version 应有 2–3 条更新说明，实际 ${highlights.size} 条", highlights.size in 2..3)
            assertTrue("$version 的更新说明不该提到别的版本", highlights.none { it.contains("版本路线图") })
        }
    }

    /** 8.2.0 的更新说明要覆盖到七个特性里用户真正能感知的那几个。 */
    @Test fun `8_2_0 highlights cover the appearance system`() {
        val copy = updateHighlightsFor("8.2.0").joinToString("\n")
        for (keyword in listOf("七套", "主题渐变", "跟随内容", "卡片材质", "底板", "深色模式", "实时预览", "预设")) {
            assertTrue("8.2.0 更新说明应提到「$keyword」", copy.contains(keyword))
        }
    }

    /** 8.1.0：上一步／下一步（回退／折返）与弹窗期间底栏行为必须写进说明书。 */
    @Test fun `manual guide documents navigation and dialog behaviour`() {
        val guide = userGuideChapters.flatMap { it.lines }.joinToString("\n")
        assertTrue(guide.contains("上一步／下一步"))
        assertTrue(guide.contains("长按上一步"))
        assertTrue(guide.contains("再按一次返回键退出应用"))
        // 弹窗口径随实现调整：现在是"页内浮层 + 上下平移进出 + 底栏仍可点"（不再写压暗/柔光）。
        assertTrue(guide.contains("从屏幕下方平移进出"))
        assertTrue(guide.contains("打开时底栏仍可点"))
        assertTrue(guide.contains("锁定竖屏"))
        // 快速入门保持简单：同一批功能只在速查里用一句话点到，不复制说明书的全部细节。
        val quick = quickStartChapters.flatMap { it.lines }.joinToString("\n")
        assertTrue(quick.contains("上一步／下一步"))
        assertTrue(!quick.contains("长按上一步会弹出本次会话的页面历史，点任意一条"))
    }

    @Test fun `quick start points to a manual guide rather than replacing it`() {
        val copy = quickStartChapters.flatMap { it.lines }.joinToString("\n")
        assertTrue(copy.contains("设置 → 使用说明书"))
        assertTrue(userGuideChapters.flatMap { it.lines }.joinToString("\n").contains("不会自动展示"))
    }

    @Test fun `today help retains details removed from persistent cards`() {
        val help = HelpCatalog.today.sections.flatMap { it.lines }.joinToString("\n")

        assertTrue(help.contains("精力只影响弹性任务"))
        assertTrue(help.contains("休息和娱乐只作为时间记录"))
        assertTrue(help.contains("退回或删除方向会保留独立步骤"))
    }

    @Test fun `plan help retains activity statistics source after visual cleanup`() {
        val help = HelpCatalog.plan.sections.flatMap { it.lines }.joinToString("\n")

        assertTrue(help.contains("活动统计只使用"))
        assertTrue(help.contains("不会自动写入结束时间"))
    }
}

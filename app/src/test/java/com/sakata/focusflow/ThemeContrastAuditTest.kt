package com.sakata.focusflow

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastAuditTest {

    private val warning = Color(0xFFB3261E)

    @Test
    fun everyBuiltInThemePassesTheAudit() {
        // 七套内置主题（含 8.2.0 新增三套）都必须全项达标——这是"新增主题不能只是数值上不同"的兜底。
        for (theme in FocusFlowThemeOption.builtInEntries()) {
            val findings = ThemeContrastAudit.audit(theme.colors)
            val bad = findings.filterNot { it.ok }
            assertTrue(
                "${theme.label} 应全项达标，实际不达标：${bad.joinToString { "${it.label}=${it.ratio}" }}",
                bad.isEmpty()
            )
        }
    }

    @Test
    fun midGreyPrimaryNowPicksReadableTextInsteadOfBeingFlagged() {
        // 中灰主色（0xFF9E9E9E）过去配白字，按钮上的字会糊，所以这条曾经断言"必须报出来"。
        //
        // 2026-09-10 改了 onOf：从"亮度 > 0.5 用黑、否则用白"改成**按对比度取优**。
        // 中灰正好落在那个阈值的坏区间里——按老规则选白字只有约 2.8:1，
        // 按对比度取优则选黑字，实测 7.84:1，本来就清楚。
        // 所以现在这条的正确期望是"**自动选到能读的那个颜色**"，
        // 而不是"把它报成不达标"——报出来反而会误导用户去改一个本来没问题的颜色。
        val colors = FocusFlowThemeOption.CUSTOM.colors.copy(primaryAction = Color(0xFF9E9E9E))
        val finding = ThemeContrastAudit.audit(colors).first { it.label == "主色上的文字" }
        assertTrue(
            "中灰主色应自动配到可读的文字色，实际 ${finding.ratio}",
            finding.ok
        )
        assertTrue("应达到正文 AA", finding.ratio >= 4.5f)
    }

    /** 真正该被报出来的那种：中灰主色 + 白字（把文字色也钉死成白）。 */
    @Test
    fun unreadableTextOnMidGreyIsStillFlagged() {
        val finding = ContrastFinding("主色上的文字", AppearanceContrast.ratio(Color.White.argbInt(), Color(0xFF9E9E9E).argbInt()), 4.5f)
        assertTrue("白字压中灰确实读不清，应被判定不达标，实际 ${finding.ratio}", !finding.ok)
    }

    @Test
    fun paleBodyTextIsFlagged() {
        // 浅色文字压在浅色页面上：正文会糊
        val colors = FocusFlowThemeOption.CUSTOM.colors.copy(text = Color(0xFFCFCFCF))
        val finding = ThemeContrastAudit.audit(colors).first { it.label == "正文 / 页面底色" }
        assertTrue(!finding.ok)
        assertNotNull(ThemeContrastAudit.warning(colors))
    }

    @Test
    fun goodPalettePassesSilently() {
        // 默认自定义配色（种子 = 海盐蓝）应当一切正常，且不打扰用户
        assertNull(ThemeContrastAudit.warning(FocusFlowThemeOption.CUSTOM.colors))
        val worst = ThemeContrastAudit.worst(FocusFlowThemeOption.CUSTOM.colors)
        assertTrue("最紧的一处也应达标，实际 ${worst.label}=${worst.ratio}", worst.ok)
    }

    @Test
    fun worstPicksTheLowestRatio() {
        val colors = FocusFlowThemeOption.CUSTOM.colors.copy(primaryAction = Color(0xFF9E9E9E))
        val worst = ThemeContrastAudit.worst(colors)
        val minRatio = ThemeContrastAudit.audit(colors).minOf { it.ratio }
        assertEquals(minRatio, worst.ratio, 0.001f)
    }

    @Test
    fun auditCoversTheSixUserEditableSlots() {
        // 六个槽位各自都该被体检到，避免"改了某一槽却没人管对比度"
        val labels = ThemeContrastAudit.audit(FocusFlowThemeOption.CUSTOM.colors).map { it.label }
        assertEquals(6, labels.size)
        assertEquals(labels.size, labels.toSet().size)
    }
}

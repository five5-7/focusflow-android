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
    fun midGreyPrimaryIsFlagged() {
        // 中灰主色 + 白字：按钮上的字会糊 → 必须报出来
        val colors = FocusFlowThemeOption.CUSTOM.colors.copy(primaryAction = Color(0xFF9E9E9E))
        val finding = ThemeContrastAudit.audit(colors).first { it.label == "主色上的文字" }
        assertTrue("中灰主色应被判为不达标，实际 ${finding.ratio}", !finding.ok)
        val message = ThemeContrastAudit.warning(colors)
        assertNotNull(message)
        assertTrue("提示里要写明是哪一处", message!!.contains("主色上的文字"))
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

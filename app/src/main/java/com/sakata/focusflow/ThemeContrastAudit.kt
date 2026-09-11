package com.sakata.focusflow

import androidx.compose.ui.graphics.Color

/** 一条对比度检查结果。 */
internal data class ContrastFinding(
    val label: String,
    val ratio: Float,
    val target: Float
) {
    val ok: Boolean get() = ratio >= target - 0.0005f
}

/**
 * 自定义配色的**对比度体检**（8.2.0 第 7 项：自定义工具升级）。
 *
 * 自定义主题允许用户自由改六个槽位，最容易踩的坑不是"不好看"而是"读不清"：
 * 主色选到中灰时，按钮上的字会糊；文字色选浅时，正文在页面上会糊。
 * 这里把最容易出问题的几对一次性算出来，编辑器当场给出提示。
 *
 * 纯函数：只吃颜色、只吐结果，可单测、可回归。
 */
internal object ThemeContrastAudit {

    /** 正文目标（WCAG AA）。 */
    const val BODY_TARGET = 4.5f

    /** 大字/图标目标（WCAG AA Large）。 */
    const val LARGE_TARGET = 3f

    fun audit(colors: FocusFlowThemeColors): List<ContrastFinding> = listOf(
        finding("正文 / 页面底色", colors.text, colors.neutral, BODY_TARGET),
        // 卡片在任何主题下都是纯白（见 AppTheme 的 surface = Color.White）
        finding("正文 / 卡片", colors.text, Color.White, BODY_TARGET),
        finding("主色上的文字", onOf(colors.primaryAction), colors.primaryAction, BODY_TARGET),
        finding("副色上的文字", onOf(colors.secondary), colors.secondary, BODY_TARGET),
        // 强调色用于图标与强调块（不是正文），按 AA Large 的 3:1 要求。
        // 实测：暮紫的强调色 3.27:1——按正文口径会误报，而它本来就不承载正文；
        // 原有四套配色是维护者要求冻结的，不能为了迁就过严的阈值去改色。
        finding("强调色上的文字", onOf(colors.accent), colors.accent, LARGE_TARGET),
        finding("导航栏图标与文字", navigationContentColor(colors.navigationBar), colors.navigationBar, LARGE_TARGET)
    )

    /** 最差的一条；没有内容时给一条恒过项，避免 UI 出现空指针式的分支。 */
    fun worst(colors: FocusFlowThemeColors): ContrastFinding =
        audit(colors).minByOrNull { it.ratio } ?: ContrastFinding("正文 / 页面底色", 21f, BODY_TARGET)

    /** 给 UI 用的一句话；全达标返回 null（不打扰）。 */
    fun warning(colors: FocusFlowThemeColors): String? {
        val bad = audit(colors).filterNot { it.ok }
        if (bad.isEmpty()) return null
        val worstOne = bad.minByOrNull { it.ratio }!!
        return "${worstOne.label}只有 ${"%.1f".format(worstOne.ratio)}:1（建议 ≥${"%.1f".format(worstOne.target)}:1）"
    }

    private fun finding(label: String, foreground: Color, background: Color, target: Float) =
        ContrastFinding(label, AppearanceContrast.ratio(foreground.argbInt(), background.argbInt()), target)
}

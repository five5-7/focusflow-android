package com.sakata.focusflow

import org.junit.Test

/**
 * 临时诊断（用完即删）：固定页面底色在各种组合下的正文对比度。
 *
 * 维护者反馈"关掉丰富效果后过亮 / 打开后反色字过暗"，这两句像是在描述**同一个根因**：
 * 底色本身与正文色的对比度不够，而不是"丰富效果"开关的问题。
 * 这里把 明暗 × 丰富开/关 × 跟随主题/各预设 全枚举出来，看哪一格不达标。
 */
class FixedColorProbe {

    private fun row(tag: String, on: Int, bg: Int) {
        val r = AppearanceContrast.ratio(on, bg)
        val flag = if (r < 4.5f) "  <<< 不达标" else if (r < 7f) "  (低于 AAA)" else ""
        println("%-52s bg=%08X ratio=%5.2f%s".format(tag, bg, r, flag))
    }

    @Test
    fun probe() {
        for (theme in listOf(FocusFlowThemeOption.OCEAN, FocusFlowThemeOption.APRICOT)) {
            for (dark in listOf(false, true)) {
                val scheme = focusFlowThemeSpec(theme, darkMode = dark).colorScheme
                val on = scheme.onBackground.argbInt()
                println("== ${theme.label} ${if (dark) "深色" else "浅色"} onBackground=${"%08X".format(on)} ==")

                // 跟随主题（pageColor = 0）
                val option = AppearanceSpec(pageBackdrop = BackdropKind.COLOR, pageColor = 0)
                val auto = run {
                    val autoBase = blendSrgb(scheme.background, scheme.primary, 0.10f)
                    adaptBackdropColor(scheme, autoBase)
                }
                row("  COLOR 跟随主题(自动派生)", on, auto.argbInt())

                // 各预设
                for ((i, preset) in PAGE_BASE_PRESETS.withIndex()) {
                    val adapted = adaptBackdropColor(scheme, androidx.compose.ui.graphics.Color(preset))
                    row("  COLOR 预设#$i", on, adapted.argbInt())
                }

                // 直接把主题中性色/主色当页面底色（用户可能的直觉选择）。
                // adaptBackdropColor 吃的是 Int（ARGB），不是 Color。
                row("  COLOR = theme.neutral", on, adaptBackdropColor(scheme, androidx.compose.ui.graphics.Color(theme.colors.neutral.argbInt())).argbInt())
                row("  COLOR = theme.primaryAction", on, adaptBackdropColor(scheme, androidx.compose.ui.graphics.Color(theme.colors.primaryAction.argbInt())).argbInt())
                row("  COLOR = theme.navigation(海盐蓝底)", on, adaptBackdropColor(scheme, androidx.compose.ui.graphics.Color(theme.colors.navigationBar.argbInt())).argbInt())

                // 丰富效果开/关：底色分支不会因此改变（只有 THEME 例外），这里顺带确认
                val off = option.copy(richEffects = false)
                println("  richEffects off -> effectivePageBackdrop=${off.effectivePageBackdrop}（COLOR 应保留）")
                println()
            }
        }
    }
}

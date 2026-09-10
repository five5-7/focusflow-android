package com.sakata.focusflow

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 8.2.0 外观系统的**对比度总账**（离线、可复现的那一半证据）。
 *
 * 维护者要求"每阶段跑单测 + 出真机证据（对比度与掉帧）"。对比度这一半不必等真机：
 * 渲染用到的每一次混色都是纯函数（[ThemeGradient.pageStops] / [blendSrgb] / [adaptBackdropColor] /
 * [scrimAlpha] / [AppearanceContrast]），所以可以把
 * **7 套主题 × 浅/深 × 4 档背景 × 3 档不透明度** 全部算出来，且用"最不利的底色"来算：
 *
 * - 渐变取三个停靠色里对正文最不利的那个；
 * - 固定颜色取 `base` 与其顶部提亮两端的较差者；
 * - 图片内容不可知，取**纯黑图**与**纯白图**两种极端经主题遮罩压过之后的较差者
 *   （这比"随手挑一张图"严格得多）。
 *
 * 真机上只需要按 `docs/8.2.0-checklist.md` §二 抽查几个点，与本表对照即可。
 * 本测试同时把结果写成 Markdown 存到 `app/build/reports/appearance-contrast-matrix.md`，
 * 供直接粘进 `docs/8.2.0-appearance-plan.md`。
 */
class AppearanceContrastMatrixTest {

    private val themes = FocusFlowThemeOption.builtInEntries()
    private val modes = listOf(false to "浅色", true to "深色")
    private val columns = listOf(
        "跟随主题" to Triple(BackdropKind.THEME, 100, 1f),
        "主题渐变" to Triple(BackdropKind.GRADIENT, 100, 1f),
        "渐变 200%" to Triple(BackdropKind.GRADIENT, 100, 2f),
        // 强度上限那一档必须进总账：上限一旦调大，这里就是"最不利底色"的看门人。
        "渐变 ${GRADIENT_STRENGTH_MAX}%（上限）" to Triple(BackdropKind.GRADIENT, 100, GRADIENT_STRENGTH_MAX / 100f),
        "固定颜色" to Triple(BackdropKind.COLOR, 100, 1f),
        "图片 1%" to Triple(BackdropKind.IMAGE, 1, 1f),
        "图片 50%" to Triple(BackdropKind.IMAGE, 50, 1f),
        "图片 100%" to Triple(BackdropKind.IMAGE, 100, 1f)
    )

    /** 该档位下正文实际面对的最不利底色（用生产代码里的同一批纯函数算）。 */
    private fun worstBackground(scheme: ColorScheme, backdrop: BackdropKind, opacity: Int, strength: Float): Int {
        val on = scheme.onBackground.argbInt()
        val candidates: List<Int> = when (backdrop) {
            BackdropKind.THEME -> listOf(scheme.background.argbInt())

            BackdropKind.GRADIENT -> ThemeGradient.pageStops(scheme, strength).map { it.argbInt() }

            BackdropKind.COLOR -> {
                // 与 appearanceBackdrop 的 COLOR 分支同一条算式：底色 → 顶部提亮 6% 的竖向渐变
                val auto = blendSrgb(scheme.background, scheme.primary, 0.10f)
                val base = adaptBackdropColor(scheme, auto)
                listOf(blendSrgb(base, Color.White, 0.06f).argbInt(), base.argbInt())
            }

            BackdropKind.IMAGE -> {
                // 与 appearanceBackdrop 的 IMAGE 分支同一条算式，顺序不能颠倒：
                //   底色渐变 → 图片按"不透明度"叠上去 → 主题遮罩按 scrimAlpha 压在**合成结果**之上。
                // 图片内容未知，取纯黑与纯白两种极端；底层渐变取三个停靠色里最不利的那个。
                val bg = scheme.background.argbInt()
                val scrim = scrimAlpha(opacity / 100f)
                val imageAlpha = opacity / 100f
                val stops = ThemeGradient.pageStops(scheme, 1f).map { it.argbInt() }
                val darkestBase = AppearanceContrast.worstCaseBackground(stops, on)
                val brightestBase = stops.maxByOrNull { AppearanceContrast.luminance(it) } ?: bg
                listOf(
                    AppearanceContrast.blend(
                        AppearanceContrast.blend(darkestBase, 0xFF000000.toInt(), imageAlpha),
                        bg,
                        scrim
                    ),
                    AppearanceContrast.blend(
                        AppearanceContrast.blend(brightestBase, 0xFFFFFFFF.toInt(), imageAlpha),
                        bg,
                        scrim
                    )
                )
            }
        }
        return AppearanceContrast.worstCaseBackground(candidates, on)
    }

    private fun bodyRatio(theme: FocusFlowThemeOption, dark: Boolean, backdrop: BackdropKind, opacity: Int, strength: Float): Float {
        val scheme = focusFlowThemeSpec(theme, darkMode = dark).colorScheme
        val background = worstBackground(scheme, backdrop, opacity, strength)
        return AppearanceContrast.ratio(scheme.onBackground.argbInt(), background)
    }

    @Test
    fun bodyTextPassesAaInEveryThemeBackdropAndMode() {
        for (theme in themes) {
            for ((dark, label) in modes) {
                for ((column, config) in columns) {
                    val (backdrop, opacity, strength) = config
                    val ratio = bodyRatio(theme, dark, backdrop, opacity, strength)
                    assertTrue(
                        "${theme.label}·$label·$column 正文对比度 $ratio 应 >= ${AppearanceContrast.AA_BODY}",
                        ratio >= AppearanceContrast.AA_BODY
                    )
                }
            }
        }
    }

    /** 不涉及图片时，设计目标是 AAA 级别的 7:1（图片只能按 AA 守，见报告里的说明）。 */
    @Test
    fun nonImageBackdropsStayAtSevenToOne() {
        for (theme in themes) {
            for ((dark, label) in modes) {
                for ((column, config) in columns) {
                    val (backdrop, opacity, strength) = config
                    if (backdrop == BackdropKind.IMAGE) continue
                    val ratio = bodyRatio(theme, dark, backdrop, opacity, strength)
                    assertTrue(
                        "${theme.label}·$label·$column 正文对比度 $ratio 应 >= 7:1",
                        ratio >= 7f
                    )
                }
            }
        }
    }

    /**
     * 回归下界：全表现在最低是 6.9:1（深色 + 图片 50%）。
     *
     * 这条不是产品需求，而是**防止无意间把遮罩调薄**：图片档只承诺 AA（4.5），
     * 但现状离 AA 还有很大余量，谁把 `scrimAlpha` 改小了、或把深色底色提亮了，这里就该红，
     * 让改动者明确知道自己在动对比度预算，而不是悄悄滑到 4.5 边缘。
     */
    @Test
    fun imageBackdropsKeepAMarginAboveAa() {
        var minimum = Float.MAX_VALUE
        var where = ""
        for (theme in themes) {
            for ((dark, label) in modes) {
                for ((column, config) in columns) {
                    val (backdrop, opacity, strength) = config
                    if (backdrop != BackdropKind.IMAGE) continue
                    val ratio = bodyRatio(theme, dark, backdrop, opacity, strength)
                    if (ratio < minimum) {
                        minimum = ratio
                        where = "${theme.label}·$label·$column"
                    }
                }
            }
        }
        assertTrue("图片档最低 $minimum:1（$where）应 >= 6.5:1", minimum >= 6.5f)
    }

    /**
     * 把总账写成 Markdown，供粘进设计与验收文档。
     *
     * 写在测试里而不是另写脚本：这样表里的每个数字都由**与渲染同一份代码**算出来，
     * 不会出现"文档数字与实现漂移"。
     */
    @Test
    fun writesTheMatrixForTheDocs() {
        val rows = themes.map { theme ->
            modes.map { (dark, label) ->
                val cells = columns.map { (_, config) ->
                    val (backdrop, opacity, strength) = config
                    "%.1f".format(bodyRatio(theme, dark, backdrop, opacity, strength))
                }
                "${theme.label}|$label|" + cells.joinToString("|")
            }
        }.flatten()

        val worst = themes.flatMap { theme ->
            modes.flatMap { (dark, label) ->
                columns.map { (column, config) ->
                    val (backdrop, opacity, strength) = config
                    Triple("${theme.label}·$label·$column", bodyRatio(theme, dark, backdrop, opacity, strength), backdrop)
                }
            }
        }.minBy { it.second }

        val header = "| 主题 | 明暗 | " + columns.joinToString("|") { it.first } + "|"
        val divider = "|" + "---|".repeat(columns.size + 2)
        val text = buildString {
            appendLine("# 8.2.0 外观对比度总账（由 AppearanceContrastMatrixTest 生成）")
            appendLine()
            appendLine("正文（onBackground）在该档位**最不利底色**上的 WCAG 对比度。")
            appendLine("「图片」档取纯黑图 / 纯白图两种极端经主题遮罩压过之后的较差者 —— 比随手挑一张图严格。")
            appendLine()
            appendLine(header)
            appendLine(divider)
            rows.forEach { appendLine("|$it|") }
            appendLine()
            appendLine("全表最低：**${"%.1f".format(worst.second)}:1**（${worst.first}，档位 ${worst.third.storageKey}）。")
            appendLine("阈值：正文 AA = ${AppearanceContrast.AA_BODY}:1；不含图片的档位按 7:1 守。")
        }
        val target = File("build/reports/appearance-contrast-matrix.md")
        target.parentFile?.mkdirs()
        target.writeText(text, Charsets.UTF_8)

        assertTrue("总账应写下 ${columns.size + 2} 列的表格", text.contains(header))
        // 表格必须真的覆盖到每一套主题与每一种模式
        assertTrue(text.contains(FocusFlowThemeOption.OCEAN.label))
        assertTrue(text.contains(FocusFlowThemeOption.BAMBOO.label))
        assertTrue(target.isFile)
    }
}

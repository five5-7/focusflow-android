package com.sakata.focusflow

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/**
 * 8.1.0 动画优化第三轮：把散落在各页的时长与缓动收拢为语义化规范。
 *
 * 约定：
 * - 所有时长都经 [motionMillis] 换算，「设置 → 外观 → 动画速度」才能覆盖全部动效
 *   （此前各子页 hub 转场与滚动条仍写死 120~260ms，关掉动画也照动）；
 * - 进入用强调减速（起步快、落定慢），退出用强调加速（起步慢、离场快），
 *   这样"谁进场、谁退场"一眼可辨，而不是两边同时匀速淡出；
 * - 速度设为 0（关闭动画）时一律退化为 [snap]，不做任何过渡。
 */
internal object MotionSpec {
    /** 进入时长：略长，留出落定感。 */
    const val ENTER_MS = 240

    /** 退出时长：略短，避免与进场争抢注意力。 */
    const val EXIT_MS = 170

    /** 位移/缩放主体时长（子页推入、页签平移）。 */
    const val MOVE_MS = 220

    /** 轻反馈（滚动条、展开收起）。 */
    const val QUICK_MS = 180

    /** 底栏圆瓣形变与选中态。 */
    const val MORPH_MS = 260

    /** 副页收起/展开时长（缩小与放大共用，透明度跟随全程）。 */
    const val SHRINK_MS = 260

    /** 强调减速：进入与落定。 */
    val enterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 强调加速：退出与离场。 */
    val exitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 关闭动画时不做任何过渡。 */
    val animationsEnabled: Boolean get() = MotionSettings.durationScale > 0f

    fun <T> enter(): FiniteAnimationSpec<T> = spec(ENTER_MS, enterEasing)

    fun <T> exit(): FiniteAnimationSpec<T> = spec(EXIT_MS, exitEasing)

    fun <T> move(): FiniteAnimationSpec<T> = spec(MOVE_MS, enterEasing)

    fun <T> quick(): FiniteAnimationSpec<T> = spec(QUICK_MS, enterEasing)

    fun <T> morph(): FiniteAnimationSpec<T> = spec(MORPH_MS, enterEasing)

    /** 副页收起：缩小与淡出同一时长、同一条"慢起快收"曲线，透明度跟缩小一起走完。 */
    fun <T> shrink(): FiniteAnimationSpec<T> = spec(SHRINK_MS, exitEasing)

    /** 副页展开：从底栏槽位放大回来，"快起慢落"。 */
    fun <T> grow(): FiniteAnimationSpec<T> = spec(SHRINK_MS, enterEasing)

    private fun <T> spec(baseMs: Int, easing: Easing): FiniteAnimationSpec<T> =
        if (animationsEnabled) tween(motionMillis(baseMs), easing = easing) else snap()
}

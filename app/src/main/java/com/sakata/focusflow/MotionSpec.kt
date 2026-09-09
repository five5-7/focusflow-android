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
 * - 所有时长都经 [motionMillis] 换算，「设置 → 外观 → 动画速度」才能覆盖全部动效；
 * - 进入用强调减速（起步快、落定慢），退出用强调加速（起步慢、离场快）；
 * - 速度设为 0（关闭动画）时一律退化为 [snap]，不做任何过渡；
 * - 副页转场：进入从底栏图标放大（不淡入，避免"淡影"）、退出缩回同一图标并同步淡出；
 *   进入时副页侧 240ms（[grow]）、主页退让侧 260ms（[move]）；返回时两侧都是 240ms
 *   （[collapseHome]），主页直接出现在下层、不参与动画（见 SubpageMotion 与各页的 hubEnter/hubExit）。
 */
internal object MotionSpec {
    /** 进入时长：略长，留出落定感。 */
    const val ENTER_MS = 240

    /** 退出时长：略短，避免与进场争抢注意力。 */
    const val EXIT_MS = 170

    /** 位移主体时长（页签平移、子页推入）：比缩放慢，让"整页移动"显得稳。 */
    const val MOVE_MS = 260

    /** 轻反馈（滚动条、展开收起）。 */
    const val QUICK_MS = 180

    /** 底栏圆瓣形变与选中态。 */
    const val MORPH_MS = 260

    /** 底栏选中底色的目的地回弹。 */
    const val PULSE_MS = 140

    /**
     * 副页收回自己主页的时长。
     * 用户要求：比"切到其他主页"快、比原来 200ms 慢 —— 240ms。
     */
    const val SUBPAGE_HOME_MS = 240

    /** 副页跨页签离开（切到其他主页）的时长：最慢的一档。 */
    const val SUBPAGE_CROSS_MS = 320

    /**
     * 副页收敛后的最终缩放：约等于底栏选中图标底色的大小，
     * 让页面看起来是被"吸进"那一格的底色里，而不是缩成一块大方块。
     */
    const val COLLAPSE_SCALE = 0.12f

    /** 主页在副页展开时的退让比例（深度缩放的底层）。 */
    const val HUB_RECEDE_SCALE = 0.96f

    /** 跳转（通知/深链）时隐藏页签的静止缩放。 */
    const val JUMP_REST_SCALE = 0.85f

    /** 页签平动幅度（dp）：普通切换 / 跳转。 */
    const val TAB_SLIDE_DP = 80

    /** 页签平动幅度（dp）：跳转时更大，方向更易读。 */
    const val JUMP_SLIDE_DP = 96

    /** 弹窗遮罩不透明度（浅色/深色同值，对齐 Material 的 Scrim）。 */
    const val SCRIM_ALPHA = 0.32f

    /** 强调减速：进入与落定。 */
    val enterEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 强调加速：退出与离场。 */
    val exitEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 缩放离场：起步即动、末端加速（强调加速曲线起步太慢，缩小时会显得拖沓）。 */
    val shrinkEasing: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** 关闭动画时不做任何过渡。 */
    val animationsEnabled: Boolean get() = MotionSettings.durationScale > 0f

    fun <T> enter(): FiniteAnimationSpec<T> = spec(ENTER_MS, enterEasing)

    fun <T> exit(): FiniteAnimationSpec<T> = spec(EXIT_MS, exitEasing)

    fun <T> move(): FiniteAnimationSpec<T> = spec(MOVE_MS, enterEasing)

    fun <T> quick(): FiniteAnimationSpec<T> = spec(QUICK_MS, enterEasing)

    fun <T> morph(): FiniteAnimationSpec<T> = spec(MORPH_MS, enterEasing)

    /** 底栏选中底色的目的地回弹：同一条规范，随「动画速度」缩放。 */
    fun <T> pulse(): FiniteAnimationSpec<T> = spec(PULSE_MS, enterEasing)

    /** 副页收回自己主页：缩小与淡出同一时长、同一条曲线，透明度跟缩小一起走完。 */
    fun <T> collapseHome(): FiniteAnimationSpec<T> = spec(SUBPAGE_HOME_MS, shrinkEasing)

    /** 副页跨页签离开：同一条曲线，但更慢，与"回到自己主页"区分开。 */
    fun <T> collapseAcross(): FiniteAnimationSpec<T> = spec(SUBPAGE_CROSS_MS, shrinkEasing)

    /** 副页展开：从底栏槽位放大回来，"快起慢落"。 */
    fun <T> grow(): FiniteAnimationSpec<T> = spec(SUBPAGE_HOME_MS, enterEasing)

    private fun <T> spec(baseMs: Int, easing: Easing): FiniteAnimationSpec<T> =
        if (animationsEnabled) tween(motionMillis(baseMs), easing = easing) else snap()
}

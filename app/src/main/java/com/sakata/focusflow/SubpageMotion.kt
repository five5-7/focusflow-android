package com.sakata.focusflow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex

/**
 * 8.1.0 子页→主页的收敛原点：由页签容器提供（对应底部导航栏该页签槽位中心），
 * 子页返回主页时主页从该位置放大覆盖、子页向该位置缩小收起。
 */
internal val LocalNavCollapseOrigin = staticCompositionLocalOf<TransformOrigin?> { null }

/**
 * 8.1.0 第三轮：页签直达会重置目标页签的副页（点击底栏入口回主页）。
 * 这种重置是"顺带发生"的，不该再播一次缩小——由 [SubpageMotion] 的 snapPageChange 显式抑制。
 */
internal val LocalPageSnapToken = staticCompositionLocalOf { 0 }

/**
 * 8.1.0 第三轮：主页一侧的转场规格，**必须与 [SubpageMotion] 的进出分支互为逆动作**。
 * 深度缩放方案下：
 * - 进入子页：主页退到 0.96 当底层（不淡出，被长出来的副页盖住）；
 * - 返回主页：主页**直接出现在下层**（用户明确要求"不放大淡入"），由副页缩回底栏图标把它露出来。
 */
internal fun hubEnter(): EnterTransition = EnterTransition.None

internal fun hubExit(): ExitTransition =
    scaleOut(MotionSpec.move(), targetScale = MotionSpec.HUB_RECEDE_SCALE)

/**
 * 进入子页（前进）：副页从底栏图标处放大展开，上一页退到 0.96 当底层。
 * 与 [SubpageMotion] 的返回分支互为逆动作（同一时长、同一原点、相反方向）。
 * 注意：**不做淡入**——从图标放大时再叠一层淡入会变成"淡淡的影子"；
 * 子页之间的父进子退（origin 传 Center）同样只放大不淡入。
 */
private fun enterSubpage(origin: TransformOrigin?): ContentTransform =
    scaleIn(MotionSpec.grow(), initialScale = MotionSpec.COLLAPSE_SCALE, transformOrigin = origin ?: TransformOrigin.Center) togetherWith
        scaleOut(MotionSpec.move(), targetScale = MotionSpec.HUB_RECEDE_SCALE)

/** Keep the outgoing destination alive until exit completes; don't read live page inside it. */
@Composable
internal fun <T : Any> SubpageMotion(
    page: T?,
    snapPageChange: Boolean = false,
    depth: (T) -> Int = { 1 },
    containerColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable (T) -> Unit
) {
    val states = rememberSaveableStateHolder()
    val transition = updateTransition(page, label = "subpage-state")
    val collapseOrigin = LocalNavCollapseOrigin.current
    // 8.1.0 第三轮：副页层永远在主页之上（主页直接出现在下层，副页缩小淡出时不能被主页的卡片压住）。
    Box(Modifier.fillMaxSize().zIndex(1f)) {
    transition.AnimatedContent(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val direction = NavigationMotion.direction(initialState?.let(depth) ?: 0, targetState?.let(depth) ?: 0)
            val origin = collapseOrigin ?: TransformOrigin.Center
            when {
                // 页签直达重置副页：这次切换不播任何动画（工作现场由回退/折返保留）。
                snapPageChange -> EnterTransition.None togetherWith ExitTransition.None
                // 前进：副页从底栏图标处长出（只有从主页进入时才用图标原点，子页之间用中心）。
                direction > 0 -> enterSubpage(if (initialState == null) origin else TransformOrigin.Center)
                // 返回主页：副页缩回底栏图标并同步淡出；主页直接出现在下层（hubEnter = None）。
                direction < 0 && targetState == null ->
                    EnterTransition.None togetherWith
                        (scaleOut(MotionSpec.collapseHome(), targetScale = MotionSpec.COLLAPSE_SCALE, transformOrigin = origin) +
                            fadeOut(MotionSpec.collapseHome()))
                // 子页之间的父子上退：父页从 0.96 放大回来，子页缩回图标。
                direction < 0 ->
                    (scaleIn(MotionSpec.move(), initialScale = MotionSpec.HUB_RECEDE_SCALE, transformOrigin = origin) +
                        fadeIn(MotionSpec.move())) togetherWith
                        (scaleOut(MotionSpec.collapseHome(), targetScale = MotionSpec.COLLAPSE_SCALE, transformOrigin = origin) +
                            fadeOut(MotionSpec.collapseHome()))
                else -> fadeIn(MotionSpec.enter()) togetherWith fadeOut(MotionSpec.exit())
            }.using(null)
        }
    ) { destination ->
        if (destination != null) {
            val outgoing = destination != page
            val input = if (outgoing) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            } else Modifier
            states.SaveableStateProvider(destination.toString()) {
                Box(Modifier.fillMaxSize().background(containerColor).then(input)) {
                    content(destination)
                }
            }
        }
    }
    // Serialize taps while both layers exist; otherwise a tap may reach the old hub.
    if (transition.isRunning || transition.currentState != transition.targetState) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        })
    }
    }
}

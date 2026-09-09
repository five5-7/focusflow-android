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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
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
 * 这种重置是"顺带发生"的，不该再播一次缩小——换 key 重建 AnimatedContent 即可。
 */
internal val LocalPageSnapToken = staticCompositionLocalOf { 0 }

/**
 * 8.1.0 第三轮：主页一侧的转场规格，**必须是 [SubpageMotion] 前进/退出的镜像**。
 * 进入子页时主页怎么退让，返回主页时主页就从同一个状态回来（同一方案、同一曲线）。
 */
internal fun hubEnter(scheme: ExitScheme): EnterTransition = when (scheme) {
    // 深度缩放：主页在底层从 0.96 回到 1.0（不淡入——它是底层，被副页盖住）。
    ExitScheme.DEPTH -> scaleIn(MotionSpec.move(), initialScale = 0.96f)
    // 左右平移：主页从左侧滑回。
    ExitScheme.SLIDE -> slideInHorizontally(MotionSpec.springSpec()) { -it / 4 } + fadeIn(MotionSpec.move())
    // 视差：主页走得更少、更慢。
    ExitScheme.PARALLAX -> slideInHorizontally(MotionSpec.move()) { -it / 6 } + fadeIn(MotionSpec.move())
    // 上滑：主页原地淡入。
    ExitScheme.LIFT -> fadeIn(MotionSpec.move())
}

internal fun hubExit(scheme: ExitScheme): ExitTransition = when (scheme) {
    ExitScheme.DEPTH -> scaleOut(MotionSpec.move(), targetScale = 0.96f)
    ExitScheme.SLIDE -> slideOutHorizontally(MotionSpec.springSpec()) { -it / 4 } + fadeOut(MotionSpec.move())
    ExitScheme.PARALLAX -> slideOutHorizontally(MotionSpec.springSpec()) { -it / 3 } + fadeOut(MotionSpec.shrink())
    ExitScheme.LIFT -> fadeOut(MotionSpec.move())
}

/** 进入子页（前进）：与下面的返回分支逐项对应，是同一组动作的倒放。 */
private fun enterSubpage(scheme: ExitScheme, origin: TransformOrigin?): ContentTransform = when {
    scheme == ExitScheme.DEPTH && origin != null ->
        (scaleIn(MotionSpec.springSpec(), initialScale = MotionSpec.COLLAPSE_SCALE, transformOrigin = origin) +
            fadeIn(MotionSpec.move())) togetherWith scaleOut(MotionSpec.move(), targetScale = 0.96f)
    scheme == ExitScheme.SLIDE ->
        (slideInHorizontally(MotionSpec.springSpec()) { it / 4 } + fadeIn(MotionSpec.move())) togetherWith
            (slideOutHorizontally(MotionSpec.springSpec()) { -it / 4 } + fadeOut(MotionSpec.move()))
    scheme == ExitScheme.PARALLAX ->
        (slideInHorizontally(MotionSpec.move()) { it / 6 } + fadeIn(MotionSpec.move())) togetherWith
            (slideOutHorizontally(MotionSpec.springSpec()) { -it / 3 } + fadeOut(MotionSpec.shrink()))
    scheme == ExitScheme.LIFT ->
        (slideInVertically(MotionSpec.springSpec()) { -it / 5 } + fadeIn(MotionSpec.move())) togetherWith
            fadeOut(MotionSpec.move())
    else ->
        (slideInHorizontally(MotionSpec.springSpec()) { it / 4 } + fadeIn(MotionSpec.move())) togetherWith
            (slideOutHorizontally(MotionSpec.springSpec()) { -it / 4 } + fadeOut(MotionSpec.move()))
}

/** Keep the outgoing destination alive until exit completes; don't read live page inside it. */
@Composable
internal fun <T : Any> SubpageMotion(
    page: T?,
    depth: (T) -> Int = { 1 },
    containerColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable (T) -> Unit
) {
    val states = rememberSaveableStateHolder()
    val transition = updateTransition(page, label = "subpage-state")
    val collapseOrigin = LocalNavCollapseOrigin.current
    val snapToken = LocalPageSnapToken.current
    // 8.1.0 第三轮：副页层永远在主页之上（主页直接出现在下层，副页缩小淡出时不能被主页的卡片压住）。
    Box(Modifier.fillMaxSize().zIndex(1f)) {
    // 页签直达导致的副页重置：换 key 重建，让新页面直接成为初始状态，不再补播一次缩小。
    key(snapToken) {
    transition.AnimatedContent(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val direction = NavigationMotion.direction(initialState?.let(depth) ?: 0, targetState?.let(depth) ?: 0)
            val scheme = MotionSettings.exitScheme
            when {
                // 前进：进入子页（或更深一层）。
                direction > 0 -> enterSubpage(scheme, collapseOrigin)
                // 返回主页：与进入严格互逆——副页怎么来的，就怎么回去（主页一侧见 hubEnter）。
                direction < 0 && targetState == null -> when {
                    scheme == ExitScheme.DEPTH && collapseOrigin != null ->
                        EnterTransition.None togetherWith
                            (scaleOut(MotionSpec.springSpec(), targetScale = MotionSpec.COLLAPSE_SCALE, transformOrigin = collapseOrigin) +
                                fadeOut(MotionSpec.move()))
                    scheme == ExitScheme.SLIDE ->
                        EnterTransition.None togetherWith
                            (slideOutHorizontally(MotionSpec.springSpec()) { it / 4 } + fadeOut(MotionSpec.move()))
                    scheme == ExitScheme.PARALLAX ->
                        EnterTransition.None togetherWith
                            (slideOutHorizontally(MotionSpec.springSpec()) { it / 3 } + fadeOut(MotionSpec.shrink()))
                    scheme == ExitScheme.LIFT ->
                        EnterTransition.None togetherWith
                            (slideOutVertically(MotionSpec.springSpec()) { -it / 5 } + fadeOut(MotionSpec.move()))
                    else ->
                        EnterTransition.None togetherWith
                            (slideOutHorizontally(MotionSpec.springSpec()) { it / 4 } + fadeOut(MotionSpec.move()))
                }
                // 子页之间的父子上退：方向相反的一对平移。
                direction < 0 ->
                    (slideInHorizontally(MotionSpec.springSpec()) { -it / 4 } + fadeIn(MotionSpec.move())) togetherWith
                        (slideOutHorizontally(MotionSpec.springSpec()) { it / 4 } + fadeOut(MotionSpec.move()))
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
    } // key(snapToken)
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

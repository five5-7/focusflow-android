package com.sakata.focusflow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
 * 8.1.0 第三轮：主页一侧的转场规格，与 [SubpageMotion] 对称。
 * 主页消失（进入子页）向左让位；主页出现（从子页返回）不播动画——它直接出现在副页下层，
 * 由副页的缩小淡出把它露出来（见 [SubpageMotion] 的收敛分支）。
 */
internal fun hubExit(): ExitTransition =
    slideOutHorizontally(MotionSpec.move()) { -it / 4 } + fadeOut(MotionSpec.move())

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
            when {
                direction > 0 ->
                    // 平移与淡入淡出同一时长：出场页若先淡完，位移会被提前截断，看起来"平动很快"。
                    (slideInHorizontally(MotionSpec.move()) { it / 6 } + fadeIn(MotionSpec.move())) togetherWith
                        (slideOutHorizontally(MotionSpec.move()) { -it / 12 } + fadeOut(MotionSpec.move()))
                // 8.1.0 第三轮：返回主页时主页直接出现在下层（不放大、不淡入），
                // 只有副页向底栏槽位缩小到图标底色大小，并随缩小的全过程同步淡出。
                direction < 0 && targetState == null && collapseOrigin != null ->
                    EnterTransition.None togetherWith
                        (scaleOut(MotionSpec.shrink(), targetScale = MotionSpec.COLLAPSE_SCALE, transformOrigin = collapseOrigin) +
                            fadeOut(MotionSpec.shrink()))
                direction < 0 ->
                    (slideInHorizontally(MotionSpec.move()) { -it / 12 } + fadeIn(MotionSpec.move())) togetherWith
                        (slideOutHorizontally(MotionSpec.move()) { it / 6 } + fadeOut(MotionSpec.move()))
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

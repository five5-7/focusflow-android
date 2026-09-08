package com.sakata.focusflow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * 8.1.0 子页→主页的收敛原点：由页签容器提供（对应底部导航栏该页签槽位中心），
 * 子页返回主页时主页从该位置放大覆盖、子页向该位置缩小收起。
 */
internal val LocalNavCollapseOrigin = staticCompositionLocalOf<TransformOrigin?> { null }

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
    Box(Modifier.fillMaxSize()) {
    transition.AnimatedContent(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val direction = NavigationMotion.direction(initialState?.let(depth) ?: 0, targetState?.let(depth) ?: 0)
            when {
                direction > 0 -> (slideInHorizontally(tween(motionMillis(220))) { it / 6 } + fadeIn(tween(motionMillis(180)))) togetherWith
                    (slideOutHorizontally(tween(motionMillis(220))) { -it / 12 } + fadeOut(tween(motionMillis(150))))
                direction < 0 && collapseOrigin != null ->
                    // 8.1.0 ④：返回主页时，主页从导航栏对应位置放大覆盖，子页向该位置缩小收起。
                    (scaleIn(tween(motionMillis(220)), initialScale = 0.40f, transformOrigin = collapseOrigin) + fadeIn(tween(motionMillis(180)))) togetherWith
                        (scaleOut(tween(motionMillis(200)), targetScale = 0.40f, transformOrigin = collapseOrigin) + fadeOut(tween(motionMillis(150))))
                direction < 0 -> (slideInHorizontally(tween(motionMillis(220))) { -it / 12 } + fadeIn(tween(motionMillis(180)))) togetherWith
                    (slideOutHorizontally(tween(motionMillis(220))) { it / 6 } + fadeOut(tween(motionMillis(150))))
                else -> fadeIn(tween(motionMillis(180))) togetherWith fadeOut(tween(motionMillis(150)))
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

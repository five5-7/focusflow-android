package com.sakata.focusflow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 8.1.0 第三轮：把弹窗从"独立窗口"改为**页内浮层**。
 *
 * 动机：Android 的 Dialog 是独立窗口，永远盖在 Activity 之上，导致弹窗打开时悬浮底栏
 * （以及回退／折返键）完全不可点——实测底栏节点连无障碍树都进不去。
 * 改成页内浮层后，底栏仍在其上方、保持可点，历史导航在弹窗期间依然可用。
 *
 * 统一性：外观复刻 Material3 AlertDialog（同圆角、同内边距、同按钮排布），
 * 所有弹窗共用同一套返回键、遮罩点击、键盘避让与进场动画。
 * 兜底：拿不到宿主时退化为系统 AlertDialog，任何弹窗都不会因此消失。
 */
internal class AppDialogHostState {
    internal var content: (@Composable () -> Unit)? by mutableStateOf(null)
    internal var onDismiss: (() -> Unit)? by mutableStateOf(null)

    /** 当前占用宿主的调用点身份；用于避免旧实例的 onDispose 误清新弹窗。 */
    internal var owner: Any? by mutableStateOf(null)

    internal val isOpen: Boolean get() = content != null
}

internal val LocalAppDialogHost = staticCompositionLocalOf<AppDialogHostState?> { null }

/**
 * 页内浮层宿主：放在悬浮底栏**之前**，因此永远位于底栏之下。
 *
 * 进场/退场用同一条规范（[MotionSpec.enter] / [MotionSpec.exit]）：
 * 遮罩淡入淡出，卡片同步轻微放大/缩小。退场时调用方已经离开组合、内容会被清空，
 * 因此这里额外保留最后一次内容 [retained]，让 170ms 的退场动画有东西可画。
 *
 * [bottomInset] 传悬浮底栏的实测高度：卡片只在底栏**之上**的区域居中，
 * 否则横屏（竖向空间只有 520dp 左右）时卡片底部按钮会被底栏盖住。
 */
@Composable
internal fun AppDialogHost(state: AppDialogHostState, bottomInset: Dp = 0.dp, modifier: Modifier = Modifier) {
    val content = state.content
    val dismiss = state.onDismiss
    val open = content != null && dismiss != null
    val retained = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    SideEffect { if (content != null) retained.value = content }
    val progress = remember { Animatable(0f) }
    val latestDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(open) {
        if (open) {
            progress.snapTo(0f)
            progress.animateTo(1f, MotionSpec.enter())
        } else {
            progress.animateTo(0f, MotionSpec.exit())
            retained.value = null
        }
    }
    // 弹窗打开期间，系统返回先关弹窗（本处理器最后注册，优先级最高）。
    BackHandler(enabled = open) { latestDismiss?.invoke() }
    val body = retained.value ?: return
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = 0.32f * progress.value }
                .background(Color.Black)
                // 遮罩点击关闭：不用 clickable，避免无障碍树里多出一个没有名字的可点节点。
                .pointerInput(Unit) { detectTapGestures { latestDismiss?.invoke() } }
        )
        // 卡片位置：优先在整屏居中（Material 观感）；空间不够时上移到"底栏之上"，
        // 保证按钮永远不会被底栏盖住（横屏竖向只有 520dp 左右，必须让位）。
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Box(
                    Modifier
                        .padding(24.dp)
                        .graphicsLayer {
                            alpha = progress.value
                            scaleX = 0.96f + 0.04f * progress.value
                            scaleY = scaleX
                        }
                        // 卡片自身吞掉点击，否则点卡片空白处会穿透到遮罩、把弹窗关掉。
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier.widthIn(min = 280.dp, max = 560.dp)
                    ) { body() }
                }
            }
        ) { measurables, constraints ->
            val pad = 24.dp.roundToPx()
            val inset = bottomInset.roundToPx()
            val available = (constraints.maxHeight - inset - pad * 2).coerceAtLeast(pad * 2 + 1)
            val placeable = measurables.first().measure(
                constraints.copy(minWidth = 0, minHeight = 0, maxHeight = available)
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                val centered = (constraints.maxHeight - placeable.height) / 2
                val safe = constraints.maxHeight - inset - placeable.height
                placeable.place(
                    x = (constraints.maxWidth - placeable.width) / 2,
                    y = minOf(centered, safe).coerceAtLeast(0)
                )
            }
        }
    }
}

/**
 * 与 Material3 AlertDialog 同形的页内弹窗；调用方式与参数完全一致，迁移只需改名。
 */
@Composable
internal fun AppDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val host = LocalAppDialogHost.current
    if (host == null) {
        // 没有宿主（例如在预览或未挂载宿主的环境）：退化为系统弹窗，保证不丢。
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            modifier = modifier
        )
        return
    }
    val token = remember { Any() }
    // 已被别的调用点占用时不再抢占，退化为系统弹窗（本应用不嵌套弹窗，这里只是保险）。
    if (host.isOpen && host.owner !== token) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            modifier = modifier
        )
        return
    }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    // 弹窗内容常依赖调用方的最新状态：每次组合刷新，宿主才能拿到最新一份。
    // 结构同 Material3 AlertDialog：标题与按钮固定，只有正文区滚动（横屏/键盘时按钮不会被挤走）。
    val contentState = rememberUpdatedState<@Composable () -> Unit> {
        Column(Modifier.padding(24.dp)) {
            title?.let {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    LocalTextStyle provides MaterialTheme.typography.headlineSmall
                ) { Box(Modifier.padding(bottom = 16.dp)) { it() } }
            }
            text?.let {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium
                ) {
                    Box(
                        Modifier.weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp)
                    ) { it() }
                }
            }
            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
    DisposableEffect(host) {
        host.owner = token
        host.onDismiss = { latestDismiss() }
        host.content = { contentState.value() }
        onDispose {
            if (host.owner === token) {
                host.content = null
                host.onDismiss = null
                host.owner = null
            }
        }
    }
}

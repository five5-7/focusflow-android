package com.sakata.focusflow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * 8.1.0 第三轮：把弹窗从"独立窗口"改为**页内浮层**。
 *
 * 动机：Android 的 Dialog 是独立窗口，永远盖在 Activity 之上，导致弹窗打开时悬浮底栏
 * （以及回退／折返键）完全不可点——实测底栏节点连无障碍树都进不去。
 * 改成页内浮层后，底栏仍在其上方、保持可点，历史导航在弹窗期间依然可用。
 *
 * 统一性：外观复刻 Material3 AlertDialog（同圆角、同内边距、同按钮排布），
 * 所有弹窗共用同一套返回键、遮罩点击、键盘避让与进场动画。
 * 导航：任何页面导航（点入口、点 ＋、跳转）都会先关闭当前弹窗
 * （[AppDialogHostState.dismissCurrent]，由 MainActivity 的 `goTo` 统一调用）。
 * 但**上一步／下一步不关弹窗**：弹窗是历史的一步（见 [PageSnapshot.dialogOpen]），
 * 上一步把它"收起"（[visible] = false，内容仍注册），下一步再原样带回——
 * 这正是用户要的"上一步后窗口关闭、再下一步窗口打开且数据还在"。
 * 嵌套：弹窗打开期间又打开另一个（例如长按底栏回退键弹历史列表）时**后开的接管**，
 * 前一个的调用点保持组合，等宿主空闲后自动重新注册（见 [AppDialog]），
 * 因此不会退回系统弹窗、底栏也始终可用。
 * 兜底：拿不到宿主时退化为系统 AlertDialog，任何弹窗都不会因此消失。
 */
internal class AppDialogHostState {
    internal var content: (@Composable () -> Unit)? by mutableStateOf(null)
    internal var onDismiss: (() -> Unit)? by mutableStateOf(null)

    /** 当前占用宿主的调用点身份；用于避免旧实例的 onDispose 误清新弹窗。 */
    internal var owner: Any? by mutableStateOf(null)

    /** 最近一次注册的序号：后开的弹窗序号更大，因此可以接管宿主。 */
    internal var seq: Int by mutableStateOf(0)
    internal var nextSeq: Int = 0

    /**
     * 宿主此刻是否真的在场（false = 被"上一步"收起，内容仍注册）。
     * 由 [AppDialogHost] 每次组合同步，用于 [dismissSuspendedOnTakeover]。
     */
    internal var visible: Boolean by mutableStateOf(true)

    /**
     * 遮罩与卡片共用的动画进度（0→1），只在绘制阶段读取，
     * 因此收起/进场期间不会引起逐帧重组。
     */
    internal val progress = Animatable(0f)

    internal val isOpen: Boolean get() = content != null

    /**
     * 主动关闭当前弹窗（页面导航或点 ＋ 之前调用）。
     * 只调调用点的 onDismissRequest，真正的清理仍由该调用点的 onDispose 完成。
     */
    internal fun dismissCurrent() {
        onDismiss?.invoke()
    }

    /**
     * 有新弹窗要接管宿主时调用：当前这个若正被"收起"（上一步把它挪出屏幕，
     * 但调用点状态仍是打开），它在页面上已经看不见、也等不到下一步了——
     * 直接真正关掉它，否则它会在新弹窗关闭后又自己冒回来。
     */
    internal fun dismissSuspendedOnTakeover() {
        if (isOpen && !visible) onDismiss?.invoke()
    }
}

internal val LocalAppDialogHost = staticCompositionLocalOf<AppDialogHostState?> { null }

/** 弹窗卡片平移距离 = 屏幕高度的这个比例（进入从下往上、退出从上往下）。 */
private const val DIALOG_SLIDE_SCREEN_FRACTION = 0.32f

/**
 * 页内浮层宿主：放在悬浮底栏**之前**，因此永远位于底栏之下。
 *
 * 进场/退场用同一条规范（[MotionSpec.enter] / [MotionSpec.exit]）：
 * 遮罩淡入淡出，卡片同步上下平移。退场时调用方已经离开组合、内容会被清空，
 * 因此这里额外保留最后一次内容 [retained]，让退场动画有东西可画。
 *
 * [visible] 由导航历史驱动：false 且内容仍注册时是"收起"而不是"关掉"——
 * 卡片滑出屏幕、不响应触摸、不进无障碍树，但组合与调用点状态全部保留，
 * 所以下一步把它带回来时数据还在（见 [PageSnapshot.dialogOpen]）。
 *
 * [bottomInset] 传悬浮底栏的实测高度：卡片只在底栏**之上**的区域居中，
 * 否则横屏（竖向空间只有 520dp 左右）时卡片底部按钮会被底栏盖住。
 */
@Composable
internal fun AppDialogHost(
    state: AppDialogHostState,
    bottomInset: Dp = 0.dp,
    visible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val content = state.content
    val dismiss = state.onDismiss
    val registered = content != null && dismiss != null
    // 在场＝已注册 且 这一步历史说弹窗是开着的。
    val open = registered && visible
    // 让宿主状态知道此刻是否在场（"收起"期间接管旧弹窗要用）。放在早退之前。
    SideEffect { state.visible = visible }
    val retained = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    SideEffect { if (content != null) retained.value = content }
    val progress = state.progress
    val latestDismiss by rememberUpdatedState(dismiss)
    val focusManager = LocalFocusManager.current
    LaunchedEffect(open) {
        if (open) {
            progress.snapTo(0f)
            progress.animateTo(1f, MotionSpec.enter())
        } else {
            // 收起/关闭都要把键盘收掉，否则弹窗走了输入法还停在屏幕上。
            // （读 progress.value 在副作用里做，避免组合期每帧取动画值。）
            if (progress.value > 0f) focusManager.clearFocus(force = true)
            progress.animateTo(0f, MotionSpec.exit())
            // 只有"真正关掉"才丢内容；单纯收起时保留，等待下一步带回来。
            if (!registered) retained.value = null
        }
    }
    // 弹窗在场期间，系统返回先关弹窗（本处理器最后注册，优先级最高）。
    BackHandler(enabled = open) { latestDismiss?.invoke() }
    val body = retained.value ?: return
    // 卡片从屏幕下方平移进来、再平移回去（用户要求"上下平移进出屏幕"）；
    // 遮罩仍然淡入淡出——跟着一起滑动会显得整个屏幕在晃。
    val slidePx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenHeightDp * DIALOG_SLIDE_SCREEN_FRACTION).dp.toPx()
    }
    // zIndex(1f)：盖住 StatusBarScrim（同 zIndex 0 的兄弟节点、组合在宿主之后），但仍在底栏（2f）之下。
    Box(
        Modifier.fillMaxSize().zIndex(1f)
            // 收起状态不进无障碍树（卡片已在屏幕外）。
            .then(if (open) Modifier else Modifier.clearAndSetSemantics {})
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = MotionSpec.SCRIM_ALPHA * progress.value }
                .background(Color.Black)
                // 遮罩点击关闭：不用 clickable，避免无障碍树里多出一个没有名字的可点节点。
                // 收起状态**完全不挂** pointerInput——只挂节点不消费也会挡住下层页面的点击。
                .then(
                    if (open) {
                        Modifier.pointerInput(Unit) { detectTapGestures { latestDismiss?.invoke() } }
                    } else {
                        Modifier
                    }
                )
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
                            val p = progress.value
                            alpha = p
                            translationY = (1f - p) * slidePx
                        }
                        // 卡片自身吞掉点击，否则点卡片空白处会穿透到遮罩、把弹窗关掉。
                        // 同样只在场时挂：收起时不能留下任何会挡住页面点击的节点。
                        .then(if (open) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier)
                ) {
                    val shape = RoundedCornerShape(28.dp)
                    Surface(
                        shape = shape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        // 阴影自己画：Material 默认是纯黑直角阴影，这里换成主题染色的软阴影，
                        // 并把卡片抬得更高，让它明显浮在压暗的页面之上（分层）。
                        shadowElevation = 0.dp,
                        // 极细描边：花哨度很低，但在深色遮罩上能把卡片边缘勾清楚。
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        // 调用方传的 modifier 作用在卡片上（与 AlertDialog 语义一致）。
                        modifier = modifier
                            .widthIn(min = 280.dp, max = 560.dp)
                            .litShadow(SurfaceLighting.DIALOG_SHADOW, shape)
                            // 维护者口径「弹窗也没有材质渲染」：弹窗卡片同样吃当前材质，
                            // 与页面卡片/底栏共用一份实现，底色是弹窗自己的 surfaceContainerHigh。
                            .surfaceMaterialFill(
                                LocalAppearance.current.effectiveCardMaterial,
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape
                            )
                    ) {
                        Box {
                            // 顶部高光：光源在上方，卡片顶面微亮、往下回落，避免"贴纸感"。
                            Box(
                                Modifier.matchParentSize().background(
                                    Brush.verticalGradient(
                                        0f to Color.White.copy(alpha = SurfaceLighting.TOP_LIGHT_ALPHA),
                                        0.6f to Color.Transparent
                                    )
                                )
                            )
                            body()
                        }
                    }
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
    // 后开的弹窗接管宿主；被顶掉的调用点不会退化为系统弹窗，而是等宿主空闲后自动重新注册。
    val mySeq = remember { ++host.nextSeq }
    val active = !host.isOpen || host.owner === token || mySeq > host.seq
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
    // active 变化时重新评估：被别人顶掉 → 注销自己；宿主空闲 → 重新注册（历史列表关掉后弹窗自己回来）。
    DisposableEffect(host, active) {
        if (active) {
            // 上一个若只是被"收起"，它已经不在场了：先真正关掉，免得它稍后冒回来。
            host.dismissSuspendedOnTakeover()
            host.owner = token
            host.seq = mySeq
            host.onDismiss = { latestDismiss() }
            host.content = { contentState.value() }
        }
        onDispose {
            if (host.owner === token) {
                host.content = null
                host.onDismiss = null
                host.owner = null
            }
        }
    }
}

package com.sakata.focusflow

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

/** Nav text remains readable independently of the user-selected body text color. */
internal fun navigationContentColor(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) Color.Black else Color.White

internal fun navigationIndicatorColor(background: Color, primary: Color): Color =
    if (contrastRatio(background, primary) >= 1.5) primary.copy(alpha = 1f) else navigationContentColor(background)

/**
 * 8.1.0 底栏形变形状：平时为完整胶囊（四角半径=高度一半）；
 * 每个上角独立按 progress 动画收缩为小圆角（18dp），对应各自图标的出现/消失。
 * 下两角始终为大圆角；左右竖直、上下平直。
 *
 * 小圆角 10dp → 16dp → 18dp（维护者口径「稍微增大一点」）：底栏高 92dp，完整胶囊
 * 半径是 46dp，所以 18dp 仍与平时状态有明显形变对比，只是上角不再那么方。
 */
private class AsymmetricCapsuleShape(
    private val progressL: Float,
    private val progressR: Float,
    private val smallRadiusDp: Float
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r2 = h / 2f
        val smallPx = with(density) { smallRadiusDp.dp.toPx() }
        val r1L = r2 + (smallPx - r2) * progressL.coerceIn(0f, 1f)
        val r1R = r2 + (smallPx - r2) * progressR.coerceIn(0f, 1f)
        val path = Path().apply {
            // 从左下大圆角开始，顺时针一圈（sweep 负值=屏幕上逆时针转过该角）
            moveTo(0f, h / 2f)
            arcTo(Rect(0f, h - 2 * r2, 2 * r2, h), 180f, -90f, false)
            lineTo(w - r2, h)
            arcTo(Rect(w - 2 * r2, h - 2 * r2, w, h), 90f, -90f, false)
            lineTo(w, r1R)
            arcTo(Rect(w - 2 * r1R, 0f, w, 2 * r1R), 0f, -90f, false)
            lineTo(r1L, 0f)
            arcTo(Rect(0f, 0f, 2 * r1L, 2 * r1L), 270f, -90f, false)
            lineTo(0f, h / 2f)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Overlay surface. Only the rounded bar receives input; exterior margins pass through. */
@Composable
internal fun FloatingNavigationBar(
    safeInsets: WindowInsets,
    containerColor: Color,
    /** 底栏底色画刷（表达得出左右渐变）。为 null 时退回 containerColor。 */
    containerBrush: Brush? = null,
    selectedTab: Int,
    hasSubpage: Boolean,
    selectedPageDescription: String,
    onSelectTab: (Int) -> Unit,
    onAdd: () -> Unit,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    /** 弹窗浮层是否打开：打开时底栏退到后面去（压暗 + 收阴影），别抢弹窗的存在感。 */
    dialogOpen: Boolean = false,
    onBackHistory: () -> Unit = {},
    onForwardHistory: () -> Unit = {},
    onLongPressBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val background by animateColorAsState(containerColor, MotionSpec.move(), label = "navigationTheme")
    val indicator = navigationIndicatorColor(background, MaterialTheme.colorScheme.primary)
    // 8.2.0：弹窗打开时底栏跟着"退后"。
    // 底栏的 zIndex 比弹窗层高（弹窗打开时它还要可点），所以弹窗那层 32% 的遮罩**盖不到它**——
    // 真机上的表现就是"弹窗开了，底栏一点没变，反而比弹窗还抢眼"（维护者反馈）。
    // 这里让底栏自己同步压暗，并把 7dp 的投影收到 1dp：压暗让它退到遮罩底下，
    // 收阴影则去掉了那圈"浮在最上层"的观感。用 MotionSpec.move() 与弹窗遮罩同节奏。
    val barDim by animateFloatAsState(if (dialogOpen) MotionSpec.SCRIM_ALPHA else 0f, MotionSpec.move(), label = "barDim")
    val barShadow by animateDpAsState(if (dialogOpen) 1.dp else 7.dp, MotionSpec.move(), label = "barShadow")
    // 8.2.0：卡片材质同样作用在底栏上（维护者口径：「材质应该包括底栏才对」）。
    //
    // 接法：**画在 Surface 内容的最底层**，而不是挂在 Surface 的 modifier 上。
    // 试过并否决的两种：
    // 1. 挂在 Surface 的 modifier 上 —— 里面的 drawBehind 排在 Surface 自己的
    //    `.background(color)` **之前**，材质被底色整块盖住，等于没渲染；
    // 2. 把 Surface 的 color 让成 Transparent 让材质露出来 —— 底色没了之后，
    //    Surface 自己的 **shadowElevation 阴影**就画到材质层上面（阴影内部原本靠不透明底色遮着），
    //    维护者立刻复现了"胶囊要么没渲染、要么就有问题"的老毛病。
    // 现在 Surface 底色保持不透明（投影照旧被遮住），材质与渐变画在内容最底层，层级自然正确。
    val barMaterial = LocalAppearance.current.effectiveCardMaterial
    val barBrush: Brush? = containerBrush
    // 8.1.0 形变：每个顶角各自跟随自己的图标——有回退才伸出左角、有折返才伸出右角；图标消失即收回。
    val backProgress by animateFloatAsState(if (canGoBack && MotionSpec.morphEnabled) 1f else 0f, MotionSpec.morph(), label = "backCorner")
    val forwardProgress by animateFloatAsState(if (canGoForward && MotionSpec.morphEnabled) 1f else 0f, MotionSpec.morph(), label = "forwardCorner")
    val barShape = AsymmetricCapsuleShape(progressL = backProgress, progressR = forwardProgress, smallRadiusDp = 18f)
    val iconBoxPx = with(LocalDensity.current) { FloatingNavigationLayout.ICON_BOX_DP.dp.toPx() }
    // 8.1.0 第三轮：选中底色改为"一块会平移的底色"——从上一个页签滑到当前页签，而不是各自淡入淡出。
    // 槽位顺序：今日 / 日程 / [加号] / 计划 / 设置。
    val selectedSlot = if (selectedTab < 2) selectedTab else selectedTab + 1
    val slotCenters = remember { mutableStateListOf<Offset?>(null, null, null, null, null) }
    // 绘制层自己的原点（根坐标）。槽位中心也用根坐标上报，两者相减得到本层坐标，
    // 避免"在 Row 上测原点、却画在与 Row 同层的 Box 里"这种坐标系错位（实测偏左 12dp）。
    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    val indicatorSlot = remember { Animatable(selectedSlot.toFloat()) }
    LaunchedEffect(selectedSlot) { indicatorSlot.animateTo(selectedSlot.toFloat(), MotionSpec.move()) }
    // 目的地变化（同一页签内子页名切换）时的轻微回弹；切页签不弹。
    val destinationPulse = remember { Animatable(1f) }
    var previousDestination by remember { mutableStateOf(selectedPageDescription) }
    var previousSelectedTab by remember { mutableIntStateOf(selectedTab) }
    LaunchedEffect(selectedPageDescription, selectedTab) {
        val tabChanged = selectedTab != previousSelectedTab
        val destinationChanged = selectedPageDescription != previousDestination
        previousDestination = selectedPageDescription
        previousSelectedTab = selectedTab
        if (!tabChanged && destinationChanged && MotionSpec.animationsEnabled) {
            destinationPulse.snapTo(0.90f)
            destinationPulse.animateTo(1f, MotionSpec.pulse())
        } else destinationPulse.snapTo(1f)
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().windowInsetsPadding(
            safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        ),
        contentAlignment = Alignment.Center
    ) {
        val margin = FloatingNavigationLayout.horizontalMarginDp(maxWidth.value, LocalDensity.current.fontScale).dp
        Box(
            Modifier.padding(horizontal = margin, vertical = 8.dp)
                .widthIn(max = FloatingNavigationLayout.MAX_BAR_WIDTH_DP.dp)
                .fillMaxWidth()
        ) {
            Surface(
                // 弹窗打开时整体压暗：用 graphicsLayer 的 alpha 让底栏"沉到遮罩下面"，
                // 与弹窗那层 Color.Black + SCRIM_ALPHA 同一个观感。
                //
                // 材质叠层必须走 surfaceMaterialFill（内部先 clip(barShape)）：
                // 之前这里直接 drawBehind 画矩形，而 drawBehind 在 Surface 的形状裁剪之外，
                // 于是底栏上冒出一整块矩形底色（维护者反馈"像一块矩形底"）。
                modifier = Modifier
                    .fillMaxWidth()
                    // 弹窗打开时**降低胶囊亮度**（维护者口径：「如果有弹窗，你降一下胶囊亮度就行了」）。
                    //
                    // 早先这里是 `graphicsLayer { alpha = 1f - barDim }` —— 把整条底栏变**半透明**。
                    // 半透明的效果是"底下的页面文字透上来"，看着发白/发灰，而不是沉下去
                    // （维护者：「弹窗出现时半透明的变化过于简化了，如果底下有字怎么办」）。
                    // 改成在整条底栏（底色 + 图标 + 文字）之上压一层黑：亮度真的降下来，
                    // 又不会透出底下的内容。barDim 没有弹窗时恒为 0，默认外观不受影响。
                    //
                    // 注：**不要**用 `Modifier.blur` 去糊弹窗背后的页面——那是「毛玻璃材质」的事
                    // （维护者：「背景模糊不是毛玻璃材质的事情吗」），别给它做一次性特例。
                    .clip(barShape)
                    .drawWithContent {
                        drawContent()
                        // 0.6 = 压暗系数：barDim 最大是 SCRIM_ALPHA(0.32)，乘完约 0.19 的黑，
                        // 底栏（浅色主题下约 rgb(225,235,230)）会降到约 rgb(182,190,186)——
                        // 明显沉下去，但图标与文字仍然清楚。
                        if (barDim > 0f) drawRect(Color.Black.copy(alpha = barDim * 0.6f))
                    }
                    // 底栏底色**用画刷而不是单色**：单色表达不出左右渐变
                    // （维护者："导航栏不会相应左右渐变的底色"）。
                    // clip(barShape) 是必须的——drawBehind 在 Surface 形状裁剪之外。
                    //
                    // 注意：这条画刷路径当前恒为 null（T-4 因"渲染成三层"被回退）。
                    .then(
                        if (containerBrush != null) {
                            Modifier.drawBehind { drawRect(containerBrush) }
                        } else {
                            Modifier
                        }
                    ),
                shape = barShape,
                // 底色恒为 Surface 自己的不透明色：它同时负责遮住 Surface 投影的内部，
                // 所以**不能**为了露出材质而把它让成透明。
                // 渐变（barBrush）与材质都画在内容最底层、盖在它上面。
                color = background,
                tonalElevation = 0.dp,
                // 维护者口径：不要那条 2dp 的硬灰线，改成"靠里浅、靠边深"的过渡——
                // 描边收成几乎看不见的发丝线，靠阴影把边缘柔化出去（3dp → 7dp）。
                // 弹窗打开时收到 1dp：那圈投影正是"抢弹窗存在感"的来源。
                shadowElevation = barShadow,
                border = BorderStroke(0.6.dp, navigationContentColor(background).copy(alpha = 0.12f))
            ) {
                // 底栏自己的两层底**必须画在 Surface 内容里**：
                // Surface 的 `color` 会盖住挂在它 modifier 上的 drawBehind（见 surfaceMaterialFill 的说明），
                // 而把 Surface 底色让成透明又会让它自己的投影浮上来（维护者当场复现"胶囊有问题"）。
                // 所以：Surface 底色保持不透明（投影照旧被它遮住），底色与材质都画在内容最底层。
                //
                // `propagateMinConstraints = true` 是必须的：Surface 内部那个 Box 就是开着它的，
                // 少了这一句，下面的 BoxWithConstraints 会拿不到最小宽度、底栏缩成内容宽度。
                Box(propagateMinConstraints = true) {
                    // ① 底色层：页面是渐变时按点取色铺一条横向画刷
                    //    （维护者："导航栏不会相应左右渐变的底色"）。
                    //    非渐变档 barBrush 为 null，交给 Surface 的纯色，那些档位逐像素不变。
                    if (barBrush != null) {
                        Box(Modifier.matchParentSize().drawBehind { drawRect(barBrush) })
                    }
                    // ② 材质层：与卡片、弹窗共用同一份实现
                    //    （维护者："材质应该包括底栏才对"）。
                    //
                    // 跟随页面渐变时（barBrush != null）只画 0.6：材质画刷是**不透明**的，
                    // 全强度会把下面那条横向渐变整块盖住，"底栏跟随左右渐变"就看不见了
                    // （真机实测过：页面渐变方向是 rightleft，而底栏那一行是平的）。
                    // 半强度让两者叠加——横向是页面渐变、纵向是材质剖面。
                    // 非渐变档没有画刷要透，保持全强度，与卡片一致。
                    Box(
                        Modifier.matchParentSize().surfaceMaterialFill(
                            barMaterial,
                            background,
                            barShape,
                            // **不要对已经半透明的材质再乘一次 alpha。**
                            // 这层 0.6 原本是为了让下面那条"跟随页面左右渐变"的画刷透上来；
                            // 而亚克力自身 alpha 就是 0.60，再乘 0.6 只剩 0.36 ——
                            // 叠在底栏这种不透明底色上几乎看不出层次，
                            // 维护者就是因此报"导航栏没有渲染上"（真机实测：亚克力档底栏
                            // 比默认只深 9 级且完全平，看着像没生效）。
                            // 半透明材质本来就透，所以它走全强度。
                            alpha = if (barBrush != null && barMaterial != CardMaterial.ACRYLIC) 0.6f else 1f
                        )
                    )
                // Internal padding contains BOTH selected background and ripple within the outer corners.
                BoxWithConstraints(Modifier.padding(FloatingNavigationLayout.INNER_PADDING_DP.dp)) {
                    val contentWidth = maxWidth.coerceAtLeast(FloatingNavigationLayout.MIN_CONTENT_WIDTH_DP.dp)
                    Box {
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            Box(Modifier.onGloballyPositioned { layerOrigin = it.positionInRoot() }) {
                            // 底色块：位置在 layout 阶段算、半径与透明度在绘制阶段算，
                            // 组合阶段不读动画值——否则整条底栏会在底色平移的每一帧重组。
                            Box(
                                Modifier
                                    .offset {
                                        val slot = indicatorSlot.value
                                        val lower = floor(slot).toInt().coerceIn(0, 4)
                                        val upper = (lower + 1).coerceAtMost(4)
                                        val from = slotCenters[lower] ?: Offset.Zero
                                        val to = slotCenters[upper] ?: from
                                        val f = slot - lower
                                        val start = from - layerOrigin
                                        val end = to - layerOrigin
                                        val centerX = start.x + (end.x - start.x) * f
                                        val centerY = start.y + (end.y - start.y) * f
                                        IntOffset((centerX - iconBoxPx / 2).roundToInt(), (centerY - iconBoxPx / 2).roundToInt())
                                    }
                                    .size(FloatingNavigationLayout.ICON_BOX_DP.dp)
                                    .drawBehind {
                                        // 槽位中心还没测到时先不画，避免首帧闪到左上角。
                                        val slot = indicatorSlot.value
                                        val lower = floor(slot).toInt().coerceIn(0, 4)
                                        val upper = (lower + 1).coerceAtMost(4)
                                        if (slotCenters[lower] == null || slotCenters[upper] == null) return@drawBehind
                                        val radius = size.minDimension / 2 * destinationPulse.value
                                        if (hasSubpage) {
                                            drawCircle(indicator, radius = radius, style = Stroke(width = 3.dp.toPx()))
                                        } else {
                                            drawCircle(indicator, radius = radius)
                                        }
                                    }
                            )
                            Row(
                                Modifier.width(contentWidth).selectableGroup().padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val labels = listOf("今日", "日程", "计划", "设置")
                                val icons = listOf(Icons.Outlined.Home, Icons.Outlined.DateRange, Icons.Outlined.List, Icons.Outlined.Settings)
                                labels.forEachIndexed { index, label ->
                                    if (index == 2) Box(
                                        Modifier.weight(1f).onGloballyPositioned {
                                            // 加号占第 3 个槽位（索引 2），底色块经过它时必须能取到中心。
                                            slotCenters[2] = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)
                                        },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                            onClick = onAdd, modifier = Modifier.size(56.dp),
                                            shape = CircleShape,
                                            color = indicator, contentColor = navigationContentColor(indicator)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.Add, contentDescription = "添加")
                                            }
                                        }
                                    }
                                    FloatingNavigationItem(
                                        label, icons[index], selectedTab == index, background, indicator,
                                        Modifier.weight(1f).semantics {
                                            if (selectedTab == index) stateDescription = selectedPageDescription +
                                                if (hasSubpage) "；再次点击返回${label}主页" else ""
                                        },
                                        hasSubpage = selectedTab == index && hasSubpage,
                                        destinationKey = if (selectedTab == index) selectedPageDescription else label,
                                        onIconCenter = { center ->
                                            val slotIndex = if (index < 2) index else index + 1
                                            if (slotCenters[slotIndex] != center) slotCenters[slotIndex] = center
                                        },
                                        onClick = { onSelectTab(index) }
                                    )
                                }
                            }
                            }
                        }
                    }
                }
                } // 底栏自己的底色 + 材质层（见上面的 Box）
            }
            // 8.1.0 顶角符号：无柄箭头（chevron），位于两顶角内侧，各自随自己的角伸缩。
            CornerSymbol(
                visible = canGoBack,
                progress = backProgress,
                icon = Icons.Filled.KeyboardArrowLeft,
                onClick = onBackHistory,
                onLongPress = onLongPressBack,
                description = "回退到上一个页面；长按查看历史",
                tint = navigationContentColor(background),
                // 维护者口径：继续向角落收（横向 0dp→-2dp、纵向 4dp→0dp）。
                // -2dp 只会吃掉圆点自身的 5dp 内边距，图标仍在胶囊内部。
                modifier = Modifier.align(Alignment.TopStart).offset(x = (-2).dp, y = 0.dp)
            )
            CornerSymbol(
                visible = canGoForward,
                progress = forwardProgress,
                icon = Icons.Filled.KeyboardArrowRight,
                onClick = onForwardHistory,
                onLongPress = null,
                description = "折返到后一个页面",
                tint = navigationContentColor(background),
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = 0.dp)
            )
        }
    }
}

/** 8.1.0 顶角符号：实心箭头图标（无圆圈底），与形状同一进度淡入；圆形波纹裁剪避免方形阴影。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CornerSymbol(
    visible: Boolean,
    progress: Float,
    icon: ImageVector,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
    description: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val clickModifier = if (onLongPress != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress)
    } else {
        Modifier.combinedClickable(onClick = onClick)
    }
    Box(
        modifier = modifier
            // 维护者口径：箭头放大（28 → 38dp 触控框），更容易看见也更好点。
            .size(38.dp)
            .graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
            .clip(CircleShape)
            .then(clickModifier)
            .minimumInteractiveComponentSize()
            .semantics { stateDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(28.dp))
    }
}

/** 8.1.0 会话页面历史列表：最近的在最上，点选直达并截断其后历史。 */
@Composable
internal fun HistoryListDialog(
    entries: List<PageSnapshot>,
    current: PageSnapshot,
    onSelect: (PageSnapshot) -> Unit,
    onDismiss: () -> Unit
) {
    val reversed = entries.reversed()
    // 列表按页面粒度展示（弹窗层不进列表），因此比较时也去掉弹窗层。
    val currentPage = current.withoutDialog()
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("本次会话的页面历史") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                reversed.forEach { snapshot ->
                    val isCurrent = snapshot == currentPage
                    Surface(
                        onClick = { onSelect(snapshot) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                snapshot.label,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun FloatingNavigationItem(
    label: String, icon: ImageVector, selected: Boolean,
    background: Color, indicator: Color, modifier: Modifier,
    hasSubpage: Boolean, destinationKey: String,
    onIconCenter: (Offset) -> Unit, onClick: () -> Unit
) {
    // 选中态只驱动图标颜色/缩放；底色块由底栏统一绘制并平移（见 FloatingNavigationBar）。
    val progress by animateFloatAsState(if (selected) 1f else 0f, MotionSpec.move(), label = "navigationSelection")
    val fill = lerp(background, indicator, progress)
    // 8.1.0 副页（空心圆环态）时图标改用与底栏对比的深色；实心态按圆底色取对比色。
    val foreground = if (selected && hasSubpage) navigationContentColor(background) else navigationContentColor(fill)
    val animatedForeground by animateColorAsState(foreground, MotionSpec.move(), label = "navigationForeground")
    Column(
        modifier.heightIn(min = FloatingNavigationLayout.MIN_ITEM_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(percent = 50))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        Box(
            Modifier.size(FloatingNavigationLayout.ICON_BOX_DP.dp)
                // 底色块需要知道自己该画在哪：由图标自己上报中心（同一坐标系，随字体/窄屏自动跟随）。
                .onGloballyPositioned {
                    val iconSize = it.size
                    onIconCenter(it.positionInRoot() + Offset(iconSize.width / 2f, iconSize.height / 2f))
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = animatedForeground,
                modifier = Modifier.size(24.dp).graphicsLayer {
                    scaleX = 0.96f + 0.04f * progress
                    scaleY = scaleX
                })
        }
        // 8.1.0 副页表示：选中且处于子页时，标签替换为子页名（如「设置」→「外观」）。
        val displayLabel = if (selected && hasSubpage) destinationKey else label
        // 8.1.0 第三轮：页签名与子页名之间交叉淡入，替代原来的瞬时替换。
        Crossfade(targetState = displayLabel, animationSpec = MotionSpec.move(), label = "navigationLabel") { text ->
            Text(
                text,
                color = navigationContentColor(background),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

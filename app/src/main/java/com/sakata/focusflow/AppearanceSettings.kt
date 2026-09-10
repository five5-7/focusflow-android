package com.sakata.focusflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 8.2.0「设置 → 外观」这一段。
 *
 * 全部可选、默认跟随主题；导入的图片只写进应用私有目录，不出本机、不上传。
 * 图片背景之上永远压一层主题遮罩（见 [scrimAlpha]），正文对比度靠它保住。
 *
 * 结构说明（第 7 项起）：三段控件都做成了**可复用**的独立组件 ——
 * [PageBackdropControls] / [CardMaterialControls] / [TimetableBaseSection]，
 * 「设置 → 外观」按顺序摆一遍，「自定义主题 → 编辑」再摆一遍，
 * 于是一处改动两处同时生效，不会出现两套长得不一样的控件。
 */
@Composable
internal fun AppearanceSettingsSection(
    appearance: AppearanceSpec,
    onAppearanceChange: (AppearanceSpec) -> Unit,
    onApplyExtractedTheme: (FocusFlowThemeColors) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageBackdropControls(
            appearance = appearance,
            onAppearanceChange = onAppearanceChange,
            onApplyExtractedTheme = onApplyExtractedTheme
        )
        // 关掉「丰富的动画与外观效果」时，卡片材质与课表底色**完全不参与渲染**
        // （effectiveCardMaterial 回落 TONAL、effectiveTimetableBackdrop 回落 THEME）。
        // AGENTS.md 明确要求"界面里不存在承诺了却不生效的开关"，所以这时把它们收起来，
        // 并写清"设置还在、重开即可恢复"，而不是留一堆点了没反应的控件。
        if (appearance.richEffects) {
            HorizontalDivider()
            CardMaterialControls(appearance = appearance, onAppearanceChange = onAppearanceChange)
            HorizontalDivider()
            TimetableBaseControls(appearance = appearance, onAppearanceChange = onAppearanceChange)
        } else {
            HorizontalDivider()
            Text(
                "卡片材质与课表底色已随「丰富的动画与外观效果」一起暂停——" +
                    "之前选过的设置都还留着，重新打开开关就会恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 页面背景控件：跟随主题 / 主题渐变（停靠色 + 强度 + 跟随内容）/ 固定颜色 / 图片
 * （导入、移除、不透明度、从图片抽主题色）。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PageBackdropControls(
    appearance: AppearanceSpec,
    onAppearanceChange: (AppearanceSpec) -> Unit,
    onApplyExtractedTheme: (FocusFlowThemeColors) -> Unit,
    /** 抽色成功后的提示词：外观页是"已应用"，主题编辑器里只更新草稿配色。 */
    extractedAppliedNote: String = "已按图片抽色并应用"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')
            val name = AppearanceImages.newName(extension)
            val stored = withContext(Dispatchers.IO) {
                val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                stream != null && AppearanceImages.store(context, name, stream, 1440, 3168)
            }
            if (stored) {
                // 先落下新图再切模式：切模式会立刻触发解码，避免出现"模式是新图但文件还是旧的"。
                onAppearanceChange(appearance.copy(pageBackdrop = BackdropKind.IMAGE, pageImage = name))
                status = "已导入，只存在本机（原图不会被上传）"
            } else {
                status = "这张图片读不出来，换一张试试"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 用 FlowRow 而不是 Row：真机上四个标签排一行会把"图片"挤掉（8.2.0 真机发现），
        // 窄屏/大字体下应该自动换行。
        //
        // 关掉「丰富效果」时只留"跟随主题 / 固定颜色"：渐变与图片那两档此时**不参与渲染**
        // （effectivePageBackdrop 会把它们回落成 THEME），留着就是"承诺了却不生效"的开关。
        // 选中态也一律按 **effective** 判定，让界面显示的就是实际画出来的那一档。
        val offered = appearance.offeredPageBackdrops.map { kind ->
            kind to when (kind) {
                BackdropKind.THEME -> "跟随主题"
                BackdropKind.GRADIENT -> "主题渐变"
                BackdropKind.COLOR -> "固定颜色"
                BackdropKind.IMAGE -> "图片"
                // 页面角色拿不到这一档（offeredPageBackdrops 里没有它），
                // 这个分支只为编译期穷尽性存在。
                BackdropKind.TRANSPARENT -> "透明"
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            offered.forEach { (kind, label) ->
                FilterChip(
                    selected = appearance.effectivePageBackdrop == kind,
                    onClick = {
                        // 关掉丰富效果时如果用户点"跟随主题"，顺手把原始档位也归位，
                        // 否则原始值会一直停在 GRADIENT，重新打开开关时"突然又变了"，很困惑。
                        onAppearanceChange(
                            if (!appearance.richEffects && kind == BackdropKind.THEME) {
                                appearance.copy(pageBackdrop = kind, gradientTop = 0, gradientBottom = 0)
                            } else {
                                appearance.copy(pageBackdrop = kind)
                            }
                        )
                    },
                    label = { Text(label) }
                )
            }
        }
        if (!appearance.richEffects) {
            Text(
                "已暂停渐变与图片背景（跟随主题／固定颜色不受影响）。" +
                    "在下面重新打开「丰富的动画与外观效果」即可恢复你之前选过的那一套。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (appearance.effectivePageBackdrop == BackdropKind.GRADIENT) {
            GradientStopsControls(appearance = appearance, onAppearanceChange = onAppearanceChange) {
                status = it
            }
        }

        // 8.2.0 §7.5：固定背景色（维护者要求"实现固定背景色"）。
        if (appearance.effectivePageBackdrop == BackdropKind.COLOR) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PAGE_BASE_PRESETS.forEach { preset ->
                    val selected = appearance.pageColor == preset
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(preset))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                            .clickable {
                                onAppearanceChange(appearance.copy(pageBackdrop = BackdropKind.COLOR, pageColor = preset))
                                status = if (timetableBaseIsReadable(preset)) {
                                    "固定背景色已更新（正文对比度达标）"
                                } else {
                                    "这个底色偏深，正文可能读不清"
                                }
                            }
                    )
                }
            }
        }

        if (appearance.effectivePageBackdrop == BackdropKind.IMAGE) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    // OpenDocument：走系统文件选择器，不需要任何存储/媒体权限，所有 API 级别一致。
                    picker.launch(arrayOf("image/*"))
                }) { Text(if (appearance.pageImage.isBlank()) "选择图片" else "更换图片") }
                if (appearance.pageImage.isNotBlank()) {
                    TextButton(onClick = {
                        val old = appearance.pageImage
                        onAppearanceChange(appearance.copy(pageImage = "", pageBackdrop = BackdropKind.THEME))
                        scope.launch { withContext(Dispatchers.IO) { AppearanceImages.delete(context, old) } }
                        status = "已移除图片背景"
                    }) { Text("移除图片") }
                }
            }
            Text("不透明度 ${appearance.backdropOpacity}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = appearance.backdropOpacity.toFloat(),
                onValueChange = { onAppearanceChange(appearance.copy(backdropOpacity = it.toInt().coerceIn(0, 100))) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "0% 等于只用主题底色；图片之上始终压一层主题遮罩，保证正文读得清。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (appearance.pageImage.isNotBlank()) {
                TextButton(onClick = {
                    scope.launch {
                        val palette = withContext(Dispatchers.Default) {
                            extractPalette(context, appearance.pageImage)
                        }
                        if (palette != null && ExtractedTheme.worthApplying(palette)) {
                            onApplyExtractedTheme(ExtractedTheme.derive(palette))
                            status = "$extractedAppliedNote（主色 #%06X）".format(palette.primary and 0xFFFFFF)
                        } else {
                            status = "这张图没有足够明显的颜色，主题保持不变"
                        }
                    }
                }) { Text("从图片抽取主题色") }
            }
        }

        Text(
            "渐变与抽色都按当前配色派生，不写死色值；文字对比度按 WCAG 校验。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        status?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 渐变停靠色与强度（只在"主题渐变"档下出现）。
 *
 * 八组"顶色 → 底色"色板 + 「跟随主题」档；强度 0–200%，100% 是设计值。
 * 「渐变跟随内容」是维护者口径：关 = 整条渐变正好一屏，开 = 渐变铺满整段内容、每屏更缓。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GradientStopsControls(
    appearance: AppearanceSpec,
    onAppearanceChange: (AppearanceSpec) -> Unit,
    onStatus: (String) -> Unit
) {
    Text("渐变配色", style = MaterialTheme.typography.labelMedium)
    // 色板数量多（自选组 + 「跟随主题」），一行放不下，用 FlowRow 自动换行。
    // 尺寸从 34dp/间距 10dp 收到 30dp/间距 8dp：旧值在 360dp 宽屏上一次只放得下 8 个
    // （9 × 34 + 8 × 10 = 386 > 可用 328dp），最后一个被挤到边上贴边显示
    // （维护者反馈"固定配色最右侧选项会被挤压"）。收小后 11 个一行、且换行也均匀。
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeGradient.PAGE_GRADIENT_PAIRS.forEach { (top, bottom) ->
            val selected = appearance.gradientTop == top && appearance.gradientBottom == bottom
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color(top), Color(bottom))
                        )
                    )
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    )
                    .clickable {
                        onAppearanceChange(appearance.copy(gradientTop = top, gradientBottom = bottom))
                        onStatus("渐变配色已更换")
                    }
            )
        }
        // "跟随主题"档：清掉自选色
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (appearance.gradientTop == 0 && appearance.gradientBottom == 0) 2.dp else 1.dp,
                    color = if (appearance.gradientTop == 0 && appearance.gradientBottom == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = CircleShape
                )
                .clickable {
                    onAppearanceChange(appearance.copy(gradientTop = 0, gradientBottom = 0))
                    onStatus("渐变配色已回到跟随主题")
                }
        )
    }

    // 维护者口径：「在提供一定量的预制前提下添加自定义即可」——预制保留在上面，
    // 这里补"自选两个颜色"。复用自定义主题编辑器里那套 HSV 取色器（ColorPaletteDialog），
    // 不另造一个，避免两处取色体验不一致。
    val followingTheme = appearance.gradientTop == 0 && appearance.gradientBottom == 0
    var editingEndpoint by remember { mutableStateOf<GradientEndpoint?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("自选两个颜色", style = MaterialTheme.typography.labelSmall)
        GradientEndpoint.entries.forEach { endpoint ->
            val current = if (endpoint == GradientEndpoint.TOP) appearance.gradientTop else appearance.gradientBottom
            val label = if (endpoint == GradientEndpoint.TOP) "顶色" else "底色"
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (current != 0) Color(current) else MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            width = if (current != 0) 2.dp else 1.dp,
                            color = if (current != 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape
                        )
                        .clickable { editingEndpoint = endpoint }
                )
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!followingTheme) {
            TextButton(onClick = {
                onAppearanceChange(appearance.copy(gradientTop = 0, gradientBottom = 0))
                onStatus("渐变配色已回到跟随主题")
            }) { Text("用主题派生") }
        }
    }
    if (followingTheme) {
        Text(
            "还没自选颜色：点上面两个圆圈分别挑「顶色」和「底色」，或直接用上面的预制。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // 取色器：起始色 = 已选过就用它，否则用"主题派生出来的那一端"，
    // 让用户从当前真实效果改起，而不是从一个突兀的纯色开始。
    editingEndpoint?.let { endpoint ->
        val scheme = MaterialTheme.colorScheme
        val derived = ThemeGradient.pageStops(scheme, appearance.gradientScale).let {
            if (endpoint == GradientEndpoint.TOP) it.first() else it.last()
        }
        val currentTop = appearance.gradientTop
        val currentBottom = appearance.gradientBottom
        ColorPaletteDialog(
            current = if (endpoint == GradientEndpoint.TOP) {
                if (currentTop != 0) Color(currentTop) else derived
            } else {
                if (currentBottom != 0) Color(currentBottom) else derived
            },
            onPick = { picked ->
                val argb = picked.argbInt()
                onAppearanceChange(
                    if (endpoint == GradientEndpoint.TOP) {
                        appearance.copy(gradientTop = argb)
                    } else {
                        appearance.copy(gradientBottom = argb)
                    }
                )
                onStatus("已选${if (endpoint == GradientEndpoint.TOP) "顶色" else "底色"}")
                editingEndpoint = null
            },
            onDismiss = { editingEndpoint = null }
        )
    }

    Text("渐变方向", style = MaterialTheme.typography.labelMedium)
    // 维护者口径：背景渐变应该可以指定方向。默认上→下 = 原来的样子。
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GradientDirection.entries.forEach { dir ->
            FilterChip(
                selected = appearance.gradientDirection == dir,
                onClick = {
                    onAppearanceChange(appearance.copy(gradientDirection = dir))
                    onStatus("渐变方向改为${dir.label}")
                },
                label = { Text(dir.label) }
            )
        }
    }

    Text("渐变强度 ${appearance.gradientStrength}%", style = MaterialTheme.typography.labelMedium)
    Slider(
        value = appearance.gradientStrength.toFloat(),
        onValueChange = { onAppearanceChange(appearance.copy(gradientStrength = it.toInt().coerceIn(0, GRADIENT_STRENGTH_MAX))) },
        valueRange = 0f..GRADIENT_STRENGTH_MAX.toFloat(),
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "100% 是设计值（顶亮 → 底色 → 微深）；调到 0% 等于纯色，往右更明显（最高 $GRADIENT_STRENGTH_MAX%）。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // 8.2.0 §7.5「渐变跟随内容」：渐变画在**滚动内容自己的高度**上，不依赖任何滚动事件
    // （上一版靠滚动量算相位，真机上相位始终为 0）。
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("渐变跟随内容", fontWeight = FontWeight.SemiBold)
            Text(
                "关＝整条渐变正好一屏，颜色变化快；开＝渐变铺满整段内容，每屏只走一小段，竖向变化更慢更缓。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = appearance.gradientFollowsContent,
            onCheckedChange = {
                onAppearanceChange(appearance.copy(gradientFollowsContent = it))
                onStatus(if (it) "渐变已改为跟随内容（更缓）" else "渐变已改回固定一屏")
            }
        )
    }
    // 把"当前到底怎么铺"写成一句可截图的话：真机上开关状态读不准，靠这行文字 + 颜色采样判定。
    Text(
        "当前：" + appearance.gradientSpanLabel(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 卡片材质四档（默认 = 原来的纯色卡片，逐像素不变）。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CardMaterialControls(
    appearance: AppearanceSpec,
    onAppearanceChange: (AppearanceSpec) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("卡片材质", fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CardMaterial.entries.forEach { material ->
                FilterChip(
                    selected = appearance.cardMaterial == material,
                    onClick = { onAppearanceChange(appearance.copy(cardMaterial = material)) },
                    label = { Text(material.label()) }
                )
            }
        }
        Text(
            "默认＝原来的纯色卡片（逐像素不变）；渐变按当前配色派生；柔光给卡面一层很淡的顶亮底沉。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 卡片渐变方向（维护者口径：卡片的渐变要能选上→下 / 下→上）。
        // 只在柔光下有意义——渐变材质的走向是主题派生的，不跟这个开关。
        if (appearance.cardMaterial == CardMaterial.SOFT) {
            Text("卡面渐变方向", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(false to "上→下", true to "下→上").forEach { (reversed, label) ->
                    FilterChip(
                        selected = appearance.cardGradientReversed == reversed,
                        onClick = { onAppearanceChange(appearance.copy(cardGradientReversed = reversed)) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

/**
 * 「课表与日程表底色」：跟随主题 / 选色 / 图片，只影响底板，课程块与日程块的颜色来自数据、不动。
 */
@Composable
internal fun TimetableBaseControls(
    appearance: AppearanceSpec,
    onAppearanceChange: (AppearanceSpec) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')
            val name = AppearanceImages.newName(extension)
            val stored = withContext(Dispatchers.IO) {
                val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                stream != null && AppearanceImages.store(context, name, stream, 1440, 3168)
            }
            if (stored) {
                onAppearanceChange(appearance.copy(timetableBackdrop = BackdropKind.IMAGE, timetableImage = name))
                status = "课表底色已换成这张图片"
            } else {
                status = "这张图片读不出来，换一张试试"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("课表与日程表底色", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                BackdropKind.THEME to "跟随主题",
                BackdropKind.TRANSPARENT to "透明",
                BackdropKind.COLOR to "选颜色",
                BackdropKind.IMAGE to "图片"
            ).forEach { (kind, label) ->
                FilterChip(
                    selected = appearance.timetableBackdrop == kind,
                    onClick = { onAppearanceChange(appearance.copy(timetableBackdrop = kind)) },
                    label = { Text(label) }
                )
            }
        }

        if (appearance.timetableBackdrop == BackdropKind.COLOR) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TIMETABLE_BASE_PRESETS.forEach { preset ->
                    val selected = appearance.timetableColor == preset
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(preset))
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape
                            )
                            .clickable {
                                onAppearanceChange(appearance.copy(timetableBackdrop = BackdropKind.COLOR, timetableColor = preset))
                                status = if (timetableBaseIsReadable(preset)) {
                                    "课表底色已更新（格线和小字对比度达标）"
                                } else {
                                    "这个底色偏深，课表格线可能看不清"
                                }
                            }
                    )
                }
            }
            Text(
                "只改课表/日程表的底板；课程块与日程块的颜色来自各自数据，不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (appearance.timetableBackdrop == BackdropKind.IMAGE) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { picker.launch(arrayOf("image/*")) }) {
                    Text(if (appearance.timetableImage.isBlank()) "选择图片" else "更换图片")
                }
                if (appearance.timetableImage.isNotBlank()) {
                    TextButton(onClick = {
                        val old = appearance.timetableImage
                        onAppearanceChange(appearance.copy(timetableImage = "", timetableBackdrop = BackdropKind.THEME))
                        scope.launch { withContext(Dispatchers.IO) { AppearanceImages.delete(context, old) } }
                        status = "课表底图已移除"
                    }) { Text("移除图片") }
                }
            }
            Text("底图不透明度 ${appearance.timetableOpacity}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = appearance.timetableOpacity.toFloat(),
                onValueChange = {
                    onAppearanceChange(appearance.copy(timetableOpacity = it.toInt().coerceIn(0, 100)))
                },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "底图上会压一层主题遮罩，保证格线与小字仍能看清。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 图上取色：只解到 64×64 再喂给抽取器（省内存、结果稳定）。 */
internal fun extractPalette(context: android.content.Context, name: String): ExtractedPalette? {
    val bitmap = AppearanceImages.load(context, name, 64, 64) ?: return null
    val map = bitmap.toPixelMap()
    val pixels = IntArray(bitmap.width * bitmap.height)
    for (y in 0 until bitmap.height) {
        for (x in 0 until bitmap.width) {
            pixels[y * bitmap.width + x] = map[x, y].argbInt()
        }
    }
    return PaletteExtractor.extract(pixels)
}

package com.sakata.focusflow

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.pow
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 主题预览色点：主色/副色/强调色/中性色（描边灰可见）/文字色（正文色可见）。
 */
internal fun previewColors(spec: FocusFlowThemeSpec): List<Color> = listOf(
    spec.colorScheme.primary, spec.colorScheme.secondary, spec.colorScheme.tertiary,
    spec.colorScheme.outline, spec.colorScheme.onSurface
)

/** 中性色槽位渲染出的实际背景：向白提亮 85%（与 AppTheme.kt 自定义主题映射一致）。 */
internal fun neutralBackground(neutral: Color): Color = lerp(neutral, Color.White, 0.85f)

/** WCAG 相对亮度。 */
internal fun relativeLuminance(color: Color): Double {
    fun linearize(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)
}

/** WCAG 对比度（1:1 ~ 21:1）。 */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** 文字与背景最低对比度：低于此值视为撞色，禁止选用。 */
internal const val MIN_TEXT_CONTRAST = 3.0

/**
 * 自定义主题配色槽位：主色/副色/强调色/中性色/文字色五个全局色，
 * 共同影响除课程色块与提醒警示外的所有界面区域。
 */
internal enum class ThemeSlot(
    val label: String,
    val hint: String,
    val pick: (FocusFlowThemeColors) -> Color,
    val set: (FocusFlowThemeColors, Color) -> FocusFlowThemeColors
) {
    PRIMARY("主色", "按钮、导航、开关", { it.primaryAction }, { c, v -> c.copy(primaryAction = v) }),
    SECONDARY("副色", "次要强调与容器", { it.secondary }, { c, v -> c.copy(secondary = v) }),
    ACCENT("强调色", "提示与引导文字", { it.accent }, { c, v -> c.copy(accent = v) }),
    NEUTRAL("中性色", "背景、卡片与描边", { it.neutral }, { c, v -> c.copy(neutral = v) }),
    TEXT("文字色", "正文与标题", { it.text }, { c, v -> c.copy(text = v) })
}

/** 预设色板：深色 / 中亮 / 浅亮三组各 10 色，保证与任何主题搭配都有可选协调色。 */
internal val presetSwatches: List<Color> = listOf(
    Color(0xFF0F6B7A), Color(0xFF4062A8), Color(0xFF5C4B9A), Color(0xFF7E4F90), Color(0xFFB84A6F), Color(0xFFC95878),
    Color(0xFFA44F34), Color(0xFFB5762E), Color(0xFF2C6D5A), Color(0xFF397A72),
    Color(0xFF2E8B9E), Color(0xFF5B82C4), Color(0xFF7D6CC0), Color(0xFF9A68B0), Color(0xFFD46A92), Color(0xFFE07E9C),
    Color(0xFFC06A4C), Color(0xFFD09247), Color(0xFF4A8F77), Color(0xFF57988F),
    Color(0xFF5AA7B5), Color(0xFF7FA0D1), Color(0xFF9D8FD3), Color(0xFFB68BC8), Color(0xFFE79AAF), Color(0xFF9FAF6E),
    Color(0xFF4C7A9C), Color(0xFF65558F), Color(0xFF6B94B4), Color(0xFF8575B0)
)

/** 黑白灰阶：补足预设色板缺失的纯黑/纯白与中性灰，供文字色与背景色选用。 */
internal val grayScaleSwatches: List<Color> = listOf(
    Color(0xFF000000), Color(0xFF333333), Color(0xFF666666), Color(0xFF999999),
    Color(0xFFCCCCCC), Color(0xFFE0E0E0), Color(0xFFF5F5F5), Color(0xFFFFFFFF)
)

/** 自定义主题预设数量上限。 */
internal const val MAX_THEME_PRESETS = 8

@Composable
internal fun CustomThemeEditorContent(
    colors: FocusFlowThemeColors,
    onColorsChange: (FocusFlowThemeColors) -> Unit,
    presets: List<ThemePreset>,
    onPresetsChange: (List<ThemePreset>) -> Unit,
    onRestoreDefault: () -> Unit,
    customActive: Boolean,
    onApplyCustom: () -> Unit
) {
    var editingSlot by remember { mutableStateOf<ThemeSlot?>(null) }
    var namingPresetOpen by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<ThemePreset?>(null) }
    Text(
        "主题使用五个全局色：主色、副色、强调色、中性色、文字色，共同影响除课程色块与提醒警示外的所有界面区域。",
        style = MaterialTheme.typography.bodySmall
    )
    // 尚未启用自定义主题时（如从"以此改色"进入）：配色只作为工作副本，确认后才切换全局主题。
    if (!customActive) {
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("配色调整尚未生效：确认满意后点「应用此配色」启用自定义主题。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onApplyCustom, modifier = Modifier.align(Alignment.End)) { Text("应用此配色") }
            }
        }
    }
    editingPreset?.let { preset ->
        Text("正在编辑预设「${preset.name}」——改动会更新到该预设。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    ThemeSlot.entries.forEach { slot ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable { editingSlot = slot }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(slot.pick(colors)))
                Column(Modifier.weight(1f)) {
                    Text(slot.label, fontWeight = FontWeight.SemiBold)
                    Text(slot.hint + " · 点选换色", style = MaterialTheme.typography.bodySmall)
                }
                Text("换色", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (editingPreset == null) {
        TextButton(onClick = onRestoreDefault) { Text("恢复默认主题配色") }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editingPreset = null }) { Text("取消编辑") }
            Button(onClick = {
                onPresetsChange(presets.map { if (it == editingPreset) it.copy(colors = colors) else it })
                editingPreset = null
            }) { Text("更新此预设") }
        }
    }
    // 已保存的预设：命名存档，点击应用、可删除；最多 8 套。
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("已保存的预设", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (editingPreset != null) {
            // 编辑预设中：只能更新或取消，避免另存与更新混淆。
        } else if (presets.size >= MAX_THEME_PRESETS) {
            Text("最多 $MAX_THEME_PRESETS 套", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = { namingPresetOpen = true }) { Text("保存当前为预设") }
        }
    }
    if (presets.isEmpty()) {
        Text("还没有预设：调整好配色后点“保存当前为预设”即可存档，之后可随时切换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        presets.forEach { preset ->
            val active = preset.colors == colors
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(preset.colors.primaryAction, preset.colors.secondary, preset.colors.accent, preset.colors.neutral, preset.colors.text).forEach { color ->
                            Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(color))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(preset.name, fontWeight = FontWeight.SemiBold)
                        if (active) Text("当前配色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    // 应用预设 = 明确确认：载入配色并启用自定义主题。
                    TextButton(onClick = { onColorsChange(preset.colors); onApplyCustom(); editingPreset = null }, enabled = !active) { Text("应用") }
                    IconButton(onClick = { editingPreset = preset; onColorsChange(preset.colors) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑预设", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = {
                        onPresetsChange(presets.filterNot { it == preset })
                        if (editingPreset == preset) editingPreset = null
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "删除预设", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    if (namingPresetOpen) {
        var presetName by remember { mutableStateOf("预设 ${presets.size + 1}") }
        AlertDialog(
            onDismissRequest = { namingPresetOpen = false },
            title = { Text("保存当前配色为预设") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("预设名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        onPresetsChange(presets + ThemePreset(presetName.trim(), colors))
                        editingPreset = null
                        namingPresetOpen = false
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { namingPresetOpen = false }) { Text("取消") } }
        )
    }
    editingSlot?.let { slot ->
        ColorPaletteDialog(
            current = slot.pick(colors),
            // 撞色限制：文字色与当前实际背景对比、中性色（背景）与当前文字对比，其余槽位不限。
            contrastOf = when (slot) {
                ThemeSlot.TEXT -> { candidate -> contrastRatio(candidate, neutralBackground(colors.neutral)) }
                ThemeSlot.NEUTRAL -> { candidate -> contrastRatio(neutralBackground(candidate), colors.text) }
                else -> null
            },
            onPick = { picked ->
                onColorsChange(slot.set(colors, picked))
                editingSlot = null
            },
            onDismiss = { editingSlot = null }
        )
    }
}

@Composable
internal fun ColorPaletteDialog(
    current: Color,
    onPick: (Color) -> Unit,
    onDismiss: () -> Unit,
    contrastOf: ((Color) -> Double)? = null
) {
    val initialHsv = remember(current) { hsvTriple(current) }
    var hue by remember(current) { mutableStateOf(initialHsv.first) }
    var saturation by remember(current) { mutableStateOf(initialHsv.second) }
    var value by remember(current) { mutableStateOf(initialHsv.third) }
    var hexText by remember(current) { mutableStateOf(formatHex(current).removePrefix("#")) }
    val tempColor = Color.hsv(hue, saturation, value)
    LaunchedEffect(tempColor) { hexText = formatHex(tempColor).removePrefix("#") }
    val contrast = contrastOf?.invoke(tempColor)
    val usable = contrast == null || contrast >= MIN_TEXT_CONTRAST
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择颜色") },
        text = {
            ScrollableDialogBox(maxHeight = 720.dp, spacing = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(current).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)))
                    Text("当前颜色 · " + formatHex(current), style = MaterialTheme.typography.bodySmall)
                }
                if (contrastOf != null) {
                    Text("为防止文字与背景撞色，对比度不足 ${MIN_TEXT_CONTRAST}:1 的候选色不可用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("预设色板 · 点选即应用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (grayScaleSwatches + presetSwatches).chunked(6).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowColors.forEach { color ->
                            val swatchContrast = contrastOf?.invoke(color)
                            val swatchUsable = swatchContrast == null || swatchContrast >= MIN_TEXT_CONTRAST
                            val selected = color == current
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(color)
                                    .then(if (swatchUsable) Modifier.clickable { onPick(color) } else Modifier)
                                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                                    .drawBehind {
                                        if (!swatchUsable) {
                                            // 撞色禁用的色块画斜杠：深色块用白杠、浅色块用深杠，保证可见。
                                            val slashColor = if (relativeLuminance(color) < 0.5) Color(0xB3FFFFFF) else Color(0x80404040)
                                            drawLine(slashColor, Offset(7.dp.toPx(), size.height - 7.dp.toPx()), Offset(size.width - 7.dp.toPx(), 7.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                                        }
                                    }
                            )
                        }
                    }
                }
                Text("自定义", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HueBar(hue = hue, onChange = { hue = it })
                SvPicker(hue = hue, saturation = saturation, value = value, onChange = { s, v ->
                    saturation = s
                    value = v
                })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(tempColor).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)))
                    Text(formatHex(tempColor), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    if (contrast != null) {
                        Text(
                            if (usable) "对比度 %.1f : 1 · 可读".format(Locale.US, contrast)
                            else "对比度 %.1f : 1 · 与底色太接近".format(Locale.US, contrast),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (usable) Color(0xFF2F8F5B) else MaterialTheme.colorScheme.error
                        )
                    }
                }
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { raw ->
                        val cleaned = raw.trim().removePrefix("#").uppercase().filter { it.isDigit() || it in 'A'..'F' }
                        hexText = cleaned.take(6)
                        if (cleaned.length == 6) {
                            val parsed = Color(android.graphics.Color.parseColor("#$cleaned"))
                            val h = hsvTriple(parsed)
                            hue = h.first
                            saturation = h.second
                            value = h.third
                        }
                    },
                    label = { Text("#RRGGBB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(tempColor); onDismiss() }, enabled = usable) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 色相滑杆：左右拖动或点选切换色相（0..360）。 */
@Composable
internal fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val stops = (0..10).map { Color.hsv(it * 36f, 1f, 1f) }
    Box(
        Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))
            .background(Brush.horizontalGradient(stops))
            .pointerInput(Unit) {
                detectTapGestures { onChange(((it.x / size.width) * 360f).coerceIn(0f, 360f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(((change.position.x / size.width) * 360f).coerceIn(0f, 360f))
                }
            }
            .drawBehind {
                val x = (hue / 360f * size.width).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx())
                drawCircle(Color.White, 7.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(2.5.dp.toPx()))
                drawCircle(Color.Black, 7.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(1.dp.toPx()))
            }
    )
}

/** 饱和度/明度取色区：横轴饱和度、纵轴明度（上亮下暗），拖动或点选取色。 */
@Composable
internal fun SvPicker(hue: Float, saturation: Float, value: Float, onChange: (Float, Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color.White, Color.Black)), blendMode = BlendMode.Multiply)
                val cx = (saturation * size.width).coerceIn(9.dp.toPx(), size.width - 9.dp.toPx())
                val cy = ((1f - value) * size.height).coerceIn(9.dp.toPx(), size.height - 9.dp.toPx())
                drawCircle(Color.White, 8.dp.toPx(), Offset(cx, cy), style = Stroke(2.5.dp.toPx()))
                drawCircle(Color.Black, 8.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
            }
            .pointerInput(hue) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f), (1f - it.y / size.height).coerceIn(0f, 1f)) }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f), (1f - change.position.y / size.height).coerceIn(0f, 1f))
                }
            }
    )
}

/** 颜色 → HSV 三元组（hue 0..360）。 */
internal fun hsvTriple(color: Color): Triple<Float, Float, Float> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return Triple(hsv[0], hsv[1], hsv[2])
}

/** 颜色 → #RRGGBB 文本。 */
internal fun formatHex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

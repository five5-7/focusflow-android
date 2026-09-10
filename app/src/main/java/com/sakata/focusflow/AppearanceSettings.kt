package com.sakata.focusflow

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
 * 8.2.0「设置 → 外观 → 页面背景」这一段。
 *
 * 全部可选、默认跟随主题；导入的图片只写进应用私有目录，不出本机、不上传。
 * 图片背景之上永远压一层主题遮罩（见 [scrimAlpha]），正文对比度靠它保住。
 */
@Composable
internal fun AppearanceSettingsSection(
    appearance: AppearanceSpec,
    onAppearanceChange: (AppearanceSpec) -> Unit,
    onApplyExtractedTheme: (FocusFlowThemeColors) -> Unit
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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                BackdropKind.THEME to "跟随主题",
                BackdropKind.GRADIENT to "主题渐变",
                BackdropKind.IMAGE to "图片"
            ).forEach { (kind, label) ->
                FilterChip(
                    selected = appearance.pageBackdrop == kind,
                    onClick = { onAppearanceChange(appearance.copy(pageBackdrop = kind)) },
                    label = { Text(label) }
                )
            }
        }

        if (appearance.pageBackdrop == BackdropKind.GRADIENT) {
            Text("渐变强度 ${appearance.gradientStrength}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = appearance.gradientStrength.toFloat(),
                onValueChange = { onAppearanceChange(appearance.copy(gradientStrength = it.toInt().coerceIn(0, 200))) },
                valueRange = 0f..200f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "100% 是设计值（顶亮 → 底色 → 微深）；调到 0% 等于纯色，往右更明显。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (appearance.pageBackdrop == BackdropKind.IMAGE) {
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
                        if (ExtractedTheme.worthApplying(palette) && palette != null) {
                            onApplyExtractedTheme(ExtractedTheme.derive(palette))
                            status = "已按图片抽色并应用（主色 #%06X）".format(palette.primary and 0xFFFFFF)
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

    HorizontalDivider()
    Text("卡片材质", fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CardMaterial.entries.forEach { material ->
            FilterChip(
                selected = appearance.cardMaterial == material,
                onClick = { onAppearanceChange(appearance.copy(cardMaterial = material)) },
                label = { Text(material.label()) }
            )
        }
    }
    Text(
        "默认＝原来的纯色卡片（逐像素不变）；渐变按当前配色派生，柔光加顶面高光与主题阴影，纸感在柔光上再叠一层程序生成的淡噪点（不增加包体积）。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    HorizontalDivider()
    TimetableBaseSection(
        appearance = appearance,
        onAppearanceChange = onAppearanceChange,
        onStatus = { status = it }
    )
}

/**
 * 「课表与日程表底色」：跟随主题 / 选色 / 图片，只影响底板，课程块与日程块的颜色来自数据、不动。
 */
@Composable
private fun TimetableBaseSection(
    appearance: AppearanceSpec,
    onAppearanceChange: (AppearanceSpec) -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                onStatus("课表底色已换成这张图片")
            } else {
                onStatus("这张图片读不出来，换一张试试")
            }
        }
    }

    Text("课表与日程表底色", fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            BackdropKind.THEME to "跟随主题",
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
                            onStatus(
                                if (timetableBaseIsReadable(preset)) "课表底色已更新（格线和小字对比度达标）"
                                else "这个底色偏深，课表格线可能看不清"
                            )
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
                    onStatus("课表底图已移除")
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
}

/** 图上取色：只解到 64×64 再喂给抽取器（省内存、结果稳定）。 */
private fun extractPalette(context: android.content.Context, name: String): ExtractedPalette? {
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

/** 局部小工具：这里只需要 6dp / 8dp 两个间距，避免为了两个值引入额外 import。 */
private fun Int.dp0() = androidx.compose.ui.unit.Dp(this.toFloat())

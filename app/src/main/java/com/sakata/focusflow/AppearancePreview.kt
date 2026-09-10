package com.sakata.focusflow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 自定义主题工具里的**实时预览**（8.2.0 第 7 项：维护者要求"预览"）。
 *
 * 关键点：这里不是另画一套"示意图"，而是**用真实渲染路径**搭一个迷你页面 ——
 * 把候选配色做成真正的 [FocusFlowThemeSpec]（走 [focusFlowThemeSpec]），
 * 再用 [Modifier.appearanceBackdrop] 画背景、用 [FocusCard] 画卡片。
 * 于是"预览里看到的"与"应用后看到的"是同一段代码，不会各自漂移。
 *
 * [bitmap] 是当前页面背景图（若有）。若预览的配色/外观引用了别的图片，
 * 调用方应按需传 null —— 拿不到图时这里只少画一张图，其余照常。
 */
@Composable
internal fun AppearancePreview(
    colors: FocusFlowThemeColors,
    appearance: AppearanceSpec,
    darkMode: Boolean,
    bitmap: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    val scheme = remember(colors, darkMode) {
        focusFlowThemeSpec(FocusFlowThemeOption.CUSTOM, colors, darkMode).colorScheme
    }
    val shape = RoundedCornerShape(16.dp)
    // 用 MaterialTheme(colorScheme = 候选配色) 而不是直接塞 LocalColorScheme：
    // 后者在 material3 里是 internal。排版与形状沿用当前环境，只换配色。
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
        CompositionLocalProvider(
            // 卡片材质由 FocusCard 从 LocalAppearance 读，所以预览必须把它也换掉。
            LocalAppearance provides appearance
        ) {
            Box(
                modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(shape)
                    // 跟随主题时不画任何背景层（与页面一致），其余档位交给同一套 appearanceBackdrop。
                    .then(
                        if (appearance.pageBackdrop == BackdropKind.THEME) {
                            Modifier.background(scheme.background)
                        } else {
                            Modifier
                        }
                    )
                    .appearanceBackdrop(appearance, scheme, bitmap)
            ) {
                Column(
                    Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "示例页面 · " + appearance.summary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                    FocusCard(containerColor = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                "卡片标题",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface
                            )
                            Text(
                                "正文与说明性小字",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(colors.navigationBar)
                        )
                        Box(Modifier.size(26.dp).clip(CircleShape).background(scheme.primary))
                        Box(Modifier.size(26.dp).clip(CircleShape).background(scheme.secondary))
                        Box(Modifier.size(26.dp).clip(CircleShape).background(scheme.tertiary))
                    }
                }
            }
        }
    }
}

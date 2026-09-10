package com.sakata.focusflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 8.1.0 第四轮：统一"光照分层"。
 *
 * 光源固定在上方：抬升的浮层顶面有一层极淡高光，下沿落一层**主题染色**的软阴影。
 * 阴影不用纯黑——暖色主题上纯黑发脏；这里把中性文字色与主色混一层暗调，
 * 既拉得开层次，又跟当前主题（含自定义主题）同色系。
 *
 * 适用范围刻意很窄：只给"真的浮在别的东西之上"的层（当前是弹窗卡片）用。
 * 页面里的普通卡片不加阴影，靠表面色分层，避免整页发灰。
 */
internal object SurfaceLighting {
    /** 顶面高光强度（白色；自顶向下到 60% 处淡出为 0）。 */
    const val TOP_LIGHT_ALPHA = 0.07f

    /** 弹窗卡片阴影高度：足够把卡片从压暗的页面上"抬起来"，又不至于糊成一团。 */
    val DIALOG_SHADOW: Dp = 20.dp
}

/**
 * 主题染色的软阴影。
 *
 * API 28 以下系统只用仰角算黑白阴影（颜色参数被忽略），属可接受降级：
 * minSdk 26 上仍有阴影，只是不带主题色。
 */
@Composable
internal fun Modifier.litShadow(elevation: Dp, shape: Shape): Modifier {
    val tint = lerp(MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary, 0.35f)
    return shadow(
        elevation = elevation,
        shape = shape,
        // 不裁切：卡片本体交给自己裁（Surface 已按 shape 裁），这里只负责画影子。
        clip = false,
        ambientColor = tint,
        spotColor = tint
    )
}

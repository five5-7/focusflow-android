package com.sakata.focusflow

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs

/**
 * 8.2.0 的统一卡片：把「卡片材质」集中在一处实现（见 docs/8.2.0-appearance-plan.md）。
 *
 * [containerColor] 传调用点原来的底色 —— 默认材质 [CardMaterial.TONAL] 下就是原来的
 * `Card(containerColor = …)`，**逐像素不变**；其余材质在这层底色之上叠加。
 *
 * 为什么要有这个组件：全应用有 51 处卡片容器、各自写 `CardDefaults.cardColors`，
 * 想改"卡片长什么样"就得改 51 个地方。收编进来的调用点以后只描述"底色是什么"。
 */
@Composable
internal fun FocusCard(
    containerColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    content: @Composable ColumnScope.() -> Unit
) {
    val material = LocalAppearance.current.cardMaterial
    if (material == CardMaterial.TONAL) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            content = content
        )
        return
    }
    Card(
        modifier = modifier.then(
            if (material == CardMaterial.SOFT || material == CardMaterial.PAPER) {
                Modifier.litShadow(SurfaceLighting.CARD_SHADOW, shape)
            } else {
                Modifier
            }
        ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box {
            Box(
                Modifier
                    .matchParentSize()
                    .cardMaterialFill(containerColor, material, MaterialTheme.colorScheme)
            )
            Column(content = content)
        }
    }
}

/** 卡片材质的底色层：底色 → 渐变（可选）→ 纸感噪点（可选）→ 两侧内收。 */
private fun Modifier.cardMaterialFill(
    containerColor: Color,
    material: CardMaterial,
    scheme: ColorScheme
): Modifier = drawBehind {
    drawRect(containerColor)
    if (material == CardMaterial.GRADIENT) {
        drawRect(ThemeGradient.card(scheme))
    }
    if (material == CardMaterial.PAPER) {
        drawRect(paperNoiseBrush())
    }
    // 两侧轻微内收：靠里的浅、靠边的略深，和底栏用同一套语言，避免"贴纸感"。
    val edge = scheme.onSurface.copy(alpha = 0.03f)
    drawRect(
        Brush.horizontalGradient(
            0f to edge,
            0.06f to Color.Transparent,
            0.94f to Color.Transparent,
            1f to edge
        )
    )
}

/**
 * 一个小缓存：同一个 key 只创建一次值。
 *
 * 存在的理由很具体：卡片是在 `drawBehind` 里取画刷的，而纸感画刷背后是**一张 64×64 位图**。
 * 每帧都新建就是每秒几百次分配 + GC 抖动——真机上的表现是"纸感比其它材质更容易掉帧"，
 * 而这类抖动最容易在帧时间实测里被误读成"纸感本身很贵"。把创建挪到首次使用之后，
 * 滚动时只是复用同一张纹理。
 *
 * 抽成独立的类是为了能被纯单测覆盖（[paperNoiseBrush] 本身要碰 `android.graphics.Bitmap`，
 * 在 JVM 单测里是 mock 不出来的）。
 */
internal class SingleValueCache<T : Any> {
    private val entries = HashMap<Pair<Long, Float>, T>()

    fun get(seed: Long, alpha: Float, create: () -> T): T = synchronized(entries) {
        entries.getOrPut(seed to alpha) { create() }
    }
}

private val paperNoiseCache = SingleValueCache<Brush>()

/**
 * 纸感噪点：**程序生成**的 64×64 贴图，不进资源、不增加包体积。
 *
 * 噪点只做"上一档/下一档"的微扰（不连续调暗），因此不会把卡片整体压暗。
 * 同一个 (seed, alpha) 只建一次（见 [SingleValueCache]）。
 */
internal fun paperNoiseBrush(seed: Long = DEFAULT_NOISE_SEED, alpha: Float = DEFAULT_NOISE_ALPHA): Brush =
    paperNoiseCache.get(seed, alpha) {
        ShaderBrush(
            ImageShader(
                noiseBitmap(seed, alpha).asImageBitmap(),
                TileMode.Repeated,
                TileMode.Repeated
            )
        )
    }

internal const val DEFAULT_NOISE_SEED = 0x5EEDL
internal const val DEFAULT_NOISE_ALPHA = 0.05f
internal const val NOISE_SIZE = 64

/**
 * 纯函数：生成噪点像素（0xAARRGGBB）。
 *
 * 用线性同余（LCG）而不是 `Random`：同一个种子永远得到同一张图，
 * 单测可以直接断言"两次调用完全一致""亮度分布不过分集中""不会把卡片压暗"。
 */
internal fun noisePixels(
    seed: Long = DEFAULT_NOISE_SEED,
    alpha: Float = DEFAULT_NOISE_ALPHA,
    size: Int = NOISE_SIZE
): IntArray {
    val pixels = IntArray(size * size)
    var state = seed and 0xFFFFFFFFL
    val a = alpha.coerceIn(0f, 1f)
    for (i in pixels.indices) {
        state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
        val v = ((state shr 16) and 0xFF).toInt()
        val shade = if (v < 128) 0 else 255
        val weight = abs(v - 128) / 128f
        val a8 = (a * 255f * weight).toInt().coerceIn(0, 255)
        pixels[i] = (a8 shl 24) or (shade shl 16) or (shade shl 8) or shade
    }
    return pixels
}

/** 渲染用：把纯函数的像素做成位图。 */
internal fun noiseBitmap(
    seed: Long = DEFAULT_NOISE_SEED,
    alpha: Float = DEFAULT_NOISE_ALPHA,
    size: Int = NOISE_SIZE
): Bitmap = Bitmap.createBitmap(noisePixels(seed, alpha, size), size, size, Bitmap.Config.ARGB_8888)

/** 卡片材质的展示名（设置页与测试共用，避免两处文案漂移）。 */
internal fun CardMaterial.label(): String = when (this) {
    CardMaterial.TONAL -> "默认"
    CardMaterial.GRADIENT -> "渐变"
    CardMaterial.SOFT -> "柔光"
    CardMaterial.PAPER -> "纸感"
}

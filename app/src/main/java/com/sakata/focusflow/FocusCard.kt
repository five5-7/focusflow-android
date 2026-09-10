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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

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
    // effectiveCardMaterial：关掉「丰富效果」时一律回落成原生纯色卡片。
    val material = LocalAppearance.current.effectiveCardMaterial
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
        // **刻意不给页面卡片加投影。**
        //
        // 曾经给柔光/纸感挂过 litShadow(CARD_SHADOW = 6dp)，真机目视复核发现它在每张卡片
        // 外围生成一圈约 20~25 灰阶、向外延伸 70~80px 的**矩形暗晕**，被读成"卡片周围一圈
        // 矩形色差"（维护者连续两轮反馈的正是这个）。而且它与 SurfaceLighting 里原本的
        // 设计口径相矛盾——那里写着"页面里的普通卡片不加阴影，靠表面色分层，避免整页发灰"。
        // 材质的分层交给填充本身（柔光的顶亮底沉 / 纸感的纸纹），不要靠投影。
        modifier = modifier,
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

/** 卡片材质的底色层：**调用方给的底色** → 材质层（渐变/柔光）→ 纸感纸纹 → 两侧内收。 */
private fun Modifier.cardMaterialFill(
    containerColor: Color,
    material: CardMaterial,
    scheme: ColorScheme
): Modifier = drawBehind {
    // 先把卡片做成**不透明**：不少调用点用的是半透明底色
    // （例如「接下来」卡 = surfaceVariant.copy(alpha = 0.45f)）。
    // 半透明意味着**页面渐变会从卡片底下透出来**，于是同一张卡片在不同滚动位置颜色不同，
    // 往上滑就变暗/变亮（维护者反馈："卡片处于下方时没有材质渲染，而在上方才有渲染"）。
    // 材质必须只由卡片自己的底色决定，所以先铺一层固定的页面底色，再叠调用方的底色——
    // 等价于"这张卡放在一块平整的页面底色上"，与它此刻落在渐变哪一段无关。
    drawRect(scheme.background)
    // 调用方指定的底色。**这一句不能省**：材质层是"以底色为基色"的渐变，
    // 直接拿它当底色会把调用方的底色（例如"已选择"用的 primaryContainer）整个换掉。
    drawRect(containerColor)
    // 材质叠层与底栏/弹窗共用同一份实现（materialBrush），只是底色不同。
    val layer = materialBrush(material, containerColor, scheme)
    if (layer != null) {
        drawRect(layer)
        if (material == CardMaterial.PAPER) {
            // 纸感 = 柔光 + 纸纹。
            // **不再叠那层整体压深**：实测 PAPER_SHEEN_ALPHA=0.05 会把卡面压低约 11 灰阶，
            // 而中性的纸纹只有约 2 灰阶的颗粒——于是"纸感"变成"卡片换了个更深的颜色"，
            // 正是维护者反复反馈的问题。纸纹现在是亮度中性的（Overlay），
            // 纸感与柔光的区别由"有没有纸纹"承担，不再靠压暗。
            drawPaperGrain()
        }
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
 * 纸纹的**每格边长（dp）**。
 *
 * 这是"矩形色差"的根因所在：`ImageShader` 的平铺单位是**图像像素**，而 64×64 的贴图
 * 在 4 倍密度屏上就是 16dp 一格 —— 于是卡面上出现一个约 16dp 的**可辨方形重复**，
 * 看起来就是"中间有一块矩形色差"（维护者反馈）。
 * 真正的纸纹颗粒应该在 1–2dp 量级，所以绘制时把噪点层缩放到 [PAPER_GRAIN_DP] 一格。
 */
internal const val PAPER_GRAIN_DP = 1.5f

/**
 * 纸感噪点：**程序生成**的 64×64 贴图，不进资源、不增加包体积。
 *
 * 噪点只做"上一档/下一档"的微扰（不连续调暗），因此不会把卡片整体压暗。
 * 同一个 (seed, alpha) 只建一次（见 [SingleValueCache]）。
 *
 * 注意：直接用这个 brush 平铺会得到 16dp 一格的大方块（见 [PAPER_GRAIN_DP]）。
 * 画到界面上请用 [DrawScope.drawPaperGrain]，它负责把缩放算对。
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

/**
 * 在画布上铺纸纹：把 64×64 的噪点层缩放到"每格 [PAPER_GRAIN_DP] dp"。
 *
 * 必须走这里而不是直接 `drawRect(paperNoiseBrush())` —— 后者会得到 16dp 一格的
 * 大方块重复（见 [PAPER_GRAIN_DP] 的说明）。用 `withTransform` 缩放画布，
 * 让 shader 的平铺单位从"图像像素"变成"想要的 dp 尺寸"。
 */
internal fun DrawScope.drawPaperGrain() {
    val grainPx = PAPER_GRAIN_DP.dp.toPx()
    val scale = (grainPx / NOISE_SIZE).coerceAtLeast(0.0001f)
    withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
        // Overlay：混合色 = 128 时恒等，比 128 亮则提亮、暗则压暗，**围绕中灰对称**。
        // 所以纸纹只加纹理、不改变卡片整体亮度（用普通 SrcOver 会整体压暗，见 noisePixels 注释）。
        drawRect(
            paperNoiseBrush(),
            topLeft = Offset.Zero,
            size = Size(size.width / scale, size.height / scale),
            blendMode = BlendMode.Overlay
        )
    }
}

/**
 * 纸感噪点的不透明度上限。
 *
 * **从 0.05 提到 0.22**：0.05 时单像素实际 alpha 平均只有 `0.05 × 0.5 × 255 ≈ 6`，
 * 即约 2.4% 的明暗扰动，而 64×64 的贴图铺在卡片上、又在 4 倍密度屏上被缩小显示，
 * 这点扰动**肉眼根本看不出来**——维护者连续两轮反馈"柔光和纸感没有区别"，根因就在这里：
 * 两者底层用的同一张柔光渐变，唯一的差别只剩这层看不见的噪点。
 * 0.22 之后是约 11% 的扰动，在真机上能看出细颗粒的纸纹，但仍不会把卡片压暗
 * （噪点是"上下扰动"，不是单向压暗）。
 */
internal const val DEFAULT_NOISE_ALPHA = 0.22f

/**
 * 纸感在柔光之外额外的**轻微整体压暗**，让"纸"比"柔光"更沉一点。
 *
 * 光靠噪点两者还是容易混（尤其在深色模式、噪点对比本来就弱的底色上），
 * 所以再给纸感一个可量化的区别：整体叠一层极淡的 onSurface。
 */
internal const val NOISE_SIZE = 64

/**
 * 纯函数：生成纸纹像素（0xAARRGGBB），**以中性灰 128 为中心**。
 *
 * 为什么不再用"黑色/白色 + 不同 alpha"：
 * 那种做法**不是亮度中性的**。alpha 合成往下压是乘性的（base×(1-a)）、往上提是加性的
 * （base×(1-a)+255a），两者不对称，于是"一半黑一半白"平均下来仍然把卡片整体压暗。
 * 真机目视复核实测：纸感把绿卡压暗了约 **26 灰阶**、白卡约 18 灰阶——纸感变成了"换个更深的颜色"，
 * 而不是"同一张纸上加了纹理"。（代码注释里原本就写着"不会把卡片整体压暗"，是被这一步破坏的。）
 *
 * 现在：像素是围绕 128 的灰阶，配合 `BlendMode.Overlay` 绘制。
 * Overlay 在混合色 = 128 时**恒等**，比 128 亮则提亮、暗则压暗，围绕中灰**对称**，
 * 所以整卡平均亮度不变，只留下纹理。
 *
 * [alpha] 现在的含义是**纹理强度**（0 = 无纹理），不再是像素不透明度。
 */
internal fun noisePixels(
    seed: Long = DEFAULT_NOISE_SEED,
    alpha: Float = DEFAULT_NOISE_ALPHA,
    size: Int = NOISE_SIZE
): IntArray {
    val pixels = IntArray(size * size)
    var state = seed and 0xFFFFFFFFL
    val strength = alpha.coerceIn(0f, 1f)
    for (i in pixels.indices) {
        state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
        val v = ((state shr 16) and 0xFF).toInt()
        // v ∈ 0..255 → delta ∈ -128..127，围绕 0 大致对称（LCG 的高字节分布均匀）
        val delta = v - 128
        val grey = (128 + (delta * strength).toInt()).coerceIn(0, 255)
        pixels[i] = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
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

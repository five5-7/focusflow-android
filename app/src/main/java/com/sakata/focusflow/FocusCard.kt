package com.sakata.focusflow

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * 8.2.0 的统一卡片：把「卡片材质」集中在一处实现（见 docs/8.2.0-appearance-plan.md）。
 *
 * [containerColor] 传调用点原来的底色 —— 默认材质 [CardMaterial.TONAL] 下就是原来的
 * `Card(containerColor = …)`，**逐像素不变**；其余材质在这层底色之上叠加。
 *
 * 为什么要有这个组件：全应用有 80 处卡片容器、各自写 `CardDefaults.cardColors`，
 * 想改"卡片长什么样"就得改 80 个地方。收编进来的调用点以后只描述"底色是什么"。
 *
 * **2026-09-11 起全部收编完毕**（`FocusCard` 调用点 35 → 80），只剩课表/日程表 2 处底板
 * 有意不收编（底色归「课表与日程表底色」设置管）。每处都带 `// 收编：` 注释，可按需回退子集。
 * 那次之所以要补收 45 处，是因为早先的盘点用带括号的 `Card(` grep，
 * **漏掉了尾随 lambda 写法** `ElevatedCard {` / `Card {`。
 */
@Composable
internal fun FocusCard(
    containerColor: Color,
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    /**
     * 可选描边。加这个参数是为了收编「设置」页那种**带描边的**卡片
     * （PlanHubItem：外观／日程与活动提醒／提醒打扰控制…）——不收编它们的话，
     * 选柔光/纸感时这些卡片毫无反应（维护者反馈过三次「像外观这样的卡片还是没有材质渲染」）。
     * null（默认）= 不加描边，与原来的 FocusCard 完全一致。
     */
    border: BorderStroke? = null,
    /**
     * 卡片阴影。默认 0 = 与原来的 `Card` 完全一致（逐像素不变）。
     * 加这个参数是为了收编那 31 处 `ElevatedCard` —— `ElevatedCard` 的默认阴影是 `1.dp`
 * （`ElevationTokens.Level1`）、默认底色是 `surfaceContainerLow`，所以收编时必须把
 * 这两个值显式带上，否则会丢阴影或变色。材质是全局的，
     * 不收编它们就等于"有些卡片不响应材质"（维护者：「我要看见所有卡片变化」）。
     */
    elevation: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /**
     * 点击。传了就用 Material3 的可点击 `Card`（保留水波纹与点击语义），
     * 而不是在外面套一个 `clickable` —— 后者会丢掉默认的点击重载。
     * 用于收编 `Card(onClick = …)` 那一类。
     */
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // effectiveCardMaterial：关掉「丰富效果」时一律回落成原生纯色卡片。
    val material = LocalAppearance.current.effectiveCardMaterial
    val colors = CardDefaults.cardColors(
        containerColor = if (material == CardMaterial.TONAL) containerColor else Color.Transparent
    )
    val elevationSpec = CardDefaults.cardElevation(defaultElevation = elevation)
    // TONAL 时内容原样交给 Card 的 ColumnScope；其余材质在底下垫一层材质与内描边。
    // 两条路径的排版都是"一个 Column 依次摆放"，所以收编前后布局一致。
    fun body(): @Composable () -> Unit = {
        if (material == CardMaterial.TONAL) {
            Column(content = content)
        } else {
            Box {
                Box(
                    Modifier
                        .matchParentSize()
                        // 亚克力：**一点透明 + 一层模糊**（维护者口径「把亚克力材质加一点透明加模糊的效果」）。
                        // 透明由材质自身的 alpha 给（见 acrylicStops）；
                        // 模糊加在**材质层**上，把那条锐利的边线和染色柔化成"透过一块塑料板看"的观感。
                        //
                        // 为什么不是"背景模糊"：per-card 的背景模糊在 Compose 公开 API 里做不到
                        // （需要按卡片位置采样背景，没有 backdrop 捕获）；而且页面底色是平滑渐变时，
                        // 模糊它等于没糊。材质层的模糊在任何背景下都看得见。
                        // **不加模糊。** 试过 Modifier.blur（6dp / 14dp）：真机实测它把亚克力
                        // 压成了"平的"（今日大卡片横向只剩 3 级变化，加之前是 37 级），
                        // 也就是说上了模糊之后**反而更不透**了。而且维护者要的"晕散"是
                        // 糊**背后**的内容，糊材质自己那一层根本做不出那个效果 ——
                        // 真背景模糊要页面级 GraphicsLayer 捕获，属独立改动。
                        .cardMaterialFill(containerColor, material, MaterialTheme.colorScheme, LocalAppearance.current.cardGradientReversed)
                )
                Column(content = content)
            }
        }
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevationSpec,
            border = border
        ) { body()() }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevationSpec,
            border = border
        ) { body()() }
    }
}

/** 卡片材质的底色层：**调用方给的底色** → 材质层（渐变/柔光）→ 两侧内收。 */
@Composable
private fun Modifier.cardMaterialFill(
    containerColor: Color,
    material: CardMaterial,
    scheme: ColorScheme,
    softReversed: Boolean
): Modifier {
    // **画刷必须在这里 remember，绝不能建在 drawBehind 的 lambda 里。**
    //
    // 材质画刷是渐变。每次新建一个 Brush 实例，Compose 就要重新编译一份 shader
    // （shader 缓存在 Brush 实例内部、按尺寸缓存），而 drawBehind 的 lambda
    // **每一帧都会执行**。原先 `materialBrush(...)` 就写在 lambda 里，等于
    // **每帧、每张卡片**都新建一次渐变画刷并重编 shader——卡片一多就持续掉帧
    // （维护者反馈"这几个版本流畅度似乎有问题"）。
    // 改成按 (材质, 底色, 方向, 配色) remember，跨帧复用同一份画刷与 shader。
    val layer = remember(material, containerColor, scheme, softReversed) {
        materialBrush(material, containerColor, scheme, softReversed)
    }
    val edge = remember(scheme) { scheme.onSurface.copy(alpha = 0.03f) }
    // 「玻璃的边」：毛玻璃靠它才读得出是玻璃（平滑背景下半透明本身看不出来）。
    val rim = remember(material, containerColor) { materialRimColor(material, containerColor) }
    val rimWidthDp = remember(material) { materialRimWidthDp(material) }
    val edgeBrush = remember(edge) {
        Brush.horizontalGradient(
            0f to edge,
            0.06f to Color.Transparent,
            0.94f to Color.Transparent,
            1f to edge
        )
    }
    return drawBehind {
        // **毛玻璃不铺不透明底**：它要的就是"底下的页面真的透上来"。
        // 其余材质必须铺（半透明底色的调用点会让页面渐变从卡片底下透出来，
        // 于是同一张卡在不同滚动位置颜色不同——维护者反馈过那个问题）。
        if (material != CardMaterial.ACRYLIC) {
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
        }
        // 材质叠层与底栏/弹窗共用同一份实现（materialBrush），只是底色不同。
        if (layer != null) drawRect(layer)
        // 两侧轻微内收：靠里的浅、靠边的略深，和底栏用同一套语言，避免"贴纸感"。
        drawRect(edgeBrush)
        // 内描边（"玻璃的边"）：贴着形状内侧画一圈亮线，毛玻璃的关键观感。
        if (rim != null) {
            val w = androidx.compose.ui.unit.Dp(rimWidthDp).toPx()
            drawRect(
                color = rim,
                topLeft = Offset(w / 2f, w / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                style = androidx.compose.ui.graphics.drawscope.Stroke(w)
            )
        }
    }
}

/** 卡片材质的展示名（设置页与测试共用，避免两处文案漂移）。 */
internal fun CardMaterial.label(): String = when (this) {
    CardMaterial.TONAL -> "默认"
    CardMaterial.GRADIENT -> "渐变"
    CardMaterial.SOFT -> "柔光"
    CardMaterial.ACRYLIC -> "亚克力"
}

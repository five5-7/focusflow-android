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
 * 为什么要有这个组件：全应用有 51 处卡片容器、各自写 `CardDefaults.cardColors`，
 * 想改"卡片长什么样"就得改 51 个地方。收编进来的调用点以后只描述"底色是什么"。
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
    content: @Composable ColumnScope.() -> Unit
) {
    // effectiveCardMaterial：关掉「丰富效果」时一律回落成原生纯色卡片。
    val material = LocalAppearance.current.effectiveCardMaterial
    if (material == CardMaterial.TONAL) {
        Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border,
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
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = border
    ) {
        Box {
            Box(
                Modifier
                    .matchParentSize()
                    .cardMaterialFill(containerColor, material, MaterialTheme.colorScheme, LocalAppearance.current.cardGradientReversed)
            )
            Column(content = content)
        }
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
    val edgeBrush = remember(edge) {
        Brush.horizontalGradient(
            0f to edge,
            0.06f to Color.Transparent,
            0.94f to Color.Transparent,
            1f to edge
        )
    }
    return drawBehind {
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
        if (layer != null) drawRect(layer)
        // 两侧轻微内收：靠里的浅、靠边的略深，和底栏用同一套语言，避免"贴纸感"。
        drawRect(edgeBrush)
    }
}

/** 卡片材质的展示名（设置页与测试共用，避免两处文案漂移）。 */
internal fun CardMaterial.label(): String = when (this) {
    CardMaterial.TONAL -> "默认"
    CardMaterial.GRADIENT -> "渐变"
    CardMaterial.SOFT -> "柔光"
}

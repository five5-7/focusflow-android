package com.sakata.focusflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val quickStartChapters = listOf(
    HelpSection("先做三件事", listOf(
        "1. 想到一件事就点底部＋ → 快速记录；先写下来，不必先填时间、分类或目标。",
        "2. 回到今日页的收集箱：能定时间的就“安排”，暂时说不清的想法可整理为“逐步推进”，资料或备忘可整理为“参考”。",
        "3. 安排后到日程查看；完成、改期或放回收集箱都由你决定。错过不等于失败，应用会保留记录并提供恢复入口。"
    )),
    HelpSection("三个去向怎么选", listOf(
        "安排：把这一件事放进具体时间，进入日程并按设置提醒。",
        "逐步推进：保留原想法，只确认一个眼下能做的下一步；下一步会作为关联任务进入收集箱，完成后再决定是否继续。",
        "参考：保存资料、灵感或备忘；它不进入日程、提醒、推荐或完成率。"
    )),
    HelpSection("默认行为与边界", listOf(
        "核心记录、收集箱和手动安排无需 AI、地点或任何额外权限。",
        "固定日程不会被自动移动；建议、缩短、改期和完成都必须由你确认。",
        "首次启动可选择是否启用校园生活。关闭时课程不参与今日、日程、空挡或推荐，但课程、节次和地点数据仍保留；可到 设置 → 高级工具 → 通勤与地点 重新开启。",
        "新安装没有预置地点；启用校园生活后，通勤预留先按单程 10 分钟估计且不读取定位。课程、地点和通勤都可稍后在设置调整。",
        "每日精力询问默认关闭；饭点提醒默认关闭，用餐结束询问是另一个默认关闭的开关；前台应用检测默认关闭且绝不会自动结束活动。AI 也默认关闭，只有你主动开启后才会工作；这些功能不会自动改设置。"
    )),
    HelpSection("何时配置可选工具", listOf(
        "返校后需要课表时，再到计划 → 课程手动新增或导入。手动课程可选周一至周日，填写“第几节开始＋连续几节”；第一次进入日程 → 课表时，按提示确认学校节次时间。",
        "需要提醒时，先在设置 → 活动提醒运行 1 分钟测试；必须在回到桌面后按时收到，才算后台设置正常。",
        "长期目标再创建目标；课表识别、AI 学习路径、地图地点搜索与应用检测都不是日常使用的前提。"
    )),
    HelpSection("页面与功能速查", listOf(
        "今日用于收集与当前状态；日程用于看时间轴；计划用于课程、目标和回顾；设置用于默认值、提醒与可选工具。",
        "外观都在 设置 → 外观：七套主题、页面背景（跟随主题／渐变／固定颜色／自选图片）、卡片材质、课表与日程表底色（可设「透明」露出页面背景）。全部可选，不选就是默认样子。",
        "底栏中间是＋快速记录；走过页面后两端会出现「上一步／下一步」，长按上一步可看本次会话的页面历史。今日页按返回会先提示，4 秒内再按才退出。",
        "想知道某项功能的完整用法、默认设置或常见问题，可到 设置 → 使用说明书；它不会自动展示。",
        "数据默认只保存在本机，没有应用内云同步。只有你主动使用 AI 或地点查询时，相关请求才会发送给你配置的服务。",
        "以后可在 设置 → 快速入门 重看；每个设置页的问号说明该功能的默认值、作用和调整方式。"
    ))
)

@Composable
internal fun QuickStartDialog(onDismiss: () -> Unit) {
    var chapter by rememberSaveable { mutableIntStateOf(0) }
    val tabs = rememberLazyListState()
    LaunchedEffect(chapter) { tabs.animateScrollToItem(chapter) }
    val contentHeight = (LocalConfiguration.current.screenHeightDp * 0.4f).coerceIn(140f, 340f).dp
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速入门 · ${chapter + 1}/${quickStartChapters.size}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(state = tabs, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(quickStartChapters) { index, section ->
                        FilterChip(selected = chapter == index, onClick = { chapter = index }, label = { Text(section.title) })
                    }
                }
                Box(Modifier.fillMaxWidth().height(contentHeight)) {
                    SubpageMotion(chapter, containerColor = AlertDialogDefaults.containerColor) { index ->
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(quickStartChapters[index].title, fontWeight = FontWeight.Bold)
                            quickStartChapters[index].lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (chapter < quickStartChapters.lastIndex) TextButton(onClick = { chapter++ }) { Text("下一节") }
            else Button(onClick = onDismiss) { Text("开始使用") }
        },
        dismissButton = {
            Row {
                if (chapter > 0) TextButton(onClick = { chapter-- }) { Text("上一节") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
internal fun CampusLifeChoiceDialog(onEnable: () -> Unit, onSkip: () -> Unit) {
    AppDialog(
        onDismissRequest = onSkip,
        title = { Text("启用校园生活？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("开启后可使用课程、固定课表、节次表、校园地点和校内通勤估算。")
                Text("暂不开启也不影响收集箱、普通日程和目标；之后可到 设置 → 高级工具 → 通勤与地点 开启。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onEnable) { Text("启用校园生活") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("暂不开启") } }
    )
}


/** 覆盖安装后每个版本只展示一次的更新说明；版本名来自 BuildConfig，路线图是唯一详情入口。 */
internal fun updateHighlightsFor(version: String): List<String> = when {
    version.startsWith("8.2.1") -> listOf(
        "新增亚克力和毛玻璃：亚克力轻度模糊并带主题色，不加描边；毛玻璃模糊更强，并有完整的圆角亮边。",
        "悬浮底栏和页内弹窗会透出并模糊实际挡住的日程内容，文字、按钮和图标保持清晰；旧设置与预设可继续使用。",
        "「丰富外观」默认关闭；主动开启后才使用这些效果，关闭后仍会保留你的选择。"
    )
    version.startsWith("8.2.0") -> listOf(
        "外观更自由：内置主题增加到七套（新增石墨／樱粉／青竹）；页面背景可选跟随主题、主题渐变、固定颜色或自选图片，图片背景还能一键抽出主题色。",
        "渐变可自选配色与强度，也能选「跟随内容」——渐变铺满数屏，滚动时颜色变化更慢更缓。卡片材质增加渐变／柔光；课表与日程表底色可单独换（跟随主题／选颜色／图片／透明，只动底板）。",
        "以上外观都适配了深色模式；自定义主题工具升级：顶部有实时预览，背景与卡片材质直接在工具里调，对比度不达标会当场指出；保存预设时可以连整套外观一起存，点一下换一整套（老预设仍只换配色，不动你的背景与卡片）。"
    )
    version.startsWith("8.1.1") -> listOf(
        "上一步／下一步与弹窗的关系修好了：打开弹窗本身不算一步。开着弹窗按上一步＝直接回上一个页面；从这里跳到别处（管理地点等）再按上一步，副页、弹窗和已经填进去的内容一起回来。",
        "弹窗卡片加了主题色柔光阴影、顶面高光和细描边，明显浮在压暗的页面上；浅色模式页面底色比卡片深一档，卡片一眼就能分出来。"
    )
    version.startsWith("8.1.0") -> listOf(
        "页面转场重做：子页从底栏图标放大展开、返回时缩回；主页之间左右平移。设置 → 外观 新增「动画速度」，可关闭或调快调慢。",
        "底栏选中色块会滑动，进入子页变成圆环；两端出现「上一步／下一步」，长按上一步可看本次会话的页面历史。",
        "弹窗不再挡住底栏：打开时底栏仍可点，卡片从下方平移进出，点入口／上一步／返回键都会先关弹窗。今日页按返回先提示，4 秒内再按才退出。"
    )
    version.startsWith("7.12.0") -> listOf(
        "课程批量管理增加全选，课程卡片操作区与空挡周视图已重新整理。",
        "历史记录可以单删、批删或清空全部，相关统计会按剩余记录重新计算。"
    )
    version.startsWith("7.11.0") -> listOf(
        "课程可以设置生效期、停用、单门删除或批量删除；空挡内容改为分类切换。",
        "通勤增加可调距离档位，并可决定未知路线是否使用默认时间。"
    )
    version.startsWith("7.10.0") -> listOf(
        "校园生活现在统一控制课程、课表、节次、地点和校内通勤；首次安装会先询问是否启用。",
        "修复当前位置空选择页；今日状态选择电动车后可直接调整并查看电量。"
    )
    version.startsWith("7.9.0-rc.4") -> listOf(
        "今日页顶部新增可折叠的状态入口，集中调整生活阶段、精力和出行方式。",
        "通勤块符号修正与统一设计语言第二轮保持不变。"
    )
    version.startsWith("7.9.0-rc.3") -> listOf(
        "修正通勤块在较短时间段中容易呈现为感叹号的问题。",
        "统一设计语言第二轮与收集箱分类入口保持不变。"
    )
    version.startsWith("7.9.0-rc.2") -> listOf(
        "收集箱顶部新增全部、待整理、推进和参考入口，可快速切换分类。",
        "通勤块、统计卡、设置折叠区、滚动条和进度条已统一视觉层级。"
    )
    version.startsWith("7.9.0") -> listOf(
        "今日、收集箱、日程和课表已减少重复标题与常驻教学，用户内容更靠前。",
        "必要影响和异常说明仍保留，完整规则与功能说明可从帮助和使用说明书查看。"
    )
    version.startsWith("7.8.0-rc.3") -> listOf(
        "课表缩小视图改为七天同屏；标准视图会在大色块内展示更多课程信息。",
        "应用会记住上次选择的课表视图，首次默认使用缩小视图。"
    )
    version.startsWith("7.8.0-rc.2") -> listOf(
        "固定周课表新增缩小视图，可在一屏查看更多日期并随时恢复标准视图。",
        "设置顶部默认说明改为折叠卡片，并统一为当前主题的标准卡片颜色。"
    )
    version.startsWith("7.8.0") -> listOf(
        "日程新增独立固定课表，按周一至周日和节次展示已确认课程。",
        "首次进入先确认参考节次表；自定义时间会同步用于课程日程和空挡计算。"
    )
    version.startsWith("7.7.0-rc.2") -> listOf(
        "新增手动打开的使用说明书，保留完整功能、默认设置与常见问题。",
        "快速入门新增功能速查，并可跳转说明书；更新提示仍只显示一次。"
    )
    version.startsWith("7.7.0") -> listOf(
        "默认设置说明与快速入门已按首次使用路径重整。",
        "更新提示只显示一次；可随时从版本路线图查看完整记录。"
    )
    else -> listOf("本次功能更新已安装；完整记录请查看版本路线图。")
}

@Composable
internal fun UpdateNoticeDialog(
    version: String,
    onDismiss: () -> Unit,
    onOpenRoadmap: () -> Unit
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("已更新至 $version") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本次更新已完成安装。核心变化：", fontWeight = FontWeight.SemiBold)
                updateHighlightsFor(version).forEach { Text("• $it") }
                Text("完整版本记录与后续候选都在版本路线图中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onOpenRoadmap) { Text("查看版本路线图") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后查看") } }
    )
}

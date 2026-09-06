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
        "新安装没有预置地点；通勤预留默认开启并先按单程 10 分钟估计，不读取定位。课程、地点和通勤都可稍后在设置调整。",
        "每日精力询问默认关闭；饭点提醒默认关闭，用餐结束询问是另一个默认关闭的开关；前台应用检测默认关闭且绝不会自动结束活动。AI 也默认关闭，只有你主动开启后才会工作；这些功能不会自动改设置。"
    )),
    HelpSection("何时配置可选工具", listOf(
        "返校后需要课表时，再到计划 → 课程手动新增或导入。手动课程可选周一至周日，填写“第几节开始＋连续几节”；第一次进入日程 → 课表时，按提示确认学校节次时间。",
        "需要提醒时，先在设置 → 活动提醒运行 1 分钟测试；必须在回到桌面后按时收到，才算后台设置正常。",
        "长期目标再创建目标；课表识别、AI 学习路径、地图地点搜索与应用检测都不是日常使用的前提。"
    )),
    HelpSection("页面与功能速查", listOf(
        "今日用于收集与当前状态；日程用于看时间轴；计划用于课程、目标和回顾；设置用于默认值、提醒与可选工具。",
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
    AlertDialog(
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


/** 覆盖安装后每个版本只展示一次的更新说明；版本名来自 BuildConfig，路线图是唯一详情入口。 */
internal fun updateHighlightsFor(version: String): List<String> = when {
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
    AlertDialog(
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

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
    HelpSection("日常流程", listOf(
        "点底部＋，用“快速记录”收集一件想做的事。",
        "在今日页收集箱编辑、安排时间；可输入 5–360 分钟，也可保留弹性范围。",
        "到日程查看安排；到点后完成、改期或放回收集箱。完成项保留并灰化。",
        "应用不会自动移动固定日程；弹性建议需要你确认。删除或放回收集箱不会撤销已发生的计划统计。"
    )),
    HelpSection("页面导航", listOf(
        "今日：现在、接下来和收集箱；餐点、精力、睡前与校园模块按需出现。",
        "日程：日／周时间轴，任务与活动可安排、改期、完成、删除。",
        "计划：课程、空挡建议、目标与执行、本周回顾、历史记录、资料工具箱与暂停项目。",
        "设置：提醒、外观和作息在主页；地点、AI、识别与应用检测在高级工具。",
        "进入子页面后用系统返回逐级返回；再次点击当前底部入口可回到该入口主页。"
    )),
    HelpSection("提醒权限", listOf(
        "允许通知，并检查日程与饭点渠道。不同系统可能称为横幅、悬浮或弹出窗口。",
        "应用会读取系统精确闹钟能力；需要授权时按系统提示设置，无法使用时会明确降级。",
        "在设置的日程与活动提醒里运行 1 分钟测试。回到桌面等待，不能把打开应用后的补发当作后台正常。",
        "厂商自启动开关无法可靠自动检测，请按设备说明允许后台活动和自启动。",
        "一次性静音和免打扰只控制状态询问、饭点和睡前减速；任务与活动到点提醒不被静音。"
    )),
    HelpSection("可选工具", listOf(
        "需要生活建议时再填写习惯基线；上学时导入课表；长期任务再建目标。",
        "＋里的安排空闲活动可给游戏、视频、学习或运动设定时间。前台应用检测需要使用情况访问权限，判断在本机完成。",
        "课表截图识别需要配置视觉模型和 key，失败会提示重试，没有本地 OCR 兜底。",
        "AI、资料、地图与应用检测都不是日常记录和日程的必需项。"
    )),
    HelpSection("数据与帮助", listOf(
        "日程、任务与记录保存在本机，没有应用内云同步。可选 AI 和地点查询会把相关请求发送给设置中指定的服务。",
        "历史记录展示最近事件，统计使用保留的完整任务历史。升级前已被旧版本截掉的记录无法重建。",
        "若损坏数据无法备份，应用会提示并暂停保存；请释放空间、重试备份后重新打开应用。",
        "之后可在 设置 → 快速入门 再次查看这些章节；各页面问号提供具体说明。"
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

package com.sakata.focusflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 手动打开的完整说明；不参与首次启动和版本更新弹窗。 */
internal val userGuideChapters = listOf(
    HelpSection("一、五分钟开始使用", listOf(
        "想到事情先点底部＋快速记录：只写内容也可以，不必先决定时间、分类或目标。",
        "在今日页收集箱中处理：能定时间就“安排”；暂时说不清的想法用“逐步推进”确认一个下一步；资料、灵感或备忘用“参考”。",
        "安排后到日程查看。完成、改期、缩小或放回收集箱都由你决定；错过计划不会抹掉记录，可从恢复入口继续处理。"
    )),
    HelpSection("二、页面与功能速查", listOf(
        "今日：当前状态、下一件事、收集箱、完成与改期概览。日程：日／周时间轴和已安排任务。计划：课程、空挡建议、目标、本周回顾、历史与资料。设置：默认值、提醒和可选工具。",
        "想录入课程：计划 → 课程；手动新增可选周一至周日，填写“第几节开始＋连续几节”。想查看固定七天课表：日程 → 课表，首次进入先确认学校节次时间。",
        "想设置地点或通勤：设置 → 高级工具 → 通勤与地点；想创建长期目标：计划 → 目标与执行；想测试通知：设置 → 日程与活动提醒。"
    )),
    HelpSection("三、日程、提醒与后台", listOf(
        "安排会进入日程；固定日程不会被自动移动。建议、改期、完成和删除都必须由你确认。",
        "提醒是否能在后台准时送达取决于设备权限与后台限制。请在“日程与活动提醒”运行 1 分钟测试，并回到桌面验证实际到达。",
        "重启恢复：手机重启后，应用收到系统开机广播会重新登记提醒；但部分厂商（实测 ColorOS）会把开机广播推迟给应用，此时**打开一次 FocusFlow 就会恢复**。若希望重启后不必打开应用也能提醒，请在系统设置里允许自启动（OPPO／一加／realme：设置 → 电池 → 应用耗电管理 → FocusFlow → 允许自启动与关联启动）。",
        "免打扰和一次性静音只降低相应提示，不会删除日程或历史。通知异常时，设置页会显示当前设备可执行的检查路径。"
    )),
    HelpSection("四、默认设置与可选能力", listOf(
        "核心记录、收集箱和手动安排不需要 AI、地点、课表或额外权限。首次安装会询问是否启用校园生活；关闭时课程不参与今日、日程、空挡或推荐，已有课程、节次和地点数据不会删除。",
        "新安装没有预置地点；启用校园生活后，通勤预留先按单程 10 分钟估计且不读取定位。可到 设置 → 高级工具 → 通勤与地点 调整或重新开启。",
        "每日精力询问、饭点提醒、用餐结束询问、前台应用检测和 AI 都默认关闭；只有你主动开启并完成必要配置后才工作。",
        "前台应用检测只增强游戏／视频的收尾提醒文案，绝不会自动结束活动。AI、地图和课表识别只在你主动使用时发送请求。"
    )),
    HelpSection("五、课程、地点、通勤与目标", listOf(
        "地点为空时可先继续录入课程；返校后再添加地点或导入地点包。选择当前位置没有可选项时会明确引导到校园地点管理。通勤时长和方式可在设置调整，已有设置不会被新版本悄悄重置。",
        "今日状态可快速切换出行方式；选择电动车后会显示电量。关闭校园生活只隐藏或停用相应入口，不会清空这些记录。",
        "目标用于需要反复推进的事：填写预期结果、第一步、完成标准、频率和时长。教程资料与 AI 建议只是候选，确认后才会保存。",
        "“逐步推进”适合尚不适合做成目标的想法；它只生成当前下一步，不会替你虚构长期计划。"
    )),
    HelpSection("六、数据、隐私、更新与常见问题", listOf(
        "数据默认只保存在本机，应用内没有云同步；换机前请先通过应用提供的备份方式保存数据。删除或改期不会改写已发生的历史统计。",
        "只有主动调用 AI 或地点查询时，相关请求才会发送至你配置的服务；不配置就不会调用。",
        "快速入门可随时重看；使用说明书不会自动展示。每次覆盖安装的新版本最多提示一次，完整变更可在 设置 → 版本路线图 查看。"
    )),
    HelpSection("七、导航、返回与动效", listOf(
        "底部悬浮栏有四个入口（今日／日程／计划／设置）和中间的＋。选中底色会滑到当前入口；进入子页时它变成圆环，该入口的文字临时替换成子页名，返回后恢复。",
        "底栏两端是“上一步／下一步”（回退／折返）：只有本次会话里走过页面时才出现。上一步＝回到上一个页面（可以跨入口，例如从计划子页直接回今日）；下一步＝撤销刚才的上一步。长按上一步会弹出本次会话的页面历史，点任意一条可直接跳过去，该条之后的历史会被截断。",
        "系统返回键：不在今日页时先回到今日主页；在今日页第一次按会提示“再按一次返回键退出应用”，4 秒内再按才退出。提示里的“不再提示”可永久关闭，设置 → 退出确认 可以改回来。弹窗打开时，返回键先关闭弹窗。",
        "弹窗是页内浮层：打开时底栏会跟着一起变暗但仍可点（可以直接切入口或用上一步／下一步），按返回键或点弹窗外面关闭，点弹窗卡片本身不会误关；键盘弹出时弹窗整体上移，按钮始终可见。",
        "设置 → 外观 里的“动画速度”四档（关闭／较快／标准／较慢）统一作用于页面转场、底栏形变与选中色块；关闭时所有转场瞬时完成。系统自带的“移除动画”设置仍然生效。",
        "应用锁定竖屏：横屏放置时界面不会旋转；窄屏与大字体下，底栏文字不重叠、弹窗按钮也不会被底栏挡住。"
    ))
)

@Composable
internal fun UserGuideSubpageContent() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("使用说明书", fontWeight = FontWeight.Bold)
                Text("完整说明只在你主动打开时展示；日常使用先看“快速入门”即可。", style = MaterialTheme.typography.bodySmall)
            }
        }
        userGuideChapters.forEach { chapter ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(chapter.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                chapter.lines.forEach { line -> Text("• $line", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

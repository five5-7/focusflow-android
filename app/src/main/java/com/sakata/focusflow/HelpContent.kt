package com.sakata.focusflow

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class HelpSection(val title: String, val lines: List<String>)

data class PageHelp(val title: String, val sections: List<HelpSection>)

enum class SettingsBlock(val title: String) {
    APPEARANCE("外观"),
    CUSTOM_THEME("自定义主题"),
    ACTIVITY_REMINDERS("活动提醒"),
    QUIET_HOURS("提醒打扰控制"),
    STATUS_CHECK_IN("状态询问"),
    WIND_DOWN("睡前减速"),
    BASELINE("习惯基线"),
    MEAL_LEARNING("饭点学习"),
    EXPENSES("个人账目"),
    COMMUTE_PLACES("通勤与地点"),
    TUTORIAL_SEARCH("教程联网搜索"),
    COURSE_VISION("课表识别（视觉模型）"),
    APP_DETECTION("前台应用检测"),
    IMPROVEMENTS("改进清单")
}

object HelpCatalog {
    val today = PageHelp("今日概览", listOf(
        HelpSection("页面说明", listOf("先看状态与下一步；具体时间安排已移到“日程”。")),
        HelpSection("当前状态与活动", listOf("开始前约定结束时间和下一步；到点后由你明确决定。", "休息和娱乐只作为时间记录，不会被简单判定为负面。")),
        HelpSection("精力与推荐", listOf("精力只影响弹性任务的推荐顺序，不会移动固定日程。")),
        HelpSection("今日餐点", listOf("完成“习惯基线”引导后，这里会按你的饭点节奏给出提醒；现在只按你填写的餐点显示。", "只有你确认“已在吃”和“吃完了”的时间会用于学习；不回应不会记为没吃。")),
        HelpSection("完成统计", listOf("统计本机保存的完成记录；待整理为收集箱中尚未安排的想法数。")),
        HelpSection("下一件合适的事", listOf("从临近日程和未定时任务中给出低压力建议；开始、缩短和改期都需要你确认。")),
        HelpSection("收集箱", listOf("这里显示最近记录；进入副页面可集中编辑、安排或删除。"))
    ))

    val schedule = PageHelp("日程", listOf(
        HelpSection("日视图", listOf("按真实时间连续排布；点击色块查看起止时间。")),
        HelpSection("周视图", listOf("周一至周日同屏显示；点击色块查看详情。")),
        HelpSection("今日待办", listOf("尚未指定时段的任务先集中显示，安排后进入时间轴。")),
        HelpSection("弹性安排", listOf("根据当前精力、任务时长和已有日程提供初步时间；只有选择后才写入日程。")),
        HelpSection("已完成任务", listOf("已完成的任务继续保留在时间轴上，并以灰色显示。"))
    ))

    val plan = PageHelp("计划", listOf(
        HelpSection("计划主页", listOf("选择一个模块进入；滚动只发生在各副页面内。", "从结果开始：填写预期结果、每周次数与单次时长。")),
        HelpSection("课程", listOf("从课表截图开始，通过硅基流动视觉模型识别（需先在 设置→课表识别（视觉模型） 开启并填写 key；本地 OCR 已移除，识别失败会提示原因，不会出低质量结果）。结果先进入待确认区，不会直接加入日程。截图中需要显示课程名称、星期和节次。", "确认课程后，它们会用于周日程和空挡计算。")),
        HelpSection("空挡建议", listOf("根据已确认课程、校内路程与缓冲时间计算，不与课程列表混放。")),
        HelpSection("目标与执行", listOf(
            "每个目标分别保存预期结果、第一步行动、完成标准和可选资料；资料库的常用标记不会自动套用到目标。",
            "安排目标任务（手动点“排入”或“按空挡自动排本周目标”）时，任务详情会带上该目标自己的第一步、资料与最低版本指引。",
            "AI 搜索只给出候选第一步；找到真实链接或材料并确认后，才从资料工具箱保存。",
            "“按空挡自动排本周目标”：本地判断，把本周未完成的目标次数排进课程空挡（避开课程与已有安排、优先更长空档），结果进入日程，可随时改期或调整。"
        )),
        HelpSection("本周回顾", listOf("创建目标并积累完成记录后，这里会给出调整建议。")),
        HelpSection("暂停项目", listOf("暂停的任务会集中放在这里，不占用日程。"))
    ))

    val settings: Map<SettingsBlock, HelpSection> = mapOf(
        SettingsBlock.APPEARANCE to HelpSection("外观", listOf(
            "选择后立即应用到页面、导航、卡片与控件；课程色块和提醒警示保持各自语义，不受主题影响。",
            "提供海盐蓝、薄荷绿、暖杏与暮紫四套内置主题，也可以自定义；选择都会记住。",
            "自定义主题的“恢复默认”会回到最近一次用过的内置主题。"
        )),
        SettingsBlock.CUSTOM_THEME to HelpSection("自定义主题", listOf(
            "主题使用五个全局色：主色、副色、强调色、中性色、文字色，共同影响除课程色块与提醒警示外的所有界面区域。",
            "课程色块（日程上的课程配色）与提醒警示（活动到点、注意休息等固定系统提醒红）保持各自语义，不接受定制。",
            "点选色板中的颜色立即应用到全局；配色保存在本机，不会影响已有的活动记录和日程。",
            "可把调好的配色“保存为预设”并命名（最多 8 套），之后点“应用”一键切换；点预设上的编辑按钮可加载该配色修改，改完点“更新此预设”；删除预设不会改动当前配色。",
            "内置主题可“改色另存”：外观页主题卡上的该按钮以这套主题的全局色为基础进入自定义编辑器，调整后保存为新预设；原生内置主题本身不会被改动。"
        )),
        SettingsBlock.ACTIVITY_REMINDERS to HelpSection("活动提醒", listOf(
            "活动提醒关闭后仍会保留活动记录和手动转场。",
            "明确的到点提醒：到达约定时间时使用更醒目的提醒。",
            "提前预告：约定结束前 N 分钟提醒一次。",
            "连续延长提示上限：到点转场时可延长的次数。",
            "日程任务会按设置提前预告，并在安排时间到达时再次提醒；提前预告不会替代到点提醒。系统允许时使用精确提醒，未授权时自动使用普通后台提醒。",
            "诊断卡会分别显示下一次提前提醒与到点提醒；“1 分钟后测试通知”使用同一后台链路，可验证权限、渠道和系统调度。",
            "电池优化状态可由 Android 自动读取；厂商自启动开关通常不公开，应用会用测试提醒的实际送达时间区分按时、延迟或未送达，不能把打开应用后的补发算作后台正常。",
            "到点提醒优先使用更强的系统闹钟唤醒路径，但只显示 FocusFlow 通知，不主动播放闹钟声音；部分设备会在状态栏显示闹钟标识。提前预告仍使用普通精确提醒。",
            "应用冷启动及每次回到前台时，会检查 Android 公开的总通知、日程渠道和饭点渠道状态；未开启时在应用内提醒。",
            "长按 FocusFlow 图标 → 应用信息 → 通知（或通知管理），先开“允许通知”，再分别检查“FocusFlow 任务提醒”和“饭点提醒”。设置页会按三星、小米／Redmi／POCO、华为／荣耀、OPPO／realme／OnePlus、vivo／iQOO、Pixel／原生 Android 显示补充路径。",
            "不同系统可能把弹出方式称为横幅、悬浮通知、顶部预览、在屏幕上弹出或显示为弹出窗口。厂商单独的开关通常不对应用公开，因此仍需手动确认；精确闹钟不可用时 FocusFlow 会自动改用普通后台提醒。"
        )),
        SettingsBlock.QUIET_HOURS to HelpSection("提醒打扰控制", listOf(
            "免打扰时段：按你设定的起止时间（支持跨天，如 23:00–07:00）静音低打扰类提醒（状态询问、饭点提醒、睡前减速）；活动到点和任务提醒保持时间敏感，不会被静音。",
            "每个类型可单独开关（默认都静音）。",
            "一次性静音：立即静音所有提醒 1 小时／3 小时／到明早 7 点，适合睡觉、上课或开会；静音结束自动恢复，也可随时手动取消。",
            "静音只是不弹通知，不会删除任何记录或训练数据。"
        )),
        SettingsBlock.STATUS_CHECK_IN to HelpSection("状态询问", listOf(
            "每日低打扰询问：询问精力与当前活动；关闭后不会删除已有记录。",
            "主动选择稍后时，推迟 N 分钟。",
            "活动进行中会自动等到稍后；没有回应时当天不连续追问。签到数据仅保存在本机，现阶段不会据此自动改动日程。"
        )),
        SettingsBlock.WIND_DOWN to HelpSection("睡前减速", listOf(
            "每晚按你填写的睡觉时间提前 40 分钟提醒开始收尾；关闭后不会删除已有记录。",
            "今日页在睡前时段显示减速进度，并根据明天第 1–2 节是否有课给出“注意休息”或“可稍晚收尾”的提示。",
            "熬夜时只给出低压力建议，不会安排任何任务。"
        )),
        SettingsBlock.EXPENSES to HelpSection("个人账目", listOf(
            "只统计你在“吃完了吗”里填写的金额草稿，不会自动记账或推断金额。",
            "数据积累后显示合计、本月支出、分类与常去地点；金额可留空，不影响任何学习。"
        )),
        SettingsBlock.BASELINE to HelpSection("习惯基线", listOf(
            "2–3 分钟填好大致作息与餐点；只有你确认过的数据才会用于后续学习。",
            "原始事件按时间追加保存，不会因学习而覆盖；你可以随时查看、修正或重建。",
            "假期与上学节奏分开保存，后续学习按“生活阶段 × 星期 × 餐次”进行。"
        )),
        SettingsBlock.MEAL_LEARNING to HelpSection("饭点学习", listOf(
            "接近预测饭点时询问是否开始吃饭；只有你确认的时间才会用于学习。",
            "完成习惯基线引导后，这里会按“生活阶段 × 星期 × 餐次”展示学到的饭点；数据不足时只用宽松提醒，不会假装精确预测。",
            "提醒按星期分组学习；假期和上学分开，避免互相影响。",
            "结束用餐可记录消费草稿，仅保存在本机，为后续账目分析预留。"
        )),
        SettingsBlock.COMMUTE_PLACES to HelpSection("通勤与地点", listOf(
            "校园生活：控制校内出行、地点包和手动位置工具；关闭不会删除已有数据。",
            "地点与空挡：地点只在安排课程空档时用于估计去图书馆、操场或下一栋教学楼是否来得及。",
            "通勤参数：只保存大致时长，不读取定位。这些都是初始估计；以后可按实际体验随时改，不需要一开始就准确。开始上学后再设置即可。",
            "电动车：电量偏低时，后续排程会避免安排需要骑车的远距离连续行程，并建议在合适时段充电。",
            "地点来源：普通使用不需要制作或提交地点文件。可选填写高德 Web 服务 key 后可搜索校园 POI 并一键加入地点列表；注意需申请“Web 服务”类型 key（手机端 SDK key 绑定应用，REST 调用会失败）。地图点选需要地图 SDK，留待后续版本。",
            "手动当前位置：仅在你选择时更新，不申请定位权限，也不会后台追踪。"
        )),
        SettingsBlock.TUTORIAL_SEARCH to HelpSection("学习路径建议", listOf(
            "可选功能：为学习目标生成 3–5 步可执行的学习路径——每步给出学什么、用什么资源（视频／文章／练习）和去 B站/知乎/慕课 搜什么关键词；不编造链接，搜到的有用内容可手动收藏到教程资料。",
            "使用你填写的硅基流动 API key；key 仅保存在本机，只发往 api.siliconflow.cn，开关关闭时不发送任何请求。",
            "模型可点预设快速切换（Qwen2.5-7B 免费默认 / DeepSeek-V4-Flash），也可手填其他模型 ID；模型列表变化以硅基流动文档为准。"
        )),
        SettingsBlock.APP_DETECTION to HelpSection("前台应用检测", listOf(
            "配合加号 → “想玩游戏”使用：到点时识别当前前台应用，还在玩游戏类应用就提醒收尾（可结束/再玩 15 分钟），并记录实际结束与超时，供周回顾和 AI 周总结使用。",
            "需要“使用情况访问”系统特殊权限：在本页点“去系统开启”，到系统设置里允许 FocusFlow 后返回；判断只在本机完成，不上传任何数据。",
            "应用分类按本机已安装应用生成：内置常见应用归类 + 应用名自动识别，识别不对或未识别的可手动归类；分类只用于收尾提醒判断。",
            "未授权时功能降级为“只提醒开始/结束，不检测前台应用”；“到点提醒开始”可在安排时选择关闭，结束提醒始终保留。"
        )),
        SettingsBlock.COURSE_VISION to HelpSection("课表识别（视觉模型）", listOf(
            "课表导入通过硅基流动视觉模型识别（4.0.1 起不再内置本地 OCR，识别失败会提示原因：检查 key、模型名或网络后重试，不会出低质量兜底结果）。",
            "与教程搜索共用同一把硅基流动 API key：key 仅保存在本机，课表图片只发往 api.siliconflow.cn，关闭开关后导入课表不联网。",
            "模型可点预设按钮快速切换（Qwen3-VL-8B 免费 / 32B / 30B-A3B / PaddleOCR-VL），也可手填其他模型 ID；旧版 Qwen2.5-VL 系列已下线，保存过的旧模型名会自动迁移到新版。",
            "识别只取课表网格内的课程：课名与教室/楼名分开记录，页脚说明（如“隐藏课程信息”）不会当成课程。",
            "地点自动归到楼级：教室号不记入地点（如“紫金港东1A-302”→“东1教学楼”，含“东1B”“东1B-201”等楼座），找教室靠“教学楼进出与找教室缓冲”时间；归并后的新楼名出现在 设置→通勤与地点→“课表识别发现的新地点”，可一键加入地点列表供后续使用。",
            "识别结果先进入“待确认课程”，逐项编辑、确认或忽略后才进入日程。"
        )),
        SettingsBlock.IMPROVEMENTS to HelpSection("改进清单", listOf(
            "记录希望深化或修改的功能；之后把条目发给我即可继续开发。",
            "本地保存，最多保留最近 100 条。"
        ))
    )
}

@Composable
fun HelpToggleButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.size(30.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp)
    ) { Text("?", fontWeight = FontWeight.Bold) }
}

@Composable
fun HelpDialog(title: String, sections: List<HelpSection>, onDismiss: () -> Unit, dismissButton: (@Composable () -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                sections.forEach { section ->
                    Text(section.title, fontWeight = FontWeight.Bold)
                    section.lines.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } },
        dismissButton = dismissButton
    )
}

/** 硅基流动 key 申请引导：新用户首次开启视觉模型时自动弹出，也可从设置页/帮助进入；可直接跳转 API 密钥页。 */
@Composable
fun CourseVisionKeyGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("如何获取硅基流动 API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. 打开硅基流动（SiliconCloud）官网，用手机号注册并登录；免费模型（如 Qwen3-VL-8B）无需充值。", style = MaterialTheme.typography.bodySmall)
                Text("2. 进入「API 密钥」页，点「新建 API 密钥」，复制 sk- 开头的 key。", style = MaterialTheme.typography.bodySmall)
                Text("3. 回到本页粘贴 key，模型保持默认即可（与教程搜索共用同一把 key）。", style = MaterialTheme.typography.bodySmall)
                Text("key 仅保存在本机，课表图片只发往 api.siliconflow.cn。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cloud.siliconflow.cn/account/apikey")))
            }) { Text("去申请 key") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

/** 首次启动的快速入门：先介绍日常闭环，再说明可选设置。 */
@Composable
fun WelcomeIntroDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速入门") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("先完成一次日常闭环：", fontWeight = FontWeight.SemiBold)
                Text("1. 点底部＋，用“快速记录”收集一件想做的事。", style = MaterialTheme.typography.bodySmall)
                Text("2. 在今日页的收集箱中编辑或安排时间；时间不确定可保持弹性。", style = MaterialTheme.typography.bodySmall)
                Text("3. 到日程查看安排；到点后完成、改期或推迟，完成项会保留并灰化。", style = MaterialTheme.typography.bodySmall)
                Text("4. 应用不会自动改动你的固定日程；弹性建议需要你确认后才会写入。", style = MaterialTheme.typography.bodySmall)
                Text("四个入口：", fontWeight = FontWeight.SemiBold)
                Text("• 今日：现在、接下来和收集箱始终靠前；餐点、精力、睡前与校园模块按需出现。", style = MaterialTheme.typography.bodySmall)
                Text("• 日程：日／周时间轴，任务与活动可改期、完成、删除。", style = MaterialTheme.typography.bodySmall)
                Text("• 计划：用堆叠入口进入课表、空挡、目标、回顾和资料工具箱；不需要时可以完全不配置。", style = MaterialTheme.typography.bodySmall)
                Text("• 设置：高频提醒、外观和作息在主页；地点、AI、识别和应用检测收在高级工具。", style = MaterialTheme.typography.bodySmall)
                Text("底部 ＋ 号：", fontWeight = FontWeight.SemiBold)
                Text("“快速记录”记想法；“安排空闲活动”给游戏／学习／运动等安排时间，到点提醒开始与收尾（游戏/视频可检测前台）。", style = MaterialTheme.typography.bodySmall)
                Text("通知与设备适配：", fontWeight = FontWeight.SemiBold)
                Text("首次启动可申请通知权限。应用打开时会检查总通知和日程／饭点渠道；如有异常可点“查看说明”。不同 Android 系统可能把弹出方式称为横幅、悬浮、顶部预览或弹出窗口，设置页会按当前设备显示补充路径。", style = MaterialTheme.typography.bodySmall)
                Text("可选设置：", fontWeight = FontWeight.SemiBold)
                Text("习惯基线只在你需要饭点、睡前或作息建议时填；上学时再导入课表；长期任务再建目标。资料、AI、地图和前台检测均不是核心流程必需项。", style = MaterialTheme.typography.bodySmall)
                Text("所有数据只保存在本机；建议只用你确认过的数据生成，数据不足时不打扰。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("之后可在 设置 → 快速入门 再次查看。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("开始使用") } }
    )
}

/** 首次完成习惯基线后的“后续在哪找”提示（只弹一次）。 */
@Composable
fun BaselineWhereToFindDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("生活基线已保存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("以后在 设置 → 习惯基线 查看、编辑或重建；同一生活阶段可“另存当前方案”保存多套作息并一键切换（生活模式多方案）。", style = MaterialTheme.typography.bodySmall)
                Text("饭点提醒、睡前减速等会按当前生活阶段（假期／上学／考试周）自动跟随，阶段也可在今日页顶部一键切换。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } }
    )
}

@Composable
fun SettingsSectionHeader(title: String, onHelp: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        HelpToggleButton(onClick = onHelp)
    }
}

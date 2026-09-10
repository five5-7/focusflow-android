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
    MEAL_LEARNING("吃饭提醒"),
    EXPENSES("个人账目"),
    COMMUTE_PLACES("通勤与地点"),
    TUTORIAL_SEARCH("教程联网搜索"),
    AI_WEEKLY_SUMMARY("AI 周总结"),
    COURSE_VISION("课表识别（视觉模型）"),
    APP_DETECTION("前台应用检测"),
    IMPROVEMENTS("改进清单")
}

object HelpCatalog {
    val today = PageHelp("今日概览", listOf(
        HelpSection("页面说明", listOf("先看状态与下一步；具体时间安排已移到“日程”。")),
        HelpSection("当前状态与活动", listOf("开始前约定结束时间和下一步；到点后由你明确决定。", "休息和娱乐只作为时间记录，不会被简单判定为负面。")),
        HelpSection("精力与推荐", listOf("精力只影响弹性任务的推荐顺序，不会移动固定日程。")),
        HelpSection("今日餐点", listOf("饭点提醒默认关闭；开启后，完成习惯基线可按你的饭点节奏提醒。", "只有你确认开始的时间会用于饭点学习；另行开启“用餐结束询问”后，确认的结束时间才用于学习用餐时长。不回应不会记为没吃。")),
        HelpSection("完成统计", listOf("统计基于发生过的事件：完成只认完成时刻，改期只认改期动作；删除任务或放回收集箱不会撤掉当天已经完成的记录。", "待整理为收集箱中尚未安排的想法数。")),
        HelpSection("恢复安排", listOf("已经错过或至少改期两次的未完成任务会出现恢复入口；只有你点击后才会缩小、重排或放回收集箱。")),
        HelpSection("下一件合适的事", listOf("从临近日程和未定时任务中给出低压力建议；开始、缩短和改期都需要你确认。")),
        HelpSection("收集箱", listOf("快速记录不要求分类或时间；进入副页面可集中编辑、安排或删除。", "整理为“逐步推进”时保留原想法，只把当前下一步另建为关联任务；完成一步不要求先安排时间，退回或删除方向会保留独立步骤。“参考”不参与日程、提醒、推荐或完成率。")),
        HelpSection("底部导航与返回", listOf(
            "底栏四个入口切换主页，中间的＋用于快速记录；选中底色会滑到当前入口，进入子页时变成圆环并显示子页名。",
            "底栏两端会出现“上一步／下一步”（回退／折返），只有本次会话里走过页面时才显示；长按上一步可看本次会话的页面历史，点一条直接跳过去。",
            "打开弹窗不算一步历史：弹窗和当前副页、已填内容算同一个整体。开着弹窗按上一步＝回到上一个页面；从别处（管理地点、其他页、本页签主页）上一步回来时，副页、弹窗和里面的内容一起回来。",
            "非今日页按返回先回今日主页；今日页按返回会先提示，4 秒内再按才退出。弹窗打开时返回键先关弹窗（弹窗期间底栏仍可点）。",
            "完整说明见 设置 → 使用说明书 第七章。"
        ))
    ))

    val schedule = PageHelp("日程", listOf(
        HelpSection("日视图", listOf("按真实时间连续排布；点击色块查看起止时间。")),
        HelpSection("周视图", listOf("周一至周日同屏显示；点击色块查看详情。")),
        HelpSection("课表", listOf("固定按周一至周日和学校节次排列，只显示已确认课程，不混入任务、活动或通勤。校园生活关闭时，入口会提示前往 设置 → 高级工具 → 通勤与地点 开启。", "首次进入先确认参考节次表；之后可在课表右上角修改时间。调整节次表不会改掉课程的星期和节次。")),
        HelpSection("今日待办", listOf("尚未指定时段的任务先集中显示，安排后进入时间轴。")),
        HelpSection("弹性安排", listOf("根据当前精力、任务时长和已有日程提供初步时间；只有选择后才写入日程。")),
        HelpSection("已完成任务", listOf("已完成的任务继续保留在时间轴上，并以灰色显示。"))
    ))

    val plan = PageHelp("计划", listOf(
        HelpSection("计划主页", listOf("选择一个模块进入；滚动只发生在各副页面内。", "从结果开始：填写预期结果、每周次数与单次时长。")),
        HelpSection("课程", listOf("可从课表截图识别，也可手动新增。手动新增选择周一至周日，填写“第几节开始＋连续几节”；首次进入课表会用每节 45 分钟的参考时间点引导确认学校作息。地点用于课程显示和已开启的出行时间估算，没有地点包时可直接自填。", "截图识别需先在 设置→高级工具→课表识别（视觉模型） 开启并填写 key；结果先进入待确认区，不会直接加入日程。截图中需要显示课程名称、星期和节次。确认课程后，它们会用于课表、周日程和空挡计算。")),
        HelpSection("空挡建议", listOf("根据已确认课程、校内路程与缓冲时间计算，不与课程列表混放。")),
        HelpSection("目标与执行", listOf(
            "每个目标分别保存预期结果、第一步行动、完成标准和可选资料；资料库的常用标记不会自动套用到目标。",
            "安排目标任务（手动点“排入”或“按空挡自动排本周目标”）时，任务详情会带上该目标自己的第一步、资料与最低版本指引。",
            "AI 搜索只给出候选第一步；找到真实链接或材料并确认后，才从资料工具箱保存。",
            "“按空挡自动排本周目标”：本地判断，把本周未完成的目标次数排进课程空挡（避开课程与已有安排、优先更长空档），结果进入日程，可随时改期或调整。"
        )),
        HelpSection("本周回顾", listOf("汇总本周计划完成率、改期和待恢复任务；同一时段至少出现两次改期后，才给出缩短任务或预留缓冲的建议。", "创建目标并积累完成记录后，这里还会给出目标调整建议。", "活动统计只使用“安排空闲活动”中由你确认的结束时间；前台检测只增强游戏／视频的收尾提醒，不会自动写入结束时间。")),
        HelpSection("历史记录", listOf("近 7 天逐日展示完成的计划数、完成率与改期数；下方是最近 50 条任务事件（创建、安排、改期、完成、放回、删除、恢复）。", "统计基于发生过的事件：同一天多次移动只计一天计划；删除或放回不会撤销当日统计；已有数据首次启动会补记可推断的历史。")),
        HelpSection("暂停项目", listOf("暂停的任务会集中放在这里，不占用日程。"))
    ))

    val settings: Map<SettingsBlock, HelpSection> = mapOf(
        SettingsBlock.APPEARANCE to HelpSection("外观", listOf(
            "选择后立即应用到页面、导航、卡片与控件；课程色块和提醒警示保持各自语义，不受主题影响。",
            "内置七套：海盐蓝、薄荷绿、暖杏、暮紫，以及新增的石墨、樱粉、青竹；也可以自定义。选择都会记住。",
            "页面背景：跟随主题／主题渐变／固定颜色／自选图片。渐变可调配色与强度（0% 即纯色）；“渐变跟随内容”开启后渐变铺满数屏，竖向变化更缓。",
            "图片背景只存在本机、不上传，可调不透明度；图上会压一层主题遮罩保可读性。“从图片抽取主题色”会按图片主色生成一整套自定义主题。",
            "卡片材质：默认／渐变／柔光；课表与日程表底色可单独设，只改底板，课程块与日程块的颜色不受影响。",
            "深色模式下这些外观都会自动适配（包括把自选的浅色压到深色底上），正文对比度仍然达标。",
            "自定义主题的“恢复默认”会回到最近一次用过的内置主题、并把背景与卡片外观一并复位；编辑器顶部有实时预览，会当场给出对比度体检，不达标会指出是哪一处。",
            "动画速度四档（关闭／较快／标准／较慢）统一作用于页面转场、底栏形变与选中色块，关闭时所有转场瞬时完成；系统自带的“移除动画”设置仍然生效。"
        )),
        SettingsBlock.CUSTOM_THEME to HelpSection("自定义主题", listOf(
            "主题使用六个全局色：主色、副色、强调色、中性色、文字色、导航栏色；导航栏背景独立配色，图标和文字自动保持对比，课程色块与提醒警示保持原有语义。",
            "课程色块（日程上的课程配色）与提醒警示（活动到点、注意休息等固定系统提醒红）保持各自语义，不接受定制。",
            "点选色板中的颜色立即应用到全局；配色保存在本机，不会影响已有的活动记录和日程。",
            "可把调好的配色“保存为预设”并命名（最多 8 套），之后点“应用”一键切换；点预设上的编辑按钮可加载该配色修改，改完点“更新此预设”；删除预设不会改动当前配色。",
            "保存预设时勾选“同时记住当前外观”，预设就从一个配色变成一整套外观（渐变停靠色、背景与不透明度、卡片材质、课表底色），点“应用”整套切换；不勾选则只存配色。老预设（只存了配色）行为不变：应用它不会动当前的背景与卡片。",
            "编辑器顶部是实时预览，用的是应用里同一套渲染；背景与卡片外观在编辑器里也能直接调，与「设置 → 外观」是同一批控件、同一份设置。",
            "内置主题可“改色另存”：外观页主题卡上的该按钮以这套主题的全局色为基础进入自定义编辑器，调整后保存为新预设；原生内置主题本身不会被改动。"
        )),
        SettingsBlock.ACTIVITY_REMINDERS to HelpSection("活动提醒", listOf(
            "活动提醒关闭后仍会保留活动记录和手动转场。",
            "活动结束提醒用于正在进行的活动转场；“活动结束前预告”是在预计结束前 N 分钟提醒收尾。",
            "日程开始提醒用于收集箱安排和目标任务；“日程开始前预告”是在任务开始前提醒准备，到点仍会再次提醒。",
            "连续延长提示上限：到点转场时可延长的次数。",
            "日程任务会按“日程开始前预告”设置提前通知，并在安排时间到达时再次提醒；活动结束前预告不会影响日程任务。系统允许时使用精确提醒，未授权时自动使用普通后台提醒。",
            "诊断卡会分别显示下一次提前提醒与到点提醒；“1 分钟后测试通知”使用同一后台链路，可验证权限、渠道和系统调度。",
            "电池优化状态可由 Android 自动读取；厂商自启动开关通常不公开，应用会用测试提醒的实际送达时间区分按时、延迟或未送达，不能把打开应用后的补发算作后台正常。",
            "重启恢复：应用收到系统开机广播后会重新登记提醒；但部分厂商（实测 ColorOS）会把开机广播推迟给应用，此时打开一次 FocusFlow 就会恢复。若希望重启后不依赖打开应用，请在系统设置里允许自启动（OPPO／一加／realme：设置 → 电池 → 应用耗电管理 → FocusFlow → 允许自启动与关联启动）。",
            "到点提醒优先使用更强的系统闹钟唤醒路径，但只显示 FocusFlow 通知，不主动播放闹钟声音；部分设备会在状态栏显示闹钟标识。提前预告仍使用普通精确提醒。",
            "应用冷启动及每次回到前台时，会检查 Android 公开的总通知与日程渠道；只有饭点提醒已开启时才要求饭点渠道可用。",
            "长按 FocusFlow 图标 → 应用信息 → 通知（或通知管理），先开“允许通知”和“FocusFlow 任务提醒”；如已开启饭点提醒，再检查“饭点提醒”。设置页会按不同设备显示补充路径。",
            "不同系统可能把弹出方式称为横幅、悬浮通知、顶部预览、在屏幕上弹出或显示为弹出窗口。厂商单独的开关通常不对应用公开，因此仍需手动确认；精确闹钟不可用时 FocusFlow 会自动改用普通后台提醒。"
        )),
        SettingsBlock.QUIET_HOURS to HelpSection("提醒打扰控制", listOf(
            "免打扰时段：按你设定的起止时间（支持跨天，如 23:00–07:00）静音低打扰类提醒（状态询问、饭点提醒、睡前减速）；活动到点和任务提醒保持时间敏感，不会被静音。",
            "每个类型可单独开关（默认都静音）。",
            "一次性静音：立即静音低打扰提醒（状态询问、饭点提醒、睡前减速）1 小时／3 小时／到明早 7 点，适合睡觉、上课或开会；任务提醒与活动到点提醒保持时间敏感，不会被静音；静音结束自动恢复，也可随时手动取消。",
            "静音只是不弹通知，不会删除任何记录或训练数据。"
        )),
        SettingsBlock.STATUS_CHECK_IN to HelpSection("状态询问", listOf(
            "每日低打扰询问默认关闭；开启后询问精力与当前活动，关闭不会删除已有记录。设置页显示下一次预计时间与最近一次触发结果。",
            "一分钟测试用于主动验证后台通知；它不受今日已记录、免打扰或当前活动阻挡，但仍需要系统通知权限。",
            "主动选择稍后时，推迟 N 分钟。",
            "日常询问遇到活动进行中会延后；静音、免打扰、已达到当天次数或系统送达过晚会跳过并显示原因。",
            "可选的第二次询问默认关闭；开启后也只有当天已经记录一次且间隔至少 4 小时才会出现，漏掉第一次不会补问。近 30 天该时段已有 6 次样本后自动暂停，每天最多两次。",
            "自动低成本采样默认开启：上午、下午、晚上轮换询问；稳定时段停止日常追问，三个时段完成后改为每周抽查。默认每天最多一次，加速建模需主动开启。",
            "默认模式建立三个初步基线约需 12–18 个有效回答日；加速模式目标为 7–10 天。漏答不算有效日，也不会在当天补发。",
            "签到数据仅保存在本机；不会自动修改询问时刻或日程，调整必须由你主动确认。"
        )),
        SettingsBlock.WIND_DOWN to HelpSection("睡前减速", listOf(
            "每晚按你填写的睡觉时间提前 40 分钟提醒开始收尾；关闭后不会删除已有记录。",
            "今日页在睡前时段显示减速进度，并根据明天第 1–2 节是否有课给出“注意休息”或“可稍晚收尾”的提示。",
            "熬夜时只给出低压力建议，不会安排任何任务。"
        )),
        SettingsBlock.EXPENSES to HelpSection("个人账目", listOf(
            "消费记录暂时隐藏（功能打磨期）：金额输入与账目入口不展示，已有数据保留不清除，其余餐食功能不受影响。"
        )),
        SettingsBlock.BASELINE to HelpSection("习惯基线", listOf(
            "2–3 分钟填好大致作息与餐点；只有你确认过的数据才会用于后续学习。",
            "原始事件按时间追加保存，不会因学习而覆盖；你可以随时查看、修正或重建。",
            "假期与上学节奏分开保存，后续学习按“生活阶段 × 星期 × 餐次”进行。"
        )),
        SettingsBlock.MEAL_LEARNING to HelpSection("吃饭提醒", listOf(
            "饭点提醒默认关闭；开启后在预计饭点提醒你好好吃饭，只有你确认开始的时间才会用于饭点学习。",
            "用餐结束询问是独立且默认关闭的开关；只有开启后才追问“吃完了吗”并学习用餐时长。关闭它不会关闭饭点提醒。",
            "完成习惯基线引导后，这里会按“生活阶段 × 星期 × 餐次”展示学到的饭点；数据不足时只用宽松提醒，不会假装精确预测。",
            "提醒按星期分组学习；假期和上学分开，避免互相影响。",
            "关闭饭点总开关会撤销饭点和用餐结束提醒，但不会删除已有记录。评价等可选内容仅保存在本机。"
        )),
        SettingsBlock.COMMUTE_PLACES to HelpSection("通勤与地点", listOf(
            "校园生活：控制校内出行、地点包和手动位置工具；关闭不会删除已有数据。",
            "地点与空挡：地点只在安排课程空档时用于估计去图书馆、操场或下一栋教学楼是否来得及。",
            "通勤参数：新安装默认开启，单程先按 10 分钟估计；不读取定位，也不会假装准确。可按实际体验随时改；已有设置会保留原值。",
            "电动车：电量偏低时，后续排程会避免安排需要骑车的远距离连续行程，并建议在合适时段充电。",
            "地点来源：普通使用不需要制作或提交地点文件。可选填写高德 Web 服务 key 后可搜索校园 POI 并一键加入地点列表；注意需申请“Web 服务”类型 key（手机端 SDK key 绑定应用，REST 调用会失败）。地图点选需要地图 SDK，留待后续版本。",
            "手动当前位置：仅在你选择时更新，不申请定位权限，也不会后台追踪。"
        )),
        SettingsBlock.TUTORIAL_SEARCH to HelpSection("学习路径建议", listOf(
            "可选功能：为学习目标生成 3–5 步可执行的学习路径——每步给出学什么、用什么资源（视频／文章／练习）和去 B站/知乎/慕课 搜什么关键词；不编造链接，搜到的有用内容可手动收藏到教程资料。",
            "使用你填写的硅基流动 API key；key 仅保存在本机，只发往 api.siliconflow.cn。关闭开关后“学习路径建议／教程搜索”不再发送请求；视频分析与资料总结是资料工具箱里的独立手动操作，仍会使用已保存的 key。",
            "模型可点预设快速切换（Qwen2.5-7B 免费默认 / DeepSeek-V4-Flash），也可手填其他模型 ID；模型列表变化以硅基流动文档为准。"
        )),
        SettingsBlock.AI_WEEKLY_SUMMARY to HelpSection("AI 周总结", listOf(
            "在“计划 → 本周回顾”里，每周按你的真实记录（目标完成、常见阻碍、游戏自律）生成本周 AI 复盘，与各目标的本地建议分开。",
            "使用你填写的硅基流动 API key，仅发往 api.siliconflow.cn，关闭开关或未填 key 时不会发送任何请求。",
            "与“学习路径建议”相互独立：key 留空时自动沿用学习路径建议的 key；填写独立 key 可单独管理。"
        )),
        SettingsBlock.APP_DETECTION to HelpSection("前台应用检测", listOf(
            "此功能默认关闭，配合加号 → “安排空闲活动”的游戏／视频类别使用；它只在到点时识别前台应用并增强收尾文案，不替你记录结束。",
            "需要“使用情况访问”系统特殊权限：在本页点“去系统开启”，到系统设置里允许 FocusFlow 后返回；判断只在本机完成，不上传任何数据。",
            "应用分类按本机已安装应用生成：内置常见应用归类 + 应用名自动识别，识别不对或未识别的可手动归类；分类只用于收尾提醒判断。",
            "未授权、无法识别或前台是其他应用时仍发送通用结束提醒，绝不会自动结束活动或写入推断结果；只有你点击结束才记录实际结束。可靠匹配时可在十分钟后复查一次。"
        )),
        SettingsBlock.COURSE_VISION to HelpSection("课表识别（视觉模型）", listOf(
            "课表导入通过硅基流动视觉模型识别（4.0.1 起不再内置本地 OCR，识别失败会提示原因：检查 key、模型名或网络后重试，不会出低质量兜底结果）。",
            "与教程搜索共用同一把硅基流动 API key：key 仅保存在本机，课表图片只发往 api.siliconflow.cn，关闭开关后导入课表不联网。",
            "模型可点预设按钮快速切换（Qwen3-VL-8B 免费 / 32B / 30B-A3B / PaddleOCR-VL），也可手填其他模型 ID；旧版 Qwen2.5-VL 系列已下线，保存过的旧模型名会自动迁移到新版。",
            "识别只取课表网格内的课程：课名与教室/楼名分开记录，页脚说明（如“隐藏课程信息”）不会当成课程。",
            "地点自动归到楼级：教室号不记入地点（如“紫金港东1A-302”→“东1教学楼”，含“东1B”“东1B-201”等楼座），找教室靠“教学楼进出与找教室缓冲”时间；归并后的新楼名出现在 设置→高级工具→通勤与地点→“课表识别发现的新地点”，可一键加入地点列表供后续使用。",
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
    AppDialog(
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
    AppDialog(
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
fun WelcomeIntroDialog(onDismiss: () -> Unit) = QuickStartDialog(onDismiss)

/** 首次完成习惯基线后的“后续在哪找”提示（只弹一次）。 */
@Composable
fun BaselineWhereToFindDialog(onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("生活基线已保存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("以后在 设置 → 习惯基线 查看、编辑或重建；同一生活阶段可“另存当前方案”保存多套作息并一键切换（生活模式多方案）。", style = MaterialTheme.typography.bodySmall)
                Text(ReminderRuleCopy.BASELINE_SAVED, style = MaterialTheme.typography.bodySmall)
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

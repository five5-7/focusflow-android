package com.sakata.focusflow

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// 版本路线图数据。版本演进（evolution）与 CHANGELOG.md 双源维护：
// 每次发版时同步更新本文件与 CHANGELOG.md，再于 CHANGELOG 顶部新增版本节。

enum class RoadmapStatus(val label: String) { DONE("已实现"), PLANNED("计划中"), CANDIDATE("候选") }

data class RoadmapEntry(val version: String, val title: String, val summary: String = "", val status: RoadmapStatus)

data class RoadmapVersion(val version: String, val entries: List<RoadmapEntry>)

object RoadmapData {
    /** 已实现版本演进（1.0 → 6.4.0），每版本浓缩 1–3 条，与 CHANGELOG.md 对应。 */
    val evolution: List<RoadmapVersion> = listOf(
        RoadmapVersion("6.4.0", listOf(
            RoadmapEntry("6.4.0", "复盘与恢复闭环", "今日页识别错过或反复改期的任务并提供缩小、重排和放回收集箱；周回顾展示完成率、改期、待恢复与高频改期时段", RoadmapStatus.DONE)
        )),
        RoadmapVersion("6.3.0", listOf(
            RoadmapEntry("6.3.0", "日常执行闭环", "日程任务可直接开始、改期和完成；今日页展示计划完成率、改期次数与完成记录", RoadmapStatus.DONE)
        )),
        RoadmapVersion("6.2.2", listOf(
            RoadmapEntry("6.2.2", "提前与到点双提醒", "提前预告不再消耗到点提醒；诊断分别显示两次触发，并以实际送达时间判断后台是否准时", RoadmapStatus.DONE)
        )),
        RoadmapVersion("6.2.1", listOf(
            RoadmapEntry("6.2.1", "日程提醒可靠性与诊断", "定时任务优先使用精确提醒；设置页显示下一条提醒和调度模式，并提供 1 分钟测试通知", RoadmapStatus.DONE)
        )),
        RoadmapVersion("6.2.0", listOf(
            RoadmapEntry("6.2.0", "主流程与页面结构收敛", "今日核心闭环前置、可选模块按数据出现；计划资料工具箱与设置高级工具集中低频能力", RoadmapStatus.DONE),
            RoadmapEntry("6.2.0", "目标级执行依据", "每个目标独立保存第一步、完成标准与资料；AI 只提供候选，确认后才保存", RoadmapStatus.DONE),
            RoadmapEntry("6.2.0", "兼容安全网与渐进拆分", "保留旧数据格式，拆分日程/时间轴/计划页面并补充纯逻辑回归测试", RoadmapStatus.DONE)
        )),
        RoadmapVersion("6.1.0", listOf(
            RoadmapEntry("6.1.0", "日程与饭点提醒闭环", "饭点忽略当日不重弹、过期不补发、跨日自动续排；日程提前提醒与重启恢复；通知权限和渠道设置入口", RoadmapStatus.DONE)
        )),
        RoadmapVersion("6.0.0", listOf(
            RoadmapEntry("6.0.0", "发布版清理与新人引导", "移除内置示例课程/任务，新用户从空白开始；首次启动引导（权限→习惯基线→快速入门）", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.9.5", listOf(
            RoadmapEntry("5.9.5", "色块相切＋单元测试", "去掉色块上下内边距、统一 7dp 圆角使相连色块贴合；新增 6 个纯逻辑类 JUnit 单元测试（22 断言全过）", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.9.4", listOf(
            RoadmapEntry("5.9.4", "时间轴色块相切＋活动间隔预留＋对话框滚动条", "色块圆角 7→3dp 更贴近相切；自动排计划在任务间预留 10 分钟缓冲；长对话框内容超高显示滚动条", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.9.3", listOf(
            RoadmapEntry("5.9.3", "滚动条可视＋课表进度条＋快速入门", "主要页面右侧可视滚动条；课表识别接入顶部进度条；功能简介改为快速入门并补充上手步骤", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.9.2", listOf(
            RoadmapEntry("5.9.2", "进度条上移＋视频分析放宽＋安排可删除", "进度条移到顶部避免键盘遮挡；视频分析门槛降 10 字且可只填标题+链接直接保存；日程时间轴任务/活动可删除（含前台检测会话与提醒）", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.9.1", listOf(
            RoadmapEntry("5.9.1", "活动频率个性化提醒", "安排空闲活动时按你历史单日习惯温和提醒超频（GameStats.historicalDailyMax，≥3 天样本才提示，不写死规则）", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.9", listOf(
            RoadmapEntry("5.9", "深色修复＋进度条＋分区折叠", "深色模式补齐 surfaceContainer* 色调（卡片不再发亮）；导航栏上方全局进度条（应用扫描/AI 生成）；应用分类三区折叠；添加本机应用 chips 横滑；空挡建议同日去重", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.8.2", listOf(
            RoadmapEntry("5.8.2", "空挡地点提示细化", "地点提示扩充游泳馆/实验楼/研讨室/图书馆等更具体映射，具体词优先", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.8.1", listOf(
            RoadmapEntry("5.8.1", "空挡建议完成率排序", "空挡内容建议优先推荐该时段历史完成率较高的目标（样本<3不参与），完成率高时标注", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.8", listOf(
            RoadmapEntry("5.8", "本地崩溃上报", "未捕获异常写入本机 crash.log；设置→稳定性与崩溃 查看/复制/清空，不引第三方、不上传", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.7", listOf(
            RoadmapEntry("5.7", "空挡建议进阶（精力/场景）", "空挡内容建议新增精力规律提示（历史签到时段精力，数据不足不显示）与地点提示（关键词→操场/图书馆/宿舍，仅提示不假装精确）", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.6.1", listOf(
            RoadmapEntry("5.6.1", "深色模式＋应用列表打磨", "深色模式（当前主题基础上调暗）；应用分类 chips 可横滑（“其他”不再被挤占）；应用增加「忽略」隐藏与恢复", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.6", listOf(
            RoadmapEntry("5.6", "安排空闲活动＋空挡内容建议", "「想玩游戏」泛化为「安排空闲活动」（游戏/视频/学习/休息/运动/自定义，游戏视频走前台检测，其余到点提醒收尾），统计改为活动自律；空挡页给每个≥30分钟空挡/自由时段「适合做什么」内容建议并一键排入；作息分组向导星期可横滑；应用分类补 <queries> 扫全本机应用", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.5.2", listOf(
            RoadmapEntry("5.5.2", "修复闪退/权限/分组/扫描", "常驻快速记录前台服务类型修复（Android14+）；补 PACKAGE_USAGE_STATS 使系统显示使用情况访问入口；作息分组编辑同步回当前方案不再丢失；分组向导改为每组显式选星期；应用分类列出本机全部应用", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.5.1", listOf(
            RoadmapEntry("5.5.1", "开学自动开校园＋多项修复", "切回上学自动重开校园生活；教程按钮两行排布与折叠标题同行；视频分析只依赖 key；学习路径解析再加固（键名变体＋主题兜底＋严格重试）；作息分组严格随方案切换并显示“当前”；应用分类改为按本机应用生成；前台检测说明移入帮助；开启功能时申请使用情况访问权限", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.5", listOf(
            RoadmapEntry("5.5", "一站式视频分析＋多轮打磨", "假期自动关校园生活；教程资料折叠；视频分析一站式整理（粘贴字幕→AI 要点→保存教程，模型入口同前）；游戏安排自定义时间与开始提醒可选；作息方案切换同步作息分组；应用清单显示应用名并支持添加本机应用；周回顾 AI 周总结", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.4", listOf(
            RoadmapEntry("5.4", "前台应用检测与游戏自律", "加号→「想玩游戏」：按空闲安排游戏时间，到点提醒开始并自动记录状态；到点检测前台应用（应用分类：内置清单＋应用名自动识别＋手动归类），仍在玩则提醒收尾并记录实际结束/超时；周回顾新增游戏自律统计与建议", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.3", listOf(
            RoadmapEntry("5.3", "新建目标 AI 教程查找", "「搜学习教程」替换生成学习路径按钮：手动三平台搜索＋AI 生成“去哪个平台搜什么”建议，保存即设为标准并回到目标对话框（预填目标名＋预期结果）", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.2.1", listOf(
            RoadmapEntry("5.2.1", "今日页餐点位置上移＋标准横幅可展开", "餐点卡片移到收集箱上方；「当前标准」横幅具体内容改为点击展开", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.2", listOf(
            RoadmapEntry("5.2", "教程标准可感知＋现在做什么/精力拆分", "教程标准横幅与未设依据目标可见、切换即时提示、一键把当前标准关联到未设依据目标；「现在做什么」回归活动记录（娱乐类可设收尾提醒），精力独立为今日页「当前精力」卡", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.1", listOf(
            RoadmapEntry("5.1", "空挡感知日程已有安排", "课间空挡与自由时段扣除日程里已排任务/事项的占用段（自由时段按占用切分成剩余子段显示）；目标建议、自动排与新增目标的空挡统计避开已占时段；同一次自动排内目标任务互相避让，防止重复占用", RoadmapStatus.DONE)
        )),
        RoadmapVersion("5.0", listOf(
            RoadmapEntry("5.0", "自由时段与作息分组向导", "安排纳入课后/整天的自由时段（不只课间空挡），空挡页单独列出并参与目标排入；作息分组改为向导式：设定作息→选星期→重复→按星期自动命名保存", RoadmapStatus.DONE),
            RoadmapEntry("5.0", "AI 总结防退化与教程标准可见", "AI 总结增加退化检测与严格重试（防“1111…”式输出）；“设为标准”效果可见：目标卡可一键打开教程链接", RoadmapStatus.DONE)
        )),
        RoadmapVersion("4.3", listOf(
            RoadmapEntry("4.3", "今日页行动化", "首页按“状态→安排→行动→目标→捕获→反馈”重排：生活阶段（假期/上学/考试周）一键切换置顶；新增“今天接下来”摘要卡（课程+任务按时间合并，点进日程）；精力与“下一件合适的事”合并为“现在做什么”行动卡（含自由开始）；新增“今天的目标”卡（带教程/最低版本指引，可直接开始，未安排时引导去排）；校园生活开关移至页尾", RoadmapStatus.DONE)
        )),
        RoadmapVersion("4.2", listOf(
            RoadmapEntry("4.2", "教程标准可管理＋执行生效", "教程资料可删除、取消标准、重选；搜索结果保存后自动设为“当前标准”；手动安排或自动排计划的目标任务详情自动带上教程与最低版本指引，教程真正参与执行（解决“收藏了教程但没实际效果”）", RoadmapStatus.DONE),
            RoadmapEntry("4.2", "识别表格＋本地判断自动排计划", "“按空挡自动排本周目标”：把本周未完成的目标次数排进课程空挡（本地判断避开课程与已有安排、优先更长空档），一键生成带提醒的任务并进入日程，可随时改期", RoadmapStatus.DONE)
        )),
        RoadmapVersion("4.1", listOf(
            RoadmapEntry("4.1", "生活模式多方案", "同一生活阶段下可另存多套作息方案（命名、最多 8 套），一键切换/删除；饭点与睡前减速按当前方案的阶段自动跟随", RoadmapStatus.DONE),
            RoadmapEntry("4.1", "提醒打扰控制＋常驻快速记录", "免打扰时段（可跨天）静音状态询问/饭点/睡前减速，活动到点与任务提醒保持时间敏感；一次性静音 1 小时/3 小时/到明早；通知栏常驻一条静音通知，一键快速记录到收集箱", RoadmapStatus.DONE),
            RoadmapEntry("4.1", "自动决策与习惯识别", "询问时刻按签到数据自动采纳（设置页标注“已自动调整”，手动调整后不再自动）；今日首页校园生活一键开关；电动车电量偏低时在空挡页给出充电空档建议；睡前减速结合深夜活跃/娱乐时段记录给出更贴合的建议", RoadmapStatus.DONE)
        )),
        RoadmapVersion("4.0.2", listOf(
            RoadmapEntry("4.0.2", "key 申请引导", "首次开启课表视觉模型且未填 key 时自动弹出“如何获取硅基流动 API key”三步引导（只弹一次），可一键跳转硅基流动 API 密钥页；设置页与帮助（小问号）里也有入口，降低新用户使用门槛（免费模型注册无需充值）", RoadmapStatus.DONE)
        )),
        RoadmapVersion("4.0.1", listOf(
            RoadmapEntry("4.0.1", "移除本地 OCR，导入只走视觉模型", "本地 OCR（ML Kit/Tesseract）效果差已整体移除：课表导入仅使用硅基流动视觉模型，识别失败直接提示原因（检查 key/模型名/网络），不再出低质量兜底结果；移除 OCR 依赖与 tessdata，APK 体积明显减小；未开启视觉模型时导入前给出引导", RoadmapStatus.DONE),
            RoadmapEntry("4.0.1", "楼级归并补全", "“东1B”“东1B-201”“东一B”等楼座与教室号统一归并为“东1教学楼”（含模型输出末尾省略号的截断值），与地点目录/地图命名一致", RoadmapStatus.DONE)
        )),
        RoadmapVersion("4.0", listOf(
            RoadmapEntry("4.0", "课表识别（视觉模型）", "可选开启：导入课表截图改用硅基流动视觉模型识别，预设 Qwen3-VL-8B 免费/32B/30B-A3B/PaddleOCR-VL 一键切换（也可手填），识别失败自动回退本机；与教程搜索共用同一把硅基流动 key，图片仅发往 api.siliconflow.cn；旧 Qwen2.5-VL 系列已下线，保存过的旧模型名自动迁移", RoadmapStatus.DONE),
            RoadmapEntry("4.0", "课名/地点分开与页脚过滤", "识别严格要求课名与教室/楼名分开记录；忽略页脚说明（如“隐藏课程信息”）等非课程文字；地点自动归到楼级（“东1A-302”→“东1教学楼”，找教室靠通勤缓冲），新楼名进入“课表识别发现的新地点”待用列表，可一键加入地点目录", RoadmapStatus.DONE),
            RoadmapEntry("4.0", "冲突课程提示与保留", "识别结果与已确认课程时间冲突时保留为待确认，卡片加警示边框与“⚠ 与已确认课程《xxx》冲突”标注；待确认区标题行新增“全部忽略”一键清空", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.18", listOf(
            RoadmapEntry("3.9.18", "自定义主题防撞色＋调色盘", "文字与背景对比度不足 3:1 禁止选用（色板斜杠禁用＋实时对比度提示）；色板补黑白灰阶，新增色相滑杆/饱和度明度取色/十六进制输入；内置主题卡改为“以此改色”按钮且不立即切换主题，确认后“应用此配色”才启用", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.17", listOf(
            RoadmapEntry("3.9.17", "预设可编辑＋内置主题改色另存", "预设可加载配色修改后更新；内置主题卡可“改色另存”为自定义预设，原生主题保留", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.16", listOf(
            RoadmapEntry("3.9.16", "设置副页面底栏反应", "进入设置副页面时底栏“设置”图标变 ◉ 并显示当前副页面名，与计划页副页面逻辑一致", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.15", listOf(
            RoadmapEntry("3.9.15", "自定义主题预设", "调好的配色可保存为命名预设（最多 8 套），列表一键应用、可删除；与当前配色一致的预设标注“当前配色”", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.14", listOf(
            RoadmapEntry("3.9.14", "砍掉档位，只留一套全局主题", "移除 3/4 色档位与档位切换，固定五个全局色（主色/副色/强调色/中性色/文字色）共同影响除课程色块与提醒警示外的所有界面区域；自定义主题页固定显示五个全局槽位", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.13", listOf(
            RoadmapEntry("3.9.13", "5 色档位改为全局配色", "主色/副色/强调色/中性色/文字色共同影响除课程色块与提醒警示外的所有界面区域，不再只是日程色块；外观页问号同步移到标题行", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.12", listOf(
            RoadmapEntry("3.9.12", "自定义主题页问号上移", "帮助问号移到标题行右侧，不再单独悬在内容上方", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.11", listOf(
            RoadmapEntry("3.9.11", "配色档位三档独立", "3 色档沿用原来的主色/副色/课程色；4 色档按功能语义（主操作/强调/日程/中性）；5 色档改为日程配色（课程/学习/运动/娱乐/休息）；三档互不覆盖，切换即换一套分工；提醒警示固定系统红", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.10", listOf(
            RoadmapEntry("3.9.10", "自定义主题按功能语义分工", "槽位改为：主操作色（按钮开关）/强调色（提示文字）/日程色（日程色块）/中性色（背景卡片）/警示色（提醒警示）；档位调整为 3 色常用 · 4 色加背景 · 5 色加警示", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.9", listOf(
            RoadmapEntry("3.9.9", "权限申请策略", "通知权限首次启动一站式申请（带回调自动跟进精确闹钟），申请过不再打扰；活动提醒页新增权限状态行一键跳转；ColorOS 实测精确闹钟无授权开关，自动放弃", RoadmapStatus.DONE),
            RoadmapEntry("3.9.9", "主题多色档位与自定义", "配色档位 3/4/5 色可切换；自定义主题按功能语义分工（主操作/强调/日程/中性/警示），从 30 色预设色板点选，立即全局生效并记住；内置四主题均新增强调色", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.8", listOf(
            RoadmapEntry("3.9.8", "提醒通知横幅化", "全部提醒升级为高重要性渠道：横幅弹出几秒＋声音（像微信）；温和版到点提醒为静音横幅；旧渠道自动删除", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.7", listOf(
            RoadmapEntry("3.9.7", "设置页枢纽化续", "外观（主题选择）与活动提醒（开关、提前预告与延长上限）同样改为枢纽卡片＋副页面，与计划页一致；设置主页只保留短分区与枢纽卡片", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.6", listOf(
            RoadmapEntry("3.9.6", "设置页枢纽化", "通勤与地点、教程联网搜索改为与计划页一致的枢纽卡片＋副页面（标题＋摘要＋箭头），副页面内保留问号帮助；设置主页大幅缩短", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.5", listOf(
            RoadmapEntry("3.9.5", "箭头图标与对齐修复", "设置页与版本路线图箭头统一换为 Material 矢量图标，修复文字与箭头垂直对齐问题", RoadmapStatus.DONE),
            RoadmapEntry("3.9.5", "教程搜索模型修复", "默认模型改为 Qwen/Qwen2.5-7B-Instruct（旧默认已下线）；已保存旧默认自动迁移，模型类错误提示附更换引导", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.4", listOf(
            RoadmapEntry("3.9.4", "设置折叠分区样式统一", "去掉独立卡片背景改为扁平标题行；问号紧贴标题、摘要与统一箭头“›”置右；消除色差与仅两块折叠的突兀感", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.3", listOf(
            RoadmapEntry("3.9.3", "设置折叠卡片重做", "通勤与地点、教程联网搜索整块收进圆角卡片，点击头部带涟漪反馈与旋转箭头，内容平滑展开收起；收起时只占一行", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9.2", listOf(
            RoadmapEntry("3.9.2", "移除后台通勤记录", "评估后砍掉：方式/时段不区分导致学习数据质量差；保留手动“记录本次耗时”路线学习，应用不再申请定位权限", RoadmapStatus.DONE),
            RoadmapEntry("3.9.2", "设置页折叠与滚动", "通勤与地点、教程联网搜索两块默认收起；返回子页面/切页后保留滚动位置；校园地点分区新增“其他”", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.9", listOf(
            RoadmapEntry("3.9", "校园地点三态", "内置目录 + 本地自定义增删改（可带坐标）+ 可选高德 Web 服务搜索；外部地点包作 DLC", RoadmapStatus.DONE),
            RoadmapEntry("3.9", "后台通勤记录", "主动开始时前台服务低频采样定位（约 60 秒/100 米），通知可见可随时停止；未授权自动降级纯计时", RoadmapStatus.DONE),
            RoadmapEntry("3.9", "教程联网搜索", "可选填硅基流动 key 联网搜集教程并比较候选来源；无 key 时功能禁用", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.8.4", listOf(
            RoadmapEntry("3.8.4", "明日准备", "减速阶段按明日具体安排生成摘要：最早课程、明日任务与待整理项", RoadmapStatus.DONE),
            RoadmapEntry("3.8.4", "冲突条纹警示", "冲突课程色块去边框改斜向条纹；时间块按真实分钟紧贴、圆角区分相邻安排", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.8.2", listOf(
            RoadmapEntry("3.8.2", "冲突课程单一色块", "重叠课程合并为一个覆盖冲突区间的黄框红底色块，不再并排显示", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.8.1", listOf(
            RoadmapEntry("3.8.1", "课程冲突显示优化", "日程冲突课程统一黄底深红警示色块；课程列表单独列出冲突并标注重叠对象；计划页显示感叹号", RoadmapStatus.DONE),
            RoadmapEntry("3.8.1", "空档碎片时间", "不足 10 分钟的空档单独列为碎片时间，与可安排空档区分", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.8", listOf(
            RoadmapEntry("3.8", "每周目标回顾", "本周进度条、近 4 周完成趋势与低压力调整建议", RoadmapStatus.DONE),
            RoadmapEntry("3.8", "睡前减速", "每晚按睡觉锚点提前 40 分钟提醒；今日页减速提示与明日早课感知", RoadmapStatus.DONE),
            RoadmapEntry("3.8", "长期反馈分析", "积累 5 条反馈后给出最常见阻碍、难度分布与调整建议", RoadmapStatus.DONE),
            RoadmapEntry("3.8", "个人账目分析", "统计手填消费草稿的合计、本月支出、分类与常去地点", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.7", listOf(
            RoadmapEntry("3.7", "签到建议", "按上午／下午／晚上汇总精力规律，给出当前时段建议；数据不足不提示", RoadmapStatus.DONE),
            RoadmapEntry("3.7", "状态询问时间建议", "按签到时间分布建议每日询问时刻，可一键采纳", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.6", listOf(
            RoadmapEntry("3.6", "课表识别不依赖 Google 服务", "无 Google Play 服务的设备改用随包携带的本地识别模型，课程名称、星期与节次照常解析为待确认课程", RoadmapStatus.DONE),
            RoadmapEntry("3.6", "课表导入防冲突", "导入识别结果与已确认课程时间冲突时拦截并列出冲突明细；时间表冲突课程以警示边框标出", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.5", listOf(
            RoadmapEntry("3.5", "版本路线图副页面", "设置页入口进入，展示当前版本、版本演进与后续候选", RoadmapStatus.DONE),
            RoadmapEntry("3.5", "全应用帮助问号", "今日、日程、计划、设置页的功能说明收敛到圆形问号入口", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.4", listOf(
            RoadmapEntry("3.4", "用餐消费草稿", "结束用餐可记录地点、分类、商家、支付方式等字段，仅保存在本机，为后续个人账目分析预留", RoadmapStatus.DONE),
            RoadmapEntry("3.4", "常去地点提示", "今日餐点卡显示每餐“常去／上次在”的地点提示", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.3", listOf(
            RoadmapEntry("3.3", "饭点提醒流程", "今日餐点卡片与“准备吃饭？／吃完了吗？”提醒流程", RoadmapStatus.DONE),
            RoadmapEntry("3.3", "饭点学习", "只用你确认的时间，按“生活阶段 × 星期 × 餐次”取中位数；数据不足时明确标注，不假装精确", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.2", listOf(
            RoadmapEntry("3.2", "习惯基线引导", "约 2–3 分钟、可跳过的引导：生活阶段、作息、餐点与娱乐时段", RoadmapStatus.DONE),
            RoadmapEntry("3.2", "原始事件记录", "事件按时间追加保存、不因学习覆盖，可查看、修正或重建；假期与上学分开保存", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.1", listOf(
            RoadmapEntry("3.1", "通勤学习", "改为多条确认记录、中位数更新，保留最近 12 次；可撤销最近记录或清除整条路线学习", RoadmapStatus.DONE),
            RoadmapEntry("3.1", "路线预览", "显示确认次数与最近五次耗时，旧版单次校正值自动迁移", RoadmapStatus.DONE)
        )),
        RoadmapVersion("3.0", listOf(
            RoadmapEntry("3.0", "可解释的活动时长建议", "按历史中位数、精力与下一项固定安排给出建议时长与预计结束时刻，保留 15 分钟缓冲", RoadmapStatus.DONE),
            RoadmapEntry("3.0", "建议不自动生效", "只提供“采用建议时间”按钮，不会自动开始活动", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.9", listOf(
            RoadmapEntry("2.9", "低打扰状态询问", "可关闭的每日询问，记录精力与当前活动；未回应当天不追问、活动中自动退避", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.8", listOf(
            RoadmapEntry("2.8", "收集箱灵活安排", "推荐空档、大致时间范围与精确时间三种方式，弹性范围独立保存", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.7", listOf(
            RoadmapEntry("2.7", "下一件合适的事可执行", "从推荐直接开始、改期或完成；提供 5–15 分钟“最低版本”入口，推荐理由可解释", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.6", listOf(
            RoadmapEntry("2.6", "课表截图 OCR", "设备端识别课程文字，只生成待确认课程，可编辑并确认", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.5", listOf(
            RoadmapEntry("2.5", "弹性任务初步安排", "未来七天最多三个候选时间，固定安排前后保留 15 分钟缓冲", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.4", listOf(
            RoadmapEntry("2.4", "精力状态", "今日页本地精力状态，偏低优先短任务；固定日程不被改写", RoadmapStatus.DONE),
            RoadmapEntry("2.4", "地点自动提供优先", "紫金港用户无需制作地点包，JSON 导入收进高级入口", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.3", listOf(
            RoadmapEntry("2.3", "收集箱摘要与副页面", "今日收集箱改为摘要加副页面入口，转场与计划副页面一致", RoadmapStatus.DONE),
            RoadmapEntry("2.3", "地点包问号说明", "地点包标题旁圆形问号，说明 JSON 结构与导入步骤", RoadmapStatus.DONE),
            RoadmapEntry("2.3", "路线预览校正", "记录或更新实际总耗时，可按出行方式恢复初始估计", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.2", listOf(
            RoadmapEntry("2.2", "校园生活开关", "总开关控制校内出行、地点包与手动位置工具，关闭不删数据", RoadmapStatus.DONE),
            RoadmapEntry("2.2", "地点包导入", "经过校验的 JSON 地点包导入，地点参与课程空档与路程估算", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.1", listOf(
            RoadmapEntry("2.1", "可解释推荐", "“下一件合适的事”依次检查错过的任务、临近固定安排与可完成的弹性任务，并显示选择原因", RoadmapStatus.DONE)
        )),
        RoadmapVersion("2.0", listOf(
            RoadmapEntry("2.0", "V2 活动模式", "开始前约定结束时间与下一步，支持倒计时、提前预告与明确的到点转场", RoadmapStatus.DONE),
            RoadmapEntry("2.0", "四套主题", "海盐蓝、薄荷绿、暖杏与暮紫，页面、导航、卡片、控件与日程色块整体换色", RoadmapStatus.DONE),
            RoadmapEntry("2.0", "提醒恢复", "活动与提醒状态本地保存，应用启动或设备重启后自动恢复", RoadmapStatus.DONE)
        )),
        RoadmapVersion("1.8", listOf(
            RoadmapEntry("1.8", "信息架构收敛", "移除时刻红线；计划页滑入淡出转场，副页面无返回箭头、系统返回返回目录", RoadmapStatus.DONE)
        )),
        RoadmapVersion("1.7", listOf(
            RoadmapEntry("1.7", "四入口导航", "底部导航改为今日、日程、计划、设置；日程独立页（日／周时间轴）", RoadmapStatus.DONE),
            RoadmapEntry("1.7", "计划模块目录", "课程、空挡建议、目标与执行、本周回顾、暂停项目各进副页面", RoadmapStatus.DONE)
        )),
        RoadmapVersion("1.6", listOf(
            RoadmapEntry("1.6", "修复加号遮挡", "修复底部中央加号容器撑满屏幕、导致内容不可见的问题", RoadmapStatus.DONE)
        )),
        RoadmapVersion("1.5", listOf(
            RoadmapEntry("1.5", "时间轴扩展", "日、周时间轴扩展为 06:00–24:00；周日程与日日程同宽", RoadmapStatus.DONE)
        )),
        RoadmapVersion("1.4", listOf(
            RoadmapEntry("1.4", "计划页堆叠", "课程与空档堆叠展示；周日程色块收窄并可显示课程信息；已完成任务灰化保留", RoadmapStatus.DONE)
        )),
        RoadmapVersion("1.3", listOf(
            RoadmapEntry("1.3", "真实时间轴", "日／周切换与 08:00–22:00 连续时间轴，色块上下界对应真实起止时间", RoadmapStatus.DONE),
            RoadmapEntry("1.3", "每周多次目标逐次安排", "每次生成独立任务并可分别完成或改期", RoadmapStatus.DONE),
            RoadmapEntry("1.3", "可覆盖安装", "Actions 改用固定签名并给 APK 加入版本号，后续版本可直接覆盖安装", RoadmapStatus.DONE)
        )),
        RoadmapVersion("1.2", listOf(
            RoadmapEntry("1.2", "标签与颜色修正", "弹性任务按实际类型显示标签与颜色；随机任务 ID 修复重复完成问题", RoadmapStatus.DONE)
        )),
        RoadmapVersion("1.1", listOf(
            RoadmapEntry("1.1", "今日页按小时日程", "始终可见的按小时日程表；收集箱提供安排、编辑、删除三个直接操作", RoadmapStatus.DONE)
        )),
        RoadmapVersion("1.0", listOf(
            RoadmapEntry("1.0", "周日程表与分类配色", "课程、学习、任务、运动、娱乐与休息使用不同颜色", RoadmapStatus.DONE),
            RoadmapEntry("1.0", "目标最低版本", "新增目标先填预期结果，提供可执行的最低版本建议", RoadmapStatus.DONE),
            RoadmapEntry("1.0", "完成统计", "已完成任务记录完成时间，显示今日与本周完成统计", RoadmapStatus.DONE)
        ))
    )

    /** 后续候选（“想玩游戏拓展”“自律类目标”“空挡建议进阶”已随 5.6/5.7/5.8.2 落地；正式理财已评估移除）。 */
    val future: List<RoadmapEntry> = listOf(
        RoadmapEntry("后续", "任意表格识别自动配置计划", "识别课表之外的各类表格（如锻炼计划、阅读计划）自动生成计划（用户澄清 4.4 学习机制提案后重定向，之后讨论）", RoadmapStatus.CANDIDATE),
        RoadmapEntry("后续", "高德地图 SDK 集成", "可视化地图、POI 点选、以设备定位为中心的搜索；代价：包体积增加、SDK key 绑定包名与签名、需要定位权限", RoadmapStatus.CANDIDATE),
        RoadmapEntry("后续", "云同步与多设备备份", "所有数据目前只在本机，无任何云端能力", RoadmapStatus.CANDIDATE),
        RoadmapEntry("后续", "主屏幕小组件", "今日概览／下一步等小组件（用户评估常驻通知已部分替代，暂不优先）", RoadmapStatus.CANDIDATE),
        RoadmapEntry("后续", "应用商店级稳定性（剩余）", "崩溃上报已上线（5.8）；剩余：自动化测试、商店发布流程", RoadmapStatus.CANDIDATE),
        RoadmapEntry("后续", "OPPO 真机验证", "验证通知、后台限制与重启恢复", RoadmapStatus.CANDIDATE)
    )
}

@Composable
fun RoadmapSubpageContent() {
    // 滚动由外层 PlanSubpageFrame 的内容区负责，这里只输出内容。
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前版本 ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
                Text("每次功能更新递增 0.1；更新记录见版本演进。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("版本演进（已实现 1.0 → 6.3.0）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        RoadmapData.evolution.forEach { version ->
            Text(version.version, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            version.entries.forEach { entry ->
                Text("• ${entry.title}：${entry.summary}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("后续候选", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        RoadmapData.future.forEach { entry -> RoadmapEntryRow(entry) }
    }
}

@Composable
private fun RoadmapEntryRow(entry: RoadmapEntry) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            entry.status.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.status == RoadmapStatus.PLANNED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontWeight = FontWeight.SemiBold)
            if (entry.summary.isNotBlank()) Text(entry.summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

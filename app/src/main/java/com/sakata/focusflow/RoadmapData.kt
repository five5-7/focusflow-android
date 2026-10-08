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
    /** 已实现版本演进（1.0 → 当前版本），每版本浓缩 1–3 条，与 CHANGELOG.md 对应。 */
    val evolution: List<RoadmapVersion> = listOf(
        RoadmapVersion("8.3.0", listOf(
            RoadmapEntry("8.3.0-rc.34", "快速记录输入与底栏键盘布局", "根布局不再把输入法避让传给悬浮导航栏；页面与弹窗独立避让键盘，导航栏保持在屏幕底部。versionCode 568，基于 rc.33 的冻结候选，旧识别方案与 fail-closed 门禁不变。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.33", "阶段9：候选冻结与真机边界收敛", "完成 rc.33 工程门禁与同机复测记录；真实课表识别仍失败，按维护者决定暂缓新识别链路，保留旧版本实现与 fail-closed 门禁。versionCode 567，Run 573基线通过。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.32", "阶段9：真实课表结果提取修复", "兼容候选数组、思考/分析段和 JSON 后尾随说明；多个并列完整结果仍拒绝，空间范围、网格一致性与人工审核门禁不变。versionCode 566，基于 rc.31 的 stage=format 同机复测反馈，待安装 Run 566 复测，Room激活关闭，未发布。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.31", "阶段9：真实课表响应归一化", "兼容模型附加字段、数字字符串、像素/数组坐标框和可选字段缺失；坐标范围与网格一致性仍经校验，缺少空间依据进入人工审核。沿用 rc.30 的 JSON 外围格式兼容；versionCode 565，Run 566工程门禁通过，已由 rc.32 替代，Room激活关闭，未发布。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.30", "阶段9：真机截图识别格式兼容", "兼容视觉模型 JSON 外围解释、代码围栏和尾逗号；缺失空间依据仍进入人工审核，不自动补星期或节次。沿用 rc.29 的可选字段审核修复；versionCode 564，已由 rc.31 替代，Room激活关闭，未发布。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.27", "阶段9：快速入门弹窗材质修复", "章节内容不再重复铺页面图片或渐变，沿用统一弹窗材质，保留动画与各章滚动位置。versionCode 561，已由rc.28替代，工程门禁与真机验收未完成，Room激活关闭，未发布。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.26", "阶段8 A：可配置视觉服务", "视觉服务配置、加密共享key、能力测试、明确上传目标和可取消识别。新网格管线待后续批次，阶段8真机与在线服务未验证，Room激活关闭，未发布。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.25", "阶段7：持久恢复与整批撤回", "30天回收站、重复规则与计划所选任务成组恢复、课程设置恢复、批量待办和课程合并／拆分持久撤回。真机按用户指示跳过，Room激活关闭，未发布。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.24", "阶段5：活动与今日页六个检查点", "待办关联计时及历史、单活动保护、重复规则停止与恢复性删除、今日页分组和明天预览。阶段5后续功能仍在实施，Room激活关闭。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.23", "阶段4：收集箱、待办与计划闭环", "收集箱批量整理、待办与计划、每日／每周重复基础、独立提醒和可恢复删除。Room产品激活关闭，待稳定签名CI与OPPO合并验收。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.3.0-rc.22", "收集箱记录分组与紧凑整理", "完整列表按创建事件区分最近、之前和时间未标记的旧记录；待整理项默认单行，点击只展开一项，编辑删除放入更多。延续rc.21快速输入，已由rc.23替代，未单独验收。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.21", "今日收集箱快速输入", "今日页收集箱共用一个卡面，直接输入标题并保存；显示总数与最近两条单行摘要，点击进入完整列表。Room产品激活关闭；已由rc.22代替，未单独验收。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.20", "连续课程与收纳摘要", "相邻同名同地点课程节次合并展示，原段可逐一编辑；周五至周日无节次重叠时仍显示课程摘要。课程写入经过统一数据源；Run 462与用户验收通过，已由rc.21替代。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.19", "课程确认与课表位置", "无冲突课程时段支持一键确认，同名时段按星期和节次汇集展示；缩小课表的周五至周日展开显示真实位置。安排时间模式选项适应宽度和字体。Run 460与用户验收通过，已由rc.20替代。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.18", "指定教务学期与超时修复", "浙大教务导入可指定学年与学期，跳过当前学期页面；自动读取学期设总时限并提示失败。Run 459 与 OPPO 教务导入验收通过，已由rc.19替代。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.17", "外观页收纳与五项材质", "外观页将主题配色、页面背景、卡片材质与课表底色分组收纳；卡片材质五档使用均衡排列，自定义主题编辑器共用同一套外观控件。Run 456通过；在单独真机验收前由rc.18替代。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.16", "历史批量选择交互", "最近事件的批量选择工具栏共用卡片材质；勾选区、操作按钮与选中态按动画速度过渡。延续 rc.15 的设置布局，已通过 OPPO 真机验收，已由 rc.17 替代。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.15", "设置等权选项布局", "页面背景与动画速度的四项选择改用自适应均衡布局，常见手机宽度 2×2，大字体与窄屏回流。延续 rc.14 的导入页外观与统一确认框，已通过 OPPO 真机验收。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.14", "独立入口外观对齐", "今日页权限提示确认改用统一弹窗；浙江大学教务导入页的背景与卡片跟随外观。延续 rc.13 的玻璃卡面不透明度及课表识别防错，已通过 OPPO 真机验收。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.13", "玻璃卡面不透明度", "延续 rc.12 的课表识别防错；外观设置可统一调节亚克力与毛玻璃卡面不透明度 40%–95%，旧设置仍保留 60%／48% 默认值。已通过 OPPO 真机验收。", RoadmapStatus.DONE),
            RoadmapEntry("8.3.0-rc.12", "课表截图识别防错", "延续权限中心、浙大课表自动导入、首次动画优化与验收反馈修复；截图识别严格校验网格中的星期和节次，拒绝多数坐标缺失或明显塌缩的整批结果，完全重叠的不同课程必须逐门编辑后确认。已由 rc.13 替代。", RoadmapStatus.DONE)
        )),
        RoadmapVersion("8.2.2", listOf(
            RoadmapEntry("8.2.2-rc.4", "通勤、文字对比与权限提醒", "三种校内出行方式可分别设置预留；玻璃卡片深色文字对比修复；今日页始终显示权限入口，关闭后可在设置中查看和恢复。", RoadmapStatus.CANDIDATE)
        )),
        RoadmapVersion("8.2.1", listOf(
            RoadmapEntry("8.2.1-rc.7", "亚克力与毛玻璃扩展", "修复玻璃卡片层级冲突与滚动回归；丰富外观默认关闭。", RoadmapStatus.CANDIDATE)
        )),
        RoadmapVersion("8.2.0", listOf(
            RoadmapEntry("8.2.0", "可选外观系统", "页面背景四档（跟随主题／主题渐变／固定颜色／自选图片，图片可调不透明度且只存本机）；渐变可自选「顶色→底色」配色与强度，并可「跟随内容」把跨度拉长到数屏、让竖向变化更缓；卡片材质（默认／渐变／柔光，抽成统一卡片组件并收编 32 处调用点）；课表与日程表底色可单独设置（跟随主题／选颜色／图片／透明，只改底板，课程块与日程块颜色不动）；新增石墨／樱粉／青竹三套主题（原有四套取值冻结并有金值回归测试）；从图片自动抽取主题色生成一整套自定义主题；自定义主题工具升级：顶部实时预览（走应用同一套渲染）、背景与卡片外观直接在里面调（与「设置 → 外观」同一批控件）、对比度体检当场标红、预设可连整套外观一起存（老预设只存配色、行为不变）、恢复默认连外观一并复位；以上全部适配深色模式（渐变另配一套幅度、自选浅色压到深色底）。默认外观与 8.1.1 逐像素一致（同机 A/B 实测 meanAbsDiff 0.37）。", RoadmapStatus.DONE)
        )),
        RoadmapVersion("8.1.1", listOf(
            RoadmapEntry("8.1.1", "上一步／下一步与弹窗的整体语义", "打开弹窗本身不再算一步历史：「副页 + 弹窗 + 已填数据」是同一个整体。开着弹窗按上一步＝回到上一个页面；从这里跳到别处（管理地点／其他页／本页签主页）再按上一步，副页、弹窗和里面的内容一起回来；用户自己关掉弹窗后，历史里的整体快照降级为“只有页面”，不会多退一步。", RoadmapStatus.DONE),
            RoadmapEntry("8.1.1", "弹窗柔光与页面分层", "弹窗卡片改为主题染色的柔和阴影 + 顶面高光 + 细描边，明显浮在压暗的页面之上；浅色模式页面底色比卡片压深 5%，四套主题的卡片—页面分层一眼可见，正文对比度仍为 13.9–14.2:1。", RoadmapStatus.DONE)
        )),
        RoadmapVersion("8.1.0", listOf(
            RoadmapEntry("8.1.0", "统一动效规范与深度缩放转场", "把散落的时长与缓动收拢为 MotionSpec（进入 240ms／退出 170ms／位移 260ms／底栏形变 260ms），全部经「设置 → 外观 → 动画速度」换算，关闭时瞬时完成；副页改为从底栏图标深度缩放进出，主页之间左右平移；底栏选中底色改为会平移的色块、进入子页时显示圆环；弹窗改为页内浮层（打开时底栏仍可点，卡片上下平移进出，点入口／上一步或返回键先关弹窗，键盘弹出自动上移）；深链／通知直接打开子页照常从图标放大；转场判定抽为 TabMotionRules 并补单测；修子页转场每帧重组导致的掉帧、离开子页后点击被吞、横屏弹窗按钮被底栏盖住、退出提示时长与冷启动返回兜底；应用锁定竖屏。", RoadmapStatus.CANDIDATE),
            RoadmapEntry("8.1.0-rc.5", "退出确认与页面历史", "根页面返回先提示、提示期内再次返回才退出（非今日页先回今日主页）；回退／折返为底栏两端圆瓣形变，长按回退键弹会话历史列表；历史规则 v3（主页↔主页不记、工作过的主页可回）；弹窗草稿自动恢复、新建弹窗可清空；检查更新只接受 GitHub 正式版（可勾选稳定签名候选）。", RoadmapStatus.CANDIDATE)
        )),
        RoadmapVersion("8.0.0", listOf(
            RoadmapEntry("8.0.0", "第三个正式版", "集中整理今日状态、收集箱、课表与课程、通勤、空挡、目标和历史，并修复计划页闪退、任务状态被旧页面覆盖及过去日程占用未来空挡的问题。", RoadmapStatus.DONE),
            RoadmapEntry("8.0.0-rc.4", "过去的安排不再占用未来", "空挡按今天起的七天计算：昨天的任务不会继续挡住下周同一天，今天已经过去的时间也不会再次推荐；跨过午夜仍在进行的安排照常保留。", RoadmapStatus.DONE)
        )),
        RoadmapVersion("7.12.0", listOf(
            RoadmapEntry("7.12.0-rc.3", "数据管理与课程／空挡界面修整", "已并入 8.0：课程批量管理、建议零结果说明、七天纵向空挡图与历史删除统计重算。", RoadmapStatus.DONE)
        )),
        RoadmapVersion("7.11.0", listOf(
            RoadmapEntry("7.11.0-rc.1", "课程、空挡与通勤完善", "已并入 8.0：课程生效期、启停、删除、空挡分类与通勤档位。", RoadmapStatus.DONE)
        )),
        RoadmapVersion("7.10.0", listOf(
            RoadmapEntry("7.10.0-rc.1", "校园生活功能边界与首次引导", "已并入 8.0：校园生活开关、首次选择、地点空状态与电动车电量。", RoadmapStatus.DONE)
        )),
        RoadmapVersion("7.9.0", listOf(
            RoadmapEntry("7.9.0-rc.4", "今日状态顶部聚合", "已并入 8.0：生活阶段、精力、校园生活和出行方式统一为顶部状态入口。", RoadmapStatus.DONE),
            RoadmapEntry("7.9.0-rc.3", "通勤块符号修正", "已并入 8.0：修正通勤块低高度视觉符号。", RoadmapStatus.DONE),
            RoadmapEntry("7.9.0-rc.2", "统一设计语言第二轮与收集箱分类入口", "已并入 8.0：收集箱筛选、通勤视觉和组件层级统一。", RoadmapStatus.DONE),
            RoadmapEntry("7.9.0-rc.1", "统一设计语言第一轮：文本减负", "已并入 8.0：完成文本减负与基础视觉规范。", RoadmapStatus.DONE)
        )),
        RoadmapVersion("7.8.0", listOf(
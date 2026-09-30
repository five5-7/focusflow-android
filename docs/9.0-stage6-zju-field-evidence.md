# 阶段 6 组① 交付物：ZJU 教务字段证据审计（只读）

- 日期：2026-09-27
- 任务来源：`docs/9.0-stage6-course-identity-design.md` 第 5 节“交给 DeepSeek Flash 的首项独立任务”
- 仓库与状态：`D:\focusflow\focusflow-9.0`；分支 `handoff/stage6-local`；审计时 HEAD `bc30da8`，工作区干净
- 设计基线 `818bd74`（2026-09-26 01:33，“Add external AI tool routing to handoff”，是 `779d013` 的父提交）已在本仓库历史中，`818bd74..HEAD` 共 17 个提交。对 `ZjuTimetable{Client,Parser,ImportActivity}.kt`、`CourseImport.kt`、`MainActivity.kt` 与 3 个 Zju 测试执行 `git diff --stat 818bd74..HEAD -- <上述路径>`：仅 `MainActivity.kt` 有 1 处与本题无关的签名修复（`TodayScreen` 参数，+1/−7）；其余文件自基线起未改动，设计审定的代码事实仍成立
- 边界声明：本文件只是只读审计与操作说明，**不是**实现、测试通过或真机验收；未修改业务代码、Room schema、版本号；未打开 Room 产品激活；**本会话未执行真实教务采样**（无账号、无个人数据、未接触任何原始响应）

## 1. 已实证字段

### 1.1 生产代码实际读取/使用的键（代码证据）

来源：`ZjuTimetableParser.kt`、`ZjuTimetableClient.kt`、`ZjuTimetableImportActivity.kt`、`CourseImport.kt`、`MainActivity.kt`。

| 键/结构 | 类型假设 | 用途与容错 | 代码位置 |
| --- | --- | --- | --- |
| `kbList` | 数组 | 缺少时整批失败 | `ZjuTimetableParser.kt:40-41` |
| `captcha_error` | 布尔或字符串 `"true"` | 任一成立即提示验证码 | `ZjuTimetableParser.kt:37-38` |
| `kcb` | 字符串（含 `<br>`、以 `zwf` 结尾的片段） | 按 `<br>` 切段、`substringBefore("zwf")` 截断；段 0=标题、段 1=周次文本、段 2=教师（不存储）、段 3=地点 | `ZjuTimetableParser.kt:53,110-114` |
| `xqj` | 数字文本 | 星期，`1..7` 之外整行无效 | `ZjuTimetableParser.kt:54,67` |
| `jcs` | 含数字的文本（如“第9-10节”） | 取全部整数作节次候选 | `ZjuTimetableParser.kt:55` |
| `djj` | 数字文本 | 起始节；缺失时回退 `jcs` 首值 | `ZjuTimetableParser.kt:56` |
| `skcd` | 数字文本 | 节数；`start+count-1` 得结束节（上限 20） | `ZjuTimetableParser.kt:57-63,84` |
| `kcmc`、`kcm` | 字符串 | 标题首选 `kcmc`，其次 `kcm`，再回退 `kcb` 段 0 | `ZjuTimetableParser.kt:64` |
| `cdmc`、`jxdd` | 字符串 | 地点首选 `cdmc`，其次 `jxdd`，再回退 `kcb` 段 3 | `ZjuTimetableParser.kt:65` |
| `dsz` | 字符串 `"0"`/`"1"` | 单双周提示；`0`/`1`、含“单周/双周”或逗号都计入非整周行 | `ZjuTimetableParser.kt:73-78` |
| `modulus`、`exponent` | 十六进制字符串 | CAS `getPubKey` 响应；任一缺失停止登录 | `ZjuTimetableClient.kt:120-127` |
| CAS 表单 `input` | name/type/value | 动态保留隐藏字段；username、password（type 或 name 匹配 pwd/pass/credential/encrypt）、rememberMe（checkbox）特殊处理；缺省补 `_eventId=submit` | `ZjuTimetableClient.kt:225-263` |
| 课表查询请求表单 | 文本 | `xnm`=学年代码、`xqm`=学期代码、`xqmmc`=学期显示、`xxqf=0`、`xsfs=0`、`captcha_value=""` | `ZjuTimetableClient.kt:196-213` |
| 学年代码/显示 | `<select id="xnm">`/`name="xnm"` 的 option | `value` 作请求代码，`text` 作显示（`schoolYear`）；优先 `selected`，否则首个 | `ZjuTimetableClient.kt:187-189,265-281` |
| 学期代码/显示 | `<select id="xqm">`/`name="xqm"` 的 option | `value` 作请求代码；`termDisplay = value.substringAfter('|', text)`，`ifBlank` 用 `text` | `ZjuTimetableClient.kt:189-192` |
| `CourseImportBatch` | 数据类 | 仅 `source/courses/newPlaces/warnings`，**无学年、学期、教学班、课次来源字段** | `CourseImport.kt:16-21` |

补充代码事实：

- `firstText` 取首个“非空白且不等于 `null`”的值；`firstInteger` 用 `\d+` 抓首个整数（`ZjuTimetableParser.kt:103-108`）。
- 去重与同步仍是旧启发式：`CourseImportPolicy.prepare` 按 `(weekday,startPeriod,endPeriod,title)` `distinctBy`（`CourseImport.kt:40`）；`syncSchoolCourses` 按标题＋星期/节次匹配（`CourseImport.kt:87-133`）；浙江大学来源走 `syncSchoolCourses`（`MainActivity.kt:750-757`）。
- 导入 Activity 只透传 `payload`、`schoolYear`、`semester` 三个 extra（`ZjuTimetableImportActivity.kt:186-193`）；`MainActivity` 只读取 `EXTRA_TIMETABLE_PAYLOAD`（`MainActivity.kt:821`），`EXTRA_SCHOOL_YEAR`/`EXTRA_SEMESTER` 在 `app/src` 无任何读取点。

### 1.2 仅由合成 fixture 证明的键（测试证据，不代表真实响应）

以下测试全部使用内联自造 JSON/HTML，**不能证明真实教务响应存在同名字段或取值格式**：

| 键/取值 | 出处 | 说明 |
| --- | --- | --- |
| `xqj`、`djj`、`skcd`、`dsz`、`kcb` | `ZjuTimetableParserTest.kt:10,26,46` | `kcb` 形状如 `数据结构<br>1-16周<br>张老师<br>紫金港东1A-302zwf` |
| `kcmc`、`cdmc`、`jcs` | `ZjuTimetableParserTest.kt:26` | `jcs="第9-10节"`、`dsz="0"` |
| `captcha_error`、缺 `kbList` | `ZjuTimetableParserTest.kt:36-37` | 失败分支 |
| `<select id="xnm">` 值 `2026-2027`、`<select name='xqm'>` 值 `1|秋`/`1|冬` | `ZjuTimetableClientTest.kt:43-46` | 真实 option 值格式**未知**；`|` 分隔是代码契约，不是取证结论 |
| CAS 表单 `execution`、`username`、`password`、`rememberMe` | `ZjuTimetableClientTest.kt:11-18` | 合成 HTML |
| `ZjuTerm` 代码 `3/12/16`、`yearCode="2026"`、`yearDisplay="2026-2027"` | `ZjuTimetableClient.kt:43-52`；`ZjuTimetableClientTest.kt:52-60` | 手动路径的硬编码常量；测试只是断言常量本身 |

### 1.3 仓库内确认不存在的字段引用

对 `app/src` 全文搜索 `jxb`、`jxbmc`、`kch_id`、`教学班`、`externalTeachingClass`、`sourceRowIndex`：**0 命中**。即设计第 2 节列出的候选稳定标识（教学班号、课程号、每课次标识、来源行号）目前在实现、测试、样例中都不存在，也没有任何真实行样本可推断其真实键名与稳定性。

## 2. 学年/学期代码与显示值流转

| 环节 | 自动读取 | 指定学期 |
| --- | --- | --- |
| 来源 | 课表查询首页 `xskbcx_cxXskbcxIndex.html?gnmkdm=N253508` 的 `xnm`/`xqm` 下拉 | 用户输入学年起始年＋`ZjuTerm` 三选一 |
| 请求代码 | `year.value`、`term.value` | `ZjuManualSemester.yearCode`（`startYear.toString()`）、`ZjuTerm.code`（3/12/16） |
| 显示值 | `year.text`；`termDisplay = term.value.substringAfter('|', term.text)` | `yearDisplay = "2026-2027"`；`term.display`（秋冬/春夏/短学期） |
| 请求使用 | `xnm=year.value`、`xqm=term.value`、`xqmmc=termDisplay` | 同左（跳过首页读取） |
| 返回 | `Success(payload, year.text, termDisplay)` | 同左 |
| 落库 | **不落库**：三个 extra 中仅 payload 被 `MainActivity` 读取；`CourseImportBatch` 无对应字段 | 同左 |

结论：`schoolYearCode`/`termCode` 在客户端内部可得（自动路径来自原始 option `value`；指定路径来自硬编码/用户输入），但当前**没有进入导入数据契约**；设计第 2 节要求的“整个批次内一致的原始学年/学期代码”需要新增传递与持久化路径，且真实 `xnm`/`xqm` 值格式尚未取证。

## 3. 缺失的真实数据证据（对照设计第 2–4 节）

| 设计依赖 | 现有证据 | 是否足以定稿 |
| --- | --- | --- |
| `externalTeachingClassId`（教学班稳定标识） | 无真实样本、仓库无字段引用 | **否**，阻断自动聚合键 |
| `externalCourseCode`（课程号佐证） | 同上 | **否** |
| `externalMeetingId`（每课次稳定标识） | 同上 | **否** |
| `sourceRowIndex`/`sourceOrder` 稳定性 | 同上（代码里连行索引都未保留） | **否** |
| 两次导入字段稳定性、同名异班、缺号/冲突样本 | 无 | **否**，无法验证“稳定” |
| `kbList` 行完整键集合、类型、空值比例 | 未知；仅解析器使用过的 8 个键 | **否** |
| 真实 `xnm`/`xqm` option 值格式与选中逻辑 | 仅合成 fixture（`2026-2027`、`1|冬`） | **否** |
| `dsz` 语义、周次真实表达（连续/单双/不连续） | 仅代码判断，无真实样本 | **否** |
| `kcb` 分段顺序与 `zwf` 哨兵的真实含义 | 仅代码与合成 fixture | **否** |

**审计结论**：现有仓库证据不满足设计第 5 节“先取证，后实现”。在设计要求的真实脱敏样本（同班多课次、同名异班或明确无班级号、跨学期/节次变动各至少一例）取得并经第二次审定之前，不得实现教学班/课程号聚合、父记录归组或一对多写入；旧实现（标题级去重与同步）应继续被视为“未证明稳定身份”的临时路径。

## 4. 本机脱敏采样操作说明（由用户执行；本会话未执行）

采样需要用户本人的已登录教务会话与真实数据，AI 不参与、不接触账号/Cookie/原始响应。建议在 `D:\focusflow\.tmp\zju-evidence-<YYYYMMDD>\`（仓库外）进行，原始文件绝不入 git、绝不上传或粘贴到任何对话。

1. **准备目录**：创建上述临时目录；确认其中无既有内容。
2. **取得原始响应（浏览器侧）**：在用户自己的浏览器登录 `zdbk.zju.edu.cn`，打开开发者工具 Network（筛选 XHR/Fetch），进入学生课表查询页并触发查询；把 `xskbcx_cxXsKb.html` 的响应体保存为 `raw-kb-<日期>.json`，同时把请求表单里的 `xnm`/`xqm`/`xqmmc` 值与首页 `xnm`/`xqm` 下拉的全部 option（value+text、哪个 selected）记录到 `raw-semester-<日期>.txt`。**不要**保存请求头、Cookie、页面截图或任何账号信息。
3. **第二次采样**：同一学期下再取一次（刷新/重登/换一天均可）存为 `raw-kb-<日期>-2.json`；如条件允许，再取一个不同学期的样本。两次同学期样本用于判断“字段值在两次导入间是否相同”。
4. **本地脱敏（建议 Node，一次一脚本，只读原始文件）**：脚本输出 `sanitized-structure.md`，**只允许**包含：
   - 顶层键名、类型、数组长度；`captcha_error` 是否存在及其类型；
   - `kbList` 每行的键清单（排序后的并集）；每个键的类型、空/`null` 比例、字符串长度分桶（如 0/1–10/11–50/>50）、“是否含 `<br>`”“是否纯数字”“是否含 `|`”“是否含 `,`/`，`”“`<br>` 段数范围”；
   - `kcb`：段数范围与“每段是否符合 标题/周次/教师/以 `zwf` 结尾的地点”这一类**布尔判断**，不输出段文本；
   - 两次同学期样本的比较：对每个键逐行只写 `相同/不同/缺失`（用**内存中随机盐**的 SHA-256 比较，盐与摘要都不落盘）；对候选标识键另写“不同值的个数”（同样只写计数）；
   - 学期：请求代码与 option 列表（非个人数据）及其是否出现在响应结构中。
   - **禁止**把任何原始字符串、姓名、学号、账号、课程名、地点原文、原始 ID 值写入输出。
5. **输出核验**：对 `sanitized-structure.md` 运行敏感度扫描（中文字符、8 位以上连续数字、邮箱/手机号模式）；命中即删除对应行并重跑，直到只剩结构描述。
6. **归档方式**：原始文件与脚本留在本机临时目录；把 `sanitized-structure.md` 的内容作为本文件的后续附录（或新开 `docs/9.0-stage6-zju-field-evidence-appendix.md`）提交，注明采样日期、学期、采样次数与脱敏规则；原始响应永不提交。
7. **通过标准**：能回答“哪些稳定键真实存在、是否跨次导入稳定、同名异班如何区分、真实 `xnm`/`xqm` 值格式”；取得后交 Sol 第二次审定，再决定是否进入限定实现。

## 5. 本次已执行与未执行

- 已执行：只读审计（上述代码/测试/流转核对）；`git status`、`git diff --stat 818bd74..HEAD`、文件哈希核对（设计文档副本与原文件 SHA-256 一致）；追加本文件与设计文档副本。
- 未执行：真实教务采样（无凭据，未接触原始响应）；任何真机；CI；一对一多课次实现。
- 可引用的近期测试证据（非本次新跑）：2026-09-27 本机 `:app:testDebugUnitTest --rerun` 与 `:app:testReleaseUnitTest --rerun` 各 145 类 865 项全部通过（0 失败/错误/跳过），包含 `ZjuTimetableParserTest`、`ZjuTimetableClientTest`、`ZjuTimetableImportUiTest`（证据 XML 位于 `app/build/test-results/`，时间戳 13:44/14:02）。
- 合规：未修改业务代码、数据模型、Room schema、版本号；未输出任何密钥、账号或个人信息；未 `pull`/`push`/合并/发布。

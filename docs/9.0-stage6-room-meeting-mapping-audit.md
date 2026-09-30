# 阶段 6 审计：课程 / 课次规则的 Room 映射能不能支持"一门课多次上课"

- **日期**：2026-09-29
- **触发**：阶段 6 检查点「下轮模型迁移顺序」第 3 步要求先审核
  `CourseMeetingRuleEntity.toLegacy(parent)` ——"多规则共享父 ID 会造成课次覆盖、提醒设置和手动编辑错位"。
- **结论一句话**：**这个担心今天不会发生，但原因不是"处理好了"，而是"结构上写不下"**；
  真要支持一对多，必须**四处一起改**，否则会从"硬失败"变成"静默错配"。

---

## 1. 检查点原本的担心 vs 逐行核对后的实际

| | 检查点原文的担心 | 核对结果 |
|---|---|---|
| 机制 | 多规则共享父 ID ⇒ 映射时互相覆盖 | 不是覆盖：**两条规则会撞主键，第二条第根本存不进库** |
| 失败方式 | 静默错位（课次挂到别的课上、提醒设置串用） | **硬失败**：读取路径有严格 1:1 校验，不满足就整份读取返回 `Invalid` |
| 严重性 | 数据错乱 | 数据错乱不会发生；但"读取失败"会**级联到提醒恢复**（见 §4） |

所以：**检查点的担心方向对（这块确实不能直接用），但失败形态说错了**——这条结论要写进检查点，免得下一个人按"静默覆盖"去防。

---

## 2. 三条结构事实（都有行号，可直接复核）

### 事实 1：课次规则的主键与外键**都**取自父课程 id ⇒ 一对多写不下
`app/src/main/java/com/sakata/focusflow/data/FocusFlowEntities.kt:343-347`

```kotlin
fun fromLegacy(course: Course, sourceOrder: Int) = CourseMeetingRuleEntity(
    course.id, course.id, sourceOrder, ...   // ← 第一个是 @PrimaryKey id，第二个是 course_id
)
```
实体定义：`:328-341`（`@PrimaryKey val id: Long`，`course_id` 只是**普通索引**、非唯一）。
⇒ 同一门课写第二条规则 → **主键冲突**（Room 上是替换/失败，不是新增一行）⇒ 库里**永远只有一条**。

### 事实 2：读取路径有严格 1:1 校验，不满足就整份失败
`app/src/main/java/com/sakata/focusflow/data/CoreDataRepositories.kt:104-110`

```kotlin
if (courses.map { it.id } != courseMeetingRules.map { it.courseId } ||
    courses.zip(courseMeetingRules).any { (parent, rule) ->
        parent.id != rule.id || parent.sourceOrder != rule.sourceOrder || ...
    }
) return CoreDataReadResult.Invalid("courses and meeting rules disagree")
```
- 逐元素比较**两个序列必须完全相等** ⇒ 一门课两条规则时长度就不同 ⇒ 直接 `Invalid`。
- 这就是**硬失败**的来源：不是"配错"，是"拒绝读"。

### 事实 3：映射本身**依赖**上面那道校验才安全
`app/src/main/java/com/sakata/focusflow/data/CoreDataRepositories.kt:131`

```kotlin
courses = courses.zip(courseMeetingRules).map { (parent, rule) -> rule.toLegacy(parent) }
```
`zip` 只按**位置**配对。它能对，唯一原因是事实 2 的校验先跑过。
⇒ **脆弱点**：谁放松了校验（例如为了支持一对多而把 `!=` 改成"包含"），却忘了改这一行，
就会立刻出现"规则配到别的课程上"的**静默错配**——正是检查点担心的那个形态。

`toLegacy` 本身：`FocusFlowEntities.kt:350-357`，还原出的 `Course.id` 取自**父实体**（不是规则），
所以课次的身份与课程身份当前是**同一个 id**（这也是"每门课一次上课"的遗留模型痕迹）。

---

## 3. 支撑测试（把事实钉住）

`app/src/test/java/com/sakata/focusflow/CourseMeetingRuleMappingTest.kt`（4 项，纯数据、不碰 Room）：
1. 规则主键与外键都等于父课程 id；
2. 同一门课两条规则**撞主键**，而内容确实不同（= 覆盖就是丢数据）；
3. 还原出的课程 id 取自父实体、课次内容取自规则；
4. 复刻那道校验：1:1 时通过（`zip` 安全），一对多时**必然不等** ⇒ 走 `Invalid` 而不是静默错配。

改动事实 1/2 中任何一条，这些测试会红 ⇒ 逼改动者回来读本文件。

---

## 4. 为什么"硬失败"值得认真对待：它会级联到提醒恢复

读取返回 `Invalid` ⇒ 核心数据运行时判定为 **`Blocked`**；而统一恢复入口
（`ReminderScheduler.restoreUnifiedReminders`）在 `ACTIVITY` 那一步遇到非 Ready 会**早退**，
跳过其后所有恢复步骤（详见 `docs/9.0-stage6-unified-scheduling-design.md` §3.4 与 §4）。
⇒ 一旦有人把 1:N 塞进这条路径，**表现不是"课程显示错"，而是"提醒不再恢复"**——排查成本高得多。

**当前可达性**：Room 产品激活**仍关闭**（`CoreDataRuntimePolicy.ACTIVATION_ENABLED = false`），
出厂配置下运行时判定为 `Ready`、走旧存储 ⇒ 这是**潜在缺陷，不是现网故障**。
这也正是检查点把"审核"排在"开产品入口"之前的原因。

---

## 5. 要支持一对多，必须**一起**改的四处

按依赖顺序（缺一处就会出现上面某种失败）：

1. **结构**：让课次规则的主键独立于课程（例如 `id` 用自己生成的值），`course_id` 保留为索引，必要时加唯一约束；
2. **校验**（`CoreDataRepositories.kt:104-110`）：从"序列逐个相等"改成"每条规则的 `courseId` 都能在课程里找到，且不漏不重；课程至少一条规则"；
3. **映射**（同文件 `:131`）：从 `zip` 改成 `groupBy(courseId)` 后按父展开，**不要**再用位置配对；
4. **恢复入口**：确认 `Blocked` 早退的爆炸半径（最好把早退收窄到只影响 `ACTIVITY` 自己，或让恢复步骤对"课程类"失败更宽容）。

> 第 4 条属**行为变更**，需要单独放行与真机验收；前三条是结构与读取路径，做完仍需真机验收。

---

## 6. 本次**没有**做的事

- 没有改任何生产代码或表结构（本审计只读）。
- 没有写迁移（旧 `Course.id` → 主记录/课次两个 id 的设计仍待放行）。
- 没有动 Room 激活开关。

## 7. 复现方式

```powershell
# 看三处事实
Select-String -Path app\src\main\java\com\sakata\focusflow\data\FocusFlowEntities.kt -Pattern 'course_meeting_rules|PrimaryKey val id|fun fromLegacy|fun toLegacy' -Context 0,10
Select-String -Path app\src\main\java\com\sakata\focusflow\data\CoreDataRepositories.kt -Pattern 'courses.zip|disagree'

# 跑支撑测试（只跑这一类，几秒钟）
.\gradlew.bat -g D:\focusflow\.gradle-home :app:testDebugUnitTest --tests com.sakata.focusflow.CourseMeetingRuleMappingTest --rerun-tasks
```
